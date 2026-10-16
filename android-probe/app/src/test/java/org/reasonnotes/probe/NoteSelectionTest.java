package org.reasonnotes.probe;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class NoteSelectionTest {
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test public void longPressStartsSelectionAndTapsToggleSeveralNotes() {
        NoteSelection selection = new NoteSelection();
        assertFalse(selection.active());
        selection.begin(A);
        assertTrue(selection.contains(A));
        assertEquals(1, selection.count());
        selection.toggle(B);
        selection.toggle(C);
        assertEquals(Set.of(A, B, C), selection.snapshot());
        selection.toggle(B);
        assertEquals(Set.of(A, C), selection.snapshot());
        selection.toggle(A);
        selection.toggle(C);
        assertFalse(selection.active());
    }

    @Test public void selectAllAndCancelNeverChangeNotes() {
        NoteSelection selection = new NoteSelection();
        selection.begin(A);
        selection.addAll(List.of(A, B, C));
        assertEquals(3, selection.count());
        selection.clear();
        assertEquals(0, selection.count());
    }

    @Test public void completionRemovesOnlyDeletedAndKeepsFailedNotes() {
        NoteSelection selection = new NoteSelection();
        selection.begin(A);
        selection.addAll(List.of(B, C));
        selection.removeAll(List.of(A, B));
        assertEquals(Set.of(C), selection.snapshot());
    }

    @Test public void snapshotDoesNotChangeWhenSelectionChanges() {
        NoteSelection selection = new NoteSelection();
        selection.begin(A);
        Set<UUID> snapshot = selection.snapshot();
        selection.toggle(B);
        assertEquals(Set.of(A), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }
}
