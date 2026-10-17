package org.reasonnotes.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A single durable display-only checkpoint, not evidence of receipt.
 * The authoritative, validated delivery state remains in DeliveryLedger.
 * One stored value avoids mixing fields from different sync operations.
 */
public final class SyncConfirmationRecord {
    private static final String KEY = "last-sync-confirmation";
    private static final String PREFIX = "v1";
    private static final int MAX_IDS = 4096;

    public record Pending(Set<String> revisionIds, OutgoingSelection selection) {
        public Pending {
            revisionIds = Set.copyOf(revisionIds);
            Objects.requireNonNull(selection, "selection");
        }
    }

    private final LocalPreferences preferences;

    public SyncConfirmationRecord(LocalPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public void save(Set<String> ids, OutgoingSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (ids.isEmpty() || ids.size() > MAX_IDS) throw new IllegalArgumentException("invalid sync IDs");
        for (String id : ids) if (!validRevisionId(id)) throw new IllegalArgumentException("invalid revision ID");
        List<String> orderedRevisions = new ArrayList<>(ids);
        orderedRevisions.sort(Comparator.naturalOrder());
        List<String> orderedNotes = selection.noteIds().stream()
                .map(UUID::toString).sorted().toList();
        String value = PREFIX + "\n" + (selection.allNotes() ? "all" : "selected")
                + "\n" + String.join(",", orderedNotes)
                + "\n" + String.join(",", orderedRevisions);
        preferences.putString(KEY, value);
    }

    public Pending load() {
        String value = preferences.getString(KEY, "");
        if (value.isEmpty()) return null;
        try {
            String[] parts = value.split("\n", -1);
            if (parts.length != 4 || !PREFIX.equals(parts[0])) return null;
            boolean all;
            if ("all".equals(parts[1])) all = true;
            else if ("selected".equals(parts[1])) all = false;
            else return null;
            Set<UUID> notes = new HashSet<>();
            if (!parts[2].isEmpty()) {
                for (String note : parts[2].split(",", -1)) notes.add(UUID.fromString(note));
            }
            if (notes.size() > MAX_IDS || (all && !notes.isEmpty())
                    || (!all && notes.isEmpty())) return null;
            Set<String> ids = new HashSet<>();
            for (String id : parts[3].split(",", -1)) {
                if (!validRevisionId(id) || !ids.add(id)) return null;
            }
            if (ids.size() > MAX_IDS) return null;
            return new Pending(Set.copyOf(ids),
                    all ? OutgoingSelection.all() : OutgoingSelection.selected(notes));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean validRevisionId(String id) {
        return id != null && id.matches("[0-9a-f]{64}");
    }
}
