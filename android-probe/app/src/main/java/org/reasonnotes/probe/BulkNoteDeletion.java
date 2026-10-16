package org.reasonnotes.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Save one recoverable tombstone per note, with all-note conflict preflight. */
public final class BulkNoteDeletion {
    public record Result(List<UUID> deleted, List<UUID> blocked, Map<UUID, String> failed) {
        public boolean successful() { return blocked.isEmpty() && failed.isEmpty(); }
    }

    private BulkNoteDeletion() {}

    public static Result delete(NoteService service, ProbeRepository repository, Set<UUID> selected) {
        if (selected.isEmpty()) return new Result(List.of(), List.of(), Map.of());
        // Check every note before changing *any*. A known conflict, already
        // deleted note, or missing note aborts the entire batch.
        Map<UUID, Set<String>> expected = new LinkedHashMap<>();
        List<UUID> blocked = new ArrayList<>();
        for (UUID id : selected) {
            try {
                List<RevisionEnvelope> heads = repository.heads(id);
                if (heads.size() != 1 || heads.get(0).deleted()) {
                    blocked.add(id);
                } else {
                    expected.put(id, Set.of(heads.get(0).revisionId()));
                }
            } catch (RuntimeException error) {
                blocked.add(id);
            }
        }
        if (!blocked.isEmpty()) return new Result(List.of(), List.copyOf(blocked), Map.of());

        List<UUID> deleted = new ArrayList<>();
        Map<UUID, String> failed = new LinkedHashMap<>();
        for (var entry : expected.entrySet()) {
            try {
                service.deleteNote(entry.getKey(), entry.getValue());
                deleted.add(entry.getKey());
            } catch (RuntimeException error) {
                // Another writer or an I/O failure may invalidate one note.
                // Report partial completion precisely; never mark it deleted.
                failed.put(entry.getKey(), error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
            }
        }
        return new Result(List.copyOf(deleted), List.of(), Map.copyOf(failed));
    }
}
