package org.reasonnotes.probe;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Home-screen multi-select state; selection never changes note history. */
public final class NoteSelection {
    private final LinkedHashSet<UUID> selected = new LinkedHashSet<>();

    public void begin(UUID noteId) {
        selected.clear();
        selected.add(noteId);
    }

    public void toggle(UUID noteId) {
        if (!selected.remove(noteId)) selected.add(noteId);
    }

    public void addAll(Collection<UUID> ids) { selected.addAll(ids); }

    public boolean contains(UUID noteId) { return selected.contains(noteId); }
    public boolean active() { return !selected.isEmpty(); }
    public int count() { return selected.size(); }
    public Set<UUID> snapshot() { return Collections.unmodifiableSet(new LinkedHashSet<>(selected)); }
    public void removeAll(Collection<UUID> ids) { selected.removeAll(ids); }
    public void clear() { selected.clear(); }
}
