package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class LauncherIconContractTest {
    private static final String ACCEPTED_ICON_SHA256 =
            "12f95baf8b1feed12b3d96890f59f8b5c3bb30c83b772dc0297e90fb42306e36";

    @Test
    public void manifestReferencesExactAcceptedLauncherResource() throws Exception {
        Path manifestPath = Path.of("src/main/AndroidManifest.xml");
        Path iconPath = Path.of("src/main/res/drawable/reasonnotes_launcher.xml");

        String manifest = new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("android:icon=\"@drawable/reasonnotes_launcher\""));
        assertTrue(manifest.contains("android:roundIcon=\"@drawable/reasonnotes_launcher\""));
        assertTrue(Files.isRegularFile(iconPath));

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(iconPath));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        assertEquals(ACCEPTED_ICON_SHA256, hex.toString());
    }
}
