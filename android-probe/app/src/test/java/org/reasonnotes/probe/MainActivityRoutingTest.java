package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    @Test public void visibleNotesScreenPollsIncomingCarriersEverySecond()
            throws Exception {
        String source = new String(Files.readAllBytes(java.nio.file.Path.of(
                "src/main/java/org/reasonnotes/probe/MainActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("incomingPollHandler.postDelayed(this, 1000L)"));
        assertTrue(source.contains("checkIncomingOnResume();"));
    }
}
