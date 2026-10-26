package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KdeConnectSenderInstrumentedTest {
    @Test
    public void installedApplicationLaunchIntentTargetsKdeConnect() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = KdeConnectSender.buildApplicationIntent(context);

        assertNotNull("installed KDE Connect application must be launchable", intent);
        assertEquals(KdeConnectSender.PACKAGE, intent.getPackage());
        assertNotNull(context.getPackageManager().resolveActivity(intent, 0));
    }

    @Test
    public void explicitInstalledActivityResolvesAndGrantedContentUriOpens() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File outbox = new File(context.getFilesDir(), "probe-repository/outgoing");
        assertTrue(outbox.mkdirs() || outbox.isDirectory());
        File file = new File(outbox, "reasonnotes-bundle-v1-" + "a".repeat(64) + ".json");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("ReasonNotes intent routing probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        Intent intent = KdeConnectSender.buildIntent(
                context, List.of(uri), "52548cf9710c43cab2fa73ed4671aea9");

        ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, 0);
        assertNotNull("installed KDE Connect explicit share activity must resolve", resolved);
        assertEquals(KdeConnectSender.PACKAGE, resolved.activityInfo.packageName);
        assertTrue("KDE Connect share activity must be exported", resolved.activityInfo.exported);
        assertEquals(Intent.ACTION_SEND, intent.getAction());
        assertEquals("application/json", intent.getType());
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertNotNull(intent.getClipData());
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input);
            assertTrue(input.read() >= 0);
        }
    }
}
