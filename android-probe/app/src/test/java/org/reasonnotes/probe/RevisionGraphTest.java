package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class RevisionGraphTest {
    private static final UUID NOTE_ID = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");

    private static RevisionEnvelope revision(String body, List<String> parents, String device, int second) {
        return RevisionEnvelope.create(
                NOTE_ID,
                parents,
                device,
                Instant.parse(String.format("2026-10-12T10:00:%02dZ", second)),
                false,
                body);
    }

    @Test
    public void unknownParentIsPendingUntilParentArrives() {
        RevisionEnvelope parent = revision("parent", List.of(), "linux", 0);
        RevisionEnvelope child = revision("child", List.of(parent.revisionId()), "android", 1);
        RevisionGraph graph = new RevisionGraph();
        graph.add(child);
        assertEquals(List.of(child.revisionId()), graph.pending(NOTE_ID));
        assertEquals(List.of(), graph.heads(NOTE_ID));
        graph.add(parent);
        assertEquals(List.of(), graph.pending(NOTE_ID));
        assertEquals(List.of(child), graph.heads(NOTE_ID));
    }

    @Test
    public void resolutionAcknowledgesBothHeadsAndLateAncestorDoesNotResurrect() {
        RevisionEnvelope root = revision("root", List.of(), "linux", 0);
        RevisionEnvelope linux = revision("linux", List.of(root.revisionId()), "linux", 1);
        RevisionEnvelope android = revision("android", List.of(root.revisionId()), "android", 2);
        RevisionGraph graph = new RevisionGraph();
        graph.add(root);
        graph.add(linux);
        graph.add(android);
        RevisionEnvelope resolution = graph.resolve(
                NOTE_ID, linux.revisionId(), "linux", Instant.parse("2026-10-12T10:00:03Z"));
        assertEquals(List.of(android.revisionId(), linux.revisionId()).stream().sorted().toList(), resolution.parents());
        assertEquals(List.of(resolution), graph.heads(NOTE_ID));
        graph.add(root);
        assertEquals(List.of(resolution), graph.heads(NOTE_ID));
    }

    @Test
    public void staleResolutionLeavesConcurrentHead() {
        RevisionEnvelope root = revision("root", List.of(), "linux", 0);
        RevisionEnvelope linux = revision("linux", List.of(root.revisionId()), "linux", 1);
        RevisionEnvelope android = revision("android", List.of(root.revisionId()), "android", 2);
        RevisionGraph partial = new RevisionGraph();
        partial.add(root);
        partial.add(linux);
        partial.add(android);
        RevisionEnvelope resolution = partial.resolve(
                NOTE_ID, linux.revisionId(), "linux", Instant.parse("2026-10-12T10:00:03Z"));
        RevisionEnvelope third = revision("third", List.of(root.revisionId()), "android-2", 4);
        partial.add(third);
        assertEquals(2, partial.heads(NOTE_ID).size());
        assertTrue(partial.heads(NOTE_ID).contains(resolution));
        assertTrue(partial.heads(NOTE_ID).contains(third));
    }
}
