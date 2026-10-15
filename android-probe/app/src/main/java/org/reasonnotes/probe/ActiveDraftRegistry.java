package org.reasonnotes.probe;

import java.util.concurrent.TimeUnit;

/** Process-local bridge that lets synchronization flush the currently open editor. */
public final class ActiveDraftRegistry {
    private static EditorAutosaveController active;

    private ActiveDraftRegistry() {}

    public static synchronized void register(EditorAutosaveController controller) {
        active = controller;
    }

    public static synchronized void unregister(EditorAutosaveController controller) {
        if (active == controller) active = null;
    }

    public static SyncCoordinator.FlushEvidence flush() {
        EditorAutosaveController controller;
        synchronized (ActiveDraftRegistry.class) { controller = active; }
        if (controller == null) return SyncCoordinator.FlushEvidence.success();
        try {
            EditorAutosaveController.Status status = controller.flush().get(30, TimeUnit.SECONDS);
            return status == EditorAutosaveController.Status.SAVED
                    ? SyncCoordinator.FlushEvidence.success()
                    : new SyncCoordinator.FlushEvidence(false, "active draft is " + status.name().toLowerCase());
        } catch (Exception error) {
            return new SyncCoordinator.FlushEvidence(false, "active draft flush failed: " + error.getMessage());
        }
    }
}
