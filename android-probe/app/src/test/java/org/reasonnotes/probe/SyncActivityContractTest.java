package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class SyncActivityContractTest {
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");

    private static List<SyncPlanner.CatalogueItem> catalogue() {
        return List.of(
                new SyncPlanner.CatalogueItem(ALPHA, "Żółty plan", false, false, Instant.EPOCH, "pending"),
                new SyncPlanner.CatalogueItem(BETA, "Beta", true, true, Instant.EPOCH.plusSeconds(1), "confirmed"));
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
    public void deliveryAndProgressLabelsNeverOverclaimConfirmation() {
        assertEquals("Pending", SyncActivity.deliveryLabel("pending"));
        assertEquals("Transfer requested — awaiting receipt", SyncActivity.deliveryLabel("unconfirmed"));
        assertEquals("Confirmed by phone", SyncActivity.deliveryLabel("confirmed"));
        assertEquals("Needs attention", SyncActivity.deliveryLabel("needs-attention"));
        SyncStatus requested = new SyncStatus(
                SyncStatus.Phase.TRANSFER_REQUESTED, 2, 1, 3, 4, 5, List.of(), "chooser launched");
        assertTrue(requested.summary().contains("requested"));
        assertTrue(requested.summary().contains("imported 3"));
        assertTrue(requested.summary().contains("confirmed 4"));
        assertFalse(requested.summary().contains("fully synchronized"));
    }

    @Test
    public void insetPaddingUsesLargestKeyboardOrNavigationInset() {
        assertEquals(28, SyncActivity.bottomContentPadding(8, 20, 4));
        assertEquals(108, SyncActivity.bottomContentPadding(8, 20, 100));
    }

    @Test
    public void productionLedgerRetainsTheInstalledProbeSiblingDirectory() {
        File files = new File("/application/files");
        assertEquals(new File(files, "delivery-state"), SyncActivity.deliveryStateDirectory(files));
    }
}
