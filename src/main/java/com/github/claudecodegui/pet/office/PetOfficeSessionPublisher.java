package com.github.claudecodegui.pet.office;

import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.pet.CodexPetFloatingService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Local metadata authority, independent of the selected tab, AI initialization and the Office page. */
@Service(Service.Level.APP)
public final class PetOfficeSessionPublisher implements Disposable {
    private final PetOfficeSessionRegistry registry = new PetOfficeSessionRegistry(UUID.randomUUID().toString());
    private final Map<Project, String> projectIds = new WeakHashMap<>();
    private final PetOfficeTabNavigator navigator = new PetOfficeTabNavigator(registry,
            action -> ApplicationManager.getApplication().invokeLater(action, ModalityState.any()));
    private boolean disposed;

    public static PetOfficeSessionPublisher getInstance() {
        return ApplicationManager.getApplication().getService(PetOfficeSessionPublisher.class);
    }

    public synchronized PetOfficeSessionRegistry.Registration register(String handle, Project project, String tab, SessionState state) {
        if (disposed || project.isDisposed()
                || (projectIds.size() >= PetOfficeSessionRegistry.MAX_SOURCES && !projectIds.containsKey(project))) {
            return null;
        }
        String id = projectIds.computeIfAbsent(project, ignored -> UUID.randomUUID().toString());
        ApplicationInfo info = ApplicationInfo.getInstance();
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            PetOfficeRuntimeService.getInstance().start();
        }
        return registry.register(new PetOfficeSessionRegistry.Metadata(handle, info.getBuild().getProductCode(),
                info.getVersionName(), id, project.getName(), tab), state);
    }

    /** A sample observes the current owner. No heartbeat or cached loading flag proves backend activity. */
    public PetOfficeSessionRegistry.CompleteSnapshot sample() {
        registry.observe();
        return registry.snapshot();
    }

    public PetOfficeSessionRegistry.CompleteSnapshot snapshot() { return registry.snapshot(); }

    public PetOfficeTabNavigator navigator() {
        return navigator;
    }

    public CodexPetFloatingService.VisualConfiguration visualConfiguration(String projectId) {
        Project owner = null;
        synchronized (this) {
            for (Map.Entry<Project, String> entry : projectIds.entrySet()) {
                if (entry.getValue().equals(projectId)) {
                    owner = entry.getKey();
                    break;
                }
            }
        }
        return owner == null || owner.isDisposed() ? null : CodexPetFloatingService.getInstance(owner).visualConfiguration();
    }

    @Override
    public synchronized void dispose() {
        if (!disposed) {
            disposed = true;
            registry.close();
            navigator.clear();
            projectIds.clear();
        }
    }
}
