package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
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
    private static final String STATE_READINESS_ACTIVE = "readiness-active";
    private static final String STATE_READINESS_ALL = "readiness-all";
    private static final String STATE_READINESS_IDS = "readiness-ids";
    private static final String STATE_READINESS_RETRY = "readiness-retry";
    private static final String STATE_READINESS_RETURNED = "readiness-returned";
    private static final long CONFIRMATION_POLL_MS = 15000L;
    private static final String CONFIRMATION_PREFS = "reasonnotes-sync-confirmation";

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
            Set<UUID> valid = this.catalogue.stream().map(SyncPlanner.CatalogueItem::noteId)
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
            if (value && catalogue.stream().anyMatch(item -> item.noteId().equals(noteId))) {
                selected.add(noteId);
            } else if (!value) {
                selected.remove(noteId);
            }
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
    private CurrentNoteStore currentStore;
    private SyncCoordinator coordinator;
    private SelectionModel selection;
    private LinearLayout catalogueView;
    private EditText search;
    private Button syncSelected;
    private Button syncAll;
    private DeliveryLedger deliveryLedger;
    private boolean syncInFlight;
    private TextView selectAllAction;
    private TextView clearAction;
    private TextView status;
    private boolean chooseMode;
    private SelectionSnapshot restored;
    private final Handler confirmationHandler = new Handler(Looper.getMainLooper());
    private final Runnable confirmationTask = this::pollIncomingConfirmations;
    private Set<String> awaitingRevisionIds = Set.of();
    private OutgoingSelection awaitingSelection;
    private SyncConfirmationRecord confirmationRecord;
    private long confirmationGeneration;
    private boolean confirmationPollInFlight;
    private boolean activityVisible;
    private KdeConnectReadinessGate readinessGate = new KdeConnectReadinessGate();
    private boolean kdeConnectLaunchInProgress;
    private boolean readinessDialogVisible;

    public static List<String> modeActions() {
        return List.of("Sync all notes", "Choose notes to sync");
    }

    static String readinessConfirmationMessage() {
        return "Is your computer visible and connected in KDE Connect?";
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

    /** User-facing status deliberately omits internal revision/receipt counts.
     * A transfer *request* is not a destination-confirmed synchronization.
     */
    public enum CompletionEvidence { CONFIRMED, AWAITING_RECEIPT, NEEDS_ATTENTION }

    static String conciseStatus(SyncStatus.Phase phase, CompletionEvidence evidence) {
        return switch (phase) {
            case IDLE -> "";
            case SCANNING -> "Syncing…";
            case ERROR -> "Error: Sync failed ❌";
            case TRANSFER_REQUESTED -> "Sync requested ✅";
            case COMPLETE -> switch (evidence) {
                case CONFIRMED -> "All selected notes synced ✅";
                case AWAITING_RECEIPT -> "Sync requested ✅";
                case NEEDS_ATTENTION -> "Error: Some notes need attention ❌";
            };
        };
    }

    static CompletionEvidence deliveryEvidence(
            List<RevisionEnvelope> history, List<DeliveryLedger.Entry> entries,
            OutgoingSelection outgoing) {
        Map<String, DeliveryLedger.Entry> byRevision = new HashMap<>();
        for (DeliveryLedger.Entry entry : entries) {
            byRevision.put(entry.revisionId(), entry);
        }
        boolean waiting = false;
        Set<UUID> live = SyncPlanner.activeNotes(history);
        for (RevisionEnvelope revision : history) {
            if (!revision.originDevice().equals("android")
                    || (live.contains(revision.noteId())
                        ? !outgoing.includes(revision.noteId()) : !outgoing.allNotes())) continue;
            DeliveryLedger.Entry state = byRevision.get(revision.revisionId());
            if (state == null || state.state().equals("pending")
                    || state.state().equals("needs-attention")) {
                return CompletionEvidence.NEEDS_ATTENTION;
            }
            if (state.state().equals("transfer-requested")) waiting = true;
        }
        return waiting ? CompletionEvidence.AWAITING_RECEIPT
                : CompletionEvidence.CONFIRMED;
    }

    static Set<String> newlyRequested(
            List<DeliveryLedger.Entry> before, List<DeliveryLedger.Entry> after) {
        Map<String, Integer> originalAttempts = new HashMap<>();
        for (DeliveryLedger.Entry entry : before) {
            originalAttempts.put(entry.revisionId(), entry.attempts());
        }
        HashSet<String> requested = new HashSet<>();
        for (DeliveryLedger.Entry entry : after) {
            if (entry.state().equals("transfer-requested")
                    && entry.attempts() > originalAttempts.getOrDefault(entry.revisionId(), 0)) {
                requested.add(entry.revisionId());
            }
        }
        return Set.copyOf(requested);
    }

    static CompletionEvidence confirmationOf(
            Set<String> requested, List<DeliveryLedger.Entry> entries) {
        if (requested.isEmpty()) return CompletionEvidence.NEEDS_ATTENTION;
        Map<String, String> states = new HashMap<>();
        for (DeliveryLedger.Entry entry : entries) {
            states.put(entry.revisionId(), entry.state());
        }
        for (String id : requested) {
            String state = states.get(id);
            if (state == null || state.equals("needs-attention") || state.equals("pending")) {
                return CompletionEvidence.NEEDS_ATTENTION;
            }
            if (!state.equals("confirmed") && !state.equals("transfer-requested")) {
                return CompletionEvidence.NEEDS_ATTENTION;
            }
        }
        return requested.stream().allMatch(id -> states.get(id).equals("confirmed"))
                ? CompletionEvidence.CONFIRMED : CompletionEvidence.AWAITING_RECEIPT;
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
            if (state.getBoolean(STATE_READINESS_ACTIVE, false)) {
                ArrayList<String> readinessIds = state.getStringArrayList(STATE_READINESS_IDS);
                Set<UUID> noteIds = readinessIds == null ? Set.of() : readinessIds.stream()
                        .map(UUID::fromString).collect(java.util.stream.Collectors.toSet());
                OutgoingSelection pending = state.getBoolean(STATE_READINESS_ALL, false)
                        ? OutgoingSelection.all() : OutgoingSelection.selected(noteIds);
                readinessGate = new KdeConnectReadinessGate(new KdeConnectReadinessGate.Snapshot(
                        new KdeConnectReadinessGate.Request(
                                pending, state.getBoolean(STATE_READINESS_RETRY, false)),
                        state.getBoolean(STATE_READINESS_RETURNED, false)));
                kdeConnectLaunchInProgress = !readinessGate.canConfirm();
            }
        }
        confirmationRecord = new SyncConfirmationRecord(new AndroidLocalPreferences(
                getSharedPreferences(CONFIRMATION_PREFS, MODE_PRIVATE)));
        SyncConfirmationRecord.Pending previous = confirmationRecord.load();
        if (previous != null) {
            awaitingRevisionIds = previous.revisionIds();
            awaitingSelection = previous.selection();
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
        KdeConnectReadinessGate.Snapshot readiness = readinessGate.snapshot();
        if (readiness.request() != null) {
            state.putBoolean(STATE_READINESS_ACTIVE, true);
            state.putBoolean(STATE_READINESS_ALL, readiness.request().selection().allNotes());
            state.putStringArrayList(STATE_READINESS_IDS, new ArrayList<>(
                    readiness.request().selection().noteIds().stream().map(UUID::toString).toList()));
            state.putBoolean(STATE_READINESS_RETRY, readiness.request().retryUnconfirmed());
            state.putBoolean(STATE_READINESS_RETURNED, readiness.returned());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        activityVisible = true;
        if (readinessGate.canConfirm()) {
            showReadinessConfirmation();
        } else if (kdeConnectLaunchInProgress && readinessGate.returnedFromKdeConnect()) {
            kdeConnectLaunchInProgress = false;
            showReadinessConfirmation();
        }
        if (!awaitingRevisionIds.isEmpty()) scheduleConfirmationPoll(500L);
    }

    @Override protected void onPause() {
        activityVisible = false;
        confirmationHandler.removeCallbacks(confirmationTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        confirmationHandler.removeCallbacks(confirmationTask);
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
        AppPreferences preferences = new AppPreferences(new AndroidLocalPreferences(
                getSharedPreferences(AppPreferences.PREFERENCES_NAME, MODE_PRIVATE)));
        String target = preferences.targetDeviceId();
        NoteService application = NoteService.openApplication(
                getFilesDir(), "android",
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS), UUID::randomUUID);
        CurrentNoteStore store = application.currentStore();
        coordinator = new SyncCoordinator(
                store,
                LINUX_PEER,
                ActiveDraftRegistry::flush,
                () -> {
                    ExchangeDirectory.ScanReport report = ExchangeDirectory.scan(
                            this, store, "android", ignored -> {});
                    return new SyncCoordinator.ScanEvidence(
                            report.imported(), report.confirmed(), report.receiptsQueued(), report.errors());
                },
                files -> requestTransfer(files, target),
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS));
        currentStore = store;
    }

    private List<SyncPlanner.CatalogueItem> currentCatalogue() {
        Map<UUID, StateEnvelope> pending = new HashMap<>();
        currentStore.pendingStates().forEach(state -> pending.put(state.noteId(), state));
        Map<UUID, SyncPlanner.CatalogueItem> items = new HashMap<>();
        for (StateEnvelope state : currentStore.list()) {
            boolean conflicted = currentStore.conflict(state.noteId()) != null;
            items.put(state.noteId(), new SyncPlanner.CatalogueItem(
                    state.noteId(), state.title().isEmpty() ? "Untitled" : state.title(),
                    false, conflicted, state.modifiedUtc(),
                    pending.containsKey(state.noteId()) ? "pending" : "confirmed"));
        }
        for (CurrentNoteStore.Conflict conflict : currentStore.conflicts()) {
            if (items.containsKey(conflict.noteId())) continue;
            StateEnvelope display = conflict.candidates().get(0);
            items.put(conflict.noteId(), new SyncPlanner.CatalogueItem(
                    conflict.noteId(), display.title().isEmpty() ? "Untitled" : display.title(),
                    false, true, display.modifiedUtc(), "pending"));
        }
        for (StateEnvelope state : pending.values()) {
            if (!state.operation().equals("delete")) continue;
            items.put(state.noteId(), new SyncPlanner.CatalogueItem(
                    state.noteId(), "Deleted note", true, false, state.modifiedUtc(), "pending"));
        }
        return items.values().stream()
                .sorted(java.util.Comparator.comparing(SyncPlanner.CatalogueItem::modifiedUtc)
                        .reversed().thenComparing(item -> item.noteId().toString()))
                .toList();
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
            syncSelected.setOnClickListener(view -> prepareSync(
                    OutgoingSelection.selected(selection.selected())));
            syncSelected.setOnLongClickListener(view -> {
                if (selection != null && selection.canSync()) {
                    offerRetry(OutgoingSelection.selected(selection.selected()));
                }
                return true;
            });
            root.addView(syncSelected, matchWrap());
        } else {
            TextView explanation = text(
                    "Review incoming changes, then send your latest notes to your computer.",
                    15, UiTokens.TEXT_SECONDARY);
            explanation.setLineSpacing(0, 1.2f);
            root.addView(explanation, withTopMargin(matchWrap()));
            syncAll = button("Start sync", true);
            syncAll.setEnabled(false);
            syncAll.setOnClickListener(view -> prepareSync(OutgoingSelection.all()));
            syncAll.setOnLongClickListener(view -> {
                offerRetry(OutgoingSelection.all());
                return true;
            });
            root.addView(syncAll, withTopMargin(matchWrap()));
        }

        // The only visible feedback region. Never display import/receipt
        // counters, raw transfer details or a permanently visible retry link.
        status = text("", 16, UiTokens.TEXT_SECONDARY);
        status.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_L), 0, 0);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
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
                List<SyncPlanner.CatalogueItem> items = currentCatalogue();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    selection = restored == null
                            ? new SelectionModel(items)
                            : new SelectionModel(items, restored);
                    if (chooseMode) {
                        selectAllAction.setEnabled(true);
                        clearAction.setEnabled(true);
                        search.setText(selection.query());
                        renderCatalogue();
                    } else {
                        syncAll.setEnabled(true);
                    }
                    if (awaitingRevisionIds.isEmpty()) {
                        status.setVisibility(View.GONE);
                    } else {
                        // Restored from the durable last-sync checkpoint.
                        // Never use a new screen selection to reinterpret it.
                        status.setText("Waiting for computer confirmation…");
                        status.setTextColor(UiTokens.TEXT_SECONDARY);
                        status.setVisibility(View.VISIBLE);
                        scheduleConfirmationPoll(500L);
                    }
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText("Error: Unable to prepare sync ❌");
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
    }

    /**
     * While this screen is visible, validate incoming receipts through the
     * normal strict receiver. There is no background service or automatic
     * resend. Stale/wrong-peer receipts cannot trigger the green state.
     */
    private void scheduleConfirmationPoll(long delay) {
        confirmationHandler.removeCallbacks(confirmationTask);
        if (activityVisible && !syncInFlight && !awaitingRevisionIds.isEmpty()) {
            confirmationHandler.postDelayed(confirmationTask, delay);
        }
    }

    private void pollIncomingConfirmations() {
        if (!activityVisible || syncInFlight || awaitingRevisionIds.isEmpty()
                || confirmationPollInFlight || currentStore == null) return;
        confirmationPollInFlight = true;
        long generation = confirmationGeneration;
        Set<String> ids = awaitingRevisionIds;
        io.execute(() -> {
            RuntimeException failure = null;
            try {
                ExchangeDirectory.scan(this, currentStore, "android", ignored -> {});
            } catch (RuntimeException error) {
                failure = error;
            }
            CompletionEvidence evidence = CompletionEvidence.NEEDS_ATTENTION;
            if (failure == null) {
                try {
                    Set<String> pending = currentStore.pendingStates().stream()
                            .map(StateEnvelope::changeId).collect(java.util.stream.Collectors.toSet());
                    evidence = ids.stream().noneMatch(pending::contains)
                            ? CompletionEvidence.CONFIRMED
                            : CompletionEvidence.AWAITING_RECEIPT;
                } catch (RuntimeException error) {
                    failure = error;
                }
            }
            RuntimeException finalFailure = failure;
            CompletionEvidence finalEvidence = evidence;
            runOnUiThread(() -> {
                confirmationPollInFlight = false;
                if (!activityVisible || generation != confirmationGeneration
                        || isFinishing() || isDestroyed()) return;
                if (finalEvidence == CompletionEvidence.CONFIRMED) {
                    awaitingRevisionIds = Set.of();
                    confirmationHandler.removeCallbacks(confirmationTask);
                    // A durable receipt can arrive hours after the original
                    // Android session ended; never expire a pending checkpoint.
                    // Do not claim all historical notes are confirmed when
                    // other, older outgoing revisions remain pending.
                    confirmationRecord.clear();
                    status.setText("All selected notes synced ✅");
                    status.setTextColor(UiTokens.SUCCESS);
                } else {
                    status.setText(finalFailure == null
                            ? "Waiting for computer confirmation…"
                            : "Checking confirmation…");
                    status.setTextColor(UiTokens.TEXT_SECONDARY);
                    scheduleConfirmationPoll(CONFIRMATION_POLL_MS);
                }
            });
        });
    }

    /**
     * Retry is still accessible via a confirmed long-press on Start sync.
     * Ordinary short presses never silently resend pending requests.
     */
    private void offerRetry(OutgoingSelection outgoing) {
        if (syncInFlight || coordinator == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Retry previous transfers?")
                .setMessage("Resend notes still waiting for confirmation from your computer?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Retry", (dialog, which) -> prepareSync(outgoing, true))
                .show();
    }

    private void prepareSync(OutgoingSelection outgoing) {
        prepareSync(outgoing, false);
    }

    private void prepareSync(OutgoingSelection outgoing, boolean retryUnconfirmed) {
        if (coordinator == null || syncInFlight
                || !readinessGate.begin(outgoing, retryUnconfirmed)) return;
        kdeConnectLaunchInProgress = true;
        KdeConnectSender.RequestOutcome outcome = KdeConnectSender.launchApplication(this);
        if (outcome.requested()) return;
        kdeConnectLaunchInProgress = false;
        readinessGate.cancel();
        status.setVisibility(View.VISIBLE);
        status.setText(outcome.detail());
        status.setTextColor(UiTokens.DANGER);
    }

    private void showReadinessConfirmation() {
        if (!readinessGate.canConfirm() || readinessDialogVisible
                || isFinishing() || isDestroyed()) return;
        readinessDialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Confirm KDE Connect")
                .setMessage(readinessConfirmationMessage())
                .setNegativeButton("Not yet", (ignored, which) -> {
                    readinessDialogVisible = false;
                    readinessGate.cancel();
                })
                .setPositiveButton("Yes, continue", (ignored, which) -> {
                    readinessDialogVisible = false;
                    KdeConnectReadinessGate.Request request = readinessGate.confirm();
                    if (request != null) {
                        startSync(request.selection(), request.retryUnconfirmed());
                    }
                })
                .create();
        dialog.setOnCancelListener(ignored -> {
            readinessDialogVisible = false;
            readinessGate.cancel();
        });
        dialog.show();
    }

    private void startSync(OutgoingSelection outgoing, boolean retryUnconfirmed) {
        if (coordinator == null || syncInFlight) return;
        syncInFlight = true;
        confirmationGeneration++;
        confirmationHandler.removeCallbacks(confirmationTask);
        // Keep the previous durable checkpoint until another request is
        // actually registered. An ordinary sync may have no new payload.
        status.setVisibility(View.VISIBLE);
        status.setText(conciseStatus(SyncStatus.Phase.SCANNING, CompletionEvidence.CONFIRMED));
        status.setTextColor(UiTokens.TEXT_SECONDARY);
        if (syncSelected != null) syncSelected.setEnabled(false);
        if (syncAll != null) syncAll.setEnabled(false);
        io.execute(() -> {
            SyncStatus result = null;
            RuntimeException failure = null;
            CompletionEvidence completion = CompletionEvidence.NEEDS_ATTENTION;
            Set<String> requestedThisRun = Set.of();
            String affectedTitle = selectedTitle(outgoing);
            String conflictTitle = selectedConflictTitle(outgoing);
            try {
                Set<String> operationIds = currentStore.pendingStates().stream()
                        .filter(state -> outgoing.includes(state.noteId()))
                        .map(StateEnvelope::changeId)
                        .collect(java.util.stream.Collectors.toSet());
                result = coordinator.synchronize(outgoing, retryUnconfirmed);
                requestedThisRun = result.phase() == SyncStatus.Phase.TRANSFER_REQUESTED
                        ? Set.copyOf(operationIds) : Set.of();
                if (result.phase() == SyncStatus.Phase.COMPLETE) {
                    completion = CompletionEvidence.CONFIRMED;
                }
            } catch (RuntimeException error) {
                failure = error;
            }
            SyncStatus finalResult = result;
            RuntimeException finalFailure = failure;
            CompletionEvidence finalCompletion = completion;
            Set<String> requestedIds = requestedThisRun;
            String finalAffectedTitle = affectedTitle;
            String finalConflictTitle = conflictTitle;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                syncInFlight = false;
                if (finalFailure != null) {
                    status.setText(CurrentSyncPresentation.message(
                            SyncStatus.error(finalFailure.getMessage()), finalAffectedTitle));
                    status.setTextColor(UiTokens.DANGER);
                } else {
                    String message = finalConflictTitle != null
                            ? CurrentSyncPresentation.conflict(finalConflictTitle)
                            : CurrentSyncPresentation.message(finalResult, finalAffectedTitle);
                    if (finalConflictTitle != null
                            && finalResult.phase() == SyncStatus.Phase.TRANSFER_REQUESTED) {
                        message = CurrentSyncPresentation.message(finalResult, finalAffectedTitle)
                                + " · " + message;
                    }
                    status.setText(message);
                    status.setTextColor(
                            finalResult.phase() == SyncStatus.Phase.ERROR
                            || (finalResult.phase() == SyncStatus.Phase.COMPLETE
                                && finalCompletion == CompletionEvidence.NEEDS_ATTENTION)
                                    ? UiTokens.DANGER : UiTokens.SUCCESS);
                }
                if (finalFailure == null
                        && finalResult.phase() == SyncStatus.Phase.TRANSFER_REQUESTED
                        && !requestedIds.isEmpty()) {
                    confirmationRecord.save(requestedIds, outgoing);
                    awaitingRevisionIds = requestedIds;
                    awaitingSelection = outgoing;
                    status.setText("Waiting for computer confirmation…");
                    status.setTextColor(UiTokens.TEXT_SECONDARY);
                    scheduleConfirmationPoll(CONFIRMATION_POLL_MS);
                } else if (finalFailure == null && !awaitingRevisionIds.isEmpty()) {
                    // No new files were sent; keep checking the last request.
                    status.setText("Waiting for computer confirmation…");
                    status.setTextColor(UiTokens.TEXT_SECONDARY);
                    scheduleConfirmationPoll(500L);
                }
                if (syncSelected != null && selection != null) {
                    syncSelected.setEnabled(selection.canSync());
                }
                if (syncAll != null) syncAll.setEnabled(true);
            });
        });
    }

    private String selectedTitle(OutgoingSelection outgoing) {
        if (outgoing.allNotes() || outgoing.noteIds().size() != 1) return null;
        UUID selected = outgoing.noteIds().iterator().next();
        return currentCatalogue().stream().filter(item -> item.noteId().equals(selected))
                .map(SyncPlanner.CatalogueItem::title).findFirst().orElse(null);
    }

    private String selectedConflictTitle(OutgoingSelection outgoing) {
        return currentCatalogue().stream()
                .filter(item -> outgoing.includes(item.noteId()) && item.conflicted())
                .map(SyncPlanner.CatalogueItem::title).findFirst().orElse(null);
    }

    private SyncCoordinator.RequestEvidence requestTransfer(
            List<SyncCoordinator.OutgoingFile> files, String targetDevice) {
        try {
            File payload = createTransportPayload(
                    new File(new File(getFilesDir(), "reasonnotes-v2"), "outgoing"),
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

    static File createTransportPayload(
            File directory, List<SyncCoordinator.OutgoingFile> files) throws IOException {
        ArrayList<TransportBundle.Item> items = new ArrayList<>();
        for (SyncCoordinator.OutgoingFile outgoing : files) {
            if (!Files.isRegularFile(outgoing.file().toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("missing or unsafe source file: " + outgoing.identity());
            }
            if (Files.size(outgoing.file().toPath()) > TransportBundle.MAX_ENVELOPE_BYTES) {
                throw new IOException("source envelope exceeds size limit: " + outgoing.identity());
            }
            String kind = switch (outgoing.kind()) {
                case RECEIPT -> "receipt";
                case REVISION -> "revision";
                case STATE -> "state-v2";
                case ACK -> "ack-v2";
            };
            items.add(new TransportBundle.Item(
                    kind, outgoing.identity(), Files.readAllBytes(outgoing.file().toPath())));
        }
        return TransportBundle.write(directory, items);
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
