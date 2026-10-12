package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ProbeRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");

    private RevisionEnvelope revision(String body, List<String> parents, String device, int second, boolean deleted) {
        return RevisionEnvelope.create(
                NOTE, parents, device, Instant.parse(String.format("2026-10-12T11:00:%02dZ", second)), deleted, body);
    }

    @Test
    public void sequentialImportAndRenamedDuplicateAreIdempotent() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        RevisionEnvelope root = revision("root", List.of(), "linux", 0, false);
        RevisionEnvelope child = revision("child", List.of(root.revisionId()), "android", 1, false);
        assertEquals("imported", repository.importEnvelope(root.serialize(), "root.md").kind());
        assertEquals("imported", repository.importEnvelope(child.serialize(), "child.md").kind());
        assertEquals("duplicate", repository.importEnvelope(child.serialize(), "child (1).md").kind());
        assertEquals(List.of(child), repository.heads(NOTE));
        assertEquals(child, repository.current(NOTE));
    }

    @Test
    public void conflictPreservesHistoryAndResolutionConverges() throws Exception {
        File rootDirectory = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(rootDirectory);
        RevisionEnvelope root = revision("base", List.of(), "linux", 0, false);
        RevisionEnvelope linux = revision("linux", List.of(root.revisionId()), "linux", 1, false);
        RevisionEnvelope android = revision("android", List.of(root.revisionId()), "android", 2, false);
        repository.importEnvelope(root.serialize(), "root.md");
        repository.importEnvelope(linux.serialize(), "linux.md");
        repository.importEnvelope(android.serialize(), "android.md");
        assertEquals(2, repository.heads(NOTE).size());
        assertEquals(linux, repository.current(NOTE));

        RevisionGraph graph = new RevisionGraph();
        graph.add(root);
        graph.add(linux);
        graph.add(android);
        RevisionEnvelope resolution = graph.resolve(
                NOTE, android.revisionId(), "android", Instant.parse("2026-10-12T11:00:03Z"));
        repository.importEnvelope(resolution.serialize(), "resolution.md");
        assertEquals(List.of(resolution), repository.heads(NOTE));
        assertEquals(resolution, repository.current(NOTE));
        assertTrue(new File(rootDirectory, "history/" + NOTE + "/" + linux.revisionId() + ".md").isFile());
    }

    @Test
    public void tombstoneAndUnknownParentDoNotLoseContent() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        RevisionEnvelope root = revision("root", List.of(), "linux", 0, false);
        repository.importEnvelope(root.serialize(), "root.md");
        RevisionEnvelope pending = revision("pending", List.of("f".repeat(64)), "android", 1, false);
        repository.importEnvelope(pending.serialize(), "pending.md");
        assertEquals(root, repository.current(NOTE));
        assertEquals(List.of(pending.revisionId()), repository.pending(NOTE));

        RevisionEnvelope tombstone = revision("", List.of(root.revisionId()), "linux", 2, true);
        repository.importEnvelope(tombstone.serialize(), "delete.md");
        assertTrue(repository.current(NOTE).deleted());
        assertFalse(new File(repository.root(), "current/" + NOTE + ".md").exists());
    }

    @Test
    public void reconcileRepairsHistoryOnlyInterruptionAndMissingView() throws Exception {
        File rootDirectory = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(rootDirectory);
        RevisionEnvelope root = revision("root", List.of(), "linux", 0, false);
        repository.importEnvelope(root.serialize(), "root.md");
        RevisionEnvelope child = revision("recovered", List.of(root.revisionId()), "linux", 1, false);
        File childPath = new File(rootDirectory, "history/" + NOTE + "/" + child.revisionId() + ".md");
        Files.write(childPath.toPath(), child.serialize());
        Files.delete(new File(rootDirectory, "current/" + NOTE + ".md").toPath());

        ProbeRepository reopened = ProbeRepository.open(rootDirectory);

        assertEquals(child, reopened.current(NOTE));
        assertEquals(
                "recovered",
                new String(
                        Files.readAllBytes(new File(rootDirectory, "current/" + NOTE + ".md").toPath()),
                        StandardCharsets.UTF_8));
        assertNull(reopened.current(UUID.fromString("cccccccc-3333-4333-8333-cccccccccccc")));
    }
}
