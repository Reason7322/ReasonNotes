package org.reasonnotes.probe;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Start a single deletion-sync worker on first visible ReasonNotes screen.
 * It stops when the last screen goes away. No Android background service. */
public final class ReasonNotesApplication extends Application
        implements Application.ActivityLifecycleCallbacks {
    private final Set<Activity> visible = new HashSet<>();
    private ScheduledExecutorService worker;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public synchronized void onActivityStarted(Activity activity) {
        visible.add(activity);
        if (worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ReasonNotes-foreground-purge");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, 5, TimeUnit.SECONDS);
    }

    @Override public synchronized void onActivityStopped(Activity activity) {
        visible.remove(activity);
        if (visible.isEmpty() && worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    /** After a local deletion, try immediately if the app is still visible. */
    public synchronized void requestImmediatePoll() {
        if (worker != null && !visible.isEmpty()) worker.execute(this::poll);
    }

    private void poll() {
        synchronized (this) {
            if (visible.isEmpty()) return;
        }
        ForegroundPurgeClient.Settings settings = ForegroundPurgeClient.settings(this);
        if (!ForegroundPurgeClient.validLanIp(settings.host())
                || !ForegroundPurgeClient.validKey(settings.key())) return;
        try {
            ProbeRepository repository = ProbeRepository.openForDisplay(
                    new File(getFilesDir(), "probe-repository"));
            Set<UUID> purged = ForegroundPurgeClient.synchronize(repository, settings);
            if (!purged.isEmpty()) notifyVisible(purged);
        } catch (Exception error) {
            // A disconnected peer is normal. All deletion fences stay durable
            // and are retried only while the application is open.
        }
    }

    private void notifyVisible(Set<UUID> removed) {
        final Activity[] activities;
        synchronized (this) { activities = visible.toArray(new Activity[0]); }
        for (Activity activity : activities) {
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (activity instanceof MainActivity main) main.onRemotePurges(removed);
                if (activity instanceof EditorActivity editor) editor.onRemotePurges(removed);
            });
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
