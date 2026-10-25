package org.reasonnotes.probe;

/** Small user-language adapter for the current synchronization operation only. */
public final class CurrentSyncPresentation {
    private CurrentSyncPresentation() {}

    public static String message(SyncStatus status, String affectedTitle) {
        return switch (status.phase()) {
            case IDLE, SCANNING -> "";
            case COMPLETE -> "Synchronized";
            case TRANSFER_REQUESTED -> "Waiting for computer confirmation";
            case ERROR -> affectedTitle == null || affectedTitle.isBlank()
                    ? "Transfer failed" : "Transfer failed for " + affectedTitle;
        };
    }

    public static String conflict(String title) {
        String value = title == null || title.isBlank() ? "This note" : title;
        return value + " requires a conflict choice";
    }
}
