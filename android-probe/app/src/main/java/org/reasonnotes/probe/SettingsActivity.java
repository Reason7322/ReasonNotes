package org.reasonnotes.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.List;

/** Human-scale settings: everyday actions first, connection details on demand. */
public final class SettingsActivity extends Activity {
    private static final int TREE_REQUEST = 41;
    private static final String STATE_DEVICE_EDITOR = "device-editor-open";
    private static final String STATE_DEVICE_DRAFT = "device-editor-draft";

    private AppPreferences preferences;
    private TextView exchangeState;
    private LinearLayout folderRow;
    private LinearLayout targetRow;
    private LinearLayout sortRow;
    private TextView targetState;
    private TextView orderingState;
    private EditText deviceId;
    private LinearLayout deviceEditor;

    public static List<String> sectionTitles() {
        return List.of("Sync", "Connection", "Notes", "About");
    }

    public static List<String> syncFoundationActions() {
        return List.of("Sync all notes", "Choose notes to sync", "Exchange directory", "Target device");
    }

    public static List<AppPreferences.NoteOrder> orderChoices() {
        return List.of(AppPreferences.NoteOrder.MODIFIED_DESCENDING,
                AppPreferences.NoteOrder.TITLE_ASCENDING);
    }

    public static String exchangeStatus(String uri) {
        return uri == null || uri.isBlank() ? "Choose a folder" : "Folder ready";
    }

    public static String aboutText(String version) {
        return "Version " + version + "\nOffline-first notes with optional device sync.";
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        preferences = new AppPreferences(new AndroidLocalPreferences(
                getSharedPreferences(AppPreferences.PREFERENCES_NAME, MODE_PRIVATE)));
        setContentView(buildContent());
        if (state != null && state.getBoolean(STATE_DEVICE_EDITOR, false)) {
            deviceEditor.setVisibility(View.VISIBLE);
            deviceId.setText(state.getString(STATE_DEVICE_DRAFT,
                    preferences.targetDeviceId()));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_DEVICE_EDITOR,
                deviceEditor != null && deviceEditor.getVisibility() == View.VISIBLE);
        if (deviceId != null) {
            state.putString(STATE_DEVICE_DRAFT, deviceId.getText().toString());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshExchangeState();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTokens.BACKGROUND);
        int horizontal = dp(UiTokens.SPACE_L);
        int vertical = dp(UiTokens.SPACE_S);
        root.setPadding(horizontal, vertical, horizontal, vertical);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(horizontal, vertical + bars.top, horizontal,
                    HomeLayout.bottomContentPadding(vertical, bars.bottom, ime.bottom));
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_note_back);
        back.setContentDescription("Back to notes");
        back.setScaleType(ImageView.ScaleType.CENTER);
        setRipple(back);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = text("Settings", 27, UiTokens.TEXT);
        heading.setTypeface(UiTokens.medium());
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        headingParams.leftMargin = dp(12);
        header.addView(heading, headingParams);
        root.addView(header, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(32));

        content.addView(sectionTitle("Sync"));
        LinearLayout sync = card();
        sync.addView(actionRow(R.drawable.ic_note_sync, "Sync all notes",
                "Send your latest note changes", () -> openSync(SyncActivity.MODE_ALL)));
        sync.addView(divider());
        sync.addView(actionRow(R.drawable.ic_note_pick, "Choose notes",
                "Pick which notes to send", () -> openSync(SyncActivity.MODE_CHOOSE)));
        content.addView(sync, matchWrap());

        content.addView(sectionTitle("Connection"));
        LinearLayout connection = card();
        folderRow = actionRow(R.drawable.ic_note_folder, "Exchange folder",
                "Checking…", this::chooseTree);
        exchangeState = (TextView) ((LinearLayout) folderRow.getChildAt(1)).getChildAt(1);
        connection.addView(folderRow);
        connection.addView(divider());
        targetRow = actionRow(R.drawable.ic_note_monitor, "Computer",
                "KDE Connect device configured", this::toggleDeviceEditor);
        targetState = (TextView) ((LinearLayout) targetRow.getChildAt(1)).getChildAt(1);
        connection.addView(targetRow);
        deviceEditor = buildDeviceEditor();
        deviceEditor.setVisibility(View.GONE);
        connection.addView(deviceEditor);
        content.addView(connection, matchWrap());

        content.addView(sectionTitle("Notes"));
        LinearLayout notes = card();
        sortRow = actionRow(R.drawable.ic_note_sort, "Sort notes",
                orderLabel(preferences.noteOrder()), this::chooseOrdering);
        orderingState = (TextView) ((LinearLayout) sortRow.getChildAt(1)).getChildAt(1);
        notes.addView(sortRow);
        content.addView(notes, matchWrap());

        content.addView(sectionTitle("About"));
        LinearLayout about = card();
        TextView name = text("ReasonNotes", 16, UiTokens.TEXT);
        name.setTypeface(UiTokens.medium());
        about.addView(name, matchWrap());
        TextView description = text(aboutText(versionName()), 13, UiTokens.TEXT_SECONDARY);
        description.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(6);
        about.addView(description, detailParams);
        content.addView(about, matchWrap());

        scroll.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.topMargin = dp(8);
        root.addView(scroll, scrollParams);
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private LinearLayout actionRow(int icon, String heading, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(76));
        setRipple(row);
        row.setContentDescription(heading + ". " + detail);
        row.setOnClickListener(view -> action.run());

        ImageView symbol = new ImageView(this);
        symbol.setImageResource(icon);
        symbol.setColorFilter(UiTokens.ACCENT);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelParams.leftMargin = dp(16);
        labelParams.rightMargin = dp(8);
        row.addView(labels, labelParams);
        TextView title = text(heading, 16, UiTokens.TEXT);
        title.setTypeface(UiTokens.medium());
        labels.addView(title, matchWrap());
        TextView subtitle = text(detail, 13, UiTokens.TEXT_SECONDARY);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(3);
        labels.addView(subtitle, subtitleParams);

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_note_chevron);
        chevron.setColorFilter(UiTokens.TEXT_SECONDARY);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        return row;
    }

    private LinearLayout buildDeviceEditor() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(6), dp(16), dp(16));
        TextView explanation = text("KDE Connect device ID", 13, UiTokens.TEXT_SECONDARY);
        container.addView(explanation, matchWrap());

        deviceId = new EditText(this);
        deviceId.setSingleLine(true);
        deviceId.setText(preferences.targetDeviceId());
        deviceId.setTextColor(UiTokens.TEXT);
        deviceId.setTextSize(14);
        deviceId.setSelectAllOnFocus(true);
        deviceId.setImeOptions(EditorInfo.IME_ACTION_DONE);
        deviceId.setBackground(UiTokens.surface(this, UiTokens.BACKGROUND));
        deviceId.setMinHeight(dp(48));
        deviceId.setPadding(dp(12), 0, dp(12), 0);
        deviceId.setContentDescription("KDE Connect target device ID");
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(8);
        container.addView(deviceId, inputParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = actionButton("Cancel", false);
        cancel.setOnClickListener(view -> {
            deviceId.setText(preferences.targetDeviceId());
            deviceEditor.setVisibility(View.GONE);
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(96), dp(44)));
        Button save = actionButton("Save", true);
        save.setOnClickListener(view -> saveTarget());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        saveParams.leftMargin = dp(8);
        actions.addView(save, saveParams);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(12);
        container.addView(actions, actionParams);
        return container;
    }

    private void toggleDeviceEditor() {
        if (deviceEditor.getVisibility() == View.VISIBLE) {
            deviceId.setText(preferences.targetDeviceId());
            deviceEditor.setVisibility(View.GONE);
        } else {
            deviceId.setText(preferences.targetDeviceId());
            deviceEditor.setVisibility(View.VISIBLE);
            deviceId.requestFocus();
        }
    }

    private void saveTarget() {
        try {
            preferences.setTargetDeviceId(deviceId.getText().toString());
            deviceEditor.setVisibility(View.GONE);
            targetState.setText("KDE Connect device configured");
            targetRow.setContentDescription("Computer. KDE Connect device configured");
            Toast.makeText(this, "Computer updated", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            deviceId.setError(error.getMessage());
        }
    }

    private void chooseOrdering() {
        List<AppPreferences.NoteOrder> choices = orderChoices();
        String[] labels = choices.stream().map(this::orderLabel).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle("Sort notes")
                .setSingleChoiceItems(labels, choices.indexOf(preferences.noteOrder()),
                        (dialog, which) -> {
                            preferences.setNoteOrder(choices.get(which));
                            orderingState.setText(orderLabel(choices.get(which)));
                            sortRow.setContentDescription(
                                    "Sort notes. " + orderLabel(choices.get(which)));
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openSync(String mode) {
        Intent intent = new Intent(this, SyncActivity.class);
        intent.putExtra(SyncActivity.EXTRA_MODE, mode);
        startActivity(intent);
    }

    private void chooseTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, TREE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TREE_REQUEST && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            ExchangeDirectory.persistGrant(this, data.getData());
            refreshExchangeState();
        }
    }

    private void refreshExchangeState() {
        if (exchangeState == null) return;
        Uri value = ExchangeDirectory.savedTree(this);
        String status = exchangeStatus(value == null ? null : value.toString());
        exchangeState.setText(status);
        if (folderRow != null) {
            folderRow.setContentDescription("Exchange folder. " + status);
        }
    }

    private String orderLabel(AppPreferences.NoteOrder value) {
        return value == AppPreferences.NoteOrder.MODIFIED_DESCENDING
                ? "Recently modified" : "Title A–Z";
    }

    private String versionName() {
        try {
            String value = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return value == null ? "unknown" : value;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return "unknown";
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTokens.surface(this, UiTokens.SURFACE));
        return card;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(UiTokens.BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(56);
        params.rightMargin = dp(16);
        divider.setLayoutParams(params);
        return divider;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 15, UiTokens.TEXT_SECONDARY);
        title.setTypeface(UiTokens.medium());
        title.setPadding(0, dp(24), 0, dp(10));
        return title;
    }

    private Button actionButton(String label, boolean accent) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(accent ? UiTokens.BACKGROUND : UiTokens.TEXT);
        button.setBackground(accent
                ? UiTokens.accent(this) : UiTokens.surface(this, UiTokens.SURFACE_RAISED));
        return button;
    }

    private void setRipple(View view) {
        TypedValue attr = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                attr, true)) view.setBackgroundResource(attr.resourceId);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return UiTokens.dp(this, value); }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
