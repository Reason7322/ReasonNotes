package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

public final class SyncCoordinatorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE_A = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID NOTE_B = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private record Fixture(
            File root,
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receipts,
            SyncPlanner planner) {}

    private Fixture fixture() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore receipts = new ReceiptStore(root, 3);
        return new Fixture(root, repository, ledger, receipts,
                new SyncPlanner(repository, ledger, receipts, "android", "linux"));
    }

    @Test
    public void flushFailureAbortsBeforeScanOrPlanning() throws Exception {
        Fixture fixture = fixture();
        ArrayList<String> order = new ArrayList<>();
        SyncCoordinator coordinator = coordinator(
                fixture,
                () -> { order.add("flush"); return new SyncCoordinator.FlushEvidence(false, "draft failed"); },
                () -> { order.add("scan"); return scan(0, 0, 0); },
                files -> { order.add("send"); return new SyncCoordinator.RequestEvidence(true, "accepted"); });

        SyncStatus status = coordinator.synchronize(OutgoingSelection.all(), true);

        assertEquals(List.of("flush"), order);
        assertEquals(SyncStatus.Phase.ERROR, status.phase());
        assertTrue(status.detail().contains("draft failed"));
    }

    @Test
    public void scansBeforePlanningAndSendsReceiptsEvenForEmptySelection() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope remote = RevisionEnvelope.create(
                NOTE_B, List.of(), "linux", NOW.minusSeconds(3), false, "# Incoming");
        ArrayList<String> order = new ArrayList<>();
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = coordinator(
                fixture,
                () -> { order.add("flush"); return SyncCoordinator.FlushEvidence.success(); },
                () -> {
                    order.add("scan");
                    ProbeRepository.ImportOutcome outcome = fixture.repository().importEnvelope(
                            remote.serialize(), "linux.md");
                    fixture.ledger().recordImported(remote.revisionId(), NOW, "test");
                    fixture.receipts().queue(remote, "android", NOW);
                    return scan(1, 0, 1);
                },
                files -> {
                    order.add("send");
                    sent.addAll(files);
                    return new SyncCoordinator.RequestEvidence(true, "accepted");
                });

        SyncStatus status = coordinator.synchronize(
                OutgoingSelection.selected(Set.of()), false);

        assertEquals(List.of("flush", "scan", "send"), order);
        assertEquals(1, sent.size());
        assertEquals(SyncCoordinator.FileKind.RECEIPT, sent.get(0).kind());
        assertEquals(1, status.imported());
        assertEquals(1, status.receiptsQueued());
        assertEquals(1, status.receiptsRequested());
        assertEquals(0, status.revisionsRequested());
    }

    @Test
    public void selectedPlanIsTheOnlyRevisionSetOfferedToSender() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope a = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# A", "android", NOW.minusSeconds(2), false);
        RevisionEnvelope b = fixture.repository().createRevision(
                NOTE_B, Set.of(), "# B", "android", NOW.minusSeconds(1), false);
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = coordinator(
                fixture,
                SyncCoordinator.FlushEvidence::success,
                () -> scan(0, 0, 0),
                files -> { sent.addAll(files); return new SyncCoordinator.RequestEvidence(true, "accepted"); });

        coordinator.synchronize(OutgoingSelection.selected(Set.of(NOTE_A)), false);

        assertEquals(List.of(a.revisionId()), sent.stream().map(SyncCoordinator.OutgoingFile::identity).toList());
        assertEquals("transfer-requested", fixture.ledger().entry(a.revisionId(), "linux").state());
        assertEquals("pending", fixture.ledger().entry(b.revisionId(), "linux").state());
    }

    @Test
    public void rejectedRequestDoesNotAdvanceLedgerAndRemainsEligible() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope revision = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# A", "android", NOW.minusSeconds(1), false);
        SyncCoordinator coordinator = coordinator(
                fixture,
                SyncCoordinator.FlushEvidence::success,
                () -> scan(0, 0, 0),
                files -> new SyncCoordinator.RequestEvidence(false, "no share activity"));

        SyncStatus status = coordinator.synchronize(OutgoingSelection.all(), false);

        assertEquals(SyncStatus.Phase.ERROR, status.phase());
        assertEquals("pending", fixture.ledger().entry(revision.revisionId(), "linux").state());
        assertEquals(List.of(revision.revisionId()),
                fixture.planner().plan(OutgoingSelection.all(), false).revisionIds());
    }

    @Test
    public void scanCanInvalidateDraftBaseWithoutReplacingDraft() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope base = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Base", "android", NOW.minusSeconds(4), false);
        DraftStore drafts = new DraftStore(fixture.root());
        DraftStore.Draft draft = drafts.write(NOTE_A, base.revisionId(), "Local draft", "unsaved body");
        RevisionEnvelope remote = RevisionEnvelope.create(
                NOTE_A, List.of(base.revisionId()), "linux", NOW.minusSeconds(2), false, "# Remote");
        SyncCoordinator coordinator = coordinator(
                fixture,
                SyncCoordinator.FlushEvidence::success,
                () -> {
                    fixture.repository().importEnvelope(remote.serialize(), "remote.md");
                    return scan(1, 0, 1);
                },
                files -> new SyncCoordinator.RequestEvidence(true, "accepted"));

        coordinator.synchronize(OutgoingSelection.selected(Set.of()), false);

        DraftStore.Draft preserved = drafts.current(NOTE_A);
        assertNotNull(preserved);
        assertEquals(draft.generation(), preserved.generation());
        assertEquals("unsaved body", preserved.content());
        assertFalse(drafts.recovery(NOTE_A, remote.revisionId()).safeToApply());
    }

    @Test
    public void physicalKdeConnectIdMigratesAndLogicalLinuxPeerDrivesReceiptRoundTrip() throws Exception {
        Fixture fixture = fixture();
        String physical = AppPreferences.DEFAULT_TARGET_DEVICE_ID;
        RevisionEnvelope outgoing = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Existing Android note", "android", NOW.minusSeconds(3), false);
        fixture.ledger().registerLocal(outgoing.revisionId(), physical);
        fixture.ledger().recordRequested(outgoing.revisionId(), physical, NOW.minusSeconds(2));
        assertEquals("linux", SyncActivity.preparePeerLedger(fixture.ledger(), physical));
        assertEquals(1, fixture.ledger().entry(outgoing.revisionId(), "linux").attempts());

        RevisionEnvelope imported = RevisionEnvelope.create(
                NOTE_B, List.of(), "linux", NOW.minusSeconds(3), false, "# Fedora test");
        fixture.repository().importEnvelope(imported.serialize(), "incoming.md");
        ReceiptEnvelope queued = fixture.receipts().queue(imported, "android", NOW.minusSeconds(1));
        ArrayList<SyncCoordinator.OutgoingFile> sent = new ArrayList<>();
        SyncCoordinator coordinator = coordinator(
                fixture, SyncCoordinator.FlushEvidence::success,
                () -> scan(0, 0, 0),
                files -> {
                    sent.addAll(files);
                    return new SyncCoordinator.RequestEvidence(true, "share activity launched");
                });
        SyncStatus status = coordinator.synchronize(OutgoingSelection.selected(Set.of()), false);
        assertEquals(0, status.revisionsRequested());
        assertEquals(1, status.receiptsRequested());
        assertEquals(List.of(queued.receiptId()),
                sent.stream().map(SyncCoordinator.OutgoingFile::identity).toList());
        assertEquals("transfer-requested",
                fixture.receipts().entry(queued.receiptId(), "linux").state());

        ReceiptEnvelope remoteReceipt = ReceiptEnvelope.create(
                outgoing.noteId(), outgoing.revisionId(), "linux", "android", NOW);
        ReceiptStore.ImportOutcome confirmation = fixture.receipts().accept(
                remoteReceipt.serialize(), fixture.repository(), fixture.ledger(),
                "android", "linux-receipt.md", NOW.plusSeconds(1));
        assertEquals("confirmed", confirmation.kind());
        assertEquals("confirmed",
                fixture.ledger().entry(outgoing.revisionId(), "linux").state());
        assertEquals(remoteReceipt.receiptId(),
                fixture.ledger().entry(outgoing.revisionId(), "linux").receiptId());
    }

    @Test
    public void statusKeepsRequestedImportedAndConfirmedEvidenceSeparate() throws Exception {
        Fixture fixture = fixture();
        fixture.repository().createRevision(
                NOTE_A, Set.of(), "# A", "android", NOW.minusSeconds(1), false);
        SyncCoordinator coordinator = coordinator(
                fixture,
                SyncCoordinator.FlushEvidence::success,
                () -> new SyncCoordinator.ScanEvidence(2, 3, 1, List.of()),
                files -> new SyncCoordinator.RequestEvidence(true, "chooser launched"));

        SyncStatus status = coordinator.synchronize(OutgoingSelection.all(), false);

        assertEquals(SyncStatus.Phase.TRANSFER_REQUESTED, status.phase());
        assertEquals(1, status.revisionsRequested());
        assertEquals(2, status.imported());
        assertEquals(3, status.confirmed());
        assertFalse(status.summary().contains("fully synchronized"));
    }

    private static SyncCoordinator coordinator(
            Fixture fixture,
            SyncCoordinator.DraftFlusher flusher,
            SyncCoordinator.IncomingScanner scanner,
            SyncCoordinator.TransferRequester sender) {
        return new SyncCoordinator(
                fixture.repository(), fixture.ledger(), fixture.receipts(), fixture.planner(),
                "linux", flusher, scanner, sender, () -> NOW);
    }

    private static SyncCoordinator.ScanEvidence scan(int imported, int confirmed, int queued) {
        return new SyncCoordinator.ScanEvidence(imported, confirmed, queued, List.of());
    }
}
