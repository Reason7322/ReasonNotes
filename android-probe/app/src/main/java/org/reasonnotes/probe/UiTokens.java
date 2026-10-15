package org.reasonnotes.probe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Temporary centralized Android tokens; Task 4 colors are not visually approved. */
public final class UiTokens {
    public static final int BACKGROUND = Color.rgb(19, 21, 26);
    public static final int SURFACE = Color.rgb(29, 32, 38);
    public static final int SURFACE_RAISED = Color.rgb(38, 41, 49);
    public static final int TEXT = Color.rgb(236, 238, 243);
    public static final int TEXT_SECONDARY = Color.rgb(162, 167, 178);
    public static final int BORDER = Color.rgb(53, 57, 65);
    public static final int ACCENT = Color.rgb(220, 175, 72);
    public static final int DANGER = Color.rgb(232, 112, 112);
    public static final int SUCCESS = Color.rgb(92, 207, 145);

    public static final int SPACE_XS = 6;
    public static final int SPACE_S = 10;
    public static final int SPACE_M = 16;
    public static final int SPACE_L = 24;
    public static final int RADIUS = 16;

    private UiTokens() {}

    public static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable surface(Context context, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, RADIUS));
        drawable.setStroke(dp(context, 1), BORDER);
        return drawable;
    }

    public static GradientDrawable accent(Context context) {
        GradientDrawable drawable = surface(context, ACCENT);
        drawable.setStroke(0, Color.TRANSPARENT);
        return drawable;
    }

    public static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }
}
