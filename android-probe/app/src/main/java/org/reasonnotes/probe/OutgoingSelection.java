package org.reasonnotes.probe;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OutgoingSelection(boolean allNotes, Set<UUID> noteIds) {
    public OutgoingSelection {
        noteIds = Set.copyOf(Objects.requireNonNull(noteIds, "noteIds"));
        if (allNotes && !noteIds.isEmpty()) {
            throw new IllegalArgumentException("all-notes selection cannot contain explicit IDs");
        }
    }

    public static OutgoingSelection all() {
        return new OutgoingSelection(true, Set.of());
    }

    public static OutgoingSelection selected(Collection<UUID> noteIds) {
        return new OutgoingSelection(false, Set.copyOf(noteIds));
    }

    public boolean includes(UUID noteId) {
        return allNotes || noteIds.contains(noteId);
    }
}
