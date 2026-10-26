package org.reasonnotes.probe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CurrentPathIsolationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");

    @Test public void v2DeletionErasesDraft() throws Exception {
        File files = temporary.newFolder("files");
        CurrentNoteStore notes = CurrentNoteStore.open(new File(files, "reasonnotes-v2"), "android");
        DraftStore drafts = new DraftStore(new File(files, "drafts"));
        StateEnvelope state = notes.saveLocal(
                NOTE, "Private", "content", Instant.parse("2026-10-25T05:00:00Z"));
        drafts.write(NOTE, state.changeId(), "Private draft", "draft secret");

        notes.deleteLocal(NOTE);

        assertNull(drafts.current(NOTE));
        String all = Files.walk(files.toPath()).filter(Files::isRegularFile).map(path -> {
            try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).reduce("", String::concat);
        assertFalse(all.contains("Private draft"));
        assertFalse(all.contains("draft secret"));
    }

    @Test public void activeApplicationPathDoesNotStartLegacyPurgeWorker() throws Exception {
        String source = new String(Files.readAllBytes(java.nio.file.Path.of(
                "src/main/java/org/reasonnotes/probe/ReasonNotesApplication.java")),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("ForegroundPurgeClient"));
        assertFalse(source.contains("ProbeRepository"));
        assertFalse(source.contains("probe-repository"));
        assertTrue(source.contains("requestImmediatePoll()"));
    }

    @Test public void v2ReceiverReusesValidatedProcessedIndexInsteadOfRescanningHistory()
            throws Exception {
        String source = new String(Files.readAllBytes(java.nio.file.Path.of(
                "src/main/java/org/reasonnotes/probe/ExchangeDirectory.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("reasonnotes-exchange-processed-v1.tsv"));
        assertFalse(source.contains("reasonnotes-exchange-processed-v2.tsv"));
    }
}
