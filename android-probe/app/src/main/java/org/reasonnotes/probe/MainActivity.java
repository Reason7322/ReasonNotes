package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    public static final String EXTRA_NOTE_ID = "note-id";
    private static final String LOCAL_PREFERENCES = "reasonnotes-app";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ProbeRepository repository;
    private NoteService service;
    private PinStore pins;
    private EditText search;
    private TextView pinnedHeading;
    private GridLayout pinnedGrid;
    private GridLayout otherGrid;
    private TextView emptyState;
    private TextView localStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        repository = ProbeRepository.open(new File(getFilesDir(), "probe-repository"));
        service = new NoteService(
                repository,
                "android",
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS),
                UUID::randomUUID);
        LocalPreferences preferences = new AndroidLocalPreferences(
                getSharedPreferences(LOCAL_PREFERENCES, MODE_PRIVATE));
        pins = new PinStore(preferences);
        setContentView(buildContent());
        renderNotes();
    }

    @Override protected void onResume() {
        super.onResume();
        if (service != null) renderNotes();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTokens.BACKGROUND);
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
        Button newNote = button("＋  New Note", true);
        newNote.setOnClickListener(view -> createNote());
        LinearLayout.LayoutParams newParams = matchWrap();
        newParams.topMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        root.addView(newNote, newParams);

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
        sections.pinned().forEach(note -> addCard(pinnedGrid, note));
        sections.others().forEach(note -> addCard(otherGrid, note));
        emptyState.setVisibility(
                sections.pinned().isEmpty() && sections.others().isEmpty() ? View.VISIBLE : View.GONE);
        localStatus.setText((sections.pinned().size() + sections.others().size()) + " notes · local history ready");
    }

    private void addCard(GridLayout grid, NoteService.NoteSummary note) {
        NoteCardView card = new NoteCardView(this, note);
        card.setOnClickListener(view -> openNote(note));
        card.setOnLongClickListener(view -> {
            showNoteMenu(card, note);
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

    private void showNoteMenu(View anchor, NoteService.NoteSummary note) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean pinned = pins.isPinned(note.noteId());
        menu.getMenu().add(pinned ? "Unpin" : "Pin");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getTitle().toString().equals("Delete")) confirmDelete(note);
            else {
                pins.setPinned(note.noteId(), !pinned);
                renderNotes();
            }
            return true;
        });
        menu.show();
    }

    private void confirmDelete(NoteService.NoteSummary note) {
        if (note.conflicted()) {
            openNamedActivity("ConflictActivity", note.noteId());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete note?")
                .setMessage("The deletion is saved as recoverable immutable history.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> io.execute(() -> {
                    try {
                        service.deleteNote(note.noteId(), repository.headIds(note.noteId()));
                        runOnUiThread(this::renderNotes);
                    } catch (RuntimeException error) {
                        runOnUiThread(() -> localStatus.setText("Delete failed: " + error.getMessage()));
                    }
                }))
                .show();
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
        button.setTextColor(accent ? UiTokens.BACKGROUND : UiTokens.TEXT);
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
