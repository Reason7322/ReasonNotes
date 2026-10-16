package org.reasonnotes.probe;

import static org.junit.Assert.*;

import java.io.File;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BulkNoteDeletionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID A = UUID.fromString("aaaaaaaa-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("bbbbbbbb-2222-4222-8222-222222222222");
    private static final UUID C = UUID.fromString("cccccccc-3333-4333-8333-333333333333");

    private NoteService service(ProbeRepository repository) {
        ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(A, B, C));
        return new NoteService(repository, "android", () -> Instant.parse("2026-10-16T12:00:00Z"), ids::removeFirst);
    }

    @Test public void deletesSeveralNotesAsIndependentRecoverableTombstones() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repo = ProbeRepository.open(root);
        NoteService service = service(repo);
        var first = service.createNote("First", "keep in history");
        var second = service.createNote("Second", "keep in history");
        var remaining = service.createNote("Third", "stays live");

        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, repo, Set.of(A, B));
        assertTrue(result.successful());
        assertEquals(Set.of(A, B), Set.copyOf(result.deleted()));
        assertEquals(Set.of(C), Set.copyOf(service.listNotes("").stream()
                .map(NoteService.NoteSummary::noteId).toList()));
        assertEquals(5, repo.revisions().size());
        assertTrue(repo.heads(A).get(0).deleted());
        assertTrue(repo.heads(B).get(0).deleted());
        assertEquals(List.of(first.revisionId()), repo.heads(A).get(0).parents());
        assertEquals(List.of(second.revisionId()), repo.heads(B).get(0).parents());
        assertEquals(remaining.revisionId(), service.openNote(C).revisionId());
        assertEquals(5, ProbeRepository.open(root).revisions().size());
    }

    @Test public void oneConflictedNoteBlocksEntireBatchWithoutDeletingAnything() throws Exception {
        ProbeRepository repo = ProbeRepository.open(temporary.newFolder("repo"));
        NoteService service = service(repo);
        var first = service.createNote("First", "live");
        var second = service.createNote("Second", "live");
        RevisionEnvelope remote = RevisionEnvelope.create(B,
                List.of(second.revisionId()), "linux",
                Instant.parse("2026-10-16T12:00:01Z"), false, "# Remote edit");
        var local = service.saveNote(B, second.baseHeadIds(), "Second local", "edited");
        repo.importEnvelope(remote.serialize(), "remote.md");
        assertEquals(2, repo.heads(B).size());
        int before = repo.revisions().size();

        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, repo, Set.of(A, B));
        assertEquals(List.of(), result.deleted());
        assertEquals(List.of(B), result.blocked());
        assertEquals(before, repo.revisions().size());
        assertEquals(first.revisionId(), service.openNote(A).revisionId());
        assertEquals(local.revisionId(), repo.heads(B).stream().filter(h ->
                h.originDevice().equals("android")).findFirst().orElseThrow().revisionId());
    }

    @Test public void nonexistentOrAlreadyDeletedNoteBlocksBatch() throws Exception {
        ProbeRepository repo = ProbeRepository.open(temporary.newFolder("repo"));
        NoteService service = service(repo);
        var first = service.createNote("First", "live");
        var second = service.createNote("Second", "live");
        service.deleteNote(B, second.baseHeadIds());
        int before = repo.revisions().size();
        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, repo, Set.of(A, B, C));
        assertEquals(List.of(), result.deleted());
        assertEquals(Set.of(B, C), Set.copyOf(result.blocked()));
        assertEquals(before, repo.revisions().size());
        assertEquals(first.revisionId(), service.openNote(A).revisionId());
    }

    @Test public void emptySelectionDoesNothing() throws Exception {
        ProbeRepository repo = ProbeRepository.open(temporary.newFolder("repo"));
        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service(repo), repo, Set.of());
        assertTrue(result.successful());
        assertEquals(0, repo.revisions().size());
    }
}
