package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private AppPreferences.ThemeMode displayedTheme;
    public static final String EXTRA_NOTE_ID = "note-id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService incomingIo = Executors.newSingleThreadExecutor();
    private boolean incomingCheckPending;
    private boolean incomingCheckInFlight;
    private CurrentNoteStore currentStore;
    private NoteService service;
    private PinStore pins;
    private EditText search;
    private TextView pinnedHeading;
    private GridLayout pinnedGrid;
    private GridLayout otherGrid;
    private TextView emptyState;
    private TextView localStatus;
    private String incomingStatus;
    private final NoteSelection selection = new NoteSelection();
    private final Set<UUID> visibleNotes = new LinkedHashSet<>();
    private LinearLayout selectionBar;
    private TextView selectionCount;
    private Button selectionDelete;
    private Button selectionOptions;
    private Button selectionCancel;
    private Button newNoteButton;
    private boolean deletionInProgress;
    private static final String STATE_SELECTED = "selected-note-ids";

    @Override protected void onCreate(Bundle state) {
        displayedTheme = UiTokens.prepare(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        UiTokens.configureSystemBars(this);
        // Construct the UI before opening or reconciling the repository.
        // Reconciliation performs disk I/O and belongs on the worker thread,
        // not in Activity.onCreate on the main UI thread.
        LocalPreferences preferences = new AndroidLocalPreferences(
                getSharedPreferences(AppPreferences.PREFERENCES_NAME, MODE_PRIVATE));
        pins = new PinStore(preferences);
        if (state != null) {
            ArrayList<String> restored = state.getStringArrayList(STATE_SELECTED);
            if (restored != null) {
                for (String id : restored) {
                    try { selection.addAll(Set.of(UUID.fromString(id))); }
                    catch (IllegalArgumentException ignored) { /* Old malformed state. */ }
                }
            }
        }
        setContentView(buildContent());
        updateSelectionUi();
        emptyState.setText("Loading notes…");
        localStatus.setText("Loading local history…");
        io.execute(() -> {
            try {
                NoteService openedService = NoteService.openApplication(
                        getFilesDir(), "android",
                        () -> Instant.now().truncatedTo(ChronoUnit.SECONDS),
                        UUID::randomUUID);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    service = openedService;
                    currentStore = openedService.currentStore();
                    updateSelectionUi();
                    renderNotes();
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    emptyState.setText("Could not load notes. Your history has not been removed.");
                    localStatus.setText("Repository error: " + error.getMessage());
                });
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (displayedTheme != UiTokens.selected(this)) {
            recreate();
            return;
        }
        // Show local notes first, before even starting the SAF provider scan.
        // A slow or broken incoming receipt must never gate the home list.
        incomingCheckPending = true;
        if (service != null) renderNotes();
    }

    /**
     * Android's counterpart to Fedora's receive-on-open behavior.
     * Use the existing SAF exchange grant; never start outgoing sync here.
     */
    private void checkIncomingOnResume() {
        if (incomingCheckInFlight) return;
        // The v1 receiver is deliberately disconnected once current-state
        // storage is active. Task 4 replaces it with the v2 receiver.
        if (currentStore == null) return;
        if (ExchangeDirectory.savedTree(this) == null) {
            incomingStatus = null;
            return;
        }
        incomingCheckInFlight = true;
        // SAF can be very slow and may report repeat receipt conflicts. Keep
        // it off the foreground note-operation queue as well as off the UI.
        incomingIo.execute(() -> {
            try {
                ExchangeDirectory.ScanReport report = IncomingReceive.scanPhone(
                        this, currentStore, importedCount -> runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            // Each new revision is already durable. Show it
                            // while older receipt files are still being scanned.
                            renderNotes();
                        }));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    incomingCheckInFlight = false;
                    incomingStatus = IncomingReceive.status(report);
                    renderNotes();
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    incomingCheckInFlight = false;
                    incomingStatus = "Incoming check failed: " + error.getMessage();
                    renderNotes();
                });
            }
        });
    }

    /** Called only for foreground remote deletions, on Android UI thread. */
    public void onRemotePurges(Set<UUID> ids) {
        if (service == null) return;
        pins.clearPins(ids);
        selection.removeAll(ids);
        updateSelectionUi();
        renderNotes();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        ArrayList<String> ids = new ArrayList<>();
        // Do not restore potentially deleted selections across recreation.
        if (!deletionInProgress) {
            for (UUID id : selection.snapshot()) ids.add(id.toString());
        }
        outState.putStringArrayList(STATE_SELECTED, ids);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (selection.active() && !deletionInProgress) {
            selection.clear();
            updateSelectionUi();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        // Allow any already-confirmed, durable note operation to complete.
        io.shutdown();
        incomingIo.shutdown();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTokens.background(this));
        int base = UiTokens.dp(this, UiTokens.SPACE_M);
        int actionBar = actionBarHeight();
        root.setPadding(base, HomeLayout.topContentPadding(base, 0, actionBar), base, base);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(
                    base,
                    HomeLayout.topContentPadding(base, bars.top, actionBar),
                    base,
                    HomeLayout.bottomContentPadding(base, bars.bottom, ime.bottom));
            return insets;
        });

        root.addView(buildHeader(), matchWrap());
        selectionBar = buildSelectionBar();
        LinearLayout.LayoutParams selectParams = matchWrap();
        selectParams.topMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        root.addView(selectionBar, selectParams);
        newNoteButton = button("＋  New Note", true);
        newNoteButton.setOnClickListener(view -> createNote());
        LinearLayout.LayoutParams newParams = matchWrap();
        newParams.topMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        root.addView(newNoteButton, newParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.VERTICAL);
        lists.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, UiTokens.dp(this, UiTokens.SPACE_M));
        pinnedHeading = sectionHeading("Pinned");
        lists.addView(pinnedHeading, matchWrap());
        pinnedGrid = grid();
        lists.addView(pinnedGrid, matchWrap());
        lists.addView(sectionHeading("Others"), matchWrap());
        otherGrid = grid();
        lists.addView(otherGrid, matchWrap());
        emptyState = text("No notes yet. Create one to start writing.", 15, UiTokens.TEXT_SECONDARY);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(0, UiTokens.dp(this, 72), 0, 0);
        lists.addView(emptyState, matchWrap());
        scroll.addView(lists);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        localStatus = text("Saved locally", 12, UiTokens.TEXT_SECONDARY);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.bottomMargin = UiTokens.dp(this, UiTokens.SPACE_XS);
        root.addView(localStatus, statusParams);

        search = new EditText(this);
        search.setHint("Search notes");
        search.setSingleLine(true);
        search.setTextColor(UiTokens.TEXT);
        search.setHintTextColor(UiTokens.TEXT_SECONDARY);
        search.setTextSize(15);
        search.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
        int fieldPadding = UiTokens.dp(this, UiTokens.SPACE_M);
        search.setPadding(fieldPadding, 0, fieldPadding, 0);
        search.setMinHeight(UiTokens.dp(this, HomeLayout.minimumTouchTargetDp()));
        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable value) { renderNotes(); }
        });
        root.addView(search, matchWrap());
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("ReasonNotes", 26, UiTokens.TEXT);
        brand.setTypeface(UiTokens.medium());
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button settings = button("⚙", false);
        settings.setContentDescription("Settings");
        settings.setOnClickListener(view -> openNamedActivity("SettingsActivity", null));
        header.addView(settings, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 52), UiTokens.dp(this, 48)));
        return header;
    }

    private LinearLayout buildSelectionBar() {
        // A dedicated selection-summary line keeps actions readable on
        // narrow phones and at increased accessibility font scales.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setVisibility(View.GONE);

        selectionCount = text("0 selected", 16, UiTokens.TEXT);
        selectionCount.setTypeface(UiTokens.medium());
        selectionCount.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(selectionCount, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        bar.addView(actions, actionsParams);

        selectionOptions = button("⋮", false);
        selectionOptions.setContentDescription(
                "Selection actions: Pin, Unpin, Select all visible");
        selectionOptions.setOnClickListener(view -> showSelectionMenu(selectionOptions));
        actions.addView(selectionOptions, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 48), UiTokens.dp(this, 48)));

        selectionDelete = button("Delete", false);
        selectionDelete.setTextColor(UiTokens.DANGER);
        selectionDelete.setContentDescription("Delete selected notes");
        selectionDelete.setOnClickListener(view -> confirmBulkDelete());
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                0, UiTokens.dp(this, 48), 1f);
        deleteParams.leftMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        actions.addView(selectionDelete, deleteParams);

        selectionCancel = button("Cancel", false);
        selectionCancel.setOnClickListener(view -> {
            selection.clear();
            updateSelectionUi();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                0, UiTokens.dp(this, 48), 1f);
        cancelParams.leftMargin = UiTokens.dp(this, UiTokens.SPACE_S);
        actions.addView(selectionCancel, cancelParams);
        return bar;
    }

    private void updateSelectionUi() {
        if (selectionBar == null) return;
        boolean active = selection.active();
        selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        newNoteButton.setVisibility(active ? View.GONE : View.VISIBLE);
        newNoteButton.setEnabled(service != null && !deletionInProgress);
        selectionCount.setText(selection.count() + " selected");
        selectionDelete.setEnabled(active && service != null && !deletionInProgress);
        selectionOptions.setEnabled(active && service != null && !deletionInProgress);
        selectionCancel.setEnabled(!deletionInProgress);
        for (GridLayout grid : new GridLayout[]{pinnedGrid, otherGrid}) {
            if (grid == null) continue;
            for (int index = 0; index < grid.getChildCount(); index++) {
                View child = grid.getChildAt(index);
                if (child instanceof NoteCardView card) {
                    card.setSelectionMode(active, selection.contains(card.note().noteId()));
                }
            }
        }
    }

    private void toggleSelection(UUID noteId) {
        if (deletionInProgress) return;
        selection.toggle(noteId);
        updateSelectionUi();
    }

    private void showSelectionMenu(View anchor) {
        if (!selection.active() || deletionInProgress) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Pin selected");
        menu.getMenu().add("Unpin selected");
        menu.getMenu().add("Select all visible");
        menu.setOnMenuItemClickListener(item -> {
            String action = item.getTitle().toString();
            if (action.equals("Select all visible")) {
                selection.addAll(visibleNotes);
                updateSelectionUi();
            } else {
                boolean pin = action.equals("Pin selected");
                for (UUID id : selection.snapshot()) pins.setPinned(id, pin);
                renderNotes();
            }
            return true;
        });
        menu.show();
    }

    private void confirmBulkDelete() {
        if (!selection.active() || deletionInProgress || service == null) return;
        Set<UUID> selectedIds = selection.snapshot();
        int count = selectedIds.size();
        new AlertDialog.Builder(this)
                .setTitle("Delete " + count + (count == 1 ? " note?" : " notes?"))
                .setMessage("Permanently erase the selected notes and all previous versions? "
                        + "This cannot be undone. Notes with unresolved conflicts are skipped. "
                        + "Their copies on paired devices will be erased once ReasonNotes reconnects.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    deletionInProgress = true;
                    updateSelectionUi();
                    io.execute(() -> {
                        try {
                            BulkNoteDeletion.Result result = BulkNoteDeletion.delete(
                                    service, selectedIds);
                            runOnUiThread(() -> finishBulkDelete(result, count));
                        } catch (RuntimeException error) {
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                deletionInProgress = false;
                                updateSelectionUi();
                                new AlertDialog.Builder(this)
                                        .setTitle("Deletion could not finish")
                                        .setMessage("An unexpected error interrupted the operation. "
                                                + "Reopen the notes list before retrying: "
                                                + error.getMessage())
                                        .setPositiveButton("OK", null)
                                        .show();
                                renderNotes();
                            });
                        }
                    });
                })
                .show();
    }

    private void finishBulkDelete(BulkNoteDeletion.Result result, int requested) {
        if (isFinishing() || isDestroyed()) return;
        deletionInProgress = false;
        pins.clearPins(result.deleted());
        selection.removeAll(result.deleted());
        if (!result.deleted().isEmpty()) {
            ((ReasonNotesApplication) getApplication()).requestImmediatePoll();
        }
        int deleted = result.deleted().size();
        int skipped = result.skipped().size();
        int failed = result.failed().size();
        if (skipped == 0 && failed == 0) {
            selection.clear();
            Toast.makeText(this, "Deleted " + deleted
                    + (deleted == 1 ? " note" : " notes"), Toast.LENGTH_LONG).show();
        } else {
            String message = "Deleted " + deleted + " of " + requested + " selected notes.";
            if (skipped > 0) {
                message += "\n\nSkipped " + skipped
                        + " missing or already permanently deleted "
                        + (skipped == 1 ? "note" : "notes")
                        + ". They were not found in the local repository.";
            }
            if (failed > 0) {
                message += "\n\n" + failed + " deletion operations could not "
                        + "be completed safely. Check the app's note list before retrying.";
            }
            new AlertDialog.Builder(this)
                    .setTitle(failed > 0 ? "Some deletions need attention"
                            : "Deleted " + deleted + ", skipped " + skipped)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        }
        updateSelectionUi();
        renderNotes();
    }

    private void renderNotes() {
        if (service == null || pinnedGrid == null) return;
        String query = search == null ? "" : search.getText().toString();
        io.execute(() -> {
            List<NoteService.NoteSummary> notes = service.listNotes(query);
            Set<UUID> visible = notes.stream().map(NoteService.NoteSummary::noteId)
                    .collect(java.util.stream.Collectors.toSet());
            HomeLayout.Sections sections = HomeLayout.partition(notes, pins.visiblePinned(visible));
            runOnUiThread(() -> applySections(sections));
        });
    }

    private void applySections(HomeLayout.Sections sections) {
        pinnedGrid.removeAllViews();
        otherGrid.removeAllViews();
        pinnedHeading.setVisibility(sections.pinned().isEmpty() ? View.GONE : View.VISIBLE);
        pinnedGrid.setVisibility(sections.pinned().isEmpty() ? View.GONE : View.VISIBLE);
        visibleNotes.clear();
        sections.pinned().forEach(note -> addCard(pinnedGrid, note));
        sections.others().forEach(note -> addCard(otherGrid, note));
        updateSelectionUi();
        emptyState.setText("No notes yet. Create one to start writing.");
        if (incomingCheckPending) {
            incomingCheckPending = false;
            checkIncomingOnResume();
        }
        emptyState.setVisibility(
                sections.pinned().isEmpty() && sections.others().isEmpty() ? View.VISIBLE : View.GONE);
        localStatus.setText(incomingStatus != null
                ? incomingStatus
                : (sections.pinned().size() + sections.others().size())
                        + " notes · local history ready");
    }

    private void addCard(GridLayout grid, NoteService.NoteSummary note) {
        visibleNotes.add(note.noteId());
        NoteCardView card = new NoteCardView(this, note);
        card.setOnClickListener(view -> {
            if (selection.active()) toggleSelection(note.noteId());
            else openNote(note);
        });
        card.setOnLongClickListener(view -> {
            if (deletionInProgress) return true;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!selection.active()) selection.begin(note.noteId());
            else selection.toggle(note.noteId());
            updateSelectionUi();
            return true;
        });
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int gap = UiTokens.dp(this, UiTokens.SPACE_XS);
        params.setMargins(gap, gap, gap, gap);
        grid.addView(card, params);
    }

    private void openNote(NoteService.NoteSummary note) {
        if (note.conflicted()) openNamedActivity("ConflictActivity", note.noteId());
        else {
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra(EXTRA_NOTE_ID, note.noteId().toString());
            startActivity(intent);
        }
    }

    private void createNote() {
        if (service == null || deletionInProgress) return;
        localStatus.setText("Creating note…");
        io.execute(() -> {
            try {
                NoteService.OpenNote note = service.createNote("Untitled", "");
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, EditorActivity.class);
                    intent.putExtra(EXTRA_NOTE_ID, note.noteId().toString());
                    startActivity(intent);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> localStatus.setText("Could not create note: " + error.getMessage()));
            }
        });
    }

    private void openNamedActivity(String simpleName, UUID noteId) {
        Intent intent = new Intent().setClassName(this, getPackageName() + "." + simpleName);
        if (noteId != null) intent.putExtra(EXTRA_NOTE_ID, noteId.toString());
        startActivity(intent);
    }

    private GridLayout grid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        return grid;
    }

    private TextView sectionHeading(String value) {
        TextView view = text(value, 14, UiTokens.TEXT_SECONDARY);
        view.setTypeface(UiTokens.medium());
        view.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, UiTokens.dp(this, UiTokens.SPACE_XS));
        return view;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value, boolean accent) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(accent ? UiTokens.ACCENT_TEXT : UiTokens.TEXT);
        button.setTypeface(UiTokens.medium());
        button.setMinHeight(UiTokens.dp(this, HomeLayout.minimumTouchTargetDp()));
        button.setBackground(accent
                ? UiTokens.accent(this)
                : UiTokens.surface(this, UiTokens.SURFACE));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int actionBarHeight() {
        if (getActionBar() == null) return 0;
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(android.R.attr.actionBarSize, value, true)) return 0;
        return TypedValue.complexToDimensionPixelSize(value.data, getResources().getDisplayMetrics());
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
    }
}
