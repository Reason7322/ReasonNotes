package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CurrentStoreApplicationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final class Clock implements Supplier<Instant> {
        private Instant value = Instant.parse("2026-10-24T16:00:00Z");
        @Override public Instant get() {
            Instant result = value;
            value = value.plusSeconds(1);
            return result;
        }
    }

    private static NoteService service(CurrentNoteStore store, UUID... ids) {
        ArrayDeque<UUID> remaining = new ArrayDeque<>(List.of(ids));
        return new NoteService(store, "android", new Clock(), remaining::removeFirst);
    }

    @Test public void createRepeatedEditAndRestartUseOnlyLatestCurrentState() throws Exception {
        File root = temporary.newFolder("current");
        CurrentNoteStore store = CurrentNoteStore.open(root, "android");
        NoteService service = service(store, NOTE);
        NoteService.OpenNote created = service.createNote("First", "one");
        NoteService.OpenNote first = service.saveNote(
                NOTE, created.baseHeadIds(), "First", "two");
        NoteService.OpenNote second = service.saveNote(
                NOTE, first.baseHeadIds(), "First", "three");

        NoteService reopened = service(CurrentNoteStore.open(root, "android"), SECOND);
        assertEquals("three", reopened.openNote(NOTE).content());
        assertEquals(List.of(second.revisionId()), reopened.currentStore().pendingStates().stream()
                .map(StateEnvelope::changeId).toList());
        assertEquals(1, reopened.listNotes("").size());
    }

    @Test public void independentEditsExposeBothCompleteVersionsAndChoiceBecomesPending() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("conflict"), "android");
        NoteService service = service(store, NOTE);
        NoteService.OpenNote base = service.createNote("Base", "original");
        store.acknowledge(base.revisionId());
        NoteService.OpenNote local = service.saveNote(
                NOTE, base.baseHeadIds(), "Local title", "local body");
        StateEnvelope remote = StateEnvelope.createUpsert(
                NOTE, List.of(base.revisionId()), "linux",
                Instant.parse("2026-10-24T16:01:00Z"), "Remote title", "remote body");
        assertEquals("conflict", store.apply(remote).kind());

        NoteService.ConflictSnapshot conflict = service.inspectConflict(NOTE);
        assertEquals(Set.of(local.revisionId(), remote.changeId()), conflict.observedHeadIds());
        assertEquals(Set.of("# Local title\n\nlocal body", "# Remote title\n\nremote body"),
                Set.copyOf(conflict.candidates().stream().map(NoteService.ConflictCandidate::body).toList()));

        service.resolveCurrentConflict(conflict, remote.changeId());
        NoteService.OpenNote resolved = service.openNote(NOTE);
        assertEquals("Remote title", resolved.title());
        assertEquals("remote body", resolved.content());
        assertEquals(List.of(resolved.revisionId()), store.pendingStates().stream()
                .map(StateEnvelope::changeId).toList());
    }

    @Test public void deletionImmediatelyErasesContentAndLaterExplicitLiveImportRestoresIt() throws Exception {
        File root = temporary.newFolder("delete");
        CurrentNoteStore store = CurrentNoteStore.open(root, "android");
        NoteService service = service(store, NOTE);
        NoteService.OpenNote opened = service.createNote("Private title", "private body");
        service.deleteNote(NOTE, opened.baseHeadIds());

        assertTrue(service.listNotes("").isEmpty());
        assertThrows(NoteService.NoteNotFoundException.class, () -> service.openNote(NOTE));
        String bytes = Files.walk(root.toPath()).filter(Files::isRegularFile)
                .map(path -> {
                    try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }).reduce("", String::concat);
        assertFalse(bytes.contains("Private title"));
        assertFalse(bytes.contains("private body"));

        StateEnvelope restored = StateEnvelope.createUpsert(
                NOTE, List.of(), "linux", Instant.parse("2026-10-24T16:02:00Z"),
                "Private title", "private body");
        assertEquals("applied", store.apply(restored).kind());
        assertEquals(restored, store.get(NOTE));
        assertNull(store.conflict(NOTE));
    }

    @Test public void sevenCurrentNotesListWithoutScanningHundredsOfLegacyRevisions() throws Exception {
        File currentRoot = temporary.newFolder("seven-current");
        CurrentNoteStore store = CurrentNoteStore.open(currentRoot, "android");
        ArrayList<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 7; index++) ids.add(UUID.randomUUID());
        NoteService service = service(store, ids.toArray(UUID[]::new));
        for (int index = 0; index < 7; index++) service.createNote("Note " + index, "body " + index);

        File legacyNoise = temporary.newFolder("legacy-noise");
        for (int index = 0; index < 400; index++) {
            File file = new File(legacyNoise, "revision-" + index + ".md");
            Files.write(file.toPath(),
                    ("historical body " + index).getBytes(StandardCharsets.UTF_8));
        }
        long started = System.nanoTime();
        List<NoteService.NoteSummary> notes = service.listNotes("");
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertEquals(7, notes.size());
        assertTrue("Current-state listing regressed: " + elapsedMs + "ms", elapsedMs < 1000);
    }

    @Test public void applicationOpenMigratesOnceAndThenUsesOnlyCurrentState() throws Exception {
        File files = temporary.newFolder("application-files");
        File legacyRoot = new File(files, "probe-repository");
        ProbeRepository legacy = ProbeRepository.open(legacyRoot);
        legacy.createInitialRevision(
                NOTE, "# Migrated\n\nlegacy body", "android",
                Instant.parse("2026-10-24T15:00:00Z"));

        NoteService opened = NoteService.openApplication(
                files, "android", new Clock(), () -> SECOND);

        assertEquals("legacy body", opened.openNote(NOTE).content());
        assertFalse("validated legacy history should be retired", legacyRoot.exists());
        assertTrue(new File(files, "reasonnotes-v2/current/" + NOTE + ".json").isFile());

        NoteService reopened = NoteService.openApplication(
                files, "android", new Clock(), () -> SECOND);
        assertEquals(List.of(NOTE), reopened.listNotes("").stream()
                .map(NoteService.NoteSummary::noteId).toList());
    }

    @Test public void currentBulkDeleteSkipsConflictButDeletesIndependentNote() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("bulk"), "android");
        NoteService service = service(store, NOTE, SECOND);
        NoteService.OpenNote first = service.createNote("First", "one");
        service.createNote("Second", "two");
        StateEnvelope remote = StateEnvelope.createUpsert(
                NOTE, first.baseHeadIds().stream().toList(), "linux",
                Instant.parse("2026-10-24T16:03:00Z"), "Remote", "remote");
        service.saveNote(NOTE, first.baseHeadIds(), "Local", "local");
        assertEquals("conflict", store.apply(remote).kind());

        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, Set.of(NOTE, SECOND));

        assertEquals(List.of(SECOND), result.deleted());
        assertEquals(List.of(NOTE), result.skipped());
        assertTrue(result.failed().isEmpty());
        assertThrows(NoteService.NoteNotFoundException.class, () -> service.openNote(SECOND));
        assertTrue(service.listNotes("").get(0).conflicted());
    }
}
