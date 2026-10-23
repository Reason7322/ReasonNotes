package org.reasonnotes.probe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class KdeConnectSender {
    public static final String PACKAGE = "org.kde.kdeconnect_tp";
    public static final String ACTIVITY = "org.kde.kdeconnect.plugins.share.ShareActivity";
    private static final Duration URI_GRANT_LIFETIME = Duration.ofMinutes(5);

    public record RequestOutcome(boolean requested, boolean explicitTarget, String detail) {}

    private KdeConnectSender() {}

    private static void grantReadAccess(Context context, List<Uri> uris) {
        for (Uri uri : uris) {
            context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    private static void revokeReadAccess(Context context, List<Uri> uris) {
        for (Uri uri : uris) {
            context.revokeUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    private static void scheduleReadAccessRevocation(Context context, List<Uri> uris) {
        Context application = context.getApplicationContext();
        Context revoker = application == null ? context : application;
        List<Uri> granted = List.copyOf(uris);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> revokeReadAccess(revoker, granted), URI_GRANT_LIFETIME.toMillis());
    }

    public static Intent buildIntent(Context context, List<Uri> uris, String deviceId) {
        if (uris.isEmpty()) throw new IllegalArgumentException("at least one URI is required");
        Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        intent.setComponent(new ComponentName(PACKAGE, ACTIVITY));
        intent.setType(uris.size() == 1
                && uris.get(0).getPath() != null
                && uris.get(0).getPath().endsWith(".json")
                ? "application/json" : "text/markdown");
        intent.putExtra("deviceId", deviceId);
        if (uris.size() == 1) {
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        ClipData clip = ClipData.newUri(
                context.getContentResolver(), "ReasonNotes sync", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clip.addItem(new ClipData.Item(uris.get(index)));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static RequestOutcome request(Activity activity, List<Uri> uris, String deviceId) {
        Intent explicit = buildIntent(activity, uris, deviceId);
        // KDE Connect delegates the stream from its exported ShareActivity to
        // asynchronous transfer code. Grant its package direct, temporary read
        // access so that delegation does not lose the activity-scoped intent grant.
        grantReadAccess(activity, uris);
        try {
            activity.startActivity(explicit);
            scheduleReadAccessRevocation(activity, uris);
            return new RequestOutcome(true, true, "explicit KDE Connect activity launched");
        } catch (ActivityNotFoundException error) {
            revokeReadAccess(activity, uris);
            Intent generic = new Intent(explicit).setComponent(null);
            generic.removeExtra("deviceId");
            try {
                activity.startActivity(Intent.createChooser(generic, "Share ReasonNotes revisions"));
                return new RequestOutcome(true, false, "Android share chooser launched");
            } catch (ActivityNotFoundException fallbackError) {
                return new RequestOutcome(false, false, "no compatible share activity");
            }
        } catch (RuntimeException error) {
            revokeReadAccess(activity, uris);
            throw error;
        }
    }
}

