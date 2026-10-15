package org.reasonnotes.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.List;

public final class SettingsActivity extends Activity {
    private static final int TREE_REQUEST = 41;
    private AppPreferences preferences;
    private TextView exchangeState;
    private EditText deviceId;

    public static List<String> sectionTitles() { return List.of("Sync", "Behavior", "About"); }
    public static List<String> syncFoundationActions() {
        return List.of("Exchange directory", "Target device");
    }
    public static List<AppPreferences.NoteOrder> orderChoices() {
        return List.of(
                AppPreferences.NoteOrder.MODIFIED_DESCENDING,
                AppPreferences.NoteOrder.TITLE_ASCENDING);
    }
    public static String exchangeStatus(String uri) {
        return uri == null || uri.isBlank()
                ? "Exchange directory not selected"
                : "Exchange directory selected";
    }
    public static String aboutText(String version) {
        return "Version " + version
                + "\nNotes use immutable local history. KDE Connect carries files; "
                + "a transfer request is not receipt confirmation.";
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        preferences = new AppPreferences(new AndroidLocalPreferences(
                getSharedPreferences(AppPreferences.PREFERENCES_NAME, MODE_PRIVATE)));
        setContentView(buildContent());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshExchangeState();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTokens.BACKGROUND);
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
        TextView title = text("Settings", 25, UiTokens.TEXT);
        title.setTypeface(UiTokens.medium());
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(sectionTitle("Sync"), matchWrap());
        content.addView(syncCard(), matchWrapWithMargins());
        content.addView(sectionTitle("Behavior"), matchWrap());
        content.addView(behaviorCard(), matchWrapWithMargins());
        content.addView(sectionTitle("About"), matchWrap());
        content.addView(aboutCard(), matchWrapWithMargins());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private View syncCard() {
        LinearLayout card = card();
        exchangeState = text("", 14, UiTokens.TEXT_SECONDARY);
        card.addView(exchangeState, matchWrap());
        Button choose = button("Choose exchange directory", true);
        choose.setOnClickListener(view -> chooseTree());
        card.addView(choose, matchWrapWithTopMargin());

        TextView targetLabel = text("KDE Connect target device ID", 13, UiTokens.TEXT_SECONDARY);
        card.addView(targetLabel, matchWrapWithTopMargin());
        deviceId = new EditText(this);
        deviceId.setSingleLine(true);
        deviceId.setText(preferences.targetDeviceId());
        deviceId.setTextColor(UiTokens.TEXT);
        deviceId.setTextSize(14);
        deviceId.setBackground(UiTokens.surface(this, UiTokens.BACKGROUND));
        int padding = UiTokens.dp(this, UiTokens.SPACE_S);
        deviceId.setPadding(padding, 0, padding, 0);
        deviceId.setMinHeight(UiTokens.dp(this, 48));
        card.addView(deviceId, matchWrap());
        Button saveTarget = button("Save target device", false);
        saveTarget.setOnClickListener(view -> {
            try {
                preferences.setTargetDeviceId(deviceId.getText().toString());
                Toast.makeText(this, "Target device saved", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException error) {
                deviceId.setError(error.getMessage());
            }
        });
        card.addView(saveTarget, matchWrapWithTopMargin());
        return card;
    }

    private View behaviorCard() {
        LinearLayout card = card();
        card.addView(text("Note ordering", 14, UiTokens.TEXT), matchWrap());
        Spinner order = new Spinner(this);
        List<AppPreferences.NoteOrder> choices = orderChoices();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                choices.stream().map(this::orderLabel).toList());
        order.setAdapter(adapter);
        order.setSelection(choices.indexOf(preferences.noteOrder()));
        order.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.setNoteOrder(choices.get(position));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        card.addView(order, matchWrapWithTopMargin());
        return card;
    }

    private View aboutCard() {
        LinearLayout card = card();
        TextView about = text(aboutText(versionName()), 14, UiTokens.TEXT_SECONDARY);
        about.setLineSpacing(0, 1.2f);
        card.addView(about, matchWrap());
        return card;
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
        if (requestCode == TREE_REQUEST
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            ExchangeDirectory.persistGrant(this, data.getData());
            refreshExchangeState();
        }
    }

    private void refreshExchangeState() {
        if (exchangeState == null) return;
        Uri value = ExchangeDirectory.savedTree(this);
        exchangeState.setText(exchangeStatus(value == null ? null : value.toString()));
    }

    private String orderLabel(AppPreferences.NoteOrder value) {
        return value == AppPreferences.NoteOrder.MODIFIED_DESCENDING
                ? "Recently modified"
                : "Title A–Z";
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
        int padding = UiTokens.dp(this, UiTokens.SPACE_M);
        card.setPadding(padding, padding, padding, padding);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 14, UiTokens.TEXT_SECONDARY);
        title.setTypeface(UiTokens.medium());
        title.setPadding(0, UiTokens.dp(this, UiTokens.SPACE_L), 0, UiTokens.dp(this, UiTokens.SPACE_XS));
        return title;
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

    private LinearLayout.LayoutParams matchWrapWithMargins() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = UiTokens.dp(this, UiTokens.SPACE_S);
        return params;
    }

    private LinearLayout.LayoutParams matchWrapWithTopMargin() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = UiTokens.dp(this, UiTokens.SPACE_S);
        return params;
    }
}
