package org.reasonnotes.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int TREE_REQUEST = 41;
    private static final UUID PROBE_NOTE = UUID.fromString("52548cf9-710c-43ca-b2fa-73ed4671aea9");
    private static final String KDE_DEVICE_ID = "254997f28f11499d80928fe8a109749e";
    private ProbeRepository repository;
    private DeliveryLedger ledger;
    private ReceiptStore receiptStore;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = ProbeRepository.open(new File(getFilesDir(), "probe-repository"));
        ledger = new DeliveryLedger(new File(getFilesDir(), "delivery-state"), 3);
        receiptStore = new ReceiptStore(repository.root(), 3);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int basePadding = 24;
        int actionBarHeight = actionBarHeight();
        content.setPadding(basePadding, contentTopPadding(basePadding, 0, actionBarHeight),
                basePadding, basePadding);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(basePadding,
                    contentTopPadding(basePadding, systemBars.top, actionBarHeight),
                    basePadding, basePadding + systemBars.bottom);
            return windowInsets;
        });
        addButton(content, "Choose ReasonNotes Exchange", view -> chooseTree());
        addButton(content, "Create deterministic probe note", view -> createProbe());
        addButton(content, "Sync now", view -> sync(false));
        addButton(content, "Retry unconfirmed", view -> sync(true));
        status = new TextView(this);
        status.setTextIsSelectable(true);
        content.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        ViewCompat.requestApplyInsets(content);
        render("Ready. Transfer requests are not completion acknowledgements.");
    }

    static int contentTopPadding(int basePadding, int systemBarTop, int actionBarHeight) {
        return basePadding + systemBarTop + actionBarHeight;
    }

    private int actionBarHeight() {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(android.R.attr.actionBarSize, value, true)) return 0;
        return TypedValue.complexToDimensionPixelSize(
                value.data, getResources().getDisplayMetrics());
    }

    @Override protected void onResume() {
        super.onResume();
        if (repository != null && ExchangeDirectory.savedTree(this) != null) {
            ExchangeDirectory.ScanReport report = ExchangeDirectory.scan(
                    this, repository, ledger, receiptStore, "android");
            render("Resume scan: imported=" + report.imported()
                    + " confirmed=" + report.confirmed()
                    + " receipt-queued=" + report.receiptsQueued()
                    + " rejected=" + report.rejected());
        }
    }

    private void addButton(LinearLayout layout, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        layout.addView(button);
    }

    private void chooseTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, TREE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TREE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            ExchangeDirectory.persistGrant(this, data.getData());
            render("Exchange directory grant persisted.");
        }
    }

    private void createProbe() {
        if (!repository.heads(PROBE_NOTE).isEmpty()) {
            render("Probe note already exists; no duplicate local edit created.");
            return;
        }
        RevisionEnvelope revision = repository.createProbeRevision(
                PROBE_NOTE, "# Android probe\n\nCreated for isolated KDE Connect testing.\n",
                "android", Instant.parse("2026-10-12T18:00:00Z"), false);
        ledger.registerLocal(revision.revisionId(), "linux");
        render("Created local revision " + revision.revisionId() + " pending for Linux.");
    }

    private void sync(boolean retry) {
        ExchangeDirectory.ScanReport scan = ExchangeDirectory.scan(
                this, repository, ledger, receiptStore, "android");
        List<String> eligible = ledger.eligible("linux", retry, List.of());
        List<String> eligibleReceipts = receiptStore.eligible("linux", retry, List.of());
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> receiptIds = new ArrayList<>();
        for (String id : eligible) {
            RevisionEnvelope revision = repository.revision(id);
            if (revision == null) continue;
            File file = new File(repository.root(), "history/" + revision.noteId() + "/" + id + ".md");
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", file));
            ids.add(id);
        }
        for (String id : eligibleReceipts) {
            File file = receiptStore.outboxFile(id);
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", file));
            receiptIds.add(id);
        }
        if (uris.isEmpty()) {
            render("Scan imported=" + scan.imported()
                    + " confirmed=" + scan.confirmed()
                    + "; no eligible revisions or receipts.");
            return;
        }
        KdeConnectSender.RequestOutcome outcome = KdeConnectSender.request(this, uris, KDE_DEVICE_ID);
        if (outcome.requested()) {
            Instant observed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            for (String id : ids) ledger.recordRequested(id, "linux", observed);
            for (String id : receiptIds) receiptStore.recordRequested(id, "linux", observed);
        }
        render("Scan imported=" + scan.imported() + "; transfer-requested=" + ids
                + "; receipt-transfer-requested=" + receiptIds
                + "; route=" + outcome.detail());
    }

    private void render(String message) {
        if (status == null) return;
        StringBuilder value = new StringBuilder(message).append("\n\n");
        value.append("Heads and previews:\n");
        for (RevisionEnvelope head : repository.heads()) {
            value.append("note ").append(head.noteId()).append("\n")
                    .append(head.originDevice()).append(" ").append(head.revisionId()).append("\n")
                    .append(head.deleted() ? "[deleted note]" : head.body()).append("\n");
        }
        value.append("\nEvents (distinct evidence):\n");
        for (DeliveryLedger.Event event : ledger.events()) {
            value.append(event.kind()).append(" ").append(event.revisionId()).append("\n");
        }
        value.append("\nReceipt outbox:\n");
        for (ReceiptStore.DeliveryEntry entry : receiptStore.entries("linux")) {
            value.append(entry.state()).append(" ").append(entry.receiptId())
                    .append(" revision ").append(entry.revisionId()).append("\n");
        }
        status.setText(value.toString());
    }
}

