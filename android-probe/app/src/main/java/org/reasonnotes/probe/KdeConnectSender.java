package org.reasonnotes.probe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

public final class KdeConnectSender {
    public static final String PACKAGE = "org.kde.kdeconnect_tp";
    public static final String ACTIVITY = "org.kde.kdeconnect.plugins.share.ShareActivity";

    public record RequestOutcome(boolean requested, boolean explicitTarget, String detail) {}

    private KdeConnectSender() {}

    public static Intent buildIntent(Context context, List<Uri> uris, String deviceId) {
        if (uris.isEmpty()) throw new IllegalArgumentException("at least one URI is required");
        Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        intent.setComponent(new ComponentName(PACKAGE, ACTIVITY));
        intent.setType(uris.size() == 1
                && uris.get(0).getPath() != null
                && uris.get(0).getPath().endsWith(".zip")
                ? "application/zip" : "text/markdown");
        intent.putExtra("deviceId", deviceId);
        if (uris.size() == 1) {
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        ClipData clip = ClipData.newUri(context.getContentResolver(), "ReasonNotes revisions", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clip.addItem(new ClipData.Item(uris.get(index)));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static RequestOutcome request(Activity activity, List<Uri> uris, String deviceId) {
        Intent explicit = buildIntent(activity, uris, deviceId);
        try {
            activity.startActivity(explicit);
            return new RequestOutcome(true, true, "explicit KDE Connect activity launched");
        } catch (ActivityNotFoundException error) {
            Intent generic = new Intent(explicit).setComponent(null);
            generic.removeExtra("deviceId");
            try {
                activity.startActivity(Intent.createChooser(generic, "Share ReasonNotes revisions"));
                return new RequestOutcome(true, false, "Android share chooser launched");
            } catch (ActivityNotFoundException fallbackError) {
                return new RequestOutcome(false, false, "no compatible share activity");
            }
        }
    }
}

