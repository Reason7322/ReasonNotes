package org.reasonnotes.probe;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class NoteCardView extends LinearLayout {
    private final NoteService.NoteSummary note;
    private final TextView selectionMark;

    public NoteCardView(Context context, NoteService.NoteSummary note) {
        super(context);
        this.note = note;
        setOrientation(VERTICAL);
        setGravity(Gravity.TOP);
        int padding = UiTokens.dp(context, UiTokens.SPACE_M);
        setPadding(padding, padding, padding, padding);
        setMinimumHeight(UiTokens.dp(context, 132));
        setBackground(UiTokens.surface(context, UiTokens.SURFACE_RAISED));
        setClickable(true);
        setFocusable(true);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.TOP | Gravity.CENTER_VERTICAL);
        TextView title = text(context, note.title(), 16, UiTokens.TEXT, UiTokens.medium());
        title.setMaxLines(2);
        titleRow.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        selectionMark = text(context, "○", 23, UiTokens.ACCENT, UiTokens.medium());
        selectionMark.setGravity(Gravity.CENTER);
        selectionMark.setVisibility(GONE);
        titleRow.addView(selectionMark, new LayoutParams(
                UiTokens.dp(context, 30), UiTokens.dp(context, 30)));
        addView(titleRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView preview = text(
                context,
                note.preview().isBlank() ? "No preview" : note.preview(),
                13,
                UiTokens.TEXT_SECONDARY,
                Typeface.DEFAULT);
        preview.setMaxLines(3);
        LayoutParams previewParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.topMargin = UiTokens.dp(context, UiTokens.SPACE_S);
        addView(preview, previewParams);

        String state = note.conflicted() ? "Conflict needs attention" : "Saved locally";
        TextView status = text(
                context,
                state,
                12,
                note.conflicted() ? UiTokens.DANGER : UiTokens.TEXT_SECONDARY,
                Typeface.DEFAULT);
        status.setMaxLines(1);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Visual and accessibility feedback for the long-press selection mode. */
    public void setSelectionMode(boolean active, boolean checked) {
        selectionMark.setVisibility(active ? VISIBLE : GONE);
        selectionMark.setText(checked ? "✓" : "○");
        GradientDrawable background = UiTokens.surface(getContext(), UiTokens.SURFACE_RAISED);
        if (checked) background.setStroke(UiTokens.dp(getContext(), 2), UiTokens.ACCENT);
        setBackground(background);
        setSelected(checked);
        setContentDescription((active ? (checked ? "Selected, " : "Not selected, ") : "")
                + note.title() + (note.conflicted() ? ", conflict needs attention" : ""));
    }

    public NoteService.NoteSummary note() { return note; }

    private static TextView text(
            Context context, String value, float size, int color, Typeface typeface) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        return view;
    }
}
