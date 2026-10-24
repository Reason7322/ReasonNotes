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
        assertEquals(List.of(first.changeId()), second.parentChangeIds());
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

    @Test public void deletionErasesContentAndRejectsEveryOldLiveCarrier() throws Exception {
        File root = temporary.newFolder("delete");
        CurrentNoteStore store = CurrentNoteStore.open(root, "android");
        StateEnvelope live = store.saveLocal(NOTE, "Secret title", "secret body", NOW);
        StateEnvelope deletion = store.deleteLocal(NOTE, NOW.plusSeconds(1));

        assertNull(store.get(NOTE));
        assertEquals(List.of(deletion), store.pendingStates());
        assertEquals("rejected-deleted", store.apply(live).kind());
        String all = Files.walk(root.toPath()).filter(Files::isRegularFile)
                .map(path -> {
                    try { return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }).reduce("", String::concat);
        assertFalse(all.contains("Secret title"));
        assertFalse(all.contains("secret body"));
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
