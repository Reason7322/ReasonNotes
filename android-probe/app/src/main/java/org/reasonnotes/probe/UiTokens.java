package org.reasonnotes.probe;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Shared Android palette matching the desktop's light/dark roles.
 * Glass uses layered translucent gradients, raised edge highlights and
 * contrast-safe text. It does not depend on desktop Wayland blur.
 */
public final class UiTokens {
    public static int BACKGROUND = Color.rgb(19, 21, 26);
    public static int SURFACE = Color.rgb(29, 32, 38);
    public static int SURFACE_RAISED = Color.rgb(38, 41, 49);
    public static int TEXT = Color.rgb(236, 238, 243);
    public static int TEXT_SECONDARY = Color.rgb(162, 167, 178);
    public static int BORDER = Color.rgb(53, 57, 65);
    public static int ACCENT = Color.rgb(220, 175, 72);
    public static int ACCENT_TEXT = Color.rgb(23, 25, 30);
    public static int DANGER = Color.rgb(232, 112, 112);
    public static int SUCCESS = Color.rgb(92, 207, 145);
    private static AppPreferences.ThemeMode current = AppPreferences.ThemeMode.DARK;

    public static AppPreferences.ThemeMode selected(Context context) {
        return new AppPreferences(new AndroidLocalPreferences(
                context.getSharedPreferences(AppPreferences.PREFERENCES_NAME,
                        Context.MODE_PRIVATE))).themeMode();
    }

    public static AppPreferences.ThemeMode currentMode() { return current; }

    /** Called before Activity.onCreate so system dialogs and input controls
     * inherit a matching native Material light/dark theme. */
    public static AppPreferences.ThemeMode prepare(Activity activity) {
        AppPreferences.ThemeMode mode = selected(activity);
        activity.setTheme(mode == AppPreferences.ThemeMode.LIGHT
                ? R.style.ReasonNotesThemeLight : R.style.ReasonNotesTheme);
        activate(mode);
        return mode;
    }

    public static void configureSystemBars(Activity activity) {
        boolean light = current == AppPreferences.ThemeMode.LIGHT;
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        flags = light
                ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : flags & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            activity.getWindow().setStatusBarContrastEnforced(false);
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    public static void activate(AppPreferences.ThemeMode mode) {
        current = mode;
        ACCENT = Color.rgb(220, 175, 72);
        ACCENT_TEXT = Color.rgb(23, 25, 30);
        if (mode == AppPreferences.ThemeMode.LIGHT) {
            BACKGROUND = Color.rgb(250, 250, 252);
            SURFACE = Color.rgb(255, 255, 255);
            SURFACE_RAISED = Color.rgb(239, 241, 245);
            TEXT = Color.rgb(29, 36, 48);
            TEXT_SECONDARY = Color.rgb(89, 100, 118);
            BORDER = Color.rgb(213, 218, 227);
            DANGER = Color.rgb(172, 50, 50);
            SUCCESS = Color.rgb(39, 115, 73);
        } else if (mode == AppPreferences.ThemeMode.LIQUID_GLASS) {
            BACKGROUND = Color.rgb(19, 29, 43);
            SURFACE = Color.argb(218, 45, 61, 81);
            SURFACE_RAISED = Color.argb(228, 61, 76, 99);
            TEXT = Color.rgb(245, 248, 255);
            TEXT_SECONDARY = Color.rgb(183, 201, 220);
            BORDER = Color.argb(175, 148, 178, 208);
            ACCENT = Color.rgb(151, 205, 242);
            ACCENT_TEXT = Color.rgb(17, 35, 50);
            DANGER = Color.rgb(250, 141, 154);
            SUCCESS = Color.rgb(121, 219, 186);
        } else {
            BACKGROUND = Color.rgb(21, 23, 28);
            SURFACE = Color.rgb(29, 32, 38);
            SURFACE_RAISED = Color.rgb(38, 41, 49);
            TEXT = Color.rgb(236, 238, 243);
            TEXT_SECONDARY = Color.rgb(162, 167, 178);
            BORDER = Color.rgb(53, 57, 65);
            DANGER = Color.rgb(232, 112, 112);
            SUCCESS = Color.rgb(92, 207, 145);
        }
    }

    public static GradientDrawable background(Context context) {
        if (current != AppPreferences.ThemeMode.LIQUID_GLASS) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(BACKGROUND);
            return bg;
        }
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ Color.rgb(26, 45, 66), Color.rgb(15, 24, 38),
                        Color.rgb(35, 40, 62) });
    }

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
        if (current == AppPreferences.ThemeMode.LIQUID_GLASS
                && color != ACCENT && color != BACKGROUND) {
            drawable.setOrientation(GradientDrawable.Orientation.TL_BR);
            drawable.setColors(new int[] {
                    Color.argb(236, 83, 101, 126),
                    color,
                    Color.argb(227, 38, 53, 74)
            });
        } else {
            drawable.setColor(color);
        }
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
