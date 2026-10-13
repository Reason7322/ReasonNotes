package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;

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
