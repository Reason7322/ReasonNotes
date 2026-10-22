package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ExchangeDirectoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void classifiesRevisionReceiptAndUnrelatedBytesIndependently() {
        RevisionEnvelope revision = RevisionEnvelope.create(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                List.of(), "linux", Instant.parse("2026-10-12T21:00:00Z"), false, "body");
        ReceiptEnvelope receipt = ReceiptEnvelope.create(
                revision.noteId(), revision.revisionId(), "android", "linux",
                Instant.parse("2026-10-12T21:00:01Z"));

        assertEquals(ExchangeDirectory.Kind.REVISION, ExchangeDirectory.classify(revision.serialize()));
        assertEquals(ExchangeDirectory.Kind.RECEIPT, ExchangeDirectory.classify(receipt.serialize()));
        assertEquals(ExchangeDirectory.Kind.UNRELATED, ExchangeDirectory.classify(
                "# ordinary markdown\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void newRevisionsArePrioritizedBeforeOldReceipts() {
        assertEquals(0, ExchangeDirectory.priority("a".repeat(64) + ".md"));
        assertEquals(0, ExchangeDirectory.priority(
                "reasonnotes-bundle-v1-" + "b".repeat(64) + ".json"));
        assertEquals(1, ExchangeDirectory.priority("a".repeat(64) + ".receipt.md"));
        assertEquals(0, ExchangeDirectory.priority("ordinary.md"));
    }

    @Test
    public void discoversOnlyLegacyMarkdownAndCanonicalJsonBundleNames() {
        String bundle = "reasonnotes-bundle-v1-" + "a".repeat(64) + ".json";
        assertEquals(true, ExchangeDirectory.isCandidateFilename("legacy.md"));
        assertEquals(true, ExchangeDirectory.isCandidateFilename(bundle));
        assertEquals(true, ExchangeDirectory.isCandidateFilename(
                bundle.replace(".json", " (3).json")));
        assertEquals(false, ExchangeDirectory.isCandidateFilename("partial.json"));
        assertEquals(false, ExchangeDirectory.isCandidateFilename("archive.zip"));
    }

    @Test
    public void onlyValidatedPurgedNoteReceiptsCanBeSilentlyIgnored() throws Exception {
        File root = temporary.newFolder("obsolete-purged-receipt");
        ProbeRepository repository = ProbeRepository.open(root);
        UUID note = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Instant now = Instant.parse("2026-10-16T18:00:00Z");
        RevisionEnvelope local = repository.createProbeRevision(
                note, "Secret already deleted", "android", now, false);
        ReceiptEnvelope old = ReceiptEnvelope.create(
                note, local.revisionId(), "linux", "android", now.plusSeconds(2));
        ReceiptEnvelope wrongDestination = ReceiptEnvelope.create(
                note, local.revisionId(), "android", "linux", now.plusSeconds(2));
        assertEquals(false, ExchangeDirectory.isObsoletePurgedReceipt(
                old.serialize(), repository, "android"));
        repository.deleteNotes(java.util.Set.of(note), "android", now.plusSeconds(3));
        assertEquals(true, repository.isPurged(note));
        assertEquals(null, repository.revision(local.revisionId()));
        assertEquals(true, ExchangeDirectory.isObsoletePurgedReceipt(
                old.serialize(), repository, "android"));
        assertEquals(false, ExchangeDirectory.isObsoletePurgedReceipt(
                wrongDestination.serialize(), repository, "android"));
        assertThrows(IllegalArgumentException.class,
                () -> ExchangeDirectory.isObsoletePurgedReceipt(
                        "not a valid receipt".getBytes(StandardCharsets.UTF_8),
                        repository, "android"));
        // An unknown non-purged note cannot bypass normal strict validation.
        ReceiptEnvelope unknown = ReceiptEnvelope.create(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "a".repeat(64), "linux", "android", now.plusSeconds(2));
        assertEquals(false, ExchangeDirectory.isObsoletePurgedReceipt(
                unknown.serialize(), repository, "android"));
    }

    @Test
    public void durableRevisionImportRecordsEvidenceBeforeQueuingReceipt() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore store = new ReceiptStore(root, 3);
        RevisionEnvelope revision = RevisionEnvelope.create(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                List.of(), "linux", Instant.parse("2026-10-12T21:00:00Z"), false, "body");
        ProbeRepository.ImportOutcome imported = repository.importEnvelope(
                revision.serialize(), "valid.md");

        boolean queued = ExchangeDirectory.finishRevisionImport(
                imported, repository, ledger, store, "android", "valid.md",
                Instant.parse("2026-10-12T21:00:01Z"));

        assertEquals(true, queued);
        assertEquals(
                List.of("reasonnotes-imported"),
                ledger.events().stream().map(DeliveryLedger.Event::kind).toList());
        assertEquals(1, store.entries("linux").size());

        ProbeRepository.ImportOutcome rejected = repository.importEnvelope(
                "not an envelope".getBytes(StandardCharsets.UTF_8), "bad.md");
        boolean rejectedQueued = ExchangeDirectory.finishRevisionImport(
                rejected, repository, ledger, store, "android", "bad.md",
                Instant.parse("2026-10-12T21:00:02Z"));

        assertEquals(false, rejectedQueued);
        assertEquals(1, ledger.events().size());
        assertEquals(1, store.entries("linux").size());
    }

    @Test
    public void mixedBundleImportsRevisionConfirmsReceiptAndQueuesReturnReceipt() throws Exception {
        File root = temporary.newFolder("bundle-repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore store = new ReceiptStore(root, 3);
        Instant started = Instant.parse("2026-10-22T10:00:00Z");
        RevisionEnvelope local = repository.createProbeRevision(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "local", "android", started, false);
        ledger.registerLocal(local.revisionId(), "linux");
        ledger.recordRequested(local.revisionId(), "linux", started.plusSeconds(1));
        ReceiptEnvelope confirmation = ReceiptEnvelope.create(
                local.noteId(), local.revisionId(), "linux", "android", started.plusSeconds(2));
        RevisionEnvelope remote = RevisionEnvelope.create(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                List.of(), "linux", started.plusSeconds(3), false, "remote body");
        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("revision", remote.revisionId(), remote.serialize()),
                new TransportBundle.Item(
                        "receipt", confirmation.receiptId(), confirmation.serialize())));

        ExchangeDirectory.ScanReport report = ExchangeDirectory.applyTransportBundle(
                carrier.payload(), carrier.filename(), repository, ledger, store,
                "android", started.plusSeconds(4));

        assertEquals(1, report.imported());
        assertEquals(1, report.confirmed());
        assertEquals(1, report.receiptsQueued());
        assertEquals(List.of(), report.errors());
        assertEquals(remote, repository.revision(remote.revisionId()));
        assertEquals("confirmed", ledger.entry(local.revisionId(), "linux").state());
        assertEquals(1, store.entries("linux").size());
    }

    @Test
    public void malformedLaterRevisionCannotConfirmEarlierReceiptOrMutateRepository()
            throws Exception {
        File root = temporary.newFolder("invalid-bundle-repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore store = new ReceiptStore(root, 3);
        Instant started = Instant.parse("2026-10-22T11:00:00Z");
        RevisionEnvelope local = repository.createProbeRevision(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "local", "android", started, false);
        ledger.registerLocal(local.revisionId(), "linux");
        ledger.recordRequested(local.revisionId(), "linux", started.plusSeconds(1));
        ReceiptEnvelope confirmation = ReceiptEnvelope.create(
                local.noteId(), local.revisionId(), "linux", "android", started.plusSeconds(2));
        RevisionEnvelope remote = RevisionEnvelope.create(
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                List.of(), "linux", started.plusSeconds(3), false, "remote body");
        TransportBundle.Carrier valid = TransportBundle.create(List.of(
                new TransportBundle.Item(
                        "receipt", confirmation.receiptId(), confirmation.serialize()),
                new TransportBundle.Item("revision", remote.revisionId(), remote.serialize())));
        byte[] invalid = new String(valid.payload(), StandardCharsets.UTF_8)
                .replace("remote body", "tampered body")
                .getBytes(StandardCharsets.UTF_8);
        String invalidName = "reasonnotes-bundle-v1-" + sha256(invalid) + ".json";

        assertThrows(IllegalArgumentException.class, () -> ExchangeDirectory.applyTransportBundle(
                invalid, invalidName, repository, ledger, store,
                "android", started.plusSeconds(4)));

        assertEquals(1, repository.revisions().size());
        assertEquals(null, repository.revision(remote.revisionId()));
        assertEquals("transfer-requested", ledger.entry(local.revisionId(), "linux").state());
        assertEquals(0, store.entries("linux").size());
        assertEquals(1, ledger.events().stream()
                .filter(event -> event.kind().equals("transfer-requested")).count());
    }

    @Test
    public void repeatedValidatedBundleIsIdempotentAndDoesNotReconfirmOrRequeue()
            throws Exception {
        File root = temporary.newFolder("repeated-bundle-repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore store = new ReceiptStore(root, 3);
        Instant started = Instant.parse("2026-10-22T12:00:00Z");
        RevisionEnvelope local = repository.createProbeRevision(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                "local", "android", started, false);
        ledger.registerLocal(local.revisionId(), "linux");
        ledger.recordRequested(local.revisionId(), "linux", started.plusSeconds(1));
        ReceiptEnvelope confirmation = ReceiptEnvelope.create(
                local.noteId(), local.revisionId(), "linux", "android", started.plusSeconds(2));
        RevisionEnvelope remote = RevisionEnvelope.create(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                List.of(), "linux", started.plusSeconds(3), false, "remote body");
        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item(
                        "receipt", confirmation.receiptId(), confirmation.serialize()),
                new TransportBundle.Item("revision", remote.revisionId(), remote.serialize())));

        ExchangeDirectory.ScanReport first = ExchangeDirectory.applyTransportBundle(
                carrier.payload(), carrier.filename(), repository, ledger, store,
                "android", started.plusSeconds(4));
        long eventCount = ledger.events().size();
        ExchangeDirectory.ScanReport repeated = ExchangeDirectory.applyTransportBundle(
                carrier.payload(), carrier.filename().replace(".json", " (1).json"),
                repository, ledger, store, "android", started.plusSeconds(5));

        assertEquals(1, first.imported());
        assertEquals(1, first.confirmed());
        assertEquals(1, repeated.duplicates());
        assertEquals(1, repeated.duplicateReceipts());
        assertEquals(0, repeated.confirmed());
        assertEquals(0, repeated.receiptsQueued());
        assertEquals(eventCount, ledger.events().size());
        assertEquals(1, store.entries("linux").size());
    }

    private static String sha256(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte next : MessageDigest.getInstance("SHA-256").digest(value)) {
            result.append(String.format("%02x", next & 0xff));
        }
        return result.toString();
    }
}
