package org.reasonnotes.probe;

import java.util.List;
import java.util.Objects;

/** Evidence-backed synchronization result. A transfer request is not confirmation. */
public record SyncStatus(
        Phase phase,
        int revisionsRequested,
        int receiptsRequested,
        int imported,
        int confirmed,
        int receiptsQueued,
        List<String> errors,
        String detail) {
    public enum Phase { IDLE, SCANNING, COMPLETE, TRANSFER_REQUESTED, ERROR }

    public SyncStatus {
        Objects.requireNonNull(phase, "phase");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        detail = Objects.requireNonNullElse(detail, "");
        if (revisionsRequested < 0 || receiptsRequested < 0 || imported < 0
                || confirmed < 0 || receiptsQueued < 0) {
            throw new IllegalArgumentException("negative sync evidence");
        }
    }

    public static SyncStatus error(String detail) {
        return new SyncStatus(Phase.ERROR, 0, 0, 0, 0, 0, List.of(detail), detail);
    }

    public String summary() {
        String evidence = "imported " + imported + ", confirmed " + confirmed
                + ", receipts queued " + receiptsQueued;
        return switch (phase) {
            case IDLE -> "Ready — " + evidence;
            case SCANNING -> "Scanning incoming notes and receipts…";
            case COMPLETE -> "No transfer requested — " + evidence;
            case TRANSFER_REQUESTED -> "Transfer requested for " + revisionsRequested
                    + " revisions and " + receiptsRequested + " receipts — " + evidence
                    + ". Awaiting destination import receipts.";
            case ERROR -> "Sync stopped: " + detail + " — " + evidence;
        };
    }
}
