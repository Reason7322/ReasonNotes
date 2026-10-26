package org.reasonnotes.probe;

import java.util.Objects;

/** Retains one user-requested sync while KDE Connect is opened and checked. */
public final class KdeConnectReadinessGate {
    public record Request(OutgoingSelection selection, boolean retryUnconfirmed) {
        public Request { Objects.requireNonNull(selection, "selection"); }
    }

    public record Snapshot(Request request, boolean returned) {}

    private Request request;
    private boolean returned;

    public KdeConnectReadinessGate() {}

    public KdeConnectReadinessGate(Snapshot snapshot) {
        if (snapshot != null) {
            request = snapshot.request();
            returned = request != null && snapshot.returned();
        }
    }

    public boolean begin(OutgoingSelection selection, boolean retryUnconfirmed) {
        Objects.requireNonNull(selection, "selection");
        if (request != null) return false;
        request = new Request(selection, retryUnconfirmed);
        returned = false;
        return true;
    }

    public boolean returnedFromKdeConnect() {
        if (request == null || returned) return false;
        returned = true;
        return true;
    }

    public boolean canConfirm() { return request != null && returned; }

    public Request confirm() {
        if (!canConfirm()) return null;
        Request confirmed = request;
        cancel();
        return confirmed;
    }

    public void cancel() {
        request = null;
        returned = false;
    }

    public Snapshot snapshot() { return new Snapshot(request, returned); }
}
