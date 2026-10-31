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
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
                page.window.setVisible(true);
                page.window.toFront();
                return;
            }
            if (opening.compareAndSet(false, true)) {
                if (!submit(this::load)) {
                    opening.set(false);
                }
            }
        });
    }

    private void load() {
        try {
            if (!SystemInfo.isWindows || GraphicsEnvironment.isHeadless() || !JBCefBrowserFactory.isJcefSupported()) {
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
            onEdt(() -> create(html));
        } catch (Exception | LinkageError failure) {
            opening.set(false);
            unavailable();
        }
    }

    private void create(String html) {
        if (disposed.get()) {
            opening.set(false);
            return;
        }
        Page page = new Page();
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
            page.window = new JFrame(ClaudeCodeGuiBundle.message("pet.office.title"));
            page.window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            page.window.setLayout(new BorderLayout());
            page.window.add(page.browser.getComponent(), BorderLayout.CENTER);
            page.window.setMinimumSize(new Dimension(560, 440));
            page.window.setSize(1000, 740);
            page.window.setLocationByPlatform(true);
            page.window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    if (current == page) {
                        current = null;
                        close(page);
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
                    + ";window.__PET_OFFICE_LANGUAGE__=" + JSON.toJson(language)
                    + ";window.__PET_OFFICE_SEND__=function(request){" + page.query.inject("request") + ";return true;};</script>";
            page.browser.loadHTML(html.replace("<!-- OFFICE_BOOTSTRAP -->", bootstrap), page.url);
            page.window.setVisible(true);
        } catch (Exception | LinkageError failure) {
            if (current == page) {
                current = null;
            }
            close(page);
            unavailable();
        } finally {
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
            return null;
        }
        if (page.pending.incrementAndGet() > 8) {
            page.pending.decrementAndGet();
            respond(page, request, null, "OFFICE_BUSY");
            return null;
        }
        if (!submit(() -> dispatch(page, request))) {
            page.pending.decrementAndGet();
            respond(page, request, null, "OFFICE_BUSY");
        }
        return null;
    }

    private void dispatch(Page page, PetOfficeLocalProtocol.Request request) {
        boolean handedOff = false;
        try {
            if (!valid(page)) {
                return;
            }
            PetOfficeSessionPublisher publisher = PetOfficeSessionPublisher.getInstance();
            if ("scene".equals(request.action())) {
                var snapshot = publisher.sample();
                byte[] bytes = PetOfficeSnapshotCodec.encode(snapshot, snapshot.connectionEpoch());
                respond(page, request, PetOfficeJsonCodec.parse(bytes), null);
            } else {
                publisher.navigator().navigate(page.token, request.navigation(), () -> valid(page), result -> {
                    try {
                        JsonObject data = new JsonObject();
                        data.addProperty("result", result.name());
                        respond(page, request, data, null);
                    } finally {
                        page.pending.decrementAndGet();
                    }
                });
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
        return !disposed.get() && current == page;
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

    private void close(Page page) {
        try {
            PetOfficeSessionPublisher.getInstance().navigator().closePage(page.token);
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
            }
        }
    }

    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any());
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
        private final String token = UUID.randomUUID().toString();
        private final String url = "https://cc-gui-office.local/" + token + ".html";
        private final AtomicInteger pending = new AtomicInteger();
        private JBCefBrowser browser;
        private JBCefJSQuery query;
        private JFrame window;
        private CefRequestHandlerAdapter requestHandler;
    }
}
