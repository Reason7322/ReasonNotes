package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CurrentNoteStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-24T13:00:00Z");

    @Test public void localEditsAreDurableAndCoalesceToOnePendingState() throws Exception {
        File root = temporary.newFolder("store");
        CurrentNoteStore store = CurrentNoteStore.open(root, "android");
        StateEnvelope first = store.saveLocal(NOTE, "One", "body one", NOW);
        StateEnvelope second = store.saveLocal(NOTE, "Two", "body two", NOW.plusSeconds(1));

        CurrentNoteStore reopened = CurrentNoteStore.open(root, "android");
        assertEquals(second, reopened.get(NOTE));
        assertEquals(List.of(second), reopened.pendingStates());
        assertEquals(first.parentChangeIds(), second.parentChangeIds());
    }

    @Test public void repeatedPendingAutosavesRemainOneBoundedDescendant() throws Exception {
        StateEnvelope base = StateEnvelope.createUpsert(
                NOTE, List.of(), "linux", NOW, "Base", "A");
        CurrentNoteStore android = CurrentNoteStore.open(
                temporary.newFolder("coalesced-android"), "android");
        CurrentNoteStore linux = CurrentNoteStore.open(
                temporary.newFolder("coalesced-linux"), "linux");
        assertEquals("applied", android.apply(base).kind());
        StateEnvelope linuxBase = linux.saveLocal(NOTE, "Base", "A", NOW);
        linux.acknowledge(linuxBase.changeId());
        assertEquals(base, linuxBase);

        StateEnvelope middle = android.saveLocal(
                NOTE, "Middle", "B", NOW.plusSeconds(1));
        StateEnvelope latest = android.saveLocal(
                NOTE, "Latest", "C", NOW.plusSeconds(2));

        assertEquals(List.of(base.changeId()), middle.parentChangeIds());
        assertEquals(List.of(base.changeId()), latest.parentChangeIds());
        assertEquals("applied", linux.apply(latest).kind());
        assertEquals(latest, linux.get(NOTE));

    }

    @Test public void validUnknownAncestryBecomesExplicitConflict() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(
                temporary.newFolder("unknown-ancestry"), "linux");
        StateEnvelope local = store.saveLocal(NOTE, "Local", "complete local", NOW);
        store.acknowledge(local.changeId());
        StateEnvelope remote = StateEnvelope.createUpsert(
                NOTE, List.of("a".repeat(64)), "android", NOW.plusSeconds(1),
                "Remote", "complete remote");

        assertEquals("conflict", store.apply(remote).kind());
        assertEquals(2, store.conflict(NOTE).candidates().size());
    }

    @Test public void concurrentEditsPreserveBothCompleteVersionsUntilResolution() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("conflict"), "android");
        StateEnvelope base = store.saveLocal(NOTE, "Base", "original", NOW);
        store.acknowledge(base.changeId());
        StateEnvelope local = store.saveLocal(NOTE, "Local", "android body", NOW.plusSeconds(1));
        StateEnvelope remote = StateEnvelope.createUpsert(
                NOTE, List.of(base.changeId()), "linux", NOW.plusSeconds(2), "Remote", "linux body");

        assertEquals("conflict", store.apply(remote).kind());
        CurrentNoteStore.Conflict conflict = store.conflict(NOTE);
        assertEquals(List.of(local, remote), conflict.candidates().stream()
                .sorted(java.util.Comparator.comparing(StateEnvelope::originDevice)).toList());

        StateEnvelope resolved = store.resolve(NOTE, remote.changeId(), NOW.plusSeconds(3));
        assertEquals("linux body", resolved.body());
        assertEquals(List.of(local.changeId(), remote.changeId()).stream().sorted().toList(),
                resolved.parentChangeIds());
        assertNull(store.conflict(NOTE));
        assertEquals(List.of(resolved), store.pendingStates());
    }

    @Test public void deletionPermanentlyPurgesEveryNoteSpecificRecordLocally() throws Exception {
        File root = temporary.newFolder("delete");
        CurrentNoteStore store = CurrentNoteStore.open(root, "android");
        store.saveLocal(NOTE, "Secret title", "secret body", NOW);
        File draft = new File(root.getParentFile(), "drafts/" + NOTE + ".draft");
        draft.getParentFile().mkdirs();
        Files.write(draft.toPath(), "draft secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StateEnvelope remote = StateEnvelope.createUpsert(
                NOTE, List.of("a".repeat(64)), "linux", NOW.plusSeconds(1),
                "Remote secret", "remote body");
        assertEquals("conflict", store.apply(remote).kind());
        assertFalse(store.pendingAcks().isEmpty());

        store.deleteLocal(NOTE);

        assertNull(store.get(NOTE));
        assertNull(store.conflict(NOTE));
        assertTrue(store.pendingStates().isEmpty());
        assertTrue(store.pendingAcks().isEmpty());
        assertFalse(draft.exists());
        assertFalse(store.isDeleted(NOTE));
        String all = Files.walk(root.toPath()).filter(Files::isRegularFile)
                .map(path -> {
                    try { return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }).reduce("", String::concat);
        assertFalse(all.contains("Secret title"));
        assertFalse(all.contains("secret body"));
        assertFalse(all.contains("Remote secret"));

        CurrentNoteStore reopened = CurrentNoteStore.open(root, "android");
        assertNull(reopened.get(NOTE));
        assertTrue(reopened.pendingStates().isEmpty());
        assertTrue(reopened.pendingAcks().isEmpty());
    }

    @Test public void incomingDeletionIsIgnoredWithoutAcknowledgment() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("ignore-delete"), "android");
        StateEnvelope live = store.saveLocal(NOTE, "Keep", "body", NOW);
        StateEnvelope deletion = StateEnvelope.createDeletion(
                NOTE, List.of(live.changeId()), "linux", NOW.plusSeconds(1));

        assertEquals("ignored-deletion", store.apply(deletion).kind());
        assertEquals(live, store.get(NOTE));
        assertTrue(store.pendingAcks().isEmpty());
    }

    @Test public void exactAcknowledgmentClearsOnlyMatchingPendingState() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("ack"), "android");
        StateEnvelope first = store.saveLocal(NOTE, "One", "body", NOW);
        store.acknowledge("f".repeat(64));
        assertEquals(List.of(first), store.pendingStates());
        store.acknowledge(first.changeId());
        assertTrue(store.pendingStates().isEmpty());
    }

    @Test public void openingRepairsCommittedCurrentWithInterruptedPendingMove() throws Exception {
        File root = temporary.newFolder("recovery");
        CurrentNoteStore store = CurrentNoteStore.open(root, "android");
        StateEnvelope state = StateEnvelope.createUpsert(NOTE, List.of(), "android", NOW, "Title", "Body");
        store.writeInterruptedLocalForTest(state);

        CurrentNoteStore reopened = CurrentNoteStore.open(root, "android");
        assertEquals(state, reopened.get(NOTE));
        assertEquals(List.of(state), reopened.pendingStates());
    }
}
