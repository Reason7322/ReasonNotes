package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public final class SettingsActivityContractTest {
    @Test
    public void settingsExposeOnlyImplementedGroupsAndActions() {
        assertEquals(List.of("Appearance", "Sync", "Connection", "Notes"), SettingsActivity.sectionTitles());
        assertEquals(
                List.of("Sync all notes", "Choose notes to sync", "Storage access", "Target device"),
                SettingsActivity.syncFoundationActions());
        assertFalse(SettingsActivity.sectionTitles().contains("About"));
        assertFalse(SettingsActivity.sectionTitles().contains("Privacy"));
        assertFalse(SettingsActivity.sectionTitles().contains("Sync pinned status across devices"));
    }

    @Test
    public void connectionUsesDirectStorageAndHasNoLegacyDeletionPairing() {
        assertEquals(List.of("Storage access", "Target device"),
                SettingsActivity.connectionActions());
        assertEquals("Access enabled", SettingsActivity.storageAccessStatus(true));
        assertEquals("Tap to allow access", SettingsActivity.storageAccessStatus(false));
        assertFalse(SettingsActivity.connectionActions().contains("Automatic deletions"));
    }

    @Test
    public void manifestDeclaresAllFilesAccessForFixedExchangeDirectory() throws Exception {
        String manifest = new String(
                Files.readAllBytes(Path.of("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains(
                "android.permission.MANAGE_EXTERNAL_STORAGE"));
    }

    @Test
    public void exchangeStatusAndAppearanceOptionsAreExplicit() {
        assertEquals("Choose a folder", SettingsActivity.exchangeStatus(null));
        assertEquals(
                "Folder ready",
                SettingsActivity.exchangeStatus("content://tree/reasonnotes"));
        assertEquals(List.of(
                AppPreferences.ThemeMode.DARK,
                AppPreferences.ThemeMode.LIGHT,
                AppPreferences.ThemeMode.LIQUID_GLASS),
                SettingsActivity.themeChoices());
        assertEquals("Dark", SettingsActivity.themeLabel(AppPreferences.ThemeMode.DARK));
        assertEquals("Light", SettingsActivity.themeLabel(AppPreferences.ThemeMode.LIGHT));
        assertEquals("Liquid Glass",
                SettingsActivity.themeLabel(AppPreferences.ThemeMode.LIQUID_GLASS));
    }

    @Test
    public void onlyImplementedOrderingChoicesArePresented() {
        assertEquals(
                List.of(AppPreferences.NoteOrder.MODIFIED_DESCENDING, AppPreferences.NoteOrder.TITLE_ASCENDING),
                SettingsActivity.orderChoices());
    }
}
