package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NoteServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final class AdvancingClock implements Supplier<Instant> {
        private Instant value = Instant.parse("2026-10-15T02:00:00Z");
        @Override public Instant get() {
            Instant result = value;
            value = value.plusSeconds(1);
            return result;
        }
    }

    private static NoteService service(ProbeRepository repository, UUID... ids) {
        ArrayDeque<UUID> remaining = new ArrayDeque<>(List.of(ids));
        return new NoteService(repository, "android", new AdvancingClock(), remaining::removeFirst);
    }

    @Test
    public void createEditDeleteUsesExactMarkdownAndImmutableHistory() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        NoteService service = service(repository, NOTE);

        NoteService.OpenNote created = service.createNote("Pierwsza", "Treść\n");
        assertEquals("# Pierwsza\n\nTreść\n", created.document());
        assertEquals(Set.of(created.revisionId()), created.baseHeadIds());
        assertEquals("Pierwsza", service.listNotes("").get(0).title());

        NoteService.OpenNote edited = service.saveNote(
                NOTE, created.baseHeadIds(), "Zmieniona", "Nowa treść");
        assertEquals("# Zmieniona\n\nNowa treść", edited.document());
        assertEquals(2, repository.revisions().size());

        NoteService.OpenNote unchanged = service.saveNote(
                NOTE, edited.baseHeadIds(), "Zmieniona", "Nowa treść");
        assertEquals(edited.revisionId(), unchanged.revisionId());
        assertEquals(2, repository.revisions().size());

        service.deleteNote(NOTE, unchanged.baseHeadIds());
        assertTrue(repository.isPurged(NOTE));
        assertTrue(service.listNotes("").isEmpty());
        assertThrows(NoteService.NoteNotFoundException.class, () -> service.openNote(NOTE));
        assertEquals(0, repository.revisions().size());

        NoteService reopened = service(ProbeRepository.open(root), SECOND);
        assertTrue(reopened.listNotes("").isEmpty());
    }

    @Test
    public void openingLegacyNoteDoesNotRewriteIt() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        String legacy = "\n##   Stary tytuł\n\nLegacy body\n";
        RevisionEnvelope imported = RevisionEnvelope.create(
                NOTE, List.of(), "linux", Instant.parse("2026-10-15T02:00:00Z"), false, legacy);
        repository.importEnvelope(imported.serialize(), "legacy.md");
        int before = repository.revisions().size();

        NoteService.OpenNote opened = service(repository, SECOND).openNote(NOTE);

        assertEquals("Stary tytuł", opened.title());
        assertEquals("Legacy body\n", opened.content());
        assertEquals(legacy, opened.document());
        assertEquals(before, repository.revisions().size());
        assertEquals(imported.serialize().length, Files.size(new File(
                repository.root(), "history/" + NOTE + "/" + imported.revisionId() + ".md").toPath()));
    }

    @Test
    public void searchNormalizesUnicodeAndPolishCaseAndExcludesDeleted() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        NoteService service = service(repository, NOTE, SECOND);
        NoteService.OpenNote polish = service.createNote("ŻÓŁĆ", "Cafe\u0301 w Łodzi");
        NoteService.OpenNote deleted = service.createNote("Usuń mnie", "żółć");
        service.deleteNote(SECOND, deleted.baseHeadIds());

        assertEquals(List.of(NOTE), service.listNotes("żółć").stream().map(NoteService.NoteSummary::noteId).toList());
        assertEquals(List.of(NOTE), service.listNotes("CAFÉ W ŁODZI").stream().map(NoteService.NoteSummary::noteId).toList());
        assertEquals(polish.revisionId(), service.openNote(NOTE).revisionId());
    }

    @Test
    public void saveAndDeleteRejectBasesChangedByIncomingImport() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        NoteService service = service(repository, NOTE);
        NoteService.OpenNote displayed = service.createNote("Displayed", "local");
        RevisionEnvelope remote = RevisionEnvelope.create(
                NOTE,
                List.of(displayed.revisionId()),
                "linux",
                Instant.parse("2026-10-15T02:10:00Z"),
                false,
                "# Remote\n\nchanged");
        repository.importEnvelope(remote.serialize(), "remote.md");

        assertThrows(
                ProbeRepository.StaleHeadsException.class,
                () -> service.saveNote(NOTE, displayed.baseHeadIds(), "Unsafe", "draft"));
        // Permanent deletion is intentional even if a remote edit arrived
        // after this editor opened: no historical branch survives the purge.
        service.deleteNote(NOTE, displayed.baseHeadIds());
        assertTrue(repository.isPurged(NOTE));
        assertTrue(repository.revisions().isEmpty());
        assertThrows(NoteService.NoteNotFoundException.class, () -> service.openNote(NOTE));
    }

    @Test
    public void listingManyRevisionsAndNotesPreservesSearchSortingAndConflicts() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("many-notes"));
        NoteService service = new NoteService(repository, "android", new AdvancingClock(),
                UUID::randomUUID);
        java.util.HashSet<UUID> created = new java.util.HashSet<>();
        for (int i = 0; i < 28; i++) {
            NoteService.OpenNote note = service.createNote("Note " + i, "body original");
            created.add(note.noteId());
            for (int j = 0; j < 3; j++) {
                note = service.saveNote(note.noteId(), note.baseHeadIds(),
                        "Note " + i, "body revision " + j);
            }
        }
        long before = System.nanoTime();
        List<NoteService.NoteSummary> all = service.listNotes("");
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - before);
        System.out.println("ANDROID_28_NOTE_LIST_JVM_MS=" + elapsedMs);
        assertEquals(28, all.size());
        assertEquals(created, new java.util.HashSet<>(
                all.stream().map(NoteService.NoteSummary::noteId).toList()));
        assertEquals(28, service.listNotes("body revision 2").size());
        assertEquals(0, service.listNotes("nonsense search phrase").size());
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).modifiedUtc().isAfter(all.get(i - 1).modifiedUtc()));
        }
        assertTrue("Home listing regressed: " + elapsedMs + "ms", elapsedMs < 3000);
    }

    @Test
    public void restartReconcilesHistoryOnlyRevision() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        NoteService service = service(repository, NOTE);
        NoteService.OpenNote initial = service.createNote("Before", "old");
        RevisionEnvelope interrupted = RevisionEnvelope.create(
                NOTE,
                List.of(initial.revisionId()),
                "linux",
                Instant.parse("2026-10-15T02:20:00Z"),
                false,
                "# Recovered\n\nafter restart");
        File path = new File(root, "history/" + NOTE + "/" + interrupted.revisionId() + ".md");
        Files.write(path.toPath(), interrupted.serialize());

        NoteService reopened = service(ProbeRepository.open(root), SECOND);

        assertEquals("Recovered", reopened.openNote(NOTE).title());
        assertEquals("Recovered", reopened.listNotes("").get(0).title());
        assertFalse(reopened.listNotes("").get(0).conflicted());
    }
}
