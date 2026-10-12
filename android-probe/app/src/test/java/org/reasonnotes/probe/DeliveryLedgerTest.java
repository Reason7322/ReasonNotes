package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
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
}
