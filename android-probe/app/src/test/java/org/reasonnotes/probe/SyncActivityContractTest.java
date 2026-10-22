package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class SyncActivityContractTest {
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
    private static final UUID DELETED = UUID.fromString("cccccccc-3333-4333-8333-cccccccccccc");

    private static List<SyncPlanner.CatalogueItem> catalogue() {
        return List.of(
                new SyncPlanner.CatalogueItem(ALPHA, "Żółty plan", false, false, Instant.EPOCH, "pending"),
                new SyncPlanner.CatalogueItem(BETA, "Beta", false, true, Instant.EPOCH.plusSeconds(1), "confirmed"),
                new SyncPlanner.CatalogueItem(DELETED, "Old deleted", true, false, Instant.EPOCH.plusSeconds(2), "pending"));
    }

    @Test
    public void exposesSeparateAllAndChooseActions() {
        assertEquals(List.of("Sync all notes", "Choose notes to sync"), SyncActivity.modeActions());
    }

    @Test
    public void independentCatalogueSupportsSearchSelectAllClearAndEmptyDisable() {
        SyncActivity.SelectionModel model = new SyncActivity.SelectionModel(catalogue());
        assertFalse(model.canSync());
        model.setQuery("ŻÓŁ");
        assertEquals(List.of(ALPHA), model.visible().stream().map(SyncPlanner.CatalogueItem::noteId).toList());
        model.selectAllVisible();
        assertEquals(Set.of(ALPHA), model.selected());
        assertTrue(model.canSync());
        model.clearSelection();
        assertFalse(model.canSync());
    }

    @Test
    public void selectionAndQueryRoundTripAcrossActivityRecreation() {
        SyncActivity.SelectionModel first = new SyncActivity.SelectionModel(catalogue());
        first.setQuery("beta");
        first.setSelected(BETA, true);

        SyncActivity.SelectionModel restored = new SyncActivity.SelectionModel(catalogue(), first.snapshot());

        assertEquals("beta", restored.query());
        assertEquals(Set.of(BETA), restored.selected());
        assertTrue(restored.canSync());
    }

    @Test
    public void deletedNotesCannotBeSelectedByTapRestoreSearchOrSelectAll() {
        SyncActivity.SelectionModel model = new SyncActivity.SelectionModel(catalogue());
        assertFalse(model.visible().stream().anyMatch(item -> item.deleted()));
        model.setSelected(DELETED, true);
        assertFalse(model.canSync());
        model.selectAllVisible();
        assertEquals(Set.of(ALPHA, BETA), model.selected());
        SyncActivity.SelectionSnapshot forged = new SyncActivity.SelectionSnapshot(
                "deleted", Set.of(DELETED, BETA));
        SyncActivity.SelectionModel resumed = new SyncActivity.SelectionModel(
                catalogue(), forged);
        assertEquals(Set.of(BETA), resumed.selected());
        assertTrue(resumed.visible().isEmpty());
        resumed.setQuery("");
        assertEquals(2, resumed.visible().size());
    }

    @Test
    public void deliveryAndProgressLabelsNeverOverclaimConfirmation() {
        assertEquals("Pending", SyncActivity.deliveryLabel("pending"));
        assertEquals("Transfer requested — awaiting receipt", SyncActivity.deliveryLabel("unconfirmed"));
        assertEquals("Confirmed by Linux", SyncActivity.deliveryLabel("confirmed"));
        assertEquals("Needs attention", SyncActivity.deliveryLabel("needs-attention"));
        SyncStatus requested = new SyncStatus(
                SyncStatus.Phase.TRANSFER_REQUESTED, 2, 1, 3, 4, 5, List.of(), "chooser launched");
        assertTrue(requested.summary().contains("requested"));
        assertTrue(requested.summary().contains("imported 3"));
        assertTrue(requested.summary().contains("confirmed 4"));
        assertFalse(requested.summary().contains("fully synchronized"));
    }

    @Test
    public void simpleSyncUiNeverExposesTechnicalCountsOrClaimsRequestedIsConfirmed() {
        assertEquals("", SyncActivity.conciseStatus(
                SyncStatus.Phase.IDLE, SyncActivity.CompletionEvidence.CONFIRMED));
        assertEquals("Syncing…", SyncActivity.conciseStatus(
                SyncStatus.Phase.SCANNING, SyncActivity.CompletionEvidence.CONFIRMED));
        assertEquals("All selected notes synced ✅", SyncActivity.conciseStatus(
                SyncStatus.Phase.COMPLETE, SyncActivity.CompletionEvidence.CONFIRMED));
        assertEquals("Sync requested ✅", SyncActivity.conciseStatus(
                SyncStatus.Phase.TRANSFER_REQUESTED, SyncActivity.CompletionEvidence.CONFIRMED));
        assertEquals("Sync requested ✅", SyncActivity.conciseStatus(
                SyncStatus.Phase.COMPLETE, SyncActivity.CompletionEvidence.AWAITING_RECEIPT));
        assertEquals("Error: Some notes need attention ❌", SyncActivity.conciseStatus(
                SyncStatus.Phase.COMPLETE, SyncActivity.CompletionEvidence.NEEDS_ATTENTION));
        assertEquals("Error: Sync failed ❌", SyncActivity.conciseStatus(
                SyncStatus.Phase.ERROR, SyncActivity.CompletionEvidence.CONFIRMED));
        for (SyncStatus.Phase phase : SyncStatus.Phase.values()) {
            for (SyncActivity.CompletionEvidence proof : SyncActivity.CompletionEvidence.values()) {
                String text = SyncActivity.conciseStatus(phase, proof);
                assertFalse(text.contains("receipt"));
                assertFalse(text.contains("revisions"));
                assertFalse(text.contains("queued"));
                assertFalse(text.contains("imported "));
                assertFalse(text.contains("confirmed "));
            }
        }
    }

    @Test
    public void completedSyncOnlyClaimsFullySyncedWhenOutgoingRevisionsAreConfirmed() {
        RevisionEnvelope outgoing = RevisionEnvelope.create(
                ALPHA, List.of(), "android", Instant.parse("2026-10-15T12:00:00Z"),
                false, "# Note");
        RevisionEnvelope incoming = RevisionEnvelope.create(
                BETA, List.of(), "linux", Instant.parse("2026-10-15T12:00:00Z"),
                false, "# Incoming");
        String id = outgoing.revisionId();
        OutgoingSelection chosen = OutgoingSelection.selected(Set.of(ALPHA));
        assertEquals(SyncActivity.CompletionEvidence.NEEDS_ATTENTION,
                SyncActivity.deliveryEvidence(List.of(outgoing, incoming), List.of(), chosen));
        DeliveryLedger.Entry pending = new DeliveryLedger.Entry(
                id, "linux", "pending", 0, null, null, null);
        DeliveryLedger.Entry awaiting = new DeliveryLedger.Entry(
                id, "linux", "transfer-requested", 1, Instant.EPOCH, null, null);
        DeliveryLedger.Entry attention = new DeliveryLedger.Entry(
                id, "linux", "needs-attention", 3, Instant.EPOCH, null, null);
        DeliveryLedger.Entry confirmed = new DeliveryLedger.Entry(
                id, "linux", "confirmed", 1, Instant.EPOCH, "c".repeat(64), Instant.EPOCH);
        assertEquals(SyncActivity.CompletionEvidence.NEEDS_ATTENTION,
                SyncActivity.deliveryEvidence(List.of(outgoing, incoming), List.of(pending), chosen));
        assertEquals(SyncActivity.CompletionEvidence.AWAITING_RECEIPT,
                SyncActivity.deliveryEvidence(List.of(outgoing, incoming), List.of(awaiting), chosen));
        assertEquals(SyncActivity.CompletionEvidence.NEEDS_ATTENTION,
                SyncActivity.deliveryEvidence(List.of(outgoing, incoming), List.of(attention), chosen));
        assertEquals(SyncActivity.CompletionEvidence.CONFIRMED,
                SyncActivity.deliveryEvidence(List.of(outgoing, incoming), List.of(confirmed), chosen));
        assertEquals(SyncActivity.CompletionEvidence.CONFIRMED,
                SyncActivity.deliveryEvidence(List.of(outgoing, incoming), List.of(),
                        OutgoingSelection.selected(Set.of(BETA))));
    }

    @Test
    public void onlyFreshTransferAttemptsBecomeLiveConfirmationTargets() {
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        String c = "c".repeat(64);
        Instant now = Instant.parse("2026-10-17T04:00:00Z");
        DeliveryLedger.Entry formerlyPending =
                new DeliveryLedger.Entry(a, "linux", "pending", 0, null, null, null);
        DeliveryLedger.Entry formerlyRequested =
                new DeliveryLedger.Entry(b, "linux", "transfer-requested", 1, now, null, null);
        DeliveryLedger.Entry previousConfirmed =
                new DeliveryLedger.Entry(c, "linux", "confirmed", 1, now, "d".repeat(64), now);
        DeliveryLedger.Entry newlyRequested =
                new DeliveryLedger.Entry(a, "linux", "transfer-requested", 1, now, null, null);
        DeliveryLedger.Entry unchanged =
                new DeliveryLedger.Entry(b, "linux", "transfer-requested", 1, now, null, null);
        assertEquals(Set.of(a), SyncActivity.newlyRequested(
                List.of(formerlyPending, formerlyRequested, previousConfirmed),
                List.of(newlyRequested, unchanged, previousConfirmed)));
    }

    @Test
    public void confirmationWatcherRequiresEveryRequestedReceiptAndRejectsWrongEvidence() {
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        Instant now = Instant.parse("2026-10-17T04:00:00Z");
        DeliveryLedger.Entry awaitingA =
                new DeliveryLedger.Entry(a, "linux", "transfer-requested", 1, now, null, null);
        DeliveryLedger.Entry confirmedA =
                new DeliveryLedger.Entry(a, "linux", "confirmed", 1, now, "c".repeat(64), now);
        DeliveryLedger.Entry awaitingB =
                new DeliveryLedger.Entry(b, "linux", "transfer-requested", 1, now, null, null);
        DeliveryLedger.Entry confirmedB =
                new DeliveryLedger.Entry(b, "linux", "confirmed", 1, now, "d".repeat(64), now);
        assertEquals(SyncActivity.CompletionEvidence.NEEDS_ATTENTION,
                SyncActivity.confirmationOf(Set.of(a, b), List.of(confirmedA)));
        assertEquals(SyncActivity.CompletionEvidence.AWAITING_RECEIPT,
                SyncActivity.confirmationOf(Set.of(a, b), List.of(confirmedA, awaitingB)));
        assertEquals(SyncActivity.CompletionEvidence.CONFIRMED,
                SyncActivity.confirmationOf(Set.of(a, b), List.of(confirmedA, confirmedB)));
        assertEquals(SyncActivity.CompletionEvidence.AWAITING_RECEIPT,
                SyncActivity.confirmationOf(Set.of(a), List.of(awaitingA)));
        assertEquals(SyncActivity.CompletionEvidence.NEEDS_ATTENTION,
                SyncActivity.confirmationOf(Set.of(), List.of(confirmedA)));
    }

    @Test
    public void actualVisibleSyncScreenContainsNoEverPresentRetryOrInternalStatusWords()
            throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/org/reasonnotes/probe/SyncActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains("Retry unconfirmed transfers"));
        assertFalse(source.contains("Retry unconfirmed selected notes"));
        assertFalse(source.contains("status.setText(finalResult.summary())"));
        assertTrue(source.contains("status.setVisibility(View.GONE)"));
        assertTrue(source.contains("offerRetry(OutgoingSelection.all())"));
    }

    @Test
    public void insetPaddingUsesLargestKeyboardOrNavigationInset() {
        assertEquals(28, SyncActivity.bottomContentPadding(8, 20, 4));
        assertEquals(108, SyncActivity.bottomContentPadding(8, 20, 100));
    }

    @Test
    public void productionPeerIsLogicalLinuxNotThePhysicalKdeConnectId() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("peer-routing").toFile();
        DeliveryLedger ledger = new DeliveryLedger(dir, 3);
        String revision = "d".repeat(64);
        String transportId = "254997f28f11499d80928fe8a109749e";
        ledger.registerLocal(revision, transportId);
        ledger.recordRequested(revision, transportId, Instant.parse("2026-10-15T12:00:00Z"));

        assertEquals("linux", SyncActivity.preparePeerLedger(ledger, transportId));
        assertEquals(1, ledger.entry(revision, "linux").attempts());
        assertEquals(null, ledger.entry(revision, transportId));
    }

    @Test
    public void productionLedgerRetainsTheInstalledProbeSiblingDirectory() {
        File files = new File("/application/files");
        assertEquals(new File(files, "delivery-state"), SyncActivity.deliveryStateDirectory(files));
    }

    @Test
    public void transferPathAlwaysUsesOneJsonCarrierAndNeverTheUndeployedZipGenerator()
            throws Exception {
        String syncSource = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/org/reasonnotes/probe/SyncActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String senderSource = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/org/reasonnotes/probe/KdeConnectSender.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(syncSource.contains("createTransportPayload("));
        assertTrue(syncSource.contains("TransportBundle.write("));
        assertFalse(syncSource.contains("TransferBundle.create("));
        assertTrue(senderSource.contains("application/json"));
    }
}
