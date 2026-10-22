package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ExchangeDirectoryInstrumentedTest {
    @Test
    public void persistedIsolatedTreeScansNotesAndRejectsPartialEnvelope() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        String supplied = arguments.getString("exchangeTreeUri");
        Uri tree = supplied == null ? ExchangeDirectory.savedTree(context) : Uri.parse(supplied);
        Assume.assumeTrue("requires user-granted isolated exchange tree", tree != null);
        ExchangeDirectory.persistGrant(context, tree);
        assertEquals(tree, ExchangeDirectory.savedTree(context));
        DocumentFile directory = DocumentFile.fromTreeUri(context, tree);
        assertTrue(directory != null && directory.isDirectory());

        RevisionEnvelope revision = RevisionEnvelope.create(
                UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd"),
                List.of(), "linux", Instant.parse("2026-10-12T13:00:00Z"), false, "phone fixture");
        write(context, directory.createFile("text/markdown", "reasonnotes-valid.md"), revision.serialize());
        write(context, directory.createFile("text/plain", "unrelated.txt"), "ignore".getBytes());
        byte[] partial = java.util.Arrays.copyOf(revision.serialize(), revision.serialize().length - 2);
        write(context, directory.createFile("text/markdown", "reasonnotes-partial.md"), partial);

        File root = new File(context.getFilesDir(), "instrumented-probe");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore receipts = new ReceiptStore(root, 3);
        ExchangeDirectory.ScanReport report = ExchangeDirectory.scan(
                context, repository, ledger, receipts, "android");

        assertEquals(1, report.imported());
        assertEquals(1, report.rejected());
        assertTrue(report.ignored() >= 1);
        assertEquals(tree, ExchangeDirectory.savedTree(context));
        assertEquals(revision, repository.current(revision.noteId()));
    }

    private static void write(Context context, DocumentFile document, byte[] data) throws Exception {
        assertTrue(document != null);
        try (OutputStream output = context.getContentResolver().openOutputStream(document.getUri(), "wt")) {
            assertTrue(output != null);
            output.write(data);
        }
    }
}
