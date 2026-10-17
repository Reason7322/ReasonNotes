package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AppPreferencesTest {
    @Test
    public void existingLinuxTargetRemainsTheDefaultAndCanBeChangedDeliberately() {
        PinStoreTest.FakePreferences values = new PinStoreTest.FakePreferences();
        AppPreferences preferences = new AppPreferences(values);

        assertEquals("254997f28f11499d80928fe8a109749e", preferences.targetDeviceId());
        preferences.setTargetDeviceId("linux-laptop");
        assertEquals("linux-laptop", new AppPreferences(values).targetDeviceId());
    }

    @Test
    public void themeDefaultsDarkAndPersistsAllThreeModesIndependentlyOfConnection() {
        PinStoreTest.FakePreferences values = new PinStoreTest.FakePreferences();
        AppPreferences initial = new AppPreferences(values);
        assertEquals(AppPreferences.ThemeMode.DARK, initial.themeMode());
        initial.setTargetDeviceId("linux-laptop");
        for (AppPreferences.ThemeMode mode : AppPreferences.ThemeMode.values()) {
            initial.setThemeMode(mode);
            AppPreferences reopened = new AppPreferences(values);
            assertEquals(mode, reopened.themeMode());
            assertEquals("linux-laptop", reopened.targetDeviceId());
        }
    }

    @Test
    public void unknownOrOldThemeNameSafelyFallsBackToDark() {
        PinStoreTest.FakePreferences values = new PinStoreTest.FakePreferences();
        values.putString("theme-mode", "future-unknown-theme");
        assertEquals(AppPreferences.ThemeMode.DARK,
                new AppPreferences(values).themeMode());
        values.putString("theme-mode", "light");
        assertEquals(AppPreferences.ThemeMode.LIGHT,
                new AppPreferences(values).themeMode());
    }

    @Test
    public void implementedOrderingPreferencePersistsAcrossInstances() {
        PinStoreTest.FakePreferences values = new PinStoreTest.FakePreferences();
        AppPreferences preferences = new AppPreferences(values);

        assertEquals(AppPreferences.NoteOrder.MODIFIED_DESCENDING, preferences.noteOrder());
        preferences.setNoteOrder(AppPreferences.NoteOrder.TITLE_ASCENDING);

        assertEquals(AppPreferences.NoteOrder.TITLE_ASCENDING, new AppPreferences(values).noteOrder());
    }
}
