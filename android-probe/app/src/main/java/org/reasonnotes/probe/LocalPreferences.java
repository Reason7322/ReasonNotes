package org.reasonnotes.probe;

import java.util.Set;

public interface LocalPreferences {
    String getString(String key, String fallback);
    void putString(String key, String value);
    Set<String> getStringSet(String key);
    void putStringSet(String key, Set<String> value);
}
