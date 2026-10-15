package org.reasonnotes.probe;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Device-local presentation state; never writes note history. */
public final class PinStore {
    private static final String KEY = "pinned-note-ids";
    private final LocalPreferences preferences;

    public PinStore(LocalPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public boolean isPinned(UUID noteId) {
        return preferences.getStringSet(KEY).contains(noteId.toString());
    }

    public void setPinned(UUID noteId, boolean pinned) {
        HashSet<String> values = new HashSet<>(preferences.getStringSet(KEY));
        if (pinned) values.add(noteId.toString());
        else values.remove(noteId.toString());
        preferences.putStringSet(KEY, values);
    }

    public Set<UUID> visiblePinned(Set<UUID> visibleNoteIds) {
        HashSet<UUID> result = new HashSet<>();
        for (String value : preferences.getStringSet(KEY)) {
            try {
                UUID noteId = UUID.fromString(value);
                if (visibleNoteIds.contains(noteId)) result.add(noteId);
            } catch (IllegalArgumentException ignored) {
                // Preserve unknown preference bytes but never expose an invalid ID.
            }
        }
        return Set.copyOf(result);
    }
}
