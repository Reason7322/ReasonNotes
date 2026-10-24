package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.util.TypedValue;
import android.graphics.Color;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class EditorActivity extends Activity {
    private AppPreferences.ThemeMode displayedTheme;
    public static final String EXTRA_NOTE_ID = MainActivity.EXTRA_NOTE_ID;
    private static final String CONFLICT_MARKER = "0".repeat(64);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor();
    private CurrentNoteStore currentStore;
    private NoteService service;
    private DraftStore drafts;
    private UUID noteId;
    private EditorAutosaveController autosave;
    private EditText title;
    private EditText body;
    private TextView editorWarning;
    private boolean conflictingChanges;
    private boolean binding;
    private boolean closing;

    @Override protected void onCreate(Bundle state) {
        displayedTheme = UiTokens.prepare(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        UiTokens.configureSystemBars(this);
        String noteValue = getIntent().getStringExtra(EXTRA_NOTE_ID);
        try {
            noteId = UUID.fromString(noteValue);
        } catch (RuntimeException error) {
            finish();
            return;
        }
        setContentView(buildContent());
        // The screen and its controls must exist before repository I/O.
        io.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                service = NoteService.openApplication(
                        getFilesDir(), "android",
                        () -> Instant.now().truncatedTo(ChronoUnit.SECONDS),
                        UUID::randomUUID);
                currentStore = service.currentStore();
                drafts = new DraftStore(new File(getFilesDir(), "drafts"));
                loadNoteOnWorker();
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        showWarning("Could not open note: " + error.getMessage());
                });
            }
        });
    }

    /** If the PC permanently deleted this note, close without autosaving. */
    public void onRemotePurges(Set<UUID> ids) {
        if (noteId == null || !ids.contains(noteId)) return;
        closing = true;
        binding = true;
        if (body != null) body.setText("");
        if (title != null) title.setText("");
        finish();
    }

    @Override protected void onPause() {
        if (!closing) flushEditor(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (autosave != null) ActiveDraftRegistry.unregister(autosave);
        debounce.shutdown();
        io.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        flushEditor(true);
    }

    static String singleHeadOrConflictMarker(Set<String> headIds) {
        return headIds.size() == 1 ? headIds.iterator().next() : CONFLICT_MARKER;
    }

    /** A reopened note starts at the end; Java text offsets are UTF-16 and
     * match EditText.setSelection offsets, including Unicode notes.
     * Only used once during initial binding, never while the user edits.
     */
    static int initialBodyCursorOffset(CharSequence loadedBody) {
        return loadedBody.length();
    }

    private View buildContent() {
        // The editor is a writing surface, not a form inside another card.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTokens.background(this));
        int horizontal = UiTokens.dp(this, UiTokens.SPACE_L);
        int vertical = UiTokens.dp(this, UiTokens.SPACE_S);
        root.setPadding(horizontal, vertical, horizontal, vertical);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(horizontal, vertical + bars.top, horizontal,
                    HomeLayout.bottomContentPadding(vertical, bars.bottom, ime.bottom));
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_note_back, "Back to notes");
        back.setOnClickListener(view -> flushEditor(true));
        toolbar.addView(back, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 48), UiTokens.dp(this, 48)));
        View space = new View(this);
        toolbar.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        ImageButton more = iconButton(R.drawable.ic_note_more, "More note actions");
        more.setOnClickListener(this::showOverflow);
        toolbar.addView(more, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 48), UiTokens.dp(this, 48)));
        root.addView(toolbar, matchWrap());

        title = new EditText(this);
        title.setHint("Title");
        title.setSingleLine(true);
        title.setTextColor(UiTokens.TEXT);
        title.setHintTextColor(UiTokens.TEXT_SECONDARY);
        title.setTextSize(27);
        title.setTypeface(UiTokens.medium());
        title.setBackgroundColor(Color.TRANSPARENT);
        title.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_L), 0,
                UiTokens.dp(this, UiTokens.SPACE_S));
        title.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        title.setEnabled(false);
        root.addView(title, matchWrap());

        // Only errors and stale drafts should interrupt writing. Routine save
        // and transport details remain in the dedicated sync interface.
        editorWarning = text("", 13, UiTokens.DANGER);
        editorWarning.setVisibility(View.GONE);
        editorWarning.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_S), 0,
                UiTokens.dp(this, UiTokens.SPACE_S));
        root.addView(editorWarning, matchWrap());

        body = new EditText(this);
        body.setHint("Start writing…");
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setTextColor(UiTokens.TEXT);
        body.setHintTextColor(UiTokens.TEXT_SECONDARY);
        body.setTextSize(17);
        body.setLineSpacing(0f, 1.18f);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setBackgroundColor(Color.TRANSPARENT);
        body.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, 0);
        body.setEnabled(false);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                ripple, true)) {
            button.setBackgroundResource(ripple.resourceId);
        } else {
            button.setBackgroundColor(Color.TRANSPARENT);
        }
        return button;
    }

    private void showWarning(String message) {
        if (editorWarning == null) return;
        editorWarning.setText(message);
        editorWarning.setVisibility(View.VISIBLE);
    }

    private void loadNoteOnWorker() {
            try {
                NoteService.OpenNote opened = service.openNote(noteId);
                if (isFinishing() || isDestroyed()) return;
                autosave = new EditorAutosaveController(
                        drafts,
                        noteId,
                        opened.revisionId(),
                        (action, delay) -> {
                            var future = debounce.schedule(action, delay, TimeUnit.MILLISECONDS);
                            return () -> future.cancel(false);
                        },
                        io,
                        draft -> service.saveNote(
                                noteId,
                                Set.of(draft.baseRevisionId()),
                                draft.title(),
                                draft.content()).revisionId(),
                        () -> singleHeadOrConflictMarker(currentStore.headIds(noteId)),
                        this::renderStatus);
                ActiveDraftRegistry.register(autosave);
                DraftStore.Recovery recovery = autosave.recovery();
                String initialTitle = recovery.draft() == null ? opened.title() : recovery.draft().title();
                String initialBody = recovery.draft() == null ? opened.content() : recovery.draft().content();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        bindEditor(initialTitle, initialBody, recovery);
                });
            } catch (NoteService.ConflictRequiredException conflict) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) openConflictAndFinish();
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        showWarning("Could not open note: " + error.getMessage());
                });
            }
    }

    private void bindEditor(String initialTitle, String initialBody, DraftStore.Recovery recovery) {
        binding = true;
        title.setText(initialTitle);
        body.setText(initialBody);
        binding = false;
        title.setEnabled(true);
        body.setEnabled(true);
        // Opening a saved note should be as ready for writing as a new one.
        // Apply the cursor only once to the just-loaded text, before listeners
        // and without changing the note or triggering an autosave.
        body.requestFocus();
        body.setSelection(initialBodyCursorOffset(body.getText()));
        body.post(() -> {
            if (isFinishing() || isDestroyed() || !body.hasFocus()) return;
            InputMethodManager ime = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.showSoftInput(body, InputMethodManager.SHOW_IMPLICIT);
        });
        TextWatcher watcher = new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable value) {
                if (binding || autosave == null) return;
                String titleValue = title.getText().toString();
                String bodyValue = body.getText().toString();
                io.execute(() -> autosave.edit(titleValue, bodyValue));
            }
        };
        title.addTextChangedListener(watcher);
        body.addTextChangedListener(watcher);
        if (recovery.draft() == null) renderStatus(EditorAutosaveController.Status.SAVED);
        else renderStatus(recovery.safeToApply()
                ? EditorAutosaveController.Status.DIRTY
                : EditorAutosaveController.Status.STALE);
    }

    private void flushEditor(boolean finishWhenSaved) {
        if (autosave == null) {
            if (finishWhenSaved) finish();
            return;
        }
        io.execute(() -> autosave.flush().whenComplete((status, error) -> runOnUiThread(() -> {
            if (finishWhenSaved && status == EditorAutosaveController.Status.SAVED) {
                closing = true;
                finish();
            } else if (finishWhenSaved && status != EditorAutosaveController.Status.SAVED) {
                renderStatus(status);
            }
        })));
    }

    private void renderStatus(EditorAutosaveController.Status status) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || editorWarning == null) return;
            switch (status) {
                case ERROR -> showWarning("Couldn't save. Your draft is preserved.");
                case STALE -> {
                    conflictingChanges = true;
                    showWarning("This note changed elsewhere. Your draft is preserved.");
                }
                case SAVED, DIRTY, SAVING -> {
                    // If the recovered draft is stale, editing cannot erase
                    // its warning or magically resolve the conflicting heads.
                    if (status == EditorAutosaveController.Status.SAVED) {
                        conflictingChanges = false;
                    }
                    if (!conflictingChanges) editorWarning.setVisibility(View.GONE);
                }
            }
        });
    }

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Save");
        menu.getMenu().add("Delete note");
        menu.getMenu().add("Sync details");
        if (conflictingChanges) menu.getMenu().add("Resolve conflict");
        menu.setOnMenuItemClickListener(item -> {
            String action = item.getTitle().toString();
            if (action.equals("Save")) flushEditor(false);
            else if (action.equals("Delete note")) confirmDelete();
            else if (action.equals("Sync details"))
                startActivity(new Intent(this, SyncActivity.class));
            else openConflictAndFinish();
            return true;
        });
        menu.show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete note?")
                .setMessage("Permanently erase this note and every previous version? "
                        + "This cannot be undone. Other paired devices will erase their "
                        + "copies when ReasonNotes reconnects.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> io.execute(() -> {
                    try {
                        service.deleteNote(noteId, currentStore.headIds(noteId));
                        ((ReasonNotesApplication) getApplication()).requestImmediatePoll();
                        runOnUiThread(() -> {
                            closing = true;
                            finish();
                        });
                    } catch (RuntimeException error) {
                        renderStatus(EditorAutosaveController.Status.STALE);
                    }
                }))
                .show();
    }

    private void openConflictAndFinish() {
        Intent intent = new Intent().setClassName(this, getPackageName() + ".ConflictActivity");
        intent.putExtra(EXTRA_NOTE_ID, noteId.toString());
        startActivity(intent);
        closing = true;
        finish();
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

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
    }
}
