package org.reasonnotes.probe;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class ExchangeDirectory {
    private static final String PREFERENCES = "reasonnotes-exchange";
    private static final String TREE_KEY = "tree-uri";
    private static final byte[] MARKER = "---\nreasonnotes-format: 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public record ScanReport(int imported, int duplicates, int rejected, int ignored, List<String> errors) {}

    private ExchangeDirectory() {}

    public static void persistGrant(Context context, Uri treeUri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(treeUri, flags);
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(TREE_KEY, treeUri.toString()).commit();
    }

    public static Uri savedTree(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(TREE_KEY, null);
        return value == null ? null : Uri.parse(value);
    }

    public static ScanReport scan(Context context, ProbeRepository repository) {
        Uri uri = savedTree(context);
        if (uri == null) return new ScanReport(0, 0, 0, 0, List.of("exchange directory not selected"));
        DocumentFile directory = DocumentFile.fromTreeUri(context, uri);
        if (directory == null || !directory.isDirectory()) {
            return new ScanReport(0, 0, 0, 0, List.of("saved exchange directory is unavailable"));
        }
        int imported = 0;
        int duplicates = 0;
        int rejected = 0;
        int ignored = 0;
        ArrayList<String> errors = new ArrayList<>();
        for (DocumentFile document : directory.listFiles()) {
            String name = document.getName();
            if (!document.isFile() || name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
                ignored++;
                continue;
            }
            try {
                byte[] data = read(context, document.getUri());
                if (!startsWith(data, MARKER)) {
                    ignored++;
                    continue;
                }
                ProbeRepository.ImportOutcome outcome = repository.importEnvelope(data, name);
                if (outcome.kind().equals("imported")) imported++;
                else if (outcome.kind().equals("duplicate")) duplicates++;
                else rejected++;
            } catch (IOException | SecurityException error) {
                errors.add(name + ": " + error.getMessage());
            }
        }
        return new ScanReport(imported, duplicates, rejected, ignored, List.copyOf(errors));
    }

    private static byte[] read(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("cannot open document");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }
}

