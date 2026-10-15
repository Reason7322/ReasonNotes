package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
    public static final String EXTRA_NOTE_ID = MainActivity.EXTRA_NOTE_ID;
    private static final String CONFLICT_MARKER = "0".repeat(64);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor();
    private ProbeRepository repository;
    private NoteService service;
    private DraftStore drafts;
    private UUID noteId;
    private EditorAutosaveController autosave;
    private EditText title;
    private EditText body;
    private TextView localStatus;
    private TextView deliveryStatus;
    private boolean binding;
    private boolean closing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        String noteValue = getIntent().getStringExtra(EXTRA_NOTE_ID);
        try {
            noteId = UUID.fromString(noteValue);
        } catch (RuntimeException error) {
            finish();
            return;
        }
        repository = ProbeRepository.open(new File(getFilesDir(), "probe-repository"));
        service = new NoteService(
                repository,
                "android",
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS),
                UUID::randomUUID);
        drafts = new DraftStore(new File(getFilesDir(), "drafts"));
        setContentView(buildContent());
        loadNote();
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

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTokens.BACKGROUND);
        int base = UiTokens.dp(this, UiTokens.SPACE_M);
        root.setPadding(base, base, base, base);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(
                    base,
                    base + bars.top,
                    base,
                    HomeLayout.bottomContentPadding(base, bars.bottom, ime.bottom));
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", "Back");
        back.setOnClickListener(view -> flushEditor(true));
        toolbar.addView(back, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 52), UiTokens.dp(this, 48)));
        TextView label = text("Edit note", 15, UiTokens.TEXT_SECONDARY);
        toolbar.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button more = button("⋮", "More actions");
        more.setOnClickListener(this::showOverflow);
        toolbar.addView(more, new LinearLayout.LayoutParams(
                UiTokens.dp(this, 52), UiTokens.dp(this, 48)));
        root.addView(toolbar, matchWrap());

        title = new EditText(this);
        title.setHint("Untitled");
        title.setSingleLine(true);
        title.setTextColor(UiTokens.TEXT);
        title.setHintTextColor(UiTokens.TEXT_SECONDARY);
        title.setTextSize(25);
        title.setTypeface(UiTokens.medium());
        title.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        title.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, UiTokens.dp(this, UiTokens.SPACE_S));
        root.addView(title, matchWrap());

        LinearLayout statusRow = new LinearLayout(this);
        localStatus = text("Loading…", 12, UiTokens.TEXT_SECONDARY);
        deliveryStatus = text("Phone delivery: not confirmed", 12, UiTokens.TEXT_SECONDARY);
        deliveryStatus.setGravity(Gravity.END);
        statusRow.addView(localStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(deliveryStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(statusRow, matchWrap());

        body = new EditText(this);
        body.setHint("Write Markdown…");
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setTextColor(UiTokens.TEXT);
        body.setHintTextColor(UiTokens.TEXT_SECONDARY);
        body.setTextSize(17);
        body.setLineSpacing(0f, 1.25f);
        body.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
        int bodyPadding = UiTokens.dp(this, UiTokens.SPACE_M);
        body.setPadding(bodyPadding, bodyPadding, bodyPadding, bodyPadding);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void loadNote() {
        io.execute(() -> {
            try {
                NoteService.OpenNote opened = service.openNote(noteId);
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
                        () -> singleHeadOrConflictMarker(repository.headIds(noteId)),
                        this::renderStatus);
                ActiveDraftRegistry.register(autosave);
                DraftStore.Recovery recovery = autosave.recovery();
                String initialTitle = recovery.draft() == null ? opened.title() : recovery.draft().title();
                String initialBody = recovery.draft() == null ? opened.content() : recovery.draft().content();
                runOnUiThread(() -> bindEditor(initialTitle, initialBody, recovery));
            } catch (NoteService.ConflictRequiredException conflict) {
                runOnUiThread(this::openConflictAndFinish);
            } catch (RuntimeException error) {
                runOnUiThread(() -> localStatus.setText("Could not open note: " + error.getMessage()));
            }
        });
    }

    private void bindEditor(String initialTitle, String initialBody, DraftStore.Recovery recovery) {
        binding = true;
        title.setText(initialTitle);
        body.setText(initialBody);
        binding = false;
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
        localStatus.setText("Saving locally…");
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
            switch (status) {
                case SAVED -> {
                    localStatus.setText("Saved locally");
                    localStatus.setTextColor(UiTokens.SUCCESS);
                }
                case DIRTY -> {
                    localStatus.setText("Unsaved draft preserved");
                    localStatus.setTextColor(UiTokens.ACCENT);
                }
                case SAVING -> {
                    localStatus.setText("Saving locally…");
                    localStatus.setTextColor(UiTokens.TEXT_SECONDARY);
                }
                case ERROR -> {
                    localStatus.setText("Save failed — draft preserved");
                    localStatus.setTextColor(UiTokens.DANGER);
                }
                case STALE -> {
                    localStatus.setText("Note changed — draft preserved");
                    localStatus.setTextColor(UiTokens.DANGER);
                }
            }
        });
    }

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Save");
        menu.getMenu().add("Delete");
        if (repository.headIds(noteId).size() > 1) menu.getMenu().add("Resolve conflict");
        menu.setOnMenuItemClickListener(item -> {
            String action = item.getTitle().toString();
            if (action.equals("Save")) flushEditor(false);
            else if (action.equals("Delete")) confirmDelete();
            else openConflictAndFinish();
            return true;
        });
        menu.show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete note?")
                .setMessage("The deletion remains recoverable in immutable history.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> io.execute(() -> {
                    try {
                        service.deleteNote(noteId, repository.headIds(noteId));
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

    private Button button(String label, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(description);
        button.setTextColor(UiTokens.TEXT);
        button.setTextSize(24);
        button.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
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

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
    }
}
