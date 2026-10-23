package org.reasonnotes.probe;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        return new NoteService(repository, "android",
                () -> Instant.parse("2026-10-16T12:00:00Z"), ids::removeFirst);
    }

    @Test public void bulkDeleteCreatesTombstonesAndPreservesHistory() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repo = ProbeRepository.open(root);
        NoteService service = service(repo);
        var first = service.createNote("First", "private old");
        service.saveNote(A, first.baseHeadIds(), "First", "private newer");
        var second = service.createNote("Second", "private second");
        var third = service.createNote("Third", "stay live");

        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, Set.of(A, B));
        assertTrue(result.successful());
        assertEquals(Set.of(A, B), Set.copyOf(result.deleted()));
        assertTrue(repo.purgedNoteIds().isEmpty());
        assertEquals(Set.of(C), Set.copyOf(service.listNotes("").stream()
                .map(NoteService.NoteSummary::noteId).toList()));
        assertEquals(6, repo.revisions().size());
        assertTrue(new File(root, "history/" + A).isDirectory());
        assertTrue(new File(root, "history/" + B).isDirectory());
        assertTrue(repo.heads(A).get(0).deleted());
        assertTrue(repo.heads(B).get(0).deleted());
        assertEquals(6, ProbeRepository.open(root).revisions().size());
    }

    @Test public void unresolvedConflictsRemainUntouchedWhileOtherNotesPurge() throws Exception {
        ProbeRepository repo = ProbeRepository.open(temporary.newFolder("conflict"));
        NoteService service = service(repo);
        var first = service.createNote("First", "live");
        var second = service.createNote("Second", "base");
        RevisionEnvelope remote = RevisionEnvelope.create(B,
                List.of(second.revisionId()), "linux",
                Instant.parse("2026-10-16T12:00:01Z"), false, "# Remote edit");
        service.saveNote(B, second.baseHeadIds(), "Local edit", "new text");
        repo.importEnvelope(remote.serialize(), "remote.md");
        assertEquals(2, repo.heads(B).size());

        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, Set.of(A, B));
        assertEquals(Set.of(A), Set.copyOf(result.deleted()));
        assertEquals(Set.of(B), Set.copyOf(result.skipped()));
        assertFalse(repo.isPurged(A));
        assertFalse(repo.isPurged(B));
        assertEquals(5, repo.revisions().size());
        assertEquals(2, repo.heads(B).size());
        assertEquals("duplicate", repo.importEnvelope(remote.serialize(), "old.md").kind());
    }

    @Test public void missingAndAlreadyDeletedIdsAreSkipped() throws Exception {
        ProbeRepository repo = ProbeRepository.open(temporary.newFolder("missing"));
        NoteService service = service(repo);
        service.createNote("First", "live");
        BulkNoteDeletion.Result first = BulkNoteDeletion.delete(service, Set.of(A, B));
        assertEquals(List.of(A), first.deleted());
        assertEquals(List.of(B), first.skipped());
        BulkNoteDeletion.Result repeated = BulkNoteDeletion.delete(service, Set.of(A, B));
        assertEquals(List.of(), repeated.deleted());
        assertEquals(Set.of(A, B), Set.copyOf(repeated.skipped()));
        assertEquals(2, repo.revisions().size());
    }

    @Test public void tombstoneOfFifteenNotesSkipsConflictAndIsFast() throws Exception {
        File root = temporary.newFolder("large");
        ProbeRepository repo = ProbeRepository.open(root);
        NoteService service = new NoteService(repo, "android",
                () -> Instant.parse("2026-10-16T12:00:00Z"), UUID::randomUUID);
        java.util.LinkedHashSet<UUID> selected = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 15; i++) selected.add(
                service.createNote("Test " + i, "private content " + i).noteId());
        UUID conflicted = selected.iterator().next();
        String base = repo.heads(conflicted).get(0).revisionId();
        repo.createRevision(conflicted, Set.of(base), "# Local", "android",
                Instant.parse("2026-10-16T12:01:00Z"), false);
        RevisionEnvelope remote = RevisionEnvelope.create(conflicted, List.of(base),
                "linux", Instant.parse("2026-10-16T12:02:00Z"), false, "# Remote");
        repo.importEnvelope(remote.serialize(), "remote.md");
        long started = System.nanoTime();
        BulkNoteDeletion.Result result = BulkNoteDeletion.delete(service, selected);
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("ANDROID_15_NOTE_TOMBSTONE_JVM_MS=" + ms);
        assertEquals(14, result.deleted().size());
        assertEquals(List.of(conflicted), result.skipped());
        assertTrue(result.failed().isEmpty());
        assertTrue(repo.purgedNoteIds().isEmpty());
        assertEquals(31, repo.revisions().size());
        assertEquals(31, ProbeRepository.open(root).revisions().size());
        assertTrue("Deletion regression: " + ms + " ms", ms < 3000);
    }

    @Test public void crashAfterFenceBeforePurgeRepairsAndBlocksOldImports() throws Exception {
        File root = temporary.newFolder("crash");
        ProbeRepository repo = ProbeRepository.open(root);
        var first = service(repo).createNote("Private", "secret");
        File marker = new File(root, "purged/" + A + ".id");
        Files.write(marker.toPath(), (A + "\n").getBytes(StandardCharsets.US_ASCII));
        assertTrue(repo.revisions().isEmpty());
        assertTrue(ProbeRepository.open(root).revisions().isEmpty());
        assertFalse(new File(root, "history/" + A).exists());
        assertEquals("purged", repo.importEnvelope(
                RevisionEnvelope.create(A, List.of(), "linux",
                        Instant.parse("2026-10-16T13:00:00Z"), false, "secret")
                        .serialize(), "stale.md").kind());
    }
}
