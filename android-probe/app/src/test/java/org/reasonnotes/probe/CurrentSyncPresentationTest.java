package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class CurrentSyncPresentationTest {
    @Test public void exposesOnlyFourUnderstandableStatesAndNamesConflict() {
        assertEquals("Synchronized", CurrentSyncPresentation.message(new SyncStatus(
                SyncStatus.Phase.COMPLETE, 0, 0, 0, 0, 0, List.of(), ""), null));
        assertEquals("Waiting for computer confirmation", CurrentSyncPresentation.message(new SyncStatus(
                SyncStatus.Phase.TRANSFER_REQUESTED, 1, 0, 0, 0, 0, List.of(), ""), null));
        assertEquals("Transfer failed for Shopping list", CurrentSyncPresentation.message(new SyncStatus(
                SyncStatus.Phase.ERROR, 0, 0, 0, 0, 0, List.of("offline"), "offline"),
                "Shopping list"));
        assertEquals("Shopping list requires a conflict choice", CurrentSyncPresentation.conflict(
                "Shopping list"));
    }
}
