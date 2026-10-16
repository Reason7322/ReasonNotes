package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PinStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID LIVE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID STALE = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Test
    public void pinsPersistLocallyAndVisiblePinsExcludeStaleIdsWithoutDeletingPreference() {
        FakePreferences preferences = new FakePreferences();
        PinStore first = new PinStore(preferences);
        first.setPinned(LIVE, true);
        first.setPinned(STALE, true);

        PinStore reopened = new PinStore(preferences);

        assertTrue(reopened.isPinned(LIVE));
        assertEquals(Set.of(LIVE), reopened.visiblePinned(Set.of(LIVE)));
        assertTrue(reopened.isPinned(STALE));
        reopened.setPinned(LIVE, false);
        assertFalse(new PinStore(preferences).isPinned(LIVE));
    }

    @Test
    public void clearingDeletedPinsKeepsUnselectedPins() {
        FakePreferences preferences = new FakePreferences();
        PinStore pins = new PinStore(preferences);
        pins.setPinned(LIVE, true);
        pins.setPinned(STALE, true);
        pins.clearPins(Set.of(LIVE));
        assertFalse(pins.isPinned(LIVE));
        assertTrue(pins.isPinned(STALE));
        pins.clearPins(Set.of(LIVE)); // idempotent
        assertTrue(pins.isPinned(STALE));
    }

    @Test
    public void pinningDoesNotCreateNoteRevisions() throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        repository.createRevision(LIVE, Set.of(), "note", "android", Instant.parse("2026-10-15T04:00:00Z"), false);
        int before = repository.revisions().size();

        PinStore pins = new PinStore(new FakePreferences());
        pins.setPinned(LIVE, true);
        pins.setPinned(LIVE, false);

        assertEquals(before, repository.revisions().size());
    }

    static final class FakePreferences implements LocalPreferences {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, Set<String>> sets = new HashMap<>();
        @Override public String getString(String key, String fallback) { return strings.getOrDefault(key, fallback); }
        @Override public void putString(String key, String value) { strings.put(key, value); }
        @Override public Set<String> getStringSet(String key) { return Set.copyOf(sets.getOrDefault(key, Set.of())); }
        @Override public void putStringSet(String key, Set<String> value) { sets.put(key, new HashSet<>(value)); }
    }
}
