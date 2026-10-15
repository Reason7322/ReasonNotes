package org.reasonnotes.probe;

import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class AndroidLocalPreferences implements LocalPreferences {
    private final SharedPreferences preferences;

    public AndroidLocalPreferences(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    @Override public String getString(String key, String fallback) {
        return preferences.getString(key, fallback);
    }

    @Override public void putString(String key, String value) {
        preferences.edit().putString(key, value).apply();
    }

    @Override public Set<String> getStringSet(String key) {
        return Set.copyOf(preferences.getStringSet(key, Set.of()));
    }

    @Override public void putStringSet(String key, Set<String> value) {
        preferences.edit().putStringSet(key, new HashSet<>(value)).apply();
    }
}
