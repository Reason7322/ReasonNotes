package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ProbeRepositoryApplicationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static Instant at(int second) {
        return Instant.parse(String.format("2026-10-15T01:00:%02dZ", second));
    }

    @Test
    public void initialRevisionIsDurableWithoutRebuildingGeneratedViews() throws Exception {
        File root = temporary.newFolder("initial-fast-path");
        ProbeRepository repository = ProbeRepository.open(root);

        RevisionEnvelope created = repository.createInitialRevision(
                NOTE, "# Untitled\n", "android", at(0));

        assertTrue(new File(root, "history/" + NOTE + "/" + created.revisionId() + ".md")
                .isFile());
        assertEquals(Set.of(created.revisionId()), repository.headIds(NOTE));
        assertFalse("new-note path rebuilt generated current view",
                new File(root, "current/" + NOTE + ".md").exists());

        repository.reconcile();
        assertTrue(new File(root, "current/" + NOTE + ".md").isFile());
    }

    @Test
    public void guardedWritesEnumerateHistoryAndRejectStaleHeadsWithoutWriting() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        RevisionEnvelope root = repository.createRevision(
                NOTE, Set.of(), "# Root\n", "android", at(0), false);
        RevisionEnvelope other = repository.createRevision(
                OTHER, Set.of(), "# Other\n", "android", at(1), false);
        RevisionEnvelope child = repository.createRevision(
                NOTE, Set.of(root.revisionId()), "# Child\n", "android", at(2), false);

        assertEquals(Set.of(NOTE, OTHER), repository.noteIds());
        assertEquals(Set.of(child.revisionId()), repository.headIds(NOTE));
        assertEquals(List.of(root, other, child), repository.revisions());

        int before = repository.revisions().size();
        ProbeRepository.StaleHeadsException error = assertThrows(
                ProbeRepository.StaleHeadsException.class,
                () -> repository.createRevision(
                        NOTE, Set.of(root.revisionId()), "stale", "android", at(3), false));
        assertEquals(Set.of(root.revisionId()), error.expected());
        assertEquals(Set.of(child.revisionId()), error.observed());
        assertEquals(before, repository.revisions().size());
        assertEquals(child, repository.current(NOTE));
    }

    @Test
    public void tombstoneAndExactConflictResolutionUseObservedHeads() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        RevisionEnvelope root = repository.createRevision(
                NOTE, Set.of(), "base", "android", at(0), false);
        RevisionEnvelope linux = RevisionEnvelope.create(
                NOTE, List.of(root.revisionId()), "linux", at(1), false, "linux edit");
        RevisionEnvelope android = RevisionEnvelope.create(
                NOTE, List.of(root.revisionId()), "android", at(2), false, "android edit");
        repository.importEnvelope(linux.serialize(), "linux.md");
        repository.importEnvelope(android.serialize(), "android.md");
        Set<String> observed = Set.of(linux.revisionId(), android.revisionId());

        RevisionEnvelope resolution = repository.resolveConflict(
                NOTE, observed, linux.revisionId(), "android", at(3));

        assertEquals(observed, Set.copyOf(resolution.parents()));
        assertEquals("linux edit", resolution.body());
        RevisionEnvelope tombstone = repository.createRevision(
                NOTE, Set.of(resolution.revisionId()), "", "android", at(4), true);
        assertTrue(tombstone.deleted());
        assertFalse(new File(repository.root(), "current/" + NOTE + ".md").exists());
    }

    @Test
    public void ancestryIsCompleteSortedAndLimitedToRequestedHistory() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        RevisionEnvelope root = repository.createRevision(NOTE, Set.of(), "root", "android", at(0), false);
        RevisionEnvelope child = repository.createRevision(
                NOTE, Set.of(root.revisionId()), "child", "android", at(1), false);
        RevisionEnvelope leaf = repository.createRevision(
                NOTE, Set.of(child.revisionId()), "leaf", "android", at(2), false);
        repository.createRevision(OTHER, Set.of(), "other", "android", at(3), false);

        assertEquals(
                Set.of(root.revisionId(), child.revisionId(), leaf.revisionId()),
                repository.ancestorRevisionIdsInclusive(Set.of(leaf.revisionId())));
    }

    @Test
    public void incomingImportCannotCrossGuardedHeadValidationAndImmutableWrite() throws Exception {
        File rootDirectory = temporary.newFolder("repo");
        ProbeRepository writer = ProbeRepository.open(rootDirectory);
        ProbeRepository importer = ProbeRepository.open(rootDirectory);
        RevisionEnvelope root = writer.createRevision(NOTE, Set.of(), "root", "android", at(0), false);
        RevisionEnvelope incoming = RevisionEnvelope.create(
                NOTE, List.of(root.revisionId()), "linux", at(1), false, "incoming");
        String deliberatelySlowBody = "x".repeat(32 * 1024 * 1024);
        CountDownLatch writerStarted = new CountDownLatch(1);
        AtomicLong localFinished = new AtomicLong();
        AtomicLong remoteFinished = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RevisionEnvelope> local = executor.submit(() -> {
                writerStarted.countDown();
                RevisionEnvelope result = writer.createRevision(
                        NOTE, Set.of(root.revisionId()), deliberatelySlowBody, "android", at(2), false);
                localFinished.set(System.nanoTime());
                return result;
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(5);
            Future<ProbeRepository.ImportOutcome> remote = executor.submit(() -> {
                ProbeRepository.ImportOutcome result = importer.importEnvelope(incoming.serialize(), "incoming.md");
                remoteFinished.set(System.nanoTime());
                return result;
            });

            RevisionEnvelope localRevision = local.get(30, TimeUnit.SECONDS);
            assertEquals("imported", remote.get(30, TimeUnit.SECONDS).kind());
            assertTrue(
                    "incoming import completed inside guarded validation/write transaction",
                    localFinished.get() <= remoteFinished.get());
            assertEquals(
                    Set.of(localRevision.revisionId(), incoming.revisionId()),
                    writer.headIds(NOTE));
        } finally {
            executor.shutdownNow();
        }
    }
}
