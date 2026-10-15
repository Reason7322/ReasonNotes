package org.reasonnotes.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SyncActivity extends Activity {
    public static final String EXTRA_MODE = "sync-mode";
    public static final String MODE_ALL = "all";
    public static final String MODE_CHOOSE = "choose";
    private static final String STATE_QUERY = "query";
    private static final String STATE_SELECTED = "selected";

    public record SelectionSnapshot(String query, Set<UUID> selected) {}

    public static final class SelectionModel {
        private final List<SyncPlanner.CatalogueItem> catalogue;
        private final HashSet<UUID> selected = new HashSet<>();
        private String query = "";

        public SelectionModel(List<SyncPlanner.CatalogueItem> catalogue) {
            this.catalogue = List.copyOf(catalogue);
        }

        public SelectionModel(List<SyncPlanner.CatalogueItem> catalogue, SelectionSnapshot snapshot) {
            this(catalogue);
            query = snapshot.query();
            Set<UUID> valid = catalogue.stream().map(SyncPlanner.CatalogueItem::noteId)
                    .collect(java.util.stream.Collectors.toSet());
            snapshot.selected().stream().filter(valid::contains).forEach(selected::add);
        }

        public void setQuery(String value) { query = value == null ? "" : value; }
        public String query() { return query; }
        public List<SyncPlanner.CatalogueItem> visible() {
            String needle = normalize(query);
            if (needle.isEmpty()) return catalogue;
            return catalogue.stream().filter(item -> normalize(item.title()).contains(needle)).toList();
        }
        public void setSelected(UUID noteId, boolean value) {
            if (value) selected.add(noteId); else selected.remove(noteId);
        }
        public void selectAllVisible() { visible().forEach(item -> selected.add(item.noteId())); }
        public void clearSelection() { selected.clear(); }
        public Set<UUID> selected() { return Set.copyOf(selected); }
        public boolean canSync() { return !selected.isEmpty(); }
        public SelectionSnapshot snapshot() { return new SelectionSnapshot(query, selected()); }

        private static String normalize(String value) {
            return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SyncPlanner planner;
    private SyncCoordinator coordinator;
    private SelectionModel selection;
    private LinearLayout catalogueView;
    private EditText search;
    private Button syncSelected;
    private TextView status;
    private boolean chooseMode;
    private SelectionSnapshot restored;

    public static List<String> modeActions() {
        return List.of("Sync all notes", "Choose notes to sync");
    }

    public static String deliveryLabel(String state) {
        return switch (state) {
            case "pending" -> "Pending";
            case "unconfirmed" -> "Transfer requested — awaiting receipt";
            case "confirmed" -> "Confirmed by phone";
            case "needs-attention" -> "Needs attention";
            case "incoming-only" -> "Received from phone";
            default -> "Unknown";
        };
    }

    public static int bottomContentPadding(int base, int systemBottom, int imeBottom) {
        return base + Math.max(systemBottom, imeBottom);
    }

    static File deliveryStateDirectory(File filesDirectory) {
        return new File(filesDirectory, "delivery-state");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        chooseMode = MODE_CHOOSE.equals(getIntent().getStringExtra(EXTRA_MODE));
        if (state != null) {
            String query = state.getString(STATE_QUERY, "");
            ArrayList<String> ids = state.getStringArrayList(STATE_SELECTED);
            Set<UUID> selectedIds = ids == null ? Set.of() : ids.stream().map(UUID::fromString)
                    .collect(java.util.stream.Collectors.toSet());
            restored = new SelectionSnapshot(query, selectedIds);
        }
        initializeSync();
        setContentView(buildContent());
        loadCatalogueAndMaybeSync();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (selection != null) {
            SelectionSnapshot snapshot = selection.snapshot();
            state.putString(STATE_QUERY, snapshot.query());
            state.putStringArrayList(STATE_SELECTED,
                    new ArrayList<>(snapshot.selected().stream().map(UUID::toString).toList()));
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void initializeSync() {
        File root = new File(getFilesDir(), "probe-repository");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(deliveryStateDirectory(getFilesDir()), 3);
        ReceiptStore receipts = new ReceiptStore(root, 3);
        AppPreferences preferences = new AppPreferences(new AndroidLocalPreferences(
                getSharedPreferences(AppPreferences.PREFERENCES_NAME, MODE_PRIVATE)));
        String target = preferences.targetDeviceId();
        planner = new SyncPlanner(repository, ledger, receipts, "android", target);
        coordinator = new SyncCoordinator(
                repository,
                ledger,
                receipts,
                planner,
                target,
                ActiveDraftRegistry::flush,
                () -> {
                    ExchangeDirectory.ScanReport report = ExchangeDirectory.scan(
                            this, repository, ledger, receipts, "android");
                    return new SyncCoordinator.ScanEvidence(
                            report.imported(), report.confirmed(), report.receiptsQueued(), report.errors());
                },
                files -> requestTransfer(files, target),
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTokens.BACKGROUND);
        int base = UiTokens.dp(this, UiTokens.SPACE_M);
        root.setPadding(base, base, base, base);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(base, base + bars.top, base,
                    bottomContentPadding(base, bars.bottom, ime.bottom));
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false);
        back.setContentDescription("Back");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiTokens.dp(this, 52), UiTokens.dp(this, 48)));
        TextView title = text(chooseMode ? "Choose notes to sync" : "Sync all notes", 25, UiTokens.TEXT);
        title.setTypeface(UiTokens.medium());
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, matchWrap());

        if (chooseMode) {
            search = new EditText(this);
            search.setHint("Search sync catalogue");
            search.setSingleLine(true);
            search.setTextColor(UiTokens.TEXT);
            search.setHintTextColor(UiTokens.TEXT_SECONDARY);
            search.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
            search.setMinHeight(UiTokens.dp(this, 48));
            search.addTextChangedListener(new SimpleTextWatcher() {
                @Override public void afterTextChanged(Editable value) {
                    if (selection != null) { selection.setQuery(value.toString()); renderCatalogue(); }
                }
            });
            root.addView(search, withTopMargin(matchWrap()));

            LinearLayout actions = new LinearLayout(this);
            Button all = button("Select All", false);
            all.setOnClickListener(view -> { selection.selectAllVisible(); renderCatalogue(); });
            Button clear = button("Clear Selection", false);
            clear.setOnClickListener(view -> { selection.clearSelection(); renderCatalogue(); });
            actions.addView(all, new LinearLayout.LayoutParams(0, UiTokens.dp(this, 48), 1f));
            actions.addView(clear, new LinearLayout.LayoutParams(0, UiTokens.dp(this, 48), 1f));
            root.addView(actions, withTopMargin(matchWrap()));

            ScrollView scroll = new ScrollView(this);
            catalogueView = new LinearLayout(this);
            catalogueView.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(catalogueView);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            syncSelected = button("Sync selected notes", true);
            syncSelected.setEnabled(false);
            syncSelected.setOnClickListener(view -> startSync(
                    OutgoingSelection.selected(selection.selected())));
            root.addView(syncSelected, matchWrap());
        } else {
            TextView explanation = text(
                    "Incoming notes and receipts are scanned first. A requested transfer remains unconfirmed until a receipt returns.",
                    15, UiTokens.TEXT_SECONDARY);
            explanation.setLineSpacing(0, 1.2f);
            root.addView(explanation, withTopMargin(matchWrap()));
            Button start = button("Sync all notes", true);
            start.setOnClickListener(view -> startSync(OutgoingSelection.all()));
            root.addView(start, withTopMargin(matchWrap()));
        }

        status = text("Ready", 14, UiTokens.TEXT_SECONDARY);
        status.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, 0);
        root.addView(status, matchWrap());
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void loadCatalogueAndMaybeSync() {
        io.execute(() -> {
            List<SyncPlanner.CatalogueItem> items = planner.catalogue();
            runOnUiThread(() -> {
                selection = restored == null
                        ? new SelectionModel(items)
                        : new SelectionModel(items, restored);
                if (chooseMode) {
                    search.setText(selection.query());
                    renderCatalogue();
                }
            });
        });
    }

    private void renderCatalogue() {
        catalogueView.removeAllViews();
        for (SyncPlanner.CatalogueItem item : selection.visible()) {
            CheckBox row = new CheckBox(this);
            row.setText(item.title() + (item.deleted() ? " · Deleted" : "")
                    + (item.conflicted() ? " · Conflict" : "")
                    + "\n" + deliveryLabel(item.deliveryState()));
            row.setTextColor(item.conflicted() ? UiTokens.DANGER : UiTokens.TEXT);
            row.setTextSize(15);
            row.setMinHeight(UiTokens.dp(this, 64));
            row.setChecked(selection.selected().contains(item.noteId()));
            row.setOnCheckedChangeListener((button, checked) -> {
                selection.setSelected(item.noteId(), checked);
                syncSelected.setEnabled(selection.canSync());
            });
            catalogueView.addView(row, matchWrap());
        }
        syncSelected.setEnabled(selection.canSync());
    }

    private void startSync(OutgoingSelection outgoing) {
        status.setText("Scanning incoming notes and receipts…");
        status.setTextColor(UiTokens.TEXT_SECONDARY);
        if (syncSelected != null) syncSelected.setEnabled(false);
        io.execute(() -> {
            SyncStatus result = coordinator.synchronize(outgoing, true);
            runOnUiThread(() -> {
                status.setText(result.summary());
                status.setTextColor(result.phase() == SyncStatus.Phase.ERROR
                        ? UiTokens.DANGER : UiTokens.SUCCESS);
                if (syncSelected != null) syncSelected.setEnabled(selection.canSync());
            });
        });
    }

    private SyncCoordinator.RequestEvidence requestTransfer(
            List<SyncCoordinator.OutgoingFile> files, String targetDevice) {
        try {
            List<Uri> uris = files.stream().map(file -> FileProvider.getUriForFile(
                    this, getPackageName() + ".files", file.file())).toList();
            CompletableFuture<KdeConnectSender.RequestOutcome> launched = new CompletableFuture<>();
            runOnUiThread(() -> launched.complete(KdeConnectSender.request(this, uris, targetDevice)));
            KdeConnectSender.RequestOutcome outcome = launched.get(30, java.util.concurrent.TimeUnit.SECONDS);
            return new SyncCoordinator.RequestEvidence(outcome.requested(), outcome.detail());
        } catch (Exception error) {
            return new SyncCoordinator.RequestEvidence(false, "transfer request failed: " + error.getMessage());
        }
    }

    private Button button(String value, boolean accent) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(accent ? UiTokens.BACKGROUND : UiTokens.TEXT);
        button.setBackground(accent ? UiTokens.accent(this) : UiTokens.surface(this, UiTokens.SURFACE_RAISED));
        button.setMinHeight(UiTokens.dp(this, 48));
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams withTopMargin(LinearLayout.LayoutParams params) {
        params.topMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        return params;
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
    }
}
