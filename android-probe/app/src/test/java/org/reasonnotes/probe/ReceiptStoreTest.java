package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ReceiptStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant T0 = Instant.parse("2026-10-12T21:00:00Z");

    private RevisionEnvelope local(ProbeRepository repository) {
        return repository.createProbeRevision(NOTE, "local", "linux", T0, false);
    }

    private ReceiptEnvelope receipt(RevisionEnvelope revision) {
        return ReceiptEnvelope.create(
                revision.noteId(), revision.revisionId(), "android", "linux", T0.plusSeconds(2));
    }

    @Test
    public void batchReceiptRequestsAdvanceEachRetryAndRejectInvalidBatchAtomically() throws Exception {
        File root = temporary.newFolder("batch-receipts");
        ReceiptStore store = new ReceiptStore(root, 3);
        RevisionEnvelope remoteA = RevisionEnvelope.create(
                NOTE, List.of(), "linux", T0, false, "# Original");
        RevisionEnvelope remoteB = RevisionEnvelope.create(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                List.of(), "linux", T0.plusSeconds(1), false, "# Second");
        String a = store.queue(remoteA, "android", T0.plusSeconds(2)).receiptId();
        String b = store.queue(remoteB, "android", T0.plusSeconds(3)).receiptId();
        File state = new File(root, "state/receipt-delivery.tsv");
        byte[] before = Files.readAllBytes(state.toPath());
        assertThrows(IllegalArgumentException.class, () -> store.recordRequestedBatch(
                List.of(a, "invalid"), "linux", T0.plusSeconds(4)));
        assertArrayEquals(before, Files.readAllBytes(state.toPath()));
        store.recordRequestedBatch(List.of(a, b), "linux", T0.plusSeconds(5));
        assertEquals(1, store.entry(a, "linux").attempts());
        assertEquals(1, store.entry(b, "linux").attempts());
        store.recordRequestedBatch(List.of(a, b), "linux", T0.plusSeconds(6));
        assertEquals(2, store.entry(a, "linux").attempts());
        assertEquals(2, store.entry(b, "linux").attempts());
    }

    @Test
    public void validReceiptIsStoredBeforeConfirmationAndDuplicateIsIdempotent() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        RevisionEnvelope revision = local(repository);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ledger.registerLocal(revision.revisionId(), "android");
        ledger.recordRequested(revision.revisionId(), "android", T0.plusSeconds(1));
        ReceiptStore store = new ReceiptStore(root, 3);
        ReceiptEnvelope receipt = receipt(revision);

        ReceiptStore.ImportOutcome first = store.accept(
                receipt.serialize(), repository, ledger, "linux", "incoming.md", T0.plusSeconds(3));
        ReceiptStore.ImportOutcome duplicate = store.accept(
                receipt.serialize(), repository, ledger, "linux", "renamed.md", T0.plusSeconds(4));

        assertEquals("confirmed", first.kind());
        assertEquals("duplicate", duplicate.kind());
        assertArrayEquals(receipt.serialize(), Files.readAllBytes(store.inboxFile(receipt.receiptId()).toPath()));
        assertEquals("confirmed", ledger.entry(revision.revisionId(), "android").state());
        assertEquals(receipt.receiptId(), ledger.entry(revision.revisionId(), "android").receiptId());
        assertEquals(1, ledger.events().stream()
                .filter(event -> event.kind().equals("reasonnotes-confirmed")).count());
        assertEquals(1, new File(root, "history/" + NOTE).listFiles().length);
    }

    @Test
    public void revisionSnapshotReceiptValidationIsStrictAndRespectsLaterPermanentPurges()
            throws Exception {
        File root = temporary.newFolder("snapshot-validation");
        ProbeRepository repository = ProbeRepository.open(root);
        RevisionEnvelope revision = local(repository);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ledger.registerLocal(revision.revisionId(), "android");
        ledger.recordRequested(revision.revisionId(), "android", T0.plusSeconds(1));
        ReceiptStore store = new ReceiptStore(root, 3);
        ReceiptEnvelope confirmation = receipt(revision);
        Map<String, RevisionEnvelope> snapshot = Map.of(revision.revisionId(), revision);
        assertEquals("confirmed", store.acceptWithSnapshot(
                confirmation.serialize(), snapshot, repository, ledger,
                "linux", "receipt.md", T0.plusSeconds(4)).kind());
        assertEquals("duplicate", store.acceptWithSnapshot(
                confirmation.serialize(), snapshot, repository, ledger,
                "linux", "receipt.md", T0.plusSeconds(5)).kind());

        ReceiptEnvelope wrongPeer = ReceiptEnvelope.create(
                revision.noteId(), revision.revisionId(), "stranger", "linux",
                T0.plusSeconds(6));
        assertThrows(IllegalArgumentException.class, () -> store.acceptWithSnapshot(
                wrongPeer.serialize(), snapshot, repository, ledger,
                "linux", "wrong.md", T0.plusSeconds(7)));
        ReceiptEnvelope unregistered = ReceiptEnvelope.create(
                revision.noteId(), "b".repeat(64), "android", "linux",
                T0.plusSeconds(8));
        assertThrows(IllegalArgumentException.class, () -> store.acceptWithSnapshot(
                unregistered.serialize(), snapshot, repository, ledger,
                "linux", "missing.md", T0.plusSeconds(9)));

        repository.purgeNote(NOTE);
        assertTrue(repository.isPurged(NOTE));
        // Even a pre-purge snapshot is not permission to confirm a purged note.
        assertThrows(IllegalArgumentException.class, () -> store.acceptWithSnapshot(
                confirmation.serialize(), snapshot, repository, ledger,
                "linux", "after-purge.md", T0.plusSeconds(11)));
        assertEquals(null, repository.revision(revision.revisionId()));
    }

    @Test
    public void secondValidReceiptForSameRevisionAndPeerIsRedundantNotAnError() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        RevisionEnvelope revision = local(repository);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ledger.registerLocal(revision.revisionId(), "android");
        ledger.recordRequested(revision.revisionId(), "android", T0.plusSeconds(1));
        ReceiptStore store = new ReceiptStore(root, 3);
        ReceiptEnvelope accepted = receipt(revision);
        store.accept(
                accepted.serialize(), repository, ledger, "linux", "accepted.md", T0.plusSeconds(3));
        ReceiptEnvelope different = ReceiptEnvelope.create(
                revision.noteId(), revision.revisionId(), "android", "linux", T0.plusSeconds(9));

        ReceiptStore.ImportOutcome redundant = store.accept(
                different.serialize(), repository, ledger, "linux", "different.md", T0.plusSeconds(10));
        assertEquals("duplicate", redundant.kind());
        assertFalse(store.inboxFile(different.receiptId()).exists());
        assertEquals(accepted.receiptId(), ledger.entry(revision.revisionId(), "android").receiptId());
        assertEquals(1, ledger.events().stream()
                .filter(event -> event.kind().equals("reasonnotes-confirmed")).count());
        // Re-scanning old matching and alternate receipts may not stop sync.
        Files.write(store.inboxFile(different.receiptId()).toPath(), different.serialize());
        ReceiptStore.ReconcileReport reconciled = new ReceiptStore(root, 3).reconcileConfirmations(
                repository, ledger, "linux", T0.plusSeconds(11));
        assertTrue(reconciled.errors().isEmpty());
        assertTrue(reconciled.confirmed().isEmpty());
        assertEquals(accepted.receiptId(), ledger.entry(revision.revisionId(), "android").receiptId());
        assertEquals(1, ledger.events().stream()
                .filter(event -> event.kind().equals("reasonnotes-confirmed")).count());
    }

    @Test
    public void receiptSavedBeforePermanentPurgeDoesNotCauseRecoveryErrorsOrResurrection()
            throws Exception {
        File root = temporary.newFolder("purged-receipt-recovery");
        ProbeRepository repository = ProbeRepository.open(root);
        RevisionEnvelope revision = local(repository);
        ReceiptEnvelope old = receipt(revision);
        ReceiptStore store = new ReceiptStore(root, 3);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        File saved = store.inboxFile(old.receiptId());
        repository.purgeNote(NOTE);
        assertTrue(repository.isPurged(NOTE));
        // Normal purge retires old receipts; simulate a previously received
        // stale receipt resurfacing after the durable purge completed.
        saved.getParentFile().mkdirs();
        Files.write(saved.toPath(), old.serialize());
        assertTrue(store.reconcileConfirmations(
                repository, ledger, "linux", T0.plusSeconds(12)).errors().isEmpty());
        assertTrue(saved.isFile()); // Evidence is retained, not deleted.
        assertEquals(null, repository.revision(revision.revisionId()));
        assertTrue(ledger.events().stream()
                .noneMatch(event -> event.kind().equals("reasonnotes-confirmed")));
    }

    @Test
    public void unknownWrongPeerAndNeverRequestedReceiptsAreRejected() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        RevisionEnvelope revision = local(repository);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ledger.registerLocal(revision.revisionId(), "android");
        ReceiptStore store = new ReceiptStore(root, 3);

        assertThrows(IllegalArgumentException.class, () -> store.accept(
                receipt(revision).serialize(), repository, ledger, "linux", "never.md", T0.plusSeconds(3)));
        ledger.recordRequested(revision.revisionId(), "android", T0.plusSeconds(1));
        ReceiptEnvelope wrongPeer = ReceiptEnvelope.create(
                revision.noteId(), revision.revisionId(), "phone", "linux", T0.plusSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> store.accept(
                wrongPeer.serialize(), repository, ledger, "linux", "peer.md", T0.plusSeconds(3)));
        ReceiptEnvelope unknown = ReceiptEnvelope.create(
                revision.noteId(), "b".repeat(64), "android", "linux", T0.plusSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> store.accept(
                unknown.serialize(), repository, ledger, "linux", "unknown.md", T0.plusSeconds(3)));
        assertFalse(store.inboxFile(wrongPeer.receiptId()).exists());
        assertEquals("transfer-requested", ledger.entry(revision.revisionId(), "android").state());
    }

    @Test
    public void startupReconcilesStoredReceiptAfterInterruptedLedgerUpdate() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        RevisionEnvelope revision = local(repository);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ledger.registerLocal(revision.revisionId(), "android");
        ledger.recordRequested(revision.revisionId(), "android", T0.plusSeconds(1));
        ReceiptStore store = new ReceiptStore(root, 3);
        ReceiptEnvelope receipt = receipt(revision);
        store.inboxFile(receipt.receiptId()).getParentFile().mkdirs();
        Files.write(store.inboxFile(receipt.receiptId()).toPath(), receipt.serialize());

        ReceiptStore.ReconcileReport report = new ReceiptStore(root, 3).reconcileConfirmations(
                repository, ledger, "linux", T0.plusSeconds(3));

        assertEquals(List.of(revision.revisionId()), report.confirmed());
        assertEquals("confirmed", ledger.entry(revision.revisionId(), "android").state());
    }

    @Test
    public void receiptOutboxIsDurableIdempotentAndBounded() throws Exception {
        File root = temporary.newFolder("repo");
        ReceiptStore store = new ReceiptStore(root, 3);
        RevisionEnvelope remote = RevisionEnvelope.create(
                NOTE, List.of(), "android", T0, false, "remote");

        ReceiptEnvelope first = store.queue(remote, "linux", T0.plusSeconds(1));
        ReceiptEnvelope duplicate = store.queue(remote, "linux", T0.plusSeconds(9));

        assertEquals(first, duplicate);
        assertEquals(List.of(first.receiptId()), store.eligible("android", false, List.of()));
        for (int second : List.of(2, 3, 4)) {
            store.recordRequested(first.receiptId(), "android", T0.plusSeconds(second));
        }
        assertEquals("needs-attention", new ReceiptStore(root, 3).entry(first.receiptId(), "android").state());
        assertEquals(List.of(), store.eligible("android", true, List.of()));
        assertEquals(List.of(first.receiptId()), store.eligible("android", false, List.of(first.receiptId())));
        assertTrue(store.outboxFile(first.receiptId()).isFile());
    }
}
