package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class KdeConnectReadinessGateTest {
    private static final UUID NOTE = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");

    @Test public void returnAndConfirmationAuthorizeExactRequestOnce() {
        KdeConnectReadinessGate gate = new KdeConnectReadinessGate();
        OutgoingSelection selection = OutgoingSelection.selected(Set.of(NOTE));

        assertTrue(gate.begin(selection, true));
        assertFalse(gate.canConfirm());
        assertTrue(gate.returnedFromKdeConnect());
        assertTrue(gate.canConfirm());

        KdeConnectReadinessGate.Request request = gate.confirm();
        assertEquals(selection, request.selection());
        assertTrue(request.retryUnconfirmed());
        assertNull(gate.confirm());
        assertFalse(gate.canConfirm());
    }

    @Test public void cancellationNeverAuthorizesSynchronization() {
        KdeConnectReadinessGate gate = new KdeConnectReadinessGate();
        assertTrue(gate.begin(OutgoingSelection.all(), false));
        assertTrue(gate.returnedFromKdeConnect());

        gate.cancel();

        assertNull(gate.confirm());
        assertFalse(gate.canConfirm());
    }

    @Test public void activeRequestRejectsOverlappingTap() {
        KdeConnectReadinessGate gate = new KdeConnectReadinessGate();
        assertTrue(gate.begin(OutgoingSelection.all(), false));
        assertFalse(gate.begin(OutgoingSelection.selected(Set.of(NOTE)), true));
    }

    @Test public void snapshotRestoresExactSelectionAfterActivityRecreation() {
        KdeConnectReadinessGate original = new KdeConnectReadinessGate();
        OutgoingSelection selection = OutgoingSelection.selected(Set.of(NOTE));
        original.begin(selection, true);
        KdeConnectReadinessGate.Snapshot snapshot = original.snapshot();

        KdeConnectReadinessGate restored = new KdeConnectReadinessGate(snapshot);
        assertTrue(restored.returnedFromKdeConnect());
        KdeConnectReadinessGate.Request request = restored.confirm();

        assertEquals(selection, request.selection());
        assertTrue(request.retryUnconfirmed());
    }
}
