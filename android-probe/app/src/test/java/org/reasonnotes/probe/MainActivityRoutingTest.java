package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class MainActivityRoutingTest {
    private static final String LINUX_DEVICE_ID = "254997f28f11499d80928fe8a109749e";

    @Test
    public void existingLinuxTargetRemainsApplicationPreferenceDefault() {
        assertEquals(LINUX_DEVICE_ID, AppPreferences.DEFAULT_TARGET_DEVICE_ID);
    }

    @Test
    public void editorAndConflictRoutingUseOneStableNoteIdExtra() {
        assertEquals("note-id", MainActivity.EXTRA_NOTE_ID);
        assertEquals(MainActivity.EXTRA_NOTE_ID, EditorActivity.EXTRA_NOTE_ID);
    }
}
