package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class MainActivityLayoutTest {
    @Test
    public void contentStartsBelowSystemBarsAndActionBar() {
        assertEquals(314, HomeLayout.topContentPadding(24, 122, 168));
        assertEquals(0, HomeLayout.actionBarInset(false, 168));
        assertEquals(168, HomeLayout.actionBarInset(true, 168));
    }

    @Test
    public void searchAndEditorRemainAboveNavigationOrKeyboard() {
        assertEquals(88, HomeLayout.bottomContentPadding(24, 64, 0));
        assertEquals(344, HomeLayout.bottomContentPadding(24, 64, 320));
    }

    @Test
    public void cardsUseTwoEqualColumnsWithMinimumTouchTargets() {
        assertEquals(172, HomeLayout.cardWidth(360, 16));
        assertEquals(48, HomeLayout.minimumTouchTargetDp());
    }

    @Test
    public void pinnedAndOtherSectionsPreserveServiceOrder() {
        UUID pinnedId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID otherId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        NoteService.NoteSummary pinned = new NoteService.NoteSummary(
                pinnedId, "Pinned", "preview", false, Instant.parse("2026-10-15T05:00:01Z"));
        NoteService.NoteSummary conflict = new NoteService.NoteSummary(
                otherId, "Conflict", "", true, Instant.parse("2026-10-15T05:00:00Z"));

        HomeLayout.Sections sections = HomeLayout.partition(List.of(pinned, conflict), Set.of(pinnedId));

        assertEquals(List.of(pinned), sections.pinned());
        assertEquals(List.of(conflict), sections.others());
        assertTrue(sections.others().get(0).conflicted());
    }
}
