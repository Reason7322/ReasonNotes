package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
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
    public void recognizesCanonicalAndCollisionLegacyEnvelopeNames() {
        String revision = "a".repeat(64);
        String receipt = "b".repeat(64);

        assertEquals(
                new ExchangeDirectory.LegacyIdentity(ExchangeDirectory.Kind.REVISION, revision),
                ExchangeDirectory.legacyIdentity(revision + ".md"));
        assertEquals(
                new ExchangeDirectory.LegacyIdentity(ExchangeDirectory.Kind.REVISION, revision),
                ExchangeDirectory.legacyIdentity(revision + " (3).md"));
        assertEquals(
                new ExchangeDirectory.LegacyIdentity(ExchangeDirectory.Kind.RECEIPT, receipt),
                ExchangeDirectory.legacyIdentity(receipt + ".receipt.md"));
        assertEquals(
                new ExchangeDirectory.LegacyIdentity(ExchangeDirectory.Kind.RECEIPT, receipt),
                ExchangeDirectory.legacyIdentity(receipt + ".receipt (2).md"));
        assertEquals(null, ExchangeDirectory.legacyIdentity("reasonnotes-valid.md"));
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
                new TransportBundle.Item("receipt", confirmation.receiptId(), confirmation.serialize())));

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
    public void invalidLaterBundleItemCannotPartiallyMutateRepository() throws Exception {
        File root = temporary.newFolder("invalid-bundle-repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore store = new ReceiptStore(root, 3);
        Instant started = Instant.parse("2026-10-22T11:00:00Z");
        RevisionEnvelope first = RevisionEnvelope.create(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                List.of(), "linux", started, false, "first");
        RevisionEnvelope second = RevisionEnvelope.create(
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                List.of(), "linux", started.plusSeconds(1), false, "second");
        TransportBundle.Carrier valid = TransportBundle.create(List.of(
                new TransportBundle.Item("revision", first.revisionId(), first.serialize()),
                new TransportBundle.Item("revision", second.revisionId(), second.serialize())));
        byte[] invalid = new String(valid.payload(), StandardCharsets.UTF_8)
                .replace(second.revisionId(), "f".repeat(64))
                .getBytes(StandardCharsets.UTF_8);
        String invalidName = "reasonnotes-bundle-v1-" + sha256(invalid) + ".json";

        assertThrows(IllegalArgumentException.class, () -> ExchangeDirectory.applyTransportBundle(
                invalid, invalidName, repository, ledger, store, "android", started.plusSeconds(2)));

        assertEquals(0, repository.revisions().size());
        File quarantine = new File(root, "quarantine");
        assertEquals(0, quarantine.listFiles() == null ? 0 : quarantine.listFiles().length);
        assertEquals(0, ledger.events().size());
    }

    private static String sha256(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte next : MessageDigest.getInstance("SHA-256").digest(value)) {
            result.append(String.format("%02x", next & 0xff));
        }
        return result.toString();
    }
}
