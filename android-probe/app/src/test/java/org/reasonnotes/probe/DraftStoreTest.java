package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DraftStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String BASE = "a".repeat(64);
    private static final String SAVED = "b".repeat(64);
    private static final String REMOTE = "c".repeat(64);

    @Test
    public void permanentPurgeClearsDraftAndRefusesLateEditorWrites() throws Exception {
        File files = temporary.newFolder("private-files");
        ProbeRepository repository = ProbeRepository.open(new File(files, "probe-repository"));
        DraftStore store = new DraftStore(new File(files, "drafts"));
        UUID noteId = UUID.fromString("bbbbbbbb-2222-4222-8222-222222222222");
        String originalRevision = "a".repeat(64);
        store.write(noteId, originalRevision, "Secret", "Private draft contents");
        assertTrue(store.current(noteId) != null);

        repository.purgeNote(noteId);
        assertNull(store.current(noteId));
        assertTrue(repository.isPurged(noteId));
        assertThrows(IllegalStateException.class,
                () -> store.write(noteId, originalRevision, "Secret", "Resurrected"));
        assertNull(store.current(noteId));
    }

    @Test
    public void restartRoundTripsExactUnicodeAndNewlines() throws Exception {
        File root = temporary.newFolder("drafts");
        DraftStore first = new DraftStore(root);
        DraftStore.Draft written = first.write(NOTE, BASE, "Zażółć\n?", "line 1\n\n尾\n");

        DraftStore.Draft reopened = new DraftStore(root).current(NOTE);

        assertEquals(written, reopened);
        assertEquals(1L, reopened.generation());
        assertTrue(new DraftStore(root).recovery(NOTE, BASE).safeToApply());
        assertFalse(new DraftStore(root).recovery(NOTE, REMOTE).safeToApply());
    }

    @Test
    public void matchingSaveCompletionRemovesOnlyMatchingGeneration() throws Exception {
        DraftStore store = new DraftStore(temporary.newFolder("drafts"));
        DraftStore.Draft draft = store.write(NOTE, BASE, "title", "body");

        store.completeSave(NOTE, draft.generation(), BASE, SAVED, SAVED);

        assertNull(store.current(NOTE));
    }

    @Test
    public void olderCompletionRebasesNewerDraftWithoutChangingItsText() throws Exception {
        DraftStore store = new DraftStore(temporary.newFolder("drafts"));
        DraftStore.Draft first = store.write(NOTE, BASE, "old", "old body");
        DraftStore.Draft newer = store.write(NOTE, BASE, "new", "new body\nexact");

        store.completeSave(NOTE, first.generation(), BASE, SAVED, SAVED);

        DraftStore.Draft remaining = store.current(NOTE);
        assertEquals(newer.generation(), remaining.generation());
        assertEquals("new", remaining.title());
        assertEquals("new body\nexact", remaining.content());
        assertEquals(SAVED, remaining.baseRevisionId());
    }

    @Test
    public void olderCompletionNeverDeletesOrRebasesNewerDraftAfterRemoteChange() throws Exception {
        DraftStore store = new DraftStore(temporary.newFolder("drafts"));
        DraftStore.Draft first = store.write(NOTE, BASE, "old", "old body");
        DraftStore.Draft newer = store.write(NOTE, BASE, "new", "new body");

        store.completeSave(NOTE, first.generation(), BASE, SAVED, REMOTE);

        assertEquals(newer, store.current(NOTE));
        assertFalse(store.recovery(NOTE, REMOTE).safeToApply());
    }

    @Test
    public void corruptDraftFailsClosedAndBytesRemainAvailable() throws Exception {
        File root = temporary.newFolder("drafts");
        File path = new File(root, NOTE + ".draft");
        byte[] corrupt = "format=1\nnote-id=broken\n".getBytes(StandardCharsets.UTF_8);
        Files.write(path.toPath(), corrupt);
        DraftStore store = new DraftStore(root);

        assertThrows(DraftStore.CorruptDraftException.class, () -> store.current(NOTE));

        assertEquals(ListBytes.hex(corrupt), ListBytes.hex(Files.readAllBytes(path.toPath())));
        assertThrows(
                DraftStore.CorruptDraftException.class,
                () -> store.write(NOTE, BASE, "replacement", "must not overwrite"));
    }

    private static final class ListBytes {
        static String hex(byte[] bytes) {
            StringBuilder value = new StringBuilder();
            for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        }
    }
}
