package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AutomaticAckReturnTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void returnsOnlyPendingAcknowledgmentsAndRemovesThemAfterAcceptance()
            throws Exception {
        UUID note = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("store"), "android");
        StateEnvelope incoming = StateEnvelope.createUpsert(
                note, List.of(), "linux", Instant.parse("2026-10-26T21:00:00Z"),
                "Restored", "body");
        assertEquals("applied", store.apply(incoming).kind());
        String acknowledgmentId = store.pendingAcks().get(0).ackId();
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();

        SyncCoordinator.RequestEvidence result = AutomaticAckReturn.send(
                store, files -> {
                    sent.addAll(files);
                    return new SyncCoordinator.RequestEvidence(true, "accepted");
                });

        assertTrue(result.requested());
        assertEquals(1, sent.size());
        assertEquals(SyncCoordinator.FileKind.ACK, sent.get(0).kind());
        assertEquals(acknowledgmentId, sent.get(0).identity());
        assertTrue(store.pendingAcks().isEmpty());
        assertTrue(store.pendingStates().isEmpty());
    }

    @Test public void failedRequestKeepsAcknowledgmentRetryable() throws Exception {
        UUID note = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("retry"), "android");
        store.apply(StateEnvelope.createUpsert(
                note, List.of(), "linux", Instant.parse("2026-10-26T21:01:00Z"),
                "Restored", "body"));

        SyncCoordinator.RequestEvidence result = AutomaticAckReturn.send(
                store, files -> new SyncCoordinator.RequestEvidence(false, "offline"));

        assertTrue(!result.requested());
        assertEquals(1, store.pendingAcks().size());
    }
}
