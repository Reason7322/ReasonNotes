package org.reasonnotes.probe;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class SyncConfirmationRecordTest {
    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String R1 = "a".repeat(64);
    private static final String R2 = "b".repeat(64);

    @Test public void persistsLastRequestAcrossNewProcessInstanceWithoutExpiry() {
        PinStoreTest.FakePreferences preferences = new PinStoreTest.FakePreferences();
        new SyncConfirmationRecord(preferences).save(Set.of(R1, R2),
                OutgoingSelection.selected(Set.of(A,B)));
        // A fresh model instance reconstructs the same exact operation. It
        // deliberately has no wall-clock deadline, so five hours is harmless.
        SyncConfirmationRecord.Pending restored = new SyncConfirmationRecord(preferences).load();
        assertEquals(Set.of(R1,R2), restored.revisionIds());
        assertEquals(OutgoingSelection.selected(Set.of(A,B)), restored.selection());
    }

    @Test public void allNotesRequestRoundTripsAndIsNotReceiptEvidence() {
        PinStoreTest.FakePreferences preferences = new PinStoreTest.FakePreferences();
        new SyncConfirmationRecord(preferences).save(Set.of(R1),
                OutgoingSelection.all());
        assertEquals(new SyncConfirmationRecord.Pending(
                Set.of(R1), OutgoingSelection.all()),
                new SyncConfirmationRecord(preferences).load());
    }

    @Test public void invalidAndStaleSerializedMetadataFailClosed() {
        PinStoreTest.FakePreferences preferences = new PinStoreTest.FakePreferences();
        SyncConfirmationRecord state = new SyncConfirmationRecord(preferences);
        assertNull(state.load());
        preferences.putString("last-sync-confirmation", "v1\nall\naaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\n"+R1);
        assertNull(state.load());
        preferences.putString("last-sync-confirmation", "v1\nselected\ninvalid\n"+R1);
        assertNull(state.load());
        preferences.putString("last-sync-confirmation", "v1\nselected\n"+A+"\n"+R1+",bad");
        assertNull(state.load());
        assertThrows(IllegalArgumentException.class,
                () -> state.save(Set.of("corrupt"), OutgoingSelection.all()));
    }

    @Test public void validLegacyCheckpointIsNotReinterpretedAsV2Confirmation() {
        PinStoreTest.FakePreferences preferences = new PinStoreTest.FakePreferences();
        preferences.putString("last-sync-confirmation", "v1\nall\n\n" + R1);

        assertNull(new SyncConfirmationRecord(preferences).load());
    }
}
