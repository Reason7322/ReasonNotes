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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.util.TypedValue;
import android.graphics.Color;
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
    private AppPreferences.ThemeMode displayedTheme;
    public static final String EXTRA_MODE = "sync-mode";
    public static final String MODE_ALL = "all";
    public static final String MODE_CHOOSE = "choose";
    // Logical revision/receipt identity, not the physical KDE Connect device ID.
    private static final String LINUX_PEER = "linux";
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
    private ProbeRepository repository;
    private SyncCoordinator coordinator;
    private SelectionModel selection;
    private LinearLayout catalogueView;
    private EditText search;
    private Button syncSelected;
    private Button syncAll;
    private TextView retryAction;
    private boolean syncInFlight;
    private TextView selectAllAction;
    private TextView clearAction;
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
            case "confirmed" -> "Confirmed by Linux";
            case "needs-attention" -> "Needs attention";
            case "incoming-only" -> "Received from Linux";
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
        displayedTheme = UiTokens.prepare(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        UiTokens.configureSystemBars(this);
        chooseMode = MODE_CHOOSE.equals(getIntent().getStringExtra(EXTRA_MODE));
        if (state != null) {
            String query = state.getString(STATE_QUERY, "");
            ArrayList<String> ids = state.getStringArrayList(STATE_SELECTED);
            Set<UUID> selectedIds = ids == null ? Set.of() : ids.stream().map(UUID::fromString)
                    .collect(java.util.stream.Collectors.toSet());
            restored = new SelectionSnapshot(query, selectedIds);
        }
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

    static String preparePeerLedger(DeliveryLedger ledger, String physicalTarget) {
        // In-place, idempotent, fail-closed migration: do not reset pre-upgrade
        // retries, delivery confirmations or the existing event history.
        ledger.migrateTargets(
                List.of(physicalTarget, AppPreferences.DEFAULT_TARGET_DEVICE_ID), LINUX_PEER);
        return LINUX_PEER;
    }

    private void initializeSync() {
        File root = new File(getFilesDir(), "probe-repository");
        // The picker only needs immutable history. Full derived-view repair is
        // deferred until a real sync is requested, not before displaying notes.
        ProbeRepository opened = ProbeRepository.openForDisplay(root);
        DeliveryLedger ledger = new DeliveryLedger(deliveryStateDirectory(getFilesDir()), 3);
        ReceiptStore receipts = new ReceiptStore(root, 3);
        AppPreferences preferences = new AppPreferences(new AndroidLocalPreferences(
                getSharedPreferences(AppPreferences.PREFERENCES_NAME, MODE_PRIVATE)));
        String target = preferences.targetDeviceId();
        String peer = preparePeerLedger(ledger, target);
        planner = new SyncPlanner(opened, ledger, receipts, "android", peer);
        coordinator = new SyncCoordinator(
                opened,
                ledger,
                receipts,
                planner,
                peer,
                ActiveDraftRegistry::flush,
                () -> {
                    ExchangeDirectory.ScanReport report = ExchangeDirectory.scan(
                            this, opened, ledger, receipts, "android");
                    return new SyncCoordinator.ScanEvidence(
                            report.imported(), report.confirmed(), report.receiptsQueued(), report.errors());
                },
                files -> requestTransfer(files, target),
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS));
        repository = opened;
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTokens.background(this));
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
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_note_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Back to settings");
        TypedValue backRipple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                backRipple, true)) back.setBackgroundResource(backRipple.resourceId);
        else back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 48), UiTokens.dp(this, 48)));
        TextView title = text(chooseMode ? "Choose notes" : "Sync notes", 25, UiTokens.TEXT);
        title.setTypeface(UiTokens.medium());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = UiTokens.dp(this, 12);
        header.addView(title, titleParams);
        root.addView(header, matchWrap());

        if (chooseMode) {
            search = new EditText(this);
            search.setHint("Find a note");
            search.setSingleLine(true);
            search.setTextColor(UiTokens.TEXT);
            search.setHintTextColor(UiTokens.TEXT_SECONDARY);
            search.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
            search.setMinHeight(UiTokens.dp(this, 48));
            search.setPadding(UiTokens.dp(this, 14), 0, UiTokens.dp(this, 14), 0);
            search.addTextChangedListener(new SimpleTextWatcher() {
                @Override public void afterTextChanged(Editable value) {
                    if (selection != null) { selection.setQuery(value.toString()); renderCatalogue(); }
                }
            });
            root.addView(search, withTopMargin(matchWrap()));

            LinearLayout actions = new LinearLayout(this);
            selectAllAction = text("Select all", 14, UiTokens.ACCENT);
            selectAllAction.setGravity(Gravity.CENTER);
            selectAllAction.setEnabled(false);
            selectAllAction.setOnClickListener(view -> {
                selection.selectAllVisible();
                renderCatalogue();
            });
            clearAction = text("Clear", 14, UiTokens.TEXT_SECONDARY);
            clearAction.setGravity(Gravity.CENTER);
            clearAction.setEnabled(false);
            clearAction.setOnClickListener(view -> {
                selection.clearSelection();
                renderCatalogue();
            });
            actions.addView(selectAllAction,
                    new LinearLayout.LayoutParams(0, UiTokens.dp(this, 48), 1f));
            actions.addView(clearAction,
                    new LinearLayout.LayoutParams(0, UiTokens.dp(this, 48), 1f));
            root.addView(actions, withTopMargin(matchWrap()));

            ScrollView scroll = new ScrollView(this);
            catalogueView = new LinearLayout(this);
            catalogueView.setOrientation(LinearLayout.VERTICAL);
            catalogueView.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
            catalogueView.setPadding(UiTokens.dp(this, 12), UiTokens.dp(this, 8),
                    UiTokens.dp(this, 12), UiTokens.dp(this, 8));
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
                    "Review incoming changes, then send your latest notes to your computer.",
                    15, UiTokens.TEXT_SECONDARY);
            explanation.setLineSpacing(0, 1.2f);
            root.addView(explanation, withTopMargin(matchWrap()));
            syncAll = button("Start sync", true);
            syncAll.setEnabled(false);
            syncAll.setOnClickListener(view -> startSync(OutgoingSelection.all()));
            root.addView(syncAll, withTopMargin(matchWrap()));
        }

        // A normal sync must NOT repeatedly resend every transfer that is
        // awaiting confirmation. Retries remain explicitly available.
        retryAction = text(chooseMode
                ? "Retry unconfirmed selected notes" : "Retry unconfirmed transfers",
                14, UiTokens.ACCENT);
        retryAction.setGravity(Gravity.CENTER);
        retryAction.setMinHeight(UiTokens.dp(this, 44));
        retryAction.setEnabled(false);
        retryAction.setContentDescription(
                "Retry earlier transfer requests still waiting for confirmation");
        retryAction.setOnClickListener(view -> {
            OutgoingSelection outgoing = chooseMode
                    ? OutgoingSelection.selected(selection.selected())
                    : OutgoingSelection.all();
            startSync(outgoing, true);
        });
        root.addView(retryAction, withTopMargin(matchWrap()));

        status = text("Loading notes…", 14, UiTokens.TEXT_SECONDARY);
        status.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, 0);
        root.addView(status, matchWrap());
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void loadCatalogueAndMaybeSync() {
        io.execute(() -> {
            try {
                // Opening and reconciling the repository may require disk I/O.
                // Build the visible UI first and initialize on the I/O worker.
                initializeSync();
                List<SyncPlanner.CatalogueItem> items = planner.catalogue();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    selection = restored == null
                            ? new SelectionModel(items)
                            : new SelectionModel(items, restored);
                    if (chooseMode) {
                        selectAllAction.setEnabled(true);
                        clearAction.setEnabled(true);
                        retryAction.setEnabled(selection.canSync());
                        search.setText(selection.query());
                        renderCatalogue();
                    } else {
                        syncAll.setEnabled(true);
                        retryAction.setEnabled(true);
                    }
                    status.setVisibility(View.GONE);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText("Cannot prepare sync: " + error.getMessage());
                    status.setTextColor(UiTokens.DANGER);
                    status.setVisibility(View.VISIBLE);
                });
            }
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
                syncSelected.setEnabled(selection.canSync() && !syncInFlight);
            });
            catalogueView.addView(row, matchWrap());
        }
        syncSelected.setEnabled(selection.canSync() && !syncInFlight);
        if (retryAction != null) retryAction.setEnabled(selection.canSync() && !syncInFlight);
    }

    private void startSync(OutgoingSelection outgoing) {
        startSync(outgoing, false);
    }

    private void startSync(OutgoingSelection outgoing, boolean retryUnconfirmed) {
        if (coordinator == null || syncInFlight) return;
        syncInFlight = true;
        status.setVisibility(View.VISIBLE);
        status.setText("Checking incoming changes…");
        status.setTextColor(UiTokens.TEXT_SECONDARY);
        if (syncSelected != null) syncSelected.setEnabled(false);
        if (syncAll != null) syncAll.setEnabled(false);
        if (retryAction != null) retryAction.setEnabled(false);
        io.execute(() -> {
            SyncStatus result = null;
            RuntimeException failure = null;
            try {
                // Required durability/recovery step only when user starts a
                // transfer, never while they are simply opening the picker.
                repository.reconcile();
                result = coordinator.synchronize(outgoing, retryUnconfirmed);
            } catch (RuntimeException error) {
                failure = error;
            }
            SyncStatus finalResult = result;
            RuntimeException finalFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                syncInFlight = false;
                if (finalFailure != null) {
                    status.setText("Sync could not complete: " + finalFailure.getMessage());
                    status.setTextColor(UiTokens.DANGER);
                } else {
                    status.setText(finalResult.summary());
                    status.setTextColor(finalResult.phase() == SyncStatus.Phase.ERROR
                            ? UiTokens.DANGER : UiTokens.SUCCESS);
                }
                if (syncSelected != null && selection != null) {
                    syncSelected.setEnabled(selection.canSync());
                }
                if (syncAll != null) syncAll.setEnabled(true);
                if (retryAction != null) retryAction.setEnabled(
                        !chooseMode || (selection != null && selection.canSync()));
            });
        });
    }

    private SyncCoordinator.RequestEvidence requestTransfer(
            List<SyncCoordinator.OutgoingFile> files, String targetDevice) {
        try {
            // A multi-file ACTION_SEND_MULTIPLE launch did not reliably reach
            // Fedora. Build a single validated ZIP before granting a URI, so
            // KDE Connect needs to read just one file from our FileProvider.
            File payload = files.size() == 1 ? files.get(0).file()
                    : TransferBundle.create(
                            new File(new File(getFilesDir(), "probe-repository"), "outgoing"),
                            files);
            List<Uri> uris = List.of(FileProvider.getUriForFile(
                    this, getPackageName() + ".files", payload));
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
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(accent ? UiTokens.ACCENT_TEXT : UiTokens.TEXT);
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
