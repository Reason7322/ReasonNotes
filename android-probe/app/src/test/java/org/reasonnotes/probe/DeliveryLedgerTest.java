package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DeliveryLedgerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String REVISION = "8".repeat(64);
    private static final String RECEIPT = "9".repeat(64);

    @Test
    public void defaultOnceExplicitRetriesBoundAtThreeAndForceRemainsAvailable() throws Exception {
        DeliveryLedger ledger = new DeliveryLedger(temporary.newFolder("state"), 3);
        ledger.registerLocal(REVISION, "linux");
        assertEquals(List.of(REVISION), ledger.eligible("linux", false, List.of()));
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:00Z"));
        assertEquals(List.of(), ledger.eligible("linux", false, List.of()));
        assertEquals(List.of(REVISION), ledger.eligible("linux", true, List.of()));
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:01Z"));
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:02Z"));
        assertEquals("needs-attention", ledger.entry(REVISION, "linux").state());
        assertEquals(3, ledger.entry(REVISION, "linux").attempts());
        assertEquals(List.of(), ledger.eligible("linux", true, List.of()));
        assertEquals(List.of(REVISION), ledger.eligible("linux", false, List.of(REVISION)));
    }

    @Test
    public void requestedCompletionAndImportEventsStayDistinct() throws Exception {
        DeliveryLedger ledger = new DeliveryLedger(temporary.newFolder("state"), 3);
        ledger.registerLocal(REVISION, "linux");
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:00Z"));
        ledger.recordEvent("kdeconnect-transfer-complete", REVISION, Instant.parse("2026-10-12T14:00:01Z"), "notification");
        ledger.recordEvent("reasonnotes-imported", REVISION, Instant.parse("2026-10-12T14:00:02Z"), "durable");
        assertEquals(
                List.of("transfer-requested", "kdeconnect-transfer-complete", "reasonnotes-imported"),
                ledger.events().stream().map(DeliveryLedger.Event::kind).toList());
        assertEquals(1, ledger.entry(REVISION, "linux").attempts());
    }

    @Test
    public void requestEvidenceDoesNotRemoveHistoryContent() throws Exception {
        File state = temporary.newFolder("state");
        File history = temporary.newFile("revision.md");
        java.nio.file.Files.write(history.toPath(), "immutable content".getBytes());
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        ledger.registerLocal(REVISION, "linux");
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:00Z"));
        assertTrue(history.isFile());
        assertEquals("immutable content", new String(java.nio.file.Files.readAllBytes(history.toPath())));
        assertEquals("transfer-requested", ledger.events().get(0).kind());
    }

    @Test
    public void confirmationIsDurableDistinctIdempotentAndRequiresPriorRequest() throws Exception {
        File state = temporary.newFolder("state");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        ledger.registerLocal(REVISION, "android");
        assertThrows(IllegalArgumentException.class, () -> ledger.recordConfirmed(
                REVISION, "android", RECEIPT, Instant.parse("2026-10-12T14:00:01Z")));
        ledger.recordRequested(REVISION, "android", Instant.parse("2026-10-12T14:00:00Z"));

        assertTrue(ledger.recordConfirmed(
                REVISION, "android", RECEIPT, Instant.parse("2026-10-12T14:00:01Z")));
        assertFalse(ledger.recordConfirmed(
                REVISION, "android", RECEIPT, Instant.parse("2026-10-12T14:00:02Z")));

        DeliveryLedger.Entry entry = new DeliveryLedger(state, 3).entry(REVISION, "android");
        assertEquals("confirmed", entry.state());
        assertEquals(RECEIPT, entry.receiptId());
        assertEquals(Instant.parse("2026-10-12T14:00:01Z"), entry.confirmedUtc());
        assertEquals(List.of(), ledger.eligible("android", false, List.of(REVISION)));
        assertEquals(
                List.of("transfer-requested", "reasonnotes-confirmed"),
                ledger.events().stream().map(DeliveryLedger.Event::kind).toList());
    }

    @Test
    public void confirmationReplayRepairsEventAfterInterruptedAppend() throws Exception {
        File state = temporary.newFolder("state");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        ledger.registerLocal(REVISION, "android");
        ledger.recordRequested(REVISION, "android", Instant.parse("2026-10-12T14:00:00Z"));
        ledger.recordConfirmed(
                REVISION, "android", RECEIPT, Instant.parse("2026-10-12T14:00:01Z"));
        File events = new File(state, "events.tsv");
        String requested = java.nio.file.Files.readAllLines(events.toPath()).get(0) + "\n";
        java.nio.file.Files.write(
                events.toPath(), requested.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertFalse(new DeliveryLedger(state, 3).recordConfirmed(
                REVISION, "android", RECEIPT, Instant.parse("2026-10-12T14:00:02Z")));

        assertEquals(
                List.of("transfer-requested", "reasonnotes-confirmed"),
                new DeliveryLedger(state, 3).events().stream()
                        .map(DeliveryLedger.Event::kind).toList());
    }

    @Test
    public void durableImportEventIsIdempotentAndRepairable() throws Exception {
        File state = temporary.newFolder("state");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);

        assertTrue(ledger.recordImported(
                REVISION, Instant.parse("2026-10-12T14:00:00Z"), "source=first.md"));
        assertFalse(ledger.recordImported(
                REVISION, Instant.parse("2026-10-12T14:00:01Z"), "source=duplicate.md"));

        assertEquals(
                List.of("reasonnotes-imported"),
                ledger.events().stream().map(DeliveryLedger.Event::kind).toList());
    }

    @Test
    public void migratesPhysicalTransportTargetPreservingRequestsConfirmationsAndEvents() throws Exception {
        String physical = "254997f28f11499d80928fe8a109749e";
        String another = "b".repeat(64);
        File state = temporary.newFolder("migration");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        Instant t = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, physical);
        ledger.recordRequested(REVISION, physical, t);
        ledger.recordRequested(REVISION, physical, t.plusSeconds(1));
        ledger.registerLocal(another, physical);
        ledger.recordRequested(another, physical, t);
        ledger.recordConfirmed(another, physical, RECEIPT, t.plusSeconds(2));
        byte[] originalEvents = java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath());

        assertTrue(ledger.migrateTarget(physical, "linux"));
        assertFalse(ledger.migrateTarget(physical, "linux"));
        DeliveryLedger restored = new DeliveryLedger(state, 3);
        assertEquals(null, restored.entry(REVISION, physical));
        assertEquals(2, restored.entry(REVISION, "linux").attempts());
        assertEquals(t.plusSeconds(1), restored.entry(REVISION, "linux").lastRequested());
        assertEquals("transfer-requested", restored.entry(REVISION, "linux").state());
        assertEquals("confirmed", restored.entry(another, "linux").state());
        assertEquals(RECEIPT, restored.entry(another, "linux").receiptId());
        assertEquals(t.plusSeconds(2), restored.entry(another, "linux").confirmedUtc());
        assertArrayEquals(originalEvents, java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath()));
        restored.registerLocal(REVISION, "linux");
        assertEquals(2, restored.entry(REVISION, "linux").attempts());
    }

    @Test
    public void migrationFailsClosedWithoutOverwritingDifferentLogicalEntry() throws Exception {
        String physical = "254997f28f11499d80928fe8a109749e";
        File state = temporary.newFolder("migration-collision");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        ledger.registerLocal(REVISION, physical);
        ledger.recordRequested(REVISION, physical, Instant.parse("2026-10-12T14:00:00Z"));
        ledger.registerLocal(REVISION, "linux");
        byte[] before = java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath());
        assertThrows(IllegalStateException.class, () -> ledger.migrateTarget(physical, "linux"));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath()));
        assertEquals(1, ledger.entry(REVISION, physical).attempts());
        assertEquals(0, ledger.entry(REVISION, "linux").attempts());
    }

    @Test
    public void multipleHistoricalPhysicalIdsAreMigratedAtomicallyOrNotAtAll() throws Exception {
        File state = temporary.newFolder("migration-multi");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String oldA = "254997f28f11499d80928fe8a109749e";
        String oldB = "alternate-physical-device";
        Instant t = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, oldA);
        ledger.recordRequested(REVISION, oldA, t);
        ledger.registerLocal(REVISION, oldB);
        byte[] before = java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath());

        assertThrows(IllegalStateException.class, () ->
                ledger.migrateTargets(List.of(oldA, oldB), "linux"));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath()));
        assertEquals(null, ledger.entry(REVISION, "linux"));

        String other = "b".repeat(64);
        ledger.registerLocal(other, oldB);
        byte[] beforeAgain = java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath());
        assertThrows(IllegalStateException.class, () ->
                ledger.migrateTargets(List.of(oldA, oldB), "linux"));
        assertArrayEquals(beforeAgain, java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath()));
    }

    @Test
    public void rediscoveringLocalRevisionDoesNotResetRequestedOrConfirmedState() throws Exception {
        DeliveryLedger ledger = new DeliveryLedger(temporary.newFolder("state"), 3);
        ledger.registerLocal(REVISION, "linux");
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:00Z"));

        ledger.registerLocal(REVISION, "linux");

        assertEquals("transfer-requested", ledger.entry(REVISION, "linux").state());
        assertEquals(1, ledger.entry(REVISION, "linux").attempts());
        ledger.recordConfirmed(REVISION, "linux", RECEIPT, Instant.parse("2026-10-12T14:00:01Z"));
        ledger.registerLocal(REVISION, "linux");
        assertEquals("confirmed", ledger.entry(REVISION, "linux").state());
    }
}
