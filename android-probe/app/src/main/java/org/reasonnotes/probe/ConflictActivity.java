package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConflictActivity extends Activity {
    private AppPreferences.ThemeMode displayedTheme;
    public record CandidatePresentation(
            String revisionId,
            String originLabel,
            boolean deleted,
            String fullContent,
            String keepLabel) {}

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private NoteService service;
    private boolean destructiveActionInFlight;
    private UUID noteId;
    private LinearLayout candidates;
    private TextView status;
    private NoteService.ConflictSnapshot snapshot;

    public static List<CandidatePresentation> present(NoteService.ConflictSnapshot snapshot) {
        ArrayList<CandidatePresentation> result = new ArrayList<>();
        for (NoteService.ConflictCandidate candidate : snapshot.candidates()) {
            String origin = originLabel(candidate.originDevice());
            String keep = (origin.equals("Linux") || origin.equals("Android"))
                    ? "Keep " + origin + " · " + candidate.revisionId().substring(0, 12)
                    : null;
            result.add(new CandidatePresentation(
                    candidate.revisionId(),
                    origin,
                    candidate.deleted(),
                    candidate.deleted() ? "[Deleted revision]" : candidate.body(),
                    keep));
        }
        return List.copyOf(result);
    }

    private static String originLabel(String originDevice) {
        String normalized = originDevice.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("linux")) return "Linux";
        if (normalized.startsWith("android")) return "Android";
        return originDevice;
    }

    @Override protected void onCreate(Bundle state) {
        displayedTheme = UiTokens.prepare(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        UiTokens.configureSystemBars(this);
        try {
            noteId = UUID.fromString(getIntent().getStringExtra(MainActivity.EXTRA_NOTE_ID));
        } catch (RuntimeException error) {
            finish();
            return;
        }
        ProbeRepository repository = ProbeRepository.open(new File(getFilesDir(), "probe-repository"));
        service = new NoteService(
                repository,
                "android",
                () -> Instant.now().truncatedTo(ChronoUnit.SECONDS),
                UUID::randomUUID);
        setContentView(buildContent());
        loadConflict();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTokens.background(this));
        int base = UiTokens.dp(this, UiTokens.SPACE_M);
        root.setPadding(base, base, base, base);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(base, base + bars.top, base, base + bars.bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false);
        back.setContentDescription("Back");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiTokens.dp(this, 52), UiTokens.dp(this, 48)));
        TextView title = text("Resolve conflict", 25, UiTokens.TEXT);
        title.setTypeface(UiTokens.medium());
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, matchWrap());

        status = text("Loading complete revision heads…", 13, UiTokens.TEXT_SECONDARY);
        status.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_S), 0, UiTokens.dp(this, UiTokens.SPACE_S));
        root.addView(status, matchWrap());

        ScrollView scroll = new ScrollView(this);
        candidates = new LinearLayout(this);
        candidates.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(candidates);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView recovery = text(
                "Superseded revisions remain recoverable in immutable history.",
                12,
                UiTokens.TEXT_SECONDARY);
        recovery.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_S), 0, UiTokens.dp(this, UiTokens.SPACE_S));
        root.addView(recovery, matchWrap());
        Button skip = button("Skip for now", false);
        skip.setOnClickListener(view -> {
            if (snapshot != null) service.skipConflict(snapshot);
            finish();
        });
        root.addView(skip, matchWrap());
        Button deleteAll = button("Permanently delete every version", false);
        deleteAll.setTextColor(UiTokens.DANGER);
        deleteAll.setContentDescription(
                "Permanently delete conflicted note and all revision history");
        deleteAll.setOnClickListener(view -> confirmPermanentDeletion());
        LinearLayout.LayoutParams deleteParams = matchWrap();
        deleteParams.topMargin = UiTokens.dp(this, UiTokens.SPACE_S);
        root.addView(deleteAll, deleteParams);
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void loadConflict() {
        io.execute(() -> {
            try {
                NoteService.ConflictSnapshot loaded = service.inspectConflict(noteId);
                runOnUiThread(() -> render(loaded, null));
            } catch (RuntimeException error) {
                runOnUiThread(() -> status.setText("Conflict is no longer available: " + error.getMessage()));
            }
        });
    }

    private void render(NoteService.ConflictSnapshot loaded, String message) {
        snapshot = loaded;
        candidates.removeAllViews();
        status.setText(message == null
                ? "Choose the exact revision to keep."
                : message);
        for (CandidatePresentation candidate : present(loaded)) {
            candidates.addView(candidateCard(candidate), matchWrapWithBottomMargin());
        }
        if (loaded.candidates().isEmpty()) {
            candidates.addView(text("The observed heads changed. Return to notes and inspect again.", 15, UiTokens.TEXT), matchWrap());
        }
    }

    private View candidateCard(CandidatePresentation candidate) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTokens.surface(this, candidate.deleted() ? UiTokens.SURFACE : UiTokens.SURFACE_RAISED));
        int padding = UiTokens.dp(this, UiTokens.SPACE_M);
        card.setPadding(padding, padding, padding, padding);

        TextView origin = text(candidate.originLabel(), 17, UiTokens.TEXT);
        origin.setTypeface(UiTokens.medium());
        card.addView(origin, matchWrap());
        TextView revision = text(candidate.revisionId(), 11, UiTokens.TEXT_SECONDARY);
        revision.setTextIsSelectable(true);
        card.addView(revision, matchWrap());
        TextView content = text(
                candidate.fullContent(),
                15,
                candidate.deleted() ? UiTokens.DANGER : UiTokens.TEXT);
        content.setTextIsSelectable(true);
        content.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_M), 0, UiTokens.dp(this, UiTokens.SPACE_M));
        card.addView(content, matchWrap());
        if (candidate.keepLabel() != null) {
            Button keep = button(candidate.keepLabel(), true);
            keep.setOnClickListener(view -> keep(candidate.revisionId()));
            card.addView(keep, matchWrap());
        }
        return card;
    }

    private void keep(String revisionId) {
        NoteService.ConflictSnapshot inspected = snapshot;
        if (inspected == null) return;
        status.setText("Revalidating current heads…");
        io.execute(() -> {
            try {
                service.resolveConflict(inspected, revisionId);
                runOnUiThread(this::finish);
            } catch (NoteService.StaleConflictException stale) {
                runOnUiThread(() -> render(
                        stale.refreshed(),
                        "Conflict changed. Review the refreshed exact revisions."));
            } catch (RuntimeException error) {
                runOnUiThread(() -> status.setText("Resolution failed: " + error.getMessage()));
            }
        });
    }

    private void confirmPermanentDeletion() {
        NoteService.ConflictSnapshot expected = snapshot;
        if (expected == null || destructiveActionInFlight) return;
        new AlertDialog.Builder(this)
                .setTitle("Permanently delete this conflicted note?")
                .setMessage("This erases every revision and all prior history for this note. "
                        + "It cannot be undone. The paired Fedora app will also erase its "
                        + "copy when both apps are open and connected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete permanently", (dialog, which) -> {
                    if (destructiveActionInFlight) return;
                    destructiveActionInFlight = true;
                    status.setText("Deleting all versions…");
                    io.execute(() -> {
                        try {
                            service.permanentlyDeleteConflict(expected);
                            // Remove presentation-only pins for this exact ID;
                            // actual content/history was purged first.
                            new PinStore(new AndroidLocalPreferences(
                                    getSharedPreferences(AppPreferences.PREFERENCES_NAME,
                                            MODE_PRIVATE)))
                                    .clearPins(java.util.Set.of(expected.noteId()));
                            ((ReasonNotesApplication) getApplication())
                                    .requestImmediatePoll();
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) finish();
                            });
                        } catch (ProbeRepository.StaleHeadsException stale) {
                            runOnUiThread(() -> {
                                destructiveActionInFlight = false;
                                status.setText("Conflict changed. Review its current revisions.");
                                loadConflict();
                            });
                        } catch (RuntimeException error) {
                            runOnUiThread(() -> {
                                destructiveActionInFlight = false;
                                status.setText("Permanent deletion could not finish: "
                                        + error.getMessage());
                            });
                        }
                    });
                })
                .show();
    }

    private Button button(String value, boolean accent) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(accent ? UiTokens.ACCENT_TEXT : UiTokens.TEXT);
        button.setBackground(accent ? UiTokens.accent(this) : UiTokens.surface(this, UiTokens.SURFACE));
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

    private LinearLayout.LayoutParams matchWrapWithBottomMargin() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = UiTokens.dp(this, UiTokens.SPACE_M);
        return params;
    }
}
