package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CurrentStateSyncTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID A = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-10-24T18:00:00Z");

    @Test public void v2BundleValidatesStateAndAckKinds() {
        StateEnvelope state = StateEnvelope.createUpsert(A, List.of(), "android", NOW, "A", "body");
        StateAckEnvelope ack = StateAckEnvelope.create(
                A, state.changeId(), "linux", "android", NOW.plusSeconds(1));

        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("state-v2", state.changeId(), state.serialize()),
                new TransportBundle.Item("ack-v2", ack.ackId(), ack.serialize())));
        TransportBundle.Carrier parsed = TransportBundle.parse(carrier.payload(), carrier.filename());

        assertEquals(List.of("ack-v2", "state-v2"),
                parsed.items().stream().map(TransportBundle.Item::kind).toList());
    }

    @Test public void importAppliesStatesBeforeAcksAndIsIdempotentWhenReordered() throws Exception {
        CurrentNoteStore sender = CurrentNoteStore.open(temporary.newFolder("sender"), "linux");
        CurrentNoteStore receiver = CurrentNoteStore.open(temporary.newFolder("receiver"), "android");
        StateEnvelope local = receiver.saveLocal(A, "Android", "old", NOW);
        StateEnvelope incoming = StateEnvelope.createUpsert(
                B, List.of(), "linux", NOW.plusSeconds(1), "Linux", "new");
        StateEnvelope sent = sender.saveLocal(A, "Android", "old", NOW);
        StateAckEnvelope confirmation = StateAckEnvelope.create(
                A, local.changeId(), "linux", "android", NOW.plusSeconds(2));
        // sender state only supplies realistic independently durable data.
        assertFalse(sent.changeId().isEmpty());

        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("ack-v2", confirmation.ackId(), confirmation.serialize()),
                new TransportBundle.Item("state-v2", incoming.changeId(), incoming.serialize())));
        CurrentTransport.ScanReport first = CurrentTransport.applyBundle(
                carrier.payload(), carrier.filename(), receiver, "android");
        CurrentTransport.ScanReport replay = CurrentTransport.applyBundle(
                carrier.payload(), carrier.filename(), receiver, "android");

        assertEquals(1, first.imported());
        assertEquals(1, first.confirmed());
        assertTrue(first.errors().isEmpty());
        assertEquals("new", receiver.get(B).body());
        assertTrue(receiver.pendingStates().stream().noneMatch(value -> value.noteId().equals(A)));
        assertEquals(0, replay.imported());
        assertEquals(0, replay.confirmed());
        assertTrue(replay.duplicates() >= 1);
    }

    @Test public void malformedItemDoesNotBlockIndependentValidState() throws Exception {
        CurrentNoteStore receiver = CurrentNoteStore.open(temporary.newFolder("independent"), "android");
        StateEnvelope good = StateEnvelope.createUpsert(B, List.of(), "linux", NOW, "Good", "safe");
        StateEnvelope wrongDestinationState = StateEnvelope.createUpsert(
                A, List.of(), "android", NOW, "Wrong", "local echo");
        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("state-v2", good.changeId(), good.serialize()),
                new TransportBundle.Item("state-v2", wrongDestinationState.changeId(),
                        wrongDestinationState.serialize())));

        CurrentTransport.ScanReport report = CurrentTransport.applyBundle(
                carrier.payload(), carrier.filename(), receiver, "android");

        assertEquals(1, report.imported());
        assertEquals("safe", receiver.get(B).body());
        assertEquals(1, report.errors().size());
    }

    @Test public void failedRequestLeavesPendingAndNextRequestRetriesWithoutExhaustion() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("retry"), "android");
        StateEnvelope a = store.saveLocal(A, "A", "one", NOW);
        store.saveLocal(B, "B", "two", NOW.plusSeconds(1));
        ArrayList<List<SyncCoordinator.OutgoingFile>> attempts = new ArrayList<>();
        SyncCoordinator failed = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> { attempts.add(files); return new SyncCoordinator.RequestEvidence(false, "offline"); },
                () -> NOW.plusSeconds(2));

        SyncStatus first = failed.synchronize(OutgoingSelection.selected(Set.of(A)), false);
        SyncStatus second = failed.synchronize(OutgoingSelection.selected(Set.of(A)), false);

        assertEquals(SyncStatus.Phase.ERROR, first.phase());
        assertEquals(SyncStatus.Phase.ERROR, second.phase());
        assertEquals(2, attempts.size());
        assertEquals(List.of(a.changeId()), attempts.get(0).stream()
                .filter(file -> file.kind() == SyncCoordinator.FileKind.STATE)
                .map(SyncCoordinator.OutgoingFile::identity).toList());
        assertEquals(2, store.pendingStates().size());
    }

    @Test public void explicitSyncRestagesConfirmedUnchangedLiveNote() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("restage"), "android");
        StateEnvelope live = store.saveLocal(A, "A", "unchanged", NOW);
        store.acknowledge(live.changeId());
        assertTrue(store.pendingStates().isEmpty());
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> { sent.addAll(files); return new SyncCoordinator.RequestEvidence(true, "accepted"); },
                () -> NOW.plusSeconds(1));

        SyncStatus result = coordinator.synchronize(OutgoingSelection.selected(Set.of(A)), false);

        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, result.phase());
        assertEquals(List.of(live.changeId()), sent.stream()
                .filter(file -> file.kind() == SyncCoordinator.FileKind.STATE)
                .map(SyncCoordinator.OutgoingFile::identity).toList());
        assertEquals(List.of(live), store.pendingStates());
    }

    @Test public void oneSyncFromSurvivingDeviceRestoresLocallyDeletedReceiver() throws Exception {
        CurrentNoteStore sender = CurrentNoteStore.open(temporary.newFolder("restore-sender"), "android");
        CurrentNoteStore receiver = CurrentNoteStore.open(temporary.newFolder("restore-receiver"), "linux");
        StateEnvelope live = sender.saveLocal(A, "A", "survives", NOW);
        receiver.apply(live);
        receiver.deleteLocal(A);
        sender.acknowledge(live.changeId());
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                sender, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> { sent.addAll(files); return new SyncCoordinator.RequestEvidence(true, "accepted"); },
                () -> NOW.plusSeconds(1));

        coordinator.synchronize(OutgoingSelection.selected(Set.of(A)), false);
        SyncCoordinator.OutgoingFile stateFile = sent.stream()
                .filter(file -> file.kind() == SyncCoordinator.FileKind.STATE).findFirst().orElseThrow();
        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("state-v2", stateFile.identity(),
                        java.nio.file.Files.readAllBytes(stateFile.file().toPath()))));
        CurrentTransport.ScanReport restored = CurrentTransport.applyBundle(
                carrier.payload(), carrier.filename(), receiver, "linux");

        assertEquals(1, restored.imported());
        assertEquals("survives", receiver.get(A).body());
    }

    @Test public void bothLocallyDeletedDevicesHaveNoStateToSend() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("both-delete"), "android");
        store.saveLocal(A, "A", "gone", NOW);
        store.deleteLocal(A);
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> { sent.addAll(files); return new SyncCoordinator.RequestEvidence(true, "unexpected"); },
                () -> NOW.plusSeconds(1));

        SyncStatus result = coordinator.synchronize(OutgoingSelection.all(), false);

        assertEquals(SyncStatus.Phase.COMPLETE, result.phase());
        assertTrue(sent.isEmpty());
    }

    @Test public void rejectedHistoricalFileDoesNotBlockIndependentOutgoingWork() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(
                temporary.newFolder("historical-warning"), "android");
        StateEnvelope pending = store.saveLocal(A, "A", "safe", NOW);
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(
                        0, 0, 0, List.of("reasonnotes-partial.md: truncated envelope")),
                files -> {
                    sent.addAll(files);
                    return new SyncCoordinator.RequestEvidence(true, "accepted");
                },
                () -> NOW.plusSeconds(1));

        SyncStatus result = coordinator.synchronize(
                OutgoingSelection.selected(Set.of(A)), false);

        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, result.phase());
        assertEquals(List.of(pending.changeId()), sent.stream()
                .filter(file -> file.kind() == SyncCoordinator.FileKind.STATE)
                .map(SyncCoordinator.OutgoingFile::identity).toList());
    }

    @Test public void unavailableExchangeStorageStillBlocksOutgoingWork() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(
                temporary.newFolder("fatal-scan"), "android");
        store.saveLocal(A, "A", "safe", NOW);
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(
                        0, 0, 0, List.of("shared exchange directory is unavailable")),
                files -> {
                    sent.addAll(files);
                    return new SyncCoordinator.RequestEvidence(true, "must not send");
                },
                () -> NOW.plusSeconds(1));

        SyncStatus result = coordinator.synchronize(
                OutgoingSelection.selected(Set.of(A)), false);

        assertEquals(SyncStatus.Phase.ERROR, result.phase());
        assertTrue(sent.isEmpty());
    }

    @Test public void operationStatusTracksOnlyRequestedSelectionAndDurableAck() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("status"), "android");
        StateEnvelope a = store.saveLocal(A, "A", "one", NOW);
        store.saveLocal(B, "B", "two", NOW.plusSeconds(1));
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> new SyncCoordinator.RequestEvidence(true, "accepted"),
                () -> NOW.plusSeconds(2));

        SyncStatus requested = coordinator.synchronize(OutgoingSelection.selected(Set.of(A)), false);
        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, requested.phase());
        assertEquals(1, requested.revisionsRequested());
        assertTrue(requested.detail().contains(a.changeId()));

        StateAckEnvelope ack = StateAckEnvelope.create(
                A, a.changeId(), "linux", "android", NOW.plusSeconds(3));
        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("ack-v2", ack.ackId(), ack.serialize())));
        CurrentTransport.applyBundle(carrier.payload(), carrier.filename(), store, "android");

        assertEquals(List.of(B), store.pendingStates().stream().map(StateEnvelope::noteId).toList());
        SyncStatus repeatedExplicitSync = coordinator.synchronize(
                OutgoingSelection.selected(Set.of(A)), false);
        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, repeatedExplicitSync.phase());
        assertEquals(1, repeatedExplicitSync.revisionsRequested());
    }

    @Test public void conflictedNoteDoesNotBlockIndependentPendingNote() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("conflict-progress"), "android");
        StateEnvelope base = store.saveLocal(A, "A", "base", NOW);
        store.acknowledge(base.changeId());
        StateEnvelope local = store.saveLocal(A, "A local", "local", NOW.plusSeconds(1));
        StateEnvelope remote = StateEnvelope.createUpsert(
                A, List.of(base.changeId()), "linux", NOW.plusSeconds(2), "A remote", "remote");
        assertEquals("conflict", store.apply(remote).kind());
        StateEnvelope independent = store.saveLocal(B, "B", "safe", NOW.plusSeconds(3));
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> { sent.addAll(files); return new SyncCoordinator.RequestEvidence(true, "accepted"); },
                () -> NOW.plusSeconds(4));

        SyncStatus result = coordinator.synchronize(OutgoingSelection.all(), false);

        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, result.phase());
        assertEquals(List.of(independent.changeId()), sent.stream()
                .filter(file -> file.kind() == SyncCoordinator.FileKind.STATE)
                .map(SyncCoordinator.OutgoingFile::identity).toList());
        assertTrue(result.detail().contains(A.toString()));
        assertTrue(store.pendingStates().stream()
                .anyMatch(state -> state.changeId().equals(local.changeId())));
    }

    @Test public void onlyConflictSendsAckButStillReturnsNamedAttention() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("only-conflict"), "android");
        StateEnvelope base = store.saveLocal(A, "A", "base", NOW);
        store.acknowledge(base.changeId());
        store.saveLocal(A, "A local", "local", NOW.plusSeconds(1));
        StateEnvelope remote = StateEnvelope.createUpsert(
                A, List.of(base.changeId()), "linux", NOW.plusSeconds(2), "A remote", "remote");
        assertEquals("conflict", store.apply(remote).kind());
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> new SyncCoordinator.RequestEvidence(true, "must not send"),
                () -> NOW.plusSeconds(3));

        SyncStatus result = coordinator.synchronize(OutgoingSelection.selected(Set.of(A)), false);

        assertEquals(SyncStatus.Phase.ERROR, result.phase());
        assertEquals(0, result.revisionsRequested());
        assertEquals(1, result.receiptsRequested());
        assertTrue(result.detail().contains(A.toString()));
        assertTrue(result.detail().contains("conflict"));
    }

    @Test public void importedLiveStateAndItsAcknowledgmentAreBothSentOnExplicitSync() throws Exception {
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("ack-only"), "android");
        StateEnvelope incoming = StateEnvelope.createUpsert(
                A, List.of(), "linux", NOW, "From Linux", "body");
        assertEquals("applied", store.apply(incoming).kind());
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = new SyncCoordinator(
                store, "linux", SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(0, 0, 0, List.of()),
                files -> { sent.addAll(files); return new SyncCoordinator.RequestEvidence(true, "accepted"); },
                () -> NOW.plusSeconds(1));

        SyncStatus result = coordinator.synchronize(OutgoingSelection.all(), false);

        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, result.phase());
        assertEquals(1, result.revisionsRequested());
        assertEquals(1, result.receiptsRequested());
        assertEquals(2, sent.size());
        assertEquals(Set.of(SyncCoordinator.FileKind.ACK, SyncCoordinator.FileKind.STATE),
                sent.stream().map(SyncCoordinator.OutgoingFile::kind)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(store.pendingAcks().isEmpty());
    }
}
