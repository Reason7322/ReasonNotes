package org.reasonnotes.probe;

import java.util.Locale;
import java.util.Objects;

public final class AppPreferences {
    public static final String PREFERENCES_NAME = "reasonnotes-app";
    public static final String DEFAULT_TARGET_DEVICE_ID = "254997f28f11499d80928fe8a109749e";
    private static final String TARGET_DEVICE = "target-device-id";
    private static final String NOTE_ORDER = "note-order";

    public enum NoteOrder { MODIFIED_DESCENDING, TITLE_ASCENDING }

    private final LocalPreferences preferences;

    public AppPreferences(LocalPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public String targetDeviceId() {
        return preferences.getString(TARGET_DEVICE, DEFAULT_TARGET_DEVICE_ID);
    }

    public void setTargetDeviceId(String value) {
        String normalized = Objects.requireNonNull(value, "value").strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("target device ID is empty");
        preferences.putString(TARGET_DEVICE, normalized);
    }

    public NoteOrder noteOrder() {
        String value = preferences.getString(
                NOTE_ORDER, NoteOrder.MODIFIED_DESCENDING.name());
        try {
            return NoteOrder.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return NoteOrder.MODIFIED_DESCENDING;
        }
    }

    public void setNoteOrder(NoteOrder value) {
        preferences.putString(NOTE_ORDER, Objects.requireNonNull(value, "value").name());
    }
}
