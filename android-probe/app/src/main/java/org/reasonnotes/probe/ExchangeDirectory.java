package org.reasonnotes.probe;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

public final class ExchangeDirectory {
    private static final String PREFERENCES = "reasonnotes-exchange";
    private static final String TREE_KEY = "tree-uri";
    private static final String PERFORMANCE_TAG = "ReasonNotes.ReceivePerf";
    private record IncomingDocument(Uri uri, String name, long modifiedUtc) {}
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
        return scan(context, repository, ledger, receiptStore, localDevice, ignored -> {});
    }

    /**
     * Validate and import the newest revision documents before processing the
     * accumulated receipt backlog. Every file is still read and cryptographically
     * checked; no filename/mtime is trusted as delivery evidence.
     *
     * progress runs after a complete durable revision import, allowing the
     * foreground activity to refresh its list without waiting for old receipts.
     */
    public static ScanReport scan(
            Context context,
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receiptStore,
            String localDevice,
            IntConsumer progress) {
        long startedNs = System.nanoTime();
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
        int scanned = 0;
        int replayed = 0;
        long listedNs = System.nanoTime();
        // Snapshot durable evidence once rather than reopening and rewriting
        // full ledgers for every previously imported revision.
        Set<String> recordedImports = new HashSet<>();
        for (DeliveryLedger.Event event : ledger.events()) {
            if (event.kind().equals("reasonnotes-imported")) {
                recordedImports.add(event.revisionId());
            }
        }
        Map<String, Set<String>> queuedReceipts = new HashMap<>();
        Map<String, RevisionEnvelope> revisionSnapshot = null;
        for (IncomingDocument document : orderedDocuments(context, uri)) {
            String name = document.name();
            try {
                scanned++;
                byte[] data = read(context, document.uri());
                Kind kind = classify(data);
                if (kind == Kind.UNRELATED) {
                    ignored++;
                    continue;
                }
                if (kind == Kind.RECEIPT) {
                    try {
                        if (isObsoletePurgedReceipt(data, repository, localDevice)) {
                            // A permanently purged note has no revision left
                            // to confirm. Keep the deletion fence authoritative.
                            // Do not accept, delete or modify this receipt.
                            ignored++;
                            continue;
                        }
                        if (revisionSnapshot == null) {
                            revisionSnapshot = new HashMap<>();
                            for (RevisionEnvelope revision : repository.revisions()) {
                                revisionSnapshot.put(revision.revisionId(), revision);
                            }
                        }
                        ReceiptStore.ImportOutcome outcome = receiptStore.acceptWithSnapshot(
                                data, revisionSnapshot, repository,
                                ledger, localDevice, name, observed);
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
                if (outcome.kind().equals("duplicate")
                        && recordedImports.contains(revision.revisionId())
                        && (revision.originDevice().equals(localDevice)
                            || queuedReceipts.computeIfAbsent(
                                    revision.originDevice(),
                                    peer -> {
                                        Set<String> recorded = new HashSet<>();
                                        for (ReceiptStore.DeliveryEntry entry : receiptStore.entries(peer)) {
                                            recorded.add(entry.revisionId());
                                        }
                                        return recorded;
                                    }).contains(revision.revisionId()))) {
                    // The stored revision bytes have already been checked
                    // by repository.importEnvelope. The durable import event
                    // and queued receipt are also already present.
                    replayed++;
                    continue;
                }
                if (finishRevisionImport(
                        outcome, revision, ledger, receiptStore,
                        localDevice, name, observed)) {
                    receiptsQueued++;
                    queuedReceipts.computeIfAbsent(
                            revision.originDevice(), peer -> new HashSet<>())
                            .add(revision.revisionId());
                }
                recordedImports.add(revision.revisionId());
                if (outcome.kind().equals("imported")) {
                    progress.accept(imported);
                }
            } catch (IOException | RuntimeException error) {
                errors.add(name + ": " + error.getMessage());
            }
        }
        long finishedNs = System.nanoTime();
        Log.i(PERFORMANCE_TAG, "scan files=" + scanned
                + " imported=" + imported + " duplicates=" + duplicates
                + " unchangedReplays=" + replayed + " receiptsRejected=" + rejectedReceipts
                + " msTotal=" + (finishedNs - startedNs) / 1_000_000
                + " msFiles=" + (finishedNs - listedNs) / 1_000_000);
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

    /**
     * One SAF child query instead of DocumentFile.getName/isFile queries for
     * each of potentially hundreds of files. Documents remain in SAF; nothing
     * is copied, moved or removed.
     */
    private static List<IncomingDocument> orderedDocuments(Context context, Uri tree) {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        ArrayList<IncomingDocument> documents = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                children, columns, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("cannot list exchange files");
            int idIndex = cursor.getColumnIndexOrThrow(columns[0]);
            int nameIndex = cursor.getColumnIndexOrThrow(columns[1]);
            int typeIndex = cursor.getColumnIndex(columns[2]);
            int timeIndex = cursor.getColumnIndex(columns[3]);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                String name = cursor.getString(nameIndex);
                if (id == null || name == null || !name.toLowerCase(
                        java.util.Locale.ROOT).endsWith(".md")) continue;
                String type = typeIndex < 0 || cursor.isNull(typeIndex)
                        ? null : cursor.getString(typeIndex);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) continue;
                long modified = timeIndex < 0 || cursor.isNull(timeIndex)
                        ? 0 : cursor.getLong(timeIndex);
                documents.add(new IncomingDocument(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        name, modified));
            }
        }
        documents.sort(Comparator
                .comparingInt((IncomingDocument file) -> priority(file.name()))
                .thenComparing(Comparator.comparingLong(
                        IncomingDocument::modifiedUtc).reversed())
                .thenComparing(IncomingDocument::name));
        return documents;
    }

    /** Real revisions first; stale receipt cleanup must not hold up notes. */
    static int priority(String name) {
        return name.endsWith(".receipt.md") ? 1 : 0;
    }

    /** A canonical receipt about an already purged note cannot be confirmed. */
    static boolean isObsoletePurgedReceipt(
            byte[] data, ProbeRepository repository, String localDevice) {
        ReceiptEnvelope receipt = ReceiptEnvelope.parse(data);
        return receipt.destinationDevice().equals(localDevice)
                && repository.isPurged(receipt.noteId());
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

