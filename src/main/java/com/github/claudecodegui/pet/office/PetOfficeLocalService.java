package com.github.claudecodegui.pet.office;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.util.JBCefBrowserFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A standalone local renderer. The page can sample metadata and focus an existing tab. */
@Service(Service.Level.APP)
public final class PetOfficeLocalService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PetOfficeLocalService.class);
    private static final int MAX_HTML_BYTES = 5 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AtomicBoolean opening = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), action -> {
                Thread thread = new Thread(action, "Pet Office local");
                thread.setDaemon(true);
                return thread;
            });
    // Page identity is retired before native resources are disposed on the EDT.
    private volatile Page current;
    private volatile Page pendingPage;

    public static PetOfficeLocalService getInstance() {
        return ApplicationManager.getApplication().getService(PetOfficeLocalService.class);
    }

    public void open() {
        onEdt(() -> {
            if (disposed.get()) {
                return;
            }
            Page page = current;
            if (page != null) {
                showOwned(page.lease);
                return;
            }
            if (opening.compareAndSet(false, true)) {
                if (!submit(this::load)) {
                    opening.set(false);
                }
            }
        });
    }

    void recover(PetOfficeRecoveryStore.Intent expected) {
        onEdt(() -> {
            if (!disposed.get() && current == null && opening.compareAndSet(false, true) && !submit(() -> load(expected, false))) {
                opening.set(false);
            }
        });
    }

    private void load() { load(null, true); }

    private void load(PetOfficeRecoveryStore.Intent expected, boolean explicit) {
        PetOfficeRuntimeLease acquired = null;
        Page attempted = null;
        try {
            if (!SystemInfo.isWindows || GraphicsEnvironment.isHeadless()) {
                throw new IOException("OFFICE_NO_RENDERER");
            }
            Path directory = PetOfficeWindowsRuntime.resolveDirectory();
            var available = PetOfficeRuntimeLease.tryAcquire(directory);
            if (available.isEmpty()) {
                if (!explicit) {
                    opening.set(false);
                } else if (!PetOfficeRuntimeService.getInstance().showExisting(directory, error -> {
                    opening.set(false);
                    if (!error.isEmpty()) { unavailable(); }
                })) {
                    throw new IOException("OFFICE_RUNTIME_CLOSED");
                }
                return;
            }
            acquired = available.get();
            if (!JBCefBrowserFactory.isJcefSupported()) {
                throw new IOException("OFFICE_NO_RENDERER");
            }
            byte[] bytes;
            try (InputStream input = getClass().getResourceAsStream("/html/pet-office.html")) {
                if (input == null) {
                    throw new IOException("OFFICE_ENTRY_MISSING");
                }
                bytes = input.readNBytes(MAX_HTML_BYTES + 1);
            }
            String html = new String(bytes, StandardCharsets.UTF_8);
            if (bytes.length > MAX_HTML_BYTES || !html.contains("<!-- OFFICE_BOOTSTRAP -->")) {
                throw new IOException("OFFICE_ENTRY_INVALID");
            }
            var store = new PetOfficePreferencesStore(directory);
            Page page = new Page(acquired, new PetOfficeWindowPreferenceController(store, acquired, store.read()));
            page.live = livePage(page, directory);
            attempted = page;
            pendingPage = page;
            var runtime = PetOfficeRuntimeService.getInstance();
            var intent = runtime.startOwner(directory, acquired, () -> showOwned(page.lease),
                    explicit ? new PetOfficeRecoveryStore(directory).read() : expected, explicit);
            page.initiallyVisible = intent.visible();
            runtime.awaitDisplayReady(acquired);
            if (page.cancelled.get()) {
                throw new IOException("OFFICE_WINDOW_CANCELLED");
            }
            onEdt(() -> create(html, page));
            acquired = null;
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            opening.set(false);
            if (attempted != null && pendingPage == attempted) {
                pendingPage = null;
            }
            if (explicit && (attempted == null || !attempted.cancelled.get())) {
                unavailable();
            }
        } finally {
            if (acquired != null) {
                release(acquired);
            }
        }
    }

    private void create(String html, Page page) {
        if (disposed.get() || page.cancelled.get()) {
            opening.set(false);
            if (pendingPage == page) { pendingPage = null; }
            close(page);
            return;
        }
        try {
            page.browser = JBCefBrowserFactory.create();
            page.query = JBCefJSQuery.create((JBCefBrowserBase) page.browser);
            page.query.addHandler(message -> receive(page, message));
            PetOfficeNavigationPolicy navigation = new PetOfficeNavigationPolicy(page.url);
            page.requestHandler = new CefRequestHandlerAdapter() {
                @Override
                public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean redirect) {
                    // Reject external documents and child frames before they acquire the native query function.
                    return !valid(page) || !navigation.allows(request.getURL(), frame != null && frame.isMain());
                }
            };
            page.browser.getJBCefClient().addRequestHandler(page.requestHandler, page.browser.getCefBrowser());
            var saved = page.preferences.state();
            page.window = new JFrame(windowTitle(saved.preferences()));
            page.window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            page.window.setLayout(new BorderLayout());
            page.window.add(page.browser.getComponent(), BorderLayout.CENTER);
            Rectangle bounds = PetOfficeWindowGeometry.restore(saved.bounds(), usableScreens());
            page.window.setMinimumSize(new Dimension(Math.min(480, bounds.width), Math.min(400, bounds.height)));
            page.window.setBounds(bounds);
            page.lastNormalBounds = new Rectangle(bounds);
            page.window.setAlwaysOnTop(saved.preferences().topmost());
            page.window.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent event) { scheduleBounds(page); }
                @Override
                public void componentResized(ComponentEvent event) { scheduleBounds(page); }
            });
            page.window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    if (current == page) {
                        changePresentation(page, true, false, () -> page.window.setVisible(false));
                    }
                }
            });
            if (disposed.get()) {
                close(page);
                return;
            }
            current = page;
            String language = Locale.getDefault().getLanguage().startsWith("zh") ? "zh" : "en";
            String bootstrap = "<script>window.__PET_OFFICE_PAGE__=" + JSON.toJson(page.token)
                    + ";window.__PET_OFFICE_BOOTSTRAP__=" + bootstrap(page, language)
                    + ";window.__PET_OFFICE_LANGUAGE__=" + JSON.toJson(language)
                    + ";window.__PET_OFFICE_SEND__=function(request){" + page.query.inject("request") + ";return true;};window.petOfficeNativeBridge=window.__PET_OFFICE_SEND__;</script>";
            page.browser.loadHTML(html.replace("<!-- OFFICE_BOOTSTRAP -->", bootstrap), page.url);
            page.window.setVisible(page.initiallyVisible);
            page.sceneRefresh = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
                if (valid(page) && page.refreshQueued.compareAndSet(false, true)) {
                    if (!submit(() -> {
                        try {
                            page.live.refresh();
                        } finally {
                            page.refreshQueued.set(false);
                        }
                    })) {
                        page.refreshQueued.set(false);
                    }
                }
            }, 500, 500, TimeUnit.MILLISECONDS);
        } catch (Exception | LinkageError failure) {
            if (current == page) {
                current = null;
            }
            close(page);
            unavailable();
        } finally {
            if (pendingPage == page) { pendingPage = null; }
            opening.set(false);
        }
    }

    private JBCefJSQuery.Response receive(Page page, String message) {
        if (!valid(page)) {
            return null;
        }
        PetOfficeLocalProtocol.Request request;
        try {
            request = PetOfficeLocalProtocol.parse(message, page.token);
        } catch (IOException invalid) {
            receiveLive(page, message);
            return null;
        }
        if (page.pending.incrementAndGet() > 8) {
            page.pending.decrementAndGet();
            respond(page, request, null, "OFFICE_BUSY");
            return null;
        }
        long receivedAt = System.nanoTime();
        if (!submit(() -> dispatch(page, request, receivedAt))) {
            page.pending.decrementAndGet();
            respond(page, request, null, "OFFICE_BUSY");
        }
        return null;
    }

    private void receiveLive(Page page, String message) {
        try {
            var data = PetOfficeLiveMessage.parse(message);
            if (!data.pageGeneration().equals(page.token) || page.pending.incrementAndGet() > 8) {
                if (data.pageGeneration().equals(page.token)) { page.pending.decrementAndGet(); }
                return;
            }
            long receivedAt = System.nanoTime();
            if (!submit(() -> {
                try {
                    if (!valid(page)) { return; }
                    switch (data.command()) {
                        case "hide":
                            onEdt(() -> { if (valid(page)) { changePresentation(page, true, false, () -> page.window.setVisible(false)); } });
                            break;
                        case "exit":
                            exitOwned(page.lease);
                            break;
                        case "navigate":
                            var request = new PetOfficeTabNavigator.Request(data.field("requestId"), data.field("sourceKey"),
                                    data.field("routeGeneration"), data.field("interactionId"));
                            PetOfficeRuntimeService.getInstance().navigate(page.token, request, () -> valid(page),
                                    result -> page.live.result(request.requestId(), result.name()), receivedAt);
                            break;
                        default:
                            page.live.receive(data);
                            if (data.command().equals("ready")) { onEdt(() -> { if (valid(page)) { page.live.visible(page.window.isVisible()); } }); }
                    }
                } catch (IOException | RuntimeException unavailable) {
                    // Invalid commands and retired pages never gain navigation, storage or window authority.
                } finally { page.pending.decrementAndGet(); }
            })) { page.pending.decrementAndGet(); }
        } catch (IOException invalid) { /* The strict live protocol has no generic command fallback. */ }
    }

    private PetOfficeLivePage livePage(Page page, Path directory) {
        var sources = new PetOfficeLivePage.Sources() {
            public PetOfficeParticipantRegistry.View view() throws IOException { return PetOfficeRuntimeService.getInstance().view(); }
            public java.util.Map<String, PetOfficeAppearanceService.Appearance> appearances(PetOfficeSessionRegistry.CompleteSnapshot snapshot) throws IOException {
                return PetOfficeRuntimeService.getInstance().appearances(snapshot);
            }
            public byte[] bytes(String key, PetOfficeAppearanceService.Appearance appearance, java.util.function.BooleanSupplier allowed) throws IOException {
                return PetOfficeRuntimeService.getInstance().assetBytes(key, appearance, allowed);
            }
        };
        var appearances = new PetOfficeAppearanceService(
                () -> PetOfficeAssetRoot.resolvePets(Path.of(com.github.claudecodegui.util.PlatformUtils.getHomeDirectory())),
                () -> com.github.claudecodegui.bridge.AiDataProcessGate.getInstance().tryAcquireReadPermit());
        return new PetOfficeLivePage(page.token, directory, page.lease, sources, () -> valid(page), page.preferences,
                preferences -> {
                    page.window.setTitle(windowTitle(preferences));
                    page.window.setAlwaysOnTop(preferences.topmost());
                },
                appearances, PetOfficeLocalService::onEdt, action -> { if (!submit(action)) { throw new RejectedExecutionException(); } },
                (callback, json) -> { if (valid(page)) { page.browser.getCefBrowser().executeJavaScript("window." + callback + "?.(" + json + ");", page.url, 0); } });
    }

    private static String bootstrap(Page page, String language) {
        JsonObject value = new JsonObject();
        value.addProperty("pageGeneration", page.token);
        value.addProperty("language", language);
        value.addProperty("mode", "live");
        value.addProperty("protocolVersion", PetOfficeSceneProtocol.VERSION);
        value.addProperty("alwaysOnTop", page.preferences.state().preferences().topmost());
        value.add("sharedPreferences", JSON.toJsonTree(page.preferences.state()));
        return JSON.toJson(value);
    }

    private void dispatch(Page page, PetOfficeLocalProtocol.Request request, long receivedAt) {
        boolean handedOff = false;
        try {
            if (!valid(page)) {
                return;
            }
            if ("scene".equals(request.action())) {
                page.preferences.refresh();
                JsonObject data = PetOfficeSceneCodec.encode(PetOfficeRuntimeService.getInstance().view());
                data.add("settings", page.preferences.view());
                respond(page, request, data, null);
            } else if ("preferences".equals(request.action())) {
                page.preferences.update(request.preferences(), () -> valid(page), preferences -> {
                    page.window.setTitle(windowTitle(preferences));
                    page.window.setAlwaysOnTop(preferences.topmost());
                }, PetOfficeLocalService::onEdt, action -> {
                    if (!submit(action)) {
                        throw new RejectedExecutionException();
                    }
                }, result -> {
                    try {
                        respond(page, request, result.data(), result.error());
                    } finally {
                        page.pending.decrementAndGet();
                    }
                });
                handedOff = true;
            } else if ("exit".equals(request.action())) {
                PetOfficeRuntimeService.getInstance().presentation(page.lease, false, false, error -> {
                    try {
                        JsonObject data = new JsonObject();
                        data.addProperty("closing", true);
                        respond(page, request, data, error.isEmpty() ? null : error);
                        if (error.isEmpty()) {
                            onEdt(() -> {
                                if (current == page) {
                                    current = null;
                                    close(page);
                                }
                            });
                        }
                    } finally {
                        page.pending.decrementAndGet();
                    }
                });
                handedOff = true;
            } else {
                PetOfficeRuntimeService.getInstance().navigate(page.token, request.navigation(), () -> valid(page), result -> {
                    try {
                        JsonObject data = new JsonObject();
                        data.addProperty("result", result.name());
                        respond(page, request, data, null);
                    } finally {
                        page.pending.decrementAndGet();
                    }
                }, receivedAt);
                handedOff = true;
            }
        } catch (Exception | LinkageError failure) {
            respond(page, request, null, "OFFICE_UNAVAILABLE");
        } finally {
            if (!handedOff) {
                page.pending.decrementAndGet();
            }
        }
    }

    private void respond(Page page, PetOfficeLocalProtocol.Request request, JsonObject data, String error) {
        String message = JSON.toJson(PetOfficeLocalProtocol.response(request, data, error));
        onEdt(() -> {
            if (valid(page)) {
                try {
                    page.browser.getCefBrowser().executeJavaScript("window.onPetOfficeResponse?.(" + JSON.toJson(message) + ");", page.url, 0);
                } catch (Exception | LinkageError closed) {
                    LOG.debug("Office response skipped after browser closure", closed);
                }
            }
        });
    }

    private boolean valid(Page page) {
        return !disposed.get() && !page.closed.get() && current == page && PetOfficeRuntimeService.getInstance().owns(page.lease);
    }

    private boolean submit(Runnable action) {
        try {
            worker.execute(action);
            return true;
        } catch (RejectedExecutionException unavailable) {
            return false;
        }
    }

    private void unavailable() {
        onEdt(() -> {
            if (!disposed.get()) {
                NotificationGroupManager.getInstance().getNotificationGroup("CC GUI Notifications")
                        .createNotification(ClaudeCodeGuiBundle.message("pet.office.unavailable"), NotificationType.WARNING).notify(null);
            }
        });
    }

    private void showOwned(PetOfficeRuntimeLease expected) {
        onEdt(() -> {
            Page page = current;
            if (page != null && page.lease == expected && valid(page)) {
                changePresentation(page, true, true, () -> {
                    page.window.setExtendedState(page.window.getExtendedState() & ~Frame.ICONIFIED);
                    page.window.setVisible(true);
                    page.window.toFront();
                });
            }
        });
    }

    void coordinatorLost(PetOfficeRuntimeLease expected) {
        onEdt(() -> {
            Page page = current;
            if (page != null && page.lease == expected) {
                current = null;
                close(page);
            }
        });
    }

    void exitOwned(PetOfficeRuntimeLease expected) {
        onEdt(() -> {
            Page page = current;
            if (page != null && page.lease == expected && valid(page)) {
                changePresentation(page, false, false, () -> {
                    current = null;
                    close(page);
                });
            } else {
                Page pending = pendingPage;
                if (pending != null && pending.lease == expected) {
                    pending.cancelled.set(true);
                    PetOfficeRuntimeService.getInstance().presentation(expected, false, false, error -> {
                        if (!error.isEmpty()) { unavailable(); }
                    });
                }
            }
        });
    }

    private void changePresentation(Page page, boolean enabled, boolean visible, Runnable apply) {
        long revision = ++page.presentationRevision;
        PetOfficeRuntimeService.getInstance().presentation(page.lease, enabled, visible, error -> onEdt(() -> {
            if (valid(page) && revision == page.presentationRevision) {
                if (error.isEmpty()) {
                    apply.run();
                    page.live.visible(visible);
                } else {
                    unavailable();
                }
            }
        }));
    }

    private void close(Page page) {
        if (!page.closed.compareAndSet(false, true)) {
            return;
        }
        if (page.boundsSave != null) {
            page.boundsSave.cancel(false);
        }
        if (page.sceneRefresh != null) { page.sceneRefresh.cancel(false); }
        Rectangle finalBounds = normalBounds(page.window);
        try {
            PetOfficeRuntimeService.getInstance().closePage(page.token);
        } catch (Exception | LinkageError failure) {
            LOG.debug("Office navigation already closed", failure);
        }
        try {
            if (page.query != null) {
                Disposer.dispose(page.query);
            }
        } catch (Exception | LinkageError failure) {
            LOG.warn("Office query cleanup failed", failure);
        } finally {
            try {
                if (page.browser != null) {
                    Disposer.dispose(page.browser);
                }
            } catch (Exception | LinkageError failure) {
                LOG.warn("Office browser cleanup failed", failure);
            } finally {
                if (page.window != null) {
                    page.window.dispose();
                }
                Runnable finish = () -> {
                    try {
                        if (page.live != null) { page.live.close(); }
                        if (finalBounds != null && page.lease.isValid()) {
                            page.preferences.saveBounds(bounds(finalBounds));
                        }
                    } catch (IOException failure) {
                        LOG.warn("Office final bounds could not be saved");
                    } finally {
                        release(page.lease);
                    }
                };
                if (!submit(finish)) {
                    java.util.concurrent.CompletableFuture.runAsync(finish);
                }
            }
        }
    }

    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any());
    }

    static String windowTitle(PetOfficePreferencesStore.Preferences preferences) {
        return preferences.officeName().isEmpty() ? ClaudeCodeGuiBundle.message("pet.office.title") : preferences.officeName();
    }

    private static java.util.List<Rectangle> usableScreens() {
        java.util.List<Rectangle> screens = new java.util.ArrayList<>();
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            var configuration = device.getDefaultConfiguration();
            Rectangle area = new Rectangle(configuration.getBounds());
            var insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            area.x += insets.left;
            area.y += insets.top;
            area.width -= insets.left + insets.right;
            area.height -= insets.top + insets.bottom;
            if (area.width > 0 && area.height > 0) {
                screens.add(area);
            }
        }
        return screens;
    }

    private static Rectangle normalBounds(JFrame window) {
        if (window == null || !window.isShowing() || window.getExtendedState() != Frame.NORMAL) {
            return null;
        }
        Rectangle bounds = window.getBounds();
        return bounds.width >= 320 && bounds.height >= 320 && bounds.width <= 7680 && bounds.height <= 4320
                && bounds.x >= -32768 && bounds.x <= 32768 && bounds.y >= -32768 && bounds.y <= 32768 ? bounds : null;
    }

    private static PetOfficePreferencesStore.Bounds bounds(Rectangle bounds) {
        return new PetOfficePreferencesStore.Bounds(bounds.x, bounds.y, bounds.width, bounds.height, true);
    }

    private void scheduleBounds(Page page) {
        if (!valid(page)) {
            return;
        }
        Rectangle normal = normalBounds(page.window);
        if (normal == null || normal.equals(page.lastNormalBounds)) {
            return;
        }
        page.lastNormalBounds = new Rectangle(normal);
        if (page.boundsSave != null) {
            page.boundsSave.cancel(false);
        }
        page.boundsSave = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> submit(() -> {
            if (valid(page)) {
                try {
                    page.preferences.saveBounds(bounds(normal));
                } catch (IOException failure) {
                    LOG.warn("Office bounds could not be saved");
                }
            }
        }), 500, TimeUnit.MILLISECONDS);
    }

    private static void release(PetOfficeRuntimeLease lease) {
        try {
            PetOfficeRuntimeService.getInstance().stopOwner(lease);
        } finally {
            try {
                lease.close();
            } catch (IOException failure) {
                LOG.warn("Office window lease cleanup failed");
            }
        }
    }

    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            worker.shutdownNow();
            onEdt(() -> {
                Page page = current;
                current = null;
                if (page != null) {
                    close(page);
                }
            });
        }
    }

    private static final class Page {
        private final PetOfficeRuntimeLease lease;
        private final PetOfficeWindowPreferenceController preferences;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean initiallyVisible = true;
        private long presentationRevision;
        private final String token = UUID.randomUUID().toString();
        private final String url = "https://cc-gui-office.local/" + token + ".html";
        private final AtomicInteger pending = new AtomicInteger();
        private JBCefBrowser browser;
        private JBCefJSQuery query;
        private JFrame window;
        private CefRequestHandlerAdapter requestHandler;
        private ScheduledFuture<?> boundsSave;
        private ScheduledFuture<?> sceneRefresh;
        private final AtomicBoolean refreshQueued = new AtomicBoolean();
        private PetOfficeLivePage live;
        private Rectangle lastNormalBounds;

        private Page(PetOfficeRuntimeLease lease, PetOfficeWindowPreferenceController preferences) {
            this.lease = lease;
            this.preferences = preferences;
        }
    }
}
