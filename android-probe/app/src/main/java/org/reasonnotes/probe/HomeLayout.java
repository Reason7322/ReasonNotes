package org.reasonnotes.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class HomeLayout {
    public record Sections(
            List<NoteService.NoteSummary> pinned,
            List<NoteService.NoteSummary> others) {}

    private HomeLayout() {}

    public static int topContentPadding(int basePadding, int systemBarTop, int actionBarHeight) {
        return basePadding + systemBarTop + actionBarHeight;
    }

    public static int actionBarInset(boolean visible, int configuredHeight) {
        return visible ? configuredHeight : 0;
    }

    public static int bottomContentPadding(int basePadding, int navigationBarBottom, int imeBottom) {
        return basePadding + Math.max(navigationBarBottom, imeBottom);
    }

    public static int cardWidth(int availableWidth, int columnGap) {
        return Math.max(0, (availableWidth - columnGap) / 2);
    }

    public static int minimumTouchTargetDp() { return 48; }

    public static Sections partition(
            List<NoteService.NoteSummary> notes, Set<UUID> pinnedNoteIds) {
        ArrayList<NoteService.NoteSummary> pinned = new ArrayList<>();
        ArrayList<NoteService.NoteSummary> others = new ArrayList<>();
        for (NoteService.NoteSummary note : notes) {
            if (pinnedNoteIds.contains(note.noteId())) pinned.add(note);
            else others.add(note);
        }
        return new Sections(List.copyOf(pinned), List.copyOf(others));
    }
}
