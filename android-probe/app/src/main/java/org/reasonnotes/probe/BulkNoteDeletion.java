package org.reasonnotes.probe;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bulk note deletion: skip conflicts without blocking independent notes. */
public final class BulkNoteDeletion {
    public record Result(List<UUID> deleted, List<UUID> skipped, Map<UUID, String> failed) {
        public boolean successful() { return failed.isEmpty(); }
    }

    private BulkNoteDeletion() {}

    public static Result delete(NoteService service, Set<UUID> selected) {
        ProbeRepository.BatchDeleteResult report = service.deleteNotes(selected);
        return new Result(report.deleted(), report.skipped(), report.failed());
    }
}
