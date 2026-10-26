package org.reasonnotes.probe;

import java.util.ArrayList;
import java.util.List;

/** Returns durable-import acknowledgments without sending any note state. */
public final class AutomaticAckReturn {
    private AutomaticAckReturn() {}

    public static SyncCoordinator.RequestEvidence send(
            CurrentNoteStore store, SyncCoordinator.TransferRequester requester) {
        List<StateAckEnvelope> acknowledgments = store.pendingAcks();
        if (acknowledgments.isEmpty()) {
            return new SyncCoordinator.RequestEvidence(true, "no acknowledgments pending");
        }
        ArrayList<SyncCoordinator.OutgoingFile> files = new ArrayList<>();
        for (StateAckEnvelope acknowledgment : acknowledgments) {
            files.add(new SyncCoordinator.OutgoingFile(
                    SyncCoordinator.FileKind.ACK,
                    acknowledgment.ackId(),
                    store.outgoingAckFile(acknowledgment)));
        }
        SyncCoordinator.RequestEvidence result = requester.request(List.copyOf(files));
        if (result.requested()) store.markAcksRequested(acknowledgments);
        return result;
    }
}
