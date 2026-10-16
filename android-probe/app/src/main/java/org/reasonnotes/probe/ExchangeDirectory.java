package org.reasonnotes.probe;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class ExchangeDirectory {
    private static final String PREFERENCES = "reasonnotes-exchange";
    private static final String TREE_KEY = "tree-uri";
    private static final byte[] REVISION_MARKER = "---\nreasonnotes-format: 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] RECEIPT_MARKER = "---\nreasonnotes-receipt-format: 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public enum Kind { REVISION, RECEIPT, UNRELATED }

    public record ScanReport(
            int imported,
            int duplicates,
            int confirmed,
            int duplicateReceipts,
            int receiptsQueued,
            int rejectedRevisions,
            int rejectedReceipts,
            int ignored,
            List<String> errors) {
        public int rejected() { return rejectedRevisions + rejectedReceipts; }
    }

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

    public static ScanReport scan(
            Context context,
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receiptStore,
            String localDevice) {
        Instant observed = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ReceiptStore.ReconcileReport recovery = receiptStore.reconcileConfirmations(
                repository, ledger, localDevice, observed);
        int confirmed = recovery.confirmed().size();
        ArrayList<String> errors = new ArrayList<>(recovery.errors());
        Uri uri = savedTree(context);
        if (uri == null) {
            errors.add("exchange directory not selected");
            return new ScanReport(
                    0, 0, confirmed, 0, 0, 0, 0, 0, List.copyOf(errors));
        }
        DocumentFile directory = DocumentFile.fromTreeUri(context, uri);
        if (directory == null || !directory.isDirectory()) {
            errors.add("saved exchange directory is unavailable");
            return new ScanReport(
                    0, 0, confirmed, 0, 0, 0, 0, 0, List.copyOf(errors));
        }
        int imported = 0;
        int duplicates = 0;
        int duplicateReceipts = 0;
        int receiptsQueued = 0;
        int rejectedRevisions = 0;
        int rejectedReceipts = 0;
        int ignored = 0;
        for (DocumentFile document : directory.listFiles()) {
            String name = document.getName();
            if (!document.isFile() || name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
                ignored++;
                continue;
            }
            try {
                byte[] data = read(context, document.getUri());
                Kind kind = classify(data);
                if (kind == Kind.UNRELATED) {
                    ignored++;
                    continue;
                }
                if (kind == Kind.RECEIPT) {
                    try {
                        ReceiptStore.ImportOutcome outcome = receiptStore.accept(
                                data, repository, ledger, localDevice, name, observed);
                        if (outcome.kind().equals("confirmed")) confirmed++;
                        else duplicateReceipts++;
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        rejectedReceipts++;
                        errors.add(name + ": " + error.getMessage());
                    }
                    continue;
                }
                ProbeRepository.ImportOutcome outcome = repository.importEnvelope(data, name);
                if (outcome.kind().equals("imported")) imported++;
                else if (outcome.kind().equals("duplicate")) duplicates++;
                else if (outcome.kind().equals("purged")) {
                    // An old transfer must never resurrect permanently deleted content.
                    ignored++;
                    continue;
                } else {
                    rejectedRevisions++;
                    continue;
                }
                // We already have the validated bytes from this transfer.
                // Don't load every repository revision again for this one.
                RevisionEnvelope revision = RevisionEnvelope.parse(data);
                if (finishRevisionImport(
                        outcome,
                        revision,
                        ledger,
                        receiptStore,
                        localDevice,
                        name,
                        observed)) receiptsQueued++;
            } catch (IOException | RuntimeException error) {
                errors.add(name + ": " + error.getMessage());
            }
        }
        return new ScanReport(
                imported,
                duplicates,
                confirmed,
                duplicateReceipts,
                receiptsQueued,
                rejectedRevisions,
                rejectedReceipts,
                ignored,
                List.copyOf(errors));
    }

    static boolean finishRevisionImport(
            ProbeRepository.ImportOutcome outcome,
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receiptStore,
            String localDevice,
            String sourceName,
            Instant observed) {
        // Compatibility for callers with only an import outcome.
        return finishRevisionImport(outcome,
                outcome.revisionId() == null ? null : repository.revision(outcome.revisionId()),
                ledger, receiptStore, localDevice, sourceName, observed);
    }

    static boolean finishRevisionImport(
            ProbeRepository.ImportOutcome outcome,
            RevisionEnvelope revision,
            DeliveryLedger ledger,
            ReceiptStore receiptStore,
            String localDevice,
            String sourceName,
            Instant observed) {
        if (!outcome.kind().equals("imported") && !outcome.kind().equals("duplicate")) {
            return false;
        }
        if (revision == null || !revision.revisionId().equals(outcome.revisionId())) {
            throw new IllegalStateException("imported revision identity changed");
        }
        ledger.recordImported(
                outcome.revisionId(), observed, "source=" + sourceName);
        if (revision.originDevice().equals(localDevice)) return false;
        boolean alreadyQueued = receiptStore.entries(revision.originDevice()).stream()
                .anyMatch(entry -> entry.revisionId().equals(revision.revisionId()));
        receiptStore.queue(revision, localDevice, observed);
        return !alreadyQueued;
    }

    static Kind classify(byte[] data) {
        if (startsWith(data, REVISION_MARKER)) return Kind.REVISION;
        if (startsWith(data, RECEIPT_MARKER)) return Kind.RECEIPT;
        return Kind.UNRELATED;
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

