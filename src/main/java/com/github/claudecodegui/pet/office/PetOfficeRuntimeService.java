package com.github.claudecodegui.pet.office;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** One opt-in coordinator and one metadata publisher per GUI IDE. No provider, CLI or ClawBot dependency. */
@Service(Service.Level.APP)
public final class PetOfficeRuntimeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PetOfficeRuntimeService.class);
    private final ExecutorService worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(32), task -> PetOfficeIpcServer.daemon(task, "Pet Office participant"));
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicReference<PetOfficeCoordinator> owner = new AtomicReference<>();
    private ScheduledFuture<?> timer;
    private Path directory;
    private volatile PetOfficeIpcClient client;
    private volatile PetOfficeSessionPublisher publisher;
    private PetOfficeEndpoint joined;
    private PetOfficeAssetChannel assetChannel;
    private long retryAt;
    private long backoffMillis = 250;
    private String lastError = "";
    private long lastPublication;
    private long nextEndpointCheck;
    private long nextRecovery;

    private final PetOfficeDisplayController display = new PetOfficeDisplayController(worker, disposed::get, () -> owner.get() != null, this::publisher);

    public static PetOfficeRuntimeService getInstance() {
        return ApplicationManager.getApplication().getService(PetOfficeRuntimeService.class);
    }

    public synchronized void start() {
        if (!disposed.get() && timer == null && SystemInfo.isWindows && !GraphicsEnvironment.isHeadless()) {
            timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
                if (queued.compareAndSet(false, true)) {
                    try {
                        worker.execute(() -> {
                            try {
                                tick();
                            } finally {
                                queued.set(false);
                            }
                        });
                    } catch (RejectedExecutionException stopped) {
                        queued.set(false);
                    }
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
        }
    }

    /** Called on the Office startup worker after eligibility and exclusive lease acquisition. */
    PetOfficeRecoveryStore.Intent startOwner(Path root, PetOfficeRuntimeLease lease, Runnable show,
                                            PetOfficeRecoveryStore.Intent expected, boolean explicit) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        var pending = worker.submit(() -> {
            if (cancelled.get() || disposed.get() || !lease.isValid() || owner.get() != null) {
                throw new IOException("OFFICE_LEADER_LOST");
            }
            directory = root;
            display.register(root);
            PetOfficeSessionPublisher publisher = publisher();
            String instanceId = publisher.snapshot().instanceId();
            var acquired = PetOfficeCoordinator.start(root, lease, instanceId, () -> !cancelled.get() && !disposed.get(),
                    () -> { if (!cancelled.get() && !disposed.get() && lease.isValid()) { show.run(); } },
                    () -> PetOfficeLocalService.getInstance().exitOwned(lease), expected, explicit);
            var server = acquired.server();
            boolean successful = false;
            PetOfficeRecoveryStore.Intent intent;
            try {
                disconnectClient();
                client = new PetOfficeIpcClient(server.endpoint(), instanceId);
                joined = server.endpoint();
                client.publish(publisher.sample());
                client.assetEndpoint(assetChannel().endpoint());
                synchronized (lease) {
                    if (cancelled.get() || disposed.get() || !lease.isValid()) {
                        throw new IOException("OFFICE_LEADER_LOST");
                    }
                    intent = acquired.recovery().read();
                    if (cancelled.get() || disposed.get() || !lease.isValid() || !owner.compareAndSet(null, acquired)) {
                        throw new IOException("OFFICE_LEADER_LOST");
                    }
                    successful = true;
                }
                start();
            } finally {
                if (!successful) {
                    disconnectClient();
                    acquired.close();
                }
            }
            return intent;
        });
        try {
            return pending.get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            synchronized (lease) {
                cancelled.set(true);
                pending.cancel(false);
            }
            Throwable cause = failure.getCause();
            if (cause instanceof IOException checked) {
                throw checked;
            }
            throw failure;
        }
    }

    /** Enqueue a user visibility request on the existing publishing stream, without blocking the EDT. */
    boolean showExisting(Path root, Consumer<String> completion) {
        try {
            worker.execute(() -> {
                String result = "";
                try {
                    directory = root;
                    if (!connectCurrent()) {
                        throw new IOException("OFFICE_COORDINATOR_UNAVAILABLE");
                    }
                    client.show();
                    start();
                } catch (Exception failure) {
                    result = code(failure);
                    fail(result);
                }
                completion.accept(result);
            });
            return true;
        } catch (RejectedExecutionException stopped) {
            return false;
        }
    }

    /** Safe exit is available to sender-only IDEs too; it never acquires renderer authority. */
    public void exitOffice(Consumer<String> completion) {
        try {
            worker.execute(() -> {
                String result = "";
                try {
                    if (directory == null) {
                        directory = PetOfficeWindowsRuntime.resolveDirectory();
                    }
                    var available = PetOfficeRuntimeLease.tryAcquire(directory);
                    if (available.isPresent()) {
                        try (var administrative = available.get()) {
                            new PetOfficeModeStore(directory).read();
                            new PetOfficeRecoveryStore(directory).disable(administrative);
                            new PetOfficeModeStore(directory).commitOverlay(administrative);
                        }
                    } else {
                        if (!connectCurrent()) {
                            throw new IOException("OFFICE_COORDINATOR_UNAVAILABLE");
                        }
                        client.exitOffice();
                    }
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    result = code(failure);
                }
                completion.accept(result);
            });
        } catch (RejectedExecutionException stopped) {
            completion.accept("OFFICE_RUNTIME_CLOSED");
        }
    }

    PetOfficeParticipantRegistry.View view() throws IOException {
        PetOfficeCoordinator current = owner.get();
        if (current == null || disposed.get() || !current.lease().isValid()) {
            throw new IOException("OFFICE_COORDINATOR_UNAVAILABLE");
        }
        return current.registry().view(current.instanceId(), current.server().endpoint().leaderEpoch());
    }

    boolean owns(PetOfficeRuntimeLease lease) {
        PetOfficeCoordinator current = owner.get();
        return !disposed.get() && current != null && current.lease() == lease && lease.isValid() && current.server().isRunning();
    }

    java.util.Map<String, PetOfficeAppearanceService.Appearance> appearances(PetOfficeSessionRegistry.CompleteSnapshot snapshot) throws IOException {
        PetOfficeCoordinator current = owner.get();
        if (current == null || !owns(current.lease())) {
            throw new IOException("OFFICE_LEADER_LOST");
        }
        return current.appearances().view(snapshot);
    }

    byte[] assetBytes(String key, PetOfficeAppearanceService.Appearance appearance, BooleanSupplier pageValid) throws IOException {
        PetOfficeCoordinator current = owner.get();
        if (current == null || !owns(current.lease())) {
            throw new IOException("OFFICE_LEADER_LOST");
        }
        return current.appearances().bytes(key, appearance, () -> owns(current.lease()) && pageValid.getAsBoolean());
    }

    private PetOfficeAssetChannel assetChannel() throws IOException {
        if (assetChannel == null) {
            PetOfficeSessionPublisher publisher = publisher();
            var assets = new PetOfficeAppearanceService(
                    () -> PetOfficeAssetRoot.resolvePets(Path.of(com.github.claudecodegui.util.PlatformUtils.getHomeDirectory())),
                    () -> com.github.claudecodegui.bridge.AiDataProcessGate.getInstance().tryAcquireReadPermit());
            assetChannel = new PetOfficeAssetChannel(publisher.snapshot().instanceId(),
                    new PetOfficeAppearancePublisher(publisher::snapshot, publisher::visualConfiguration, assets));
        }
        return assetChannel;
    }

    void navigate(String page, PetOfficeTabNavigator.Request request, BooleanSupplier pageValid,
                  Consumer<PetOfficeTabNavigator.Result> completed, long receivedAt) {
        PetOfficeCoordinator current = owner.get();
        if (current == null || !owns(current.lease())) {
            completed.accept(PetOfficeTabNavigator.Result.UNAVAILABLE);
        } else {
            current.server().routes().submit(page, request, pageValid, completed, receivedAt);
        }
    }

    void closePage(String page) {
        PetOfficeCoordinator current = owner.get();
        if (current != null) {
            current.server().routes().closePage(page);
        }
    }

    void presentation(PetOfficeRuntimeLease lease, boolean enabled, boolean visible, Consumer<String> completion) {
        try {
            worker.execute(() -> {
                String error = "";
                try {
                    PetOfficeCoordinator current = owner.get();
                    if (current == null || !owns(lease)) {
                        throw new IOException("OFFICE_LEADER_LOST");
                    }
                    current.presentation(enabled, visible);
                } catch (Exception failure) {
                    error = code(failure);
                }
                completion.accept(error);
            });
        } catch (RejectedExecutionException stopped) {
            completion.accept("OFFICE_RUNTIME_CLOSED");
        }
    }

    /** Window disposal is acknowledged before this worker-side cleanup; this method never releases the lease. */
    void stopOwner(PetOfficeRuntimeLease lease) {
        PetOfficeCoordinator current = owner.get();
        if (current != null && current.lease() == lease && owner.compareAndSet(current, null)) {
            try {
                current.close();
            } catch (IOException unavailable) {
                LOG.warn("[PetOffice] OFFICE_ENDPOINT_CLEANUP_FAILED");
            }
        }
    }

    private void tick() {
        if (disposed.get()) {
            return;
        }
        try {
            if (directory == null) {
                directory = PetOfficeWindowsRuntime.resolveDirectory();
            }
            display.observe(directory);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            display.failed();
            lastError = code(unavailable);
            return;
        }
        if (retryAt != 0 && System.nanoTime() - retryAt < 0) {
            return;
        }
        try {
            if (directory == null) {
                directory = PetOfficeWindowsRuntime.resolveDirectory();
            }
            long now = System.nanoTime();
            boolean connected = client != null;
            if (nextEndpointCheck == 0 || now - nextEndpointCheck >= 0) {
                nextEndpointCheck = now + TimeUnit.SECONDS.toNanos(2);
                connected = connectCurrent();
            }
            if (connected) {
                if (lastPublication == 0 || now - lastPublication >= TimeUnit.SECONDS.toNanos(2)) {
                    client.publish(publisher().sample());
                    lastPublication = now;
                }
                PetOfficeIpcClient observed = client;
                long began = System.nanoTime();
                for (var command : observed.poll()) {
                    dispatch(observed, command, began);
                }
                PetOfficeCoordinator current = owner.get();
                if (current != null) {
                    current.server().routes().expire();
                }
                lastError = "";
                backoffMillis = 250;
                retryAt = 0;
            } else {
                recover();
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail(code(failure));
            recover();
        }
    }

    /** Runs on the startup worker, leaving the participant/control worker free to collect EDT acknowledgements. */
    void awaitDisplayReady(PetOfficeRuntimeLease lease) throws Exception {
        PetOfficeCoordinator current = owner.get();
        if (current == null || !owns(lease)) {
            throw new IOException("OFFICE_LEADER_LOST");
        }
        current.awaitDisplayReady(() -> owns(lease));
    }

    private void dispatch(PetOfficeIpcClient observed, PetOfficeRouteBroker.Command command, long began) {
        long receivedAt = began - TimeUnit.MILLISECONDS.toNanos(PetOfficeRouteBroker.DEADLINE_MILLIS - command.remainingMillis());
        publisher().navigator().navigate(observed.navigationPage(), command.request(),
                () -> !disposed.get() && client == observed && !observed.isClosed(), result -> {
                    try {
                        worker.execute(() -> {
                            if (client != observed || observed.isClosed() || disposed.get()) {
                                return;
                            }
                            try {
                                observed.complete(command, result);
                            } catch (IOException failure) {
                                fail(code(failure));
                            }
                        });
                    } catch (RejectedExecutionException stopped) {
                        // A disposed participant never replays navigation through a replacement connection.
                    }
                }, receivedAt);
    }

    private void recover() {
        if (disposed.get() || owner.get() != null || directory == null || nextRecovery != 0 && System.nanoTime() - nextRecovery < 0) {
            return;
        }
        nextRecovery = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3000 + ThreadLocalRandom.current().nextLong(1000));
        try {
            var intent = new PetOfficeRecoveryStore(directory).read();
            if (intent != null && intent.enabled()) {
                PetOfficeLocalService.getInstance().recover(intent);
            }
        } catch (IOException unavailable) {
            // Invalid/unknown intent fails closed; only a valid durable opt-in permits election.
        }
    }

    private boolean connectCurrent() throws IOException {
        if (disposed.get()) {
            throw new IOException("OFFICE_RUNTIME_CLOSED");
        }
        PetOfficeEndpoint endpoint = new PetOfficeEndpointStore(directory).read();
        if (endpoint == null) {
            disconnectClient();
            return false;
        }
        if (client == null || !endpoint.equals(joined)) {
            disconnectClient();
            PetOfficeSessionPublisher publisher = publisher();
            client = new PetOfficeIpcClient(endpoint, publisher.snapshot().instanceId());
            joined = endpoint;
            client.assetEndpoint(assetChannel().endpoint());
            lastPublication = 0;
        }
        return true;
    }

    private void fail(String code) {
        disconnectClient();
        PetOfficeCoordinator current = owner.get();
        if (current != null && !disposed.get() && !current.server().isRunning()) {
            current.server().close();
            PetOfficeLocalService.getInstance().coordinatorLost(current.lease());
        }
        retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMillis + ThreadLocalRandom.current().nextLong(250));
        backoffMillis = Math.min(5000, backoffMillis * 2);
        if (!disposed.get() && !code.equals(lastError)) {
            lastError = code;
            LOG.warn("[PetOffice] " + code);
        }
    }

    private void disconnectClient() {
        if (client != null) {
            if (publisher != null) {
                publisher.navigator().closePage(client.navigationPage());
            }
            client.close();
            client = null;
        }
        if (assetChannel != null) {
            assetChannel.close();
            assetChannel = null;
        }
        joined = null;
        nextEndpointCheck = 0;
    }

    private PetOfficeSessionPublisher publisher() {
        if (publisher == null) {
            publisher = PetOfficeSessionPublisher.getInstance();
        }
        return publisher;
    }

    private static String code(Exception failure) {
        String message = failure.getMessage();
        return message != null && message.matches("OFFICE_[A-Z_]{1,56}") ? message : "OFFICE_IPC_UNAVAILABLE";
    }

    @Override
    public synchronized void dispose() {
        if (disposed.compareAndSet(false, true)) {
            if (timer != null) {
                timer.cancel(false);
            }
            PetOfficeCoordinator current = owner.get();
            if (current != null) {
                current.server().close();
                current.appearances().close();
            }
            // Close the current socket immediately to interrupt blocked reads before queued cleanup.
            PetOfficeIpcClient connected = client;
            if (connected != null) {
                connected.close();
            }
            try {
                worker.execute(this::disconnectClient);
            } catch (RejectedExecutionException stopped) {
                // Native resources are already interrupted; stale navigation remains fenced by disposal.
            }
            display.retireAndStop();
        }
    }
}
