package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class IncomingReceiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void receiveOnlyImportQueuesAcknowledgmentWithoutRequestingATransfer() throws Exception {
        File root = temporary.newFolder("repository");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore store = new ReceiptStore(root, 3);
        RevisionEnvelope revision = RevisionEnvelope.create(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                List.of(), "linux", Instant.parse("2026-10-15T18:00:00Z"), false,
                "# PC Note\n\nThis is a note from Fedora");
        Instant observed = Instant.parse("2026-10-15T18:00:10Z");

        IncomingReceive.Scanner incoming = () -> {
            ProbeRepository.ImportOutcome imported = repository.importEnvelope(
                    revision.serialize(), revision.revisionId() + ".md");
            boolean queued = ExchangeDirectory.finishRevisionImport(
                    imported, repository, ledger, store, "android",
                    revision.revisionId() + ".md", observed);
            return new ExchangeDirectory.ScanReport(
                    imported.kind().equals("imported") ? 1 : 0,
                    imported.kind().equals("duplicate") ? 1 : 0,
                    0, 0, queued ? 1 : 0, 0, 0, 0, List.of());
        };

        ExchangeDirectory.ScanReport first = IncomingReceive.run(incoming);
        assertEquals(1, first.imported());
        assertEquals(1, first.receiptsQueued());
        assertEquals("Received 1 note revisions", IncomingReceive.status(first));
        assertEquals(revision, repository.current(revision.noteId()));
        assertEquals(1, store.entries("linux").size());
        assertEquals("pending", store.entries("linux").get(0).state());
        assertEquals(0, store.entries("linux").get(0).attempts());
        assertEquals(
                List.of("reasonnotes-imported"),
                ledger.events().stream().map(DeliveryLedger.Event::kind).toList());

        // Re-entering the app must not create new receipts or transfer requests.
        ExchangeDirectory.ScanReport second = IncomingReceive.run(incoming);
        assertEquals(0, second.imported());
        assertEquals(1, second.duplicates());
        assertEquals(0, second.receiptsQueued());
        assertNull(IncomingReceive.status(second));
        assertEquals(1, store.entries("linux").size());
        assertEquals(0, store.entries("linux").get(0).attempts());
        assertEquals(1, ledger.events().size());
    }

    @Test
    public void receiveStatusHandlesConfirmationsAndErrorsWithoutInventingSuccess() {
        assertEquals("Confirmed 1 deliveries", IncomingReceive.status(
                new ExchangeDirectory.ScanReport(0, 0, 1, 0, 0, 0, 0, 0, List.of())));
        assertEquals("Received 2 revisions · confirmed 1", IncomingReceive.status(
                new ExchangeDirectory.ScanReport(2, 0, 1, 0, 0, 0, 0, 0, List.of())));
        assertEquals("Incoming check: document tree permission denied", IncomingReceive.status(
                new ExchangeDirectory.ScanReport(
                        0, 0, 0, 0, 0, 0, 0, 0, List.of("document tree permission denied"))));
        assertNull(IncomingReceive.status(new ExchangeDirectory.ScanReport(
                0, 2, 0, 1, 0, 0, 0, 25, List.of())));
        assertEquals(
                "Received 2 note revisions · Incoming check: unexpected receipt",
                IncomingReceive.status(new ExchangeDirectory.ScanReport(
                        2, 20, 0, 0, 2, 0, 1, 0, List.of("unexpected receipt"))));
    }

    @Test
    public void receiveOnlyEntryPointExposesNoOutboundTransferMechanism() {
        for (var method : IncomingReceive.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("transfer"));
            assertFalse(method.getName().toLowerCase().contains("send"));
            assertFalse(method.getName().toLowerCase().contains("syncout"));
        }
        assertTrue(ExchangeDirectory.classify(
                ("---\nreasonnotes-format: 1\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
                == ExchangeDirectory.Kind.REVISION);
    }
}
