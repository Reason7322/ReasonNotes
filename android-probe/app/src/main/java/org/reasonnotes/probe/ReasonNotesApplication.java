package org.reasonnotes.probe;

import android.app.Application;

/** Application root. Synchronization is explicitly user-triggered. */
public final class ReasonNotesApplication extends Application {

    @Override public void onCreate() {
        super.onCreate();
    }

    /** Compatibility hook for existing UI calls; v2 sync is user-triggered. */
    public void requestImmediatePoll() {}
}
