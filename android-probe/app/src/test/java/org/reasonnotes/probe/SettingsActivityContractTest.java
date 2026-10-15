package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class SettingsActivityContractTest {
    @Test
    public void settingsExposeOnlyImplementedGroupsAndActions() {
        assertEquals(List.of("Sync", "Behavior", "About"), SettingsActivity.sectionTitles());
        assertEquals(
                List.of("Exchange directory", "Target device"),
                SettingsActivity.syncFoundationActions());
        assertFalse(SettingsActivity.sectionTitles().contains("Appearance"));
        assertFalse(SettingsActivity.sectionTitles().contains("Privacy"));
        assertFalse(SettingsActivity.sectionTitles().contains("Sync pinned status across devices"));
    }

    @Test
    public void exchangeAndAboutCopyStateEvidencePrecisely() {
        assertEquals("Exchange directory not selected", SettingsActivity.exchangeStatus(null));
        assertEquals(
                "Exchange directory selected",
                SettingsActivity.exchangeStatus("content://tree/reasonnotes"));
        String about = SettingsActivity.aboutText("0.1");
        assertTrue(about.contains("Version 0.1"));
        assertTrue(about.contains("immutable local history"));
        assertTrue(about.contains("transfer request is not receipt confirmation"));
    }

    @Test
    public void onlyImplementedOrderingChoicesArePresented() {
        assertEquals(
                List.of(AppPreferences.NoteOrder.MODIFIED_DESCENDING, AppPreferences.NoteOrder.TITLE_ASCENDING),
                SettingsActivity.orderChoices());
    }
}
