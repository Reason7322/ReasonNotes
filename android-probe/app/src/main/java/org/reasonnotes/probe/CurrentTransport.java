package org.reasonnotes.probe;

import java.util.ArrayList;
import java.util.List;

/** Pure v2 carrier application; Android SAF enumeration remains in ExchangeDirectory. */
public final class CurrentTransport {
    public record ScanReport(
            int imported, int duplicates, int confirmed, int conflicts,
            int acknowledgmentsQueued, List<String> errors) {
        public ScanReport { errors = List.copyOf(errors); }
    }

    private CurrentTransport() {}

    public static ScanReport applyBundle(
            byte[] data, String sourceName, CurrentNoteStore store, String localDevice) {
        TransportBundle.Carrier carrier = TransportBundle.parse(data, sourceName);
        int imported = 0;
        int duplicates = 0;
        int confirmed = 0;
        int conflicts = 0;
        int queuedBefore = store.pendingAcks().size();
        ArrayList<String> errors = new ArrayList<>();

        // A carrier may be canonically sorted with acknowledgments first. Apply
        // durable state mutations first so confirmation never precedes import.
        for (TransportBundle.Item item : carrier.items()) {
            if (!item.kind().equals("state-v2") && !item.kind().equals("revision")) continue;
            try {
                StateEnvelope state = item.kind().equals("state-v2")
                        ? StateEnvelope.parse(item.envelope())
                        : convertLegacy(RevisionEnvelope.parse(item.envelope()));
                if (state.originDevice().equals(localDevice))
                    throw new IllegalArgumentException("state originated on this device");
                CurrentNoteStore.ApplyOutcome outcome = store.apply(state);
                switch (outcome.kind()) {
                    case "applied", "deleted" -> imported++;
                    case "duplicate", "ignored-deletion", "rejected-deleted" -> duplicates++;
                    case "conflict" -> { imported++; conflicts++; }
                    default -> errors.add(item.identity() + ": " + outcome.kind());
                }
            } catch (RuntimeException error) {
                errors.add(item.identity() + ": " + error.getMessage());
            }
        }
        for (TransportBundle.Item item : carrier.items()) {
            if (!item.kind().equals("ack-v2")) continue;
            try {
                StateAckEnvelope ack = StateAckEnvelope.parse(item.envelope());
                if (store.acknowledge(ack)) confirmed++;
                else duplicates++;
            } catch (RuntimeException error) {
                errors.add(item.identity() + ": " + error.getMessage());
            }
        }
        int queued = Math.max(0, store.pendingAcks().size() - queuedBefore);
        return new ScanReport(imported, duplicates, confirmed, conflicts, queued, errors);
    }

    public static ScanReport applyLegacyRevision(
            byte[] data, CurrentNoteStore store, String localDevice) {
        RevisionEnvelope revision = RevisionEnvelope.parse(data);
        StateEnvelope state = convertLegacy(revision);
        if (state.originDevice().equals(localDevice))
            throw new IllegalArgumentException("state originated on this device");
        int before = store.pendingAcks().size();
        CurrentNoteStore.ApplyOutcome outcome = store.apply(state);
        return new ScanReport(
                outcome.kind().equals("applied") || outcome.kind().equals("deleted")
                        || outcome.kind().equals("conflict") ? 1 : 0,
                outcome.kind().equals("duplicate") || outcome.kind().equals("ignored-deletion")
                        || outcome.kind().equals("rejected-deleted") ? 1 : 0,
                0, outcome.kind().equals("conflict") ? 1 : 0,
                Math.max(0, store.pendingAcks().size() - before),
                List.of());
    }

    private static StateEnvelope convertLegacy(RevisionEnvelope revision) {
        if (revision.deleted()) {
            return StateEnvelope.createDeletion(
                    revision.noteId(), revision.parents(), revision.originDevice(),
                    revision.modifiedUtc());
        }
        NoteDocument.Parts parts = NoteDocument.split(revision.body());
        return StateEnvelope.createUpsert(
                revision.noteId(), revision.parents(), revision.originDevice(),
                revision.modifiedUtc(),
                parts.title().isEmpty() ? "Untitled" : parts.title(), parts.content());
    }
}
