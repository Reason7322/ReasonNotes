package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.io.File;
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
        assertEquals(1, ExchangeDirectory.priority("a".repeat(64) + ".receipt.md"));
        assertEquals(0, ExchangeDirectory.priority("ordinary.md"));
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
}
