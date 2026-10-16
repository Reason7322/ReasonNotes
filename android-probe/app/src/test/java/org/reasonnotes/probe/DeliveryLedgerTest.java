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
    public void bulkRegisterPreservesExistingConfirmedAndRetryState() throws Exception {
        File state = temporary.newFolder("batched");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String second = "a".repeat(64);
        String third = "b".repeat(64);
        ledger.registerLocal(REVISION, "linux");
        ledger.recordRequested(REVISION, "linux", Instant.parse("2026-10-12T14:00:00Z"));
        ledger.recordConfirmed(REVISION, "linux", RECEIPT, Instant.parse("2026-10-12T14:00:01Z"));
        ledger.registerLocals(List.of(REVISION, second, third, second), "linux");
        assertEquals(3, ledger.entries("linux").size());
        assertEquals("confirmed", ledger.entry(REVISION, "linux").state());
        assertEquals(RECEIPT, ledger.entry(REVISION, "linux").receiptId());
        assertEquals("pending", ledger.entry(second, "linux").state());
        assertEquals("pending", ledger.entry(third, "linux").state());

        var ledgerPath = new File(state, "delivery.tsv").toPath();
        var old = java.nio.file.attribute.FileTime.from(
                Instant.parse("2001-01-01T00:00:00Z"));
        java.nio.file.Files.setLastModifiedTime(ledgerPath, old);
        ledger.registerLocals(List.of(REVISION, second, third), "linux");
        assertEquals(old, java.nio.file.Files.getLastModifiedTime(ledgerPath));
        assertEquals(3, new DeliveryLedger(state, 3).entries("linux").size());
        assertThrows(IllegalArgumentException.class, () -> ledger.registerLocals(
                List.of("not-a-revision", "c".repeat(64)), "linux"));
        assertEquals(3, ledger.entries("linux").size());
    }

    @Test
    public void oneBatchKeepsPerRevisionAuditsAndRejectsMixedInvalidTransfersAtomically() throws Exception {
        File state = temporary.newFolder("batch-request");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        String confirmed = "c".repeat(64);
        Instant when = Instant.parse("2026-10-15T14:00:00Z");
        ledger.registerLocals(List.of(a, b, confirmed), "linux");
        ledger.recordRequested(confirmed, "linux", when);
        ledger.recordConfirmed(confirmed, "linux", RECEIPT, when.plusSeconds(1));
        byte[] ledgerBefore = java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath());
        byte[] eventsBefore = java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath());
        assertThrows(IllegalArgumentException.class, () -> ledger.recordRequestedBatch(
                List.of(a, confirmed), "linux", when));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordRequestedBatch(
                List.of(a, a), "linux", when));
        assertArrayEquals(ledgerBefore, java.nio.file.Files.readAllBytes(
                new File(state, "delivery.tsv").toPath()));
        assertArrayEquals(eventsBefore, java.nio.file.Files.readAllBytes(
                new File(state, "events.tsv").toPath()));
        ledger.recordRequestedBatch(List.of(a, b), "linux", when.plusSeconds(2));
        assertEquals(1, ledger.entry(a, "linux").attempts());
        assertEquals(1, ledger.entry(b, "linux").attempts());
        assertEquals("confirmed", ledger.entry(confirmed, "linux").state());
        assertEquals(2, ledger.events().stream().filter(event -> event.kind().equals(
                "transfer-requested") && (event.revisionId().equals(a)
                || event.revisionId().equals(b))).count());
    }

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
    public void realPhoneMixedLedgerPreservesMostConservativeEvidenceAndRetryCount() throws Exception {
        String physical = "254997f28f11499d80928fe8a109749e";
        File state = temporary.newFolder("real-phone-conflict");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String revision = "7c1792c1475a12cb755982e69fd448871ed3d348a5a7823b8a9ef1a352ce0a83";
        Instant old = Instant.parse("2026-10-12T19:11:59Z");
        Instant recent = Instant.parse("2026-10-15T12:00:27Z");

        ledger.registerLocal(revision, "linux");
        for (int i = 0; i < 3; i++) ledger.recordRequested(revision, "linux", old);
        ledger.registerLocal(revision, physical);
        ledger.recordRequested(revision, physical, recent.minusSeconds(1));
        ledger.recordRequested(revision, physical, recent);
        byte[] originalEvents = java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath());

        assertTrue(ledger.migrateTargets(List.of(physical), "linux"));
        assertFalse(ledger.migrateTargets(List.of(physical), "linux"));
        DeliveryLedger updated = new DeliveryLedger(state, 3);
        DeliveryLedger.Entry merged = updated.entry(revision, "linux");
        assertEquals("needs-attention", merged.state());
        assertEquals(3, merged.attempts());
        assertEquals(recent, merged.lastRequested());
        assertEquals(null, merged.receiptId());
        assertEquals(null, merged.confirmedUtc());
        assertEquals(null, updated.entry(revision, physical));
        assertEquals(List.of(), updated.eligible("linux", true, List.of()));
        assertArrayEquals(originalEvents,
                java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath()));
    }

    @Test
    public void mixedPendingAndRequestedRecordsMergeWithoutInventingDelivery() throws Exception {
        String physical = "254997f28f11499d80928fe8a109749e";
        File state = temporary.newFolder("pending-v-requested");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        Instant requested = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, physical);
        ledger.recordRequested(REVISION, physical, requested);
        ledger.registerLocal(REVISION, "linux");

        assertTrue(ledger.migrateTarget(physical, "linux"));
        DeliveryLedger.Entry merged = ledger.entry(REVISION, "linux");
        assertEquals("transfer-requested", merged.state());
        assertEquals(1, merged.attempts());
        assertEquals(requested, merged.lastRequested());
        assertEquals(null, merged.receiptId());
    }

    @Test
    public void confirmedEvidenceWinsOverUnconfirmedButNeverInventsReceipt() throws Exception {
        String physical = "254997f28f11499d80928fe8a109749e";
        File state = temporary.newFolder("confirmed-mixed");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        Instant start = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, physical);
        ledger.recordRequested(REVISION, physical, start.plusSeconds(3));
        ledger.recordRequested(REVISION, physical, start.plusSeconds(4));
        ledger.registerLocal(REVISION, "linux");
        ledger.recordRequested(REVISION, "linux", start);
        ledger.recordConfirmed(REVISION, "linux", RECEIPT, start.plusSeconds(1));

        assertTrue(ledger.migrateTarget(physical, "linux"));
        DeliveryLedger.Entry merged = ledger.entry(REVISION, "linux");
        assertEquals("confirmed", merged.state());
        assertEquals(2, merged.attempts());
        assertEquals(start.plusSeconds(4), merged.lastRequested());
        assertEquals(RECEIPT, merged.receiptId());
        assertEquals(start.plusSeconds(1), merged.confirmedUtc());
    }

    @Test
    public void incompatibleConfirmationsFailClosedAndLeaveEntireLedgerUnchanged() throws Exception {
        File state = temporary.newFolder("confirmed-conflict");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String physical = "254997f28f11499d80928fe8a109749e";
        Instant at = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, physical);
        ledger.recordRequested(REVISION, physical, at);
        ledger.recordConfirmed(REVISION, physical, RECEIPT, at.plusSeconds(1));
        ledger.registerLocal(REVISION, "linux");
        ledger.recordRequested(REVISION, "linux", at);
        ledger.recordConfirmed(REVISION, "linux", "a".repeat(64), at.plusSeconds(2));
        byte[] before = java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath());
        byte[] events = java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath());

        assertThrows(IllegalStateException.class, () ->
                ledger.migrateTargets(List.of(physical), "linux"));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath()));
        assertArrayEquals(events, java.nio.file.Files.readAllBytes(new File(state, "events.tsv").toPath()));
    }

    @Test
    public void laterConfirmationConflictCannotPartiallyMigrateOtherRevisions() throws Exception {
        File state = temporary.newFolder("staged-conflict");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String physical = "254997f28f11499d80928fe8a109749e";
        String conflict = "c".repeat(64);
        Instant at = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, physical);
        ledger.recordRequested(REVISION, physical, at);
        ledger.registerLocal(conflict, physical);
        ledger.recordRequested(conflict, physical, at);
        ledger.recordConfirmed(conflict, physical, RECEIPT, at.plusSeconds(1));
        ledger.registerLocal(conflict, "linux");
        ledger.recordRequested(conflict, "linux", at);
        ledger.recordConfirmed(conflict, "linux", "a".repeat(64), at.plusSeconds(2));
        byte[] snapshot = java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath());

        assertThrows(IllegalStateException.class, () -> ledger.migrateTarget(physical, "linux"));
        assertArrayEquals(snapshot, java.nio.file.Files.readAllBytes(new File(state, "delivery.tsv").toPath()));
        assertEquals(null, ledger.entry(REVISION, "linux"));
        assertEquals("transfer-requested", ledger.entry(REVISION, physical).state());
    }

    @Test
    public void multipleHistoricalPhysicalIdsAreMergedInOneAtomicWrite() throws Exception {
        File state = temporary.newFolder("migration-multi");
        DeliveryLedger ledger = new DeliveryLedger(state, 3);
        String oldA = "254997f28f11499d80928fe8a109749e";
        String oldB = "alternate-physical-device";
        Instant t = Instant.parse("2026-10-12T14:00:00Z");
        ledger.registerLocal(REVISION, oldA);
        ledger.recordRequested(REVISION, oldA, t);
        ledger.registerLocal(REVISION, oldB);
        ledger.recordRequested(REVISION, oldB, t.plusSeconds(5));
        ledger.registerLocal(REVISION, "linux");
        assertTrue(ledger.migrateTargets(List.of(oldA, oldB), "linux"));
        assertEquals(1, ledger.entry(REVISION, "linux").attempts());
        assertEquals(t.plusSeconds(5), ledger.entry(REVISION, "linux").lastRequested());
        assertEquals("transfer-requested", ledger.entry(REVISION, "linux").state());
        assertEquals(null, ledger.entry(REVISION, oldA));
        assertEquals(null, ledger.entry(REVISION, oldB));
        assertFalse(ledger.migrateTargets(List.of(oldA, oldB), "linux"));
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
