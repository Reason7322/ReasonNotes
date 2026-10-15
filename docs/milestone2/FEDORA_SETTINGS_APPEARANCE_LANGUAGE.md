# Fedora desktop Settings: appearance and language

Checkpoint: 2026-10-15. Scope: native Qt 6 / Qt Quick ReasonNotes desktop application only. No Android APK, notes storage format, synchronization protocol, delivery ledgers, or user note contents were changed.

## User experience

- New **Settings** entry with gear SVG in the sidebar, above the existing Local/Phone status footer.
- Selecting Settings opens a normal main-panel page rather than a cramped floating dialog. There is a **Back to notes** control, and selecting/creating a note in the sidebar automatically returns to the editor.
- **Appearance:** Dark (the exact established baseline palette) or Light (new high-contrast light palette, including SVG icon variants). Changes apply immediately to the sidebar, editor, Sync dialog and conflict view.
- **Language:** English or Polski. Language changes apply immediately to the application chrome in sidebar, editor, synchronization modal, conflict view, deletion prompt, status labels and Settings page.
- User-authored Markdown and note titles remain exactly as written, and technical details in unexpected transport errors may remain in their original English (rather than risking misleading translated diagnostics).
- Settings are persisted separately from note data in the user's native Qt `QSettings` configuration (`ui/themeMode` and `ui/language`). They survive application restarts and never alter the note repository.
- Default is **Dark / English**, preserving the appearance and language of the previous release. Invalid configuration values safely fall back to the default.

## Implementation

- `src/reasonnotes_app/preferences.py`: small persistent QObject with validated properties, change notifications and immediate QSettings sync. Used by native entry point and passed as a QML context property.
- `linux-app/qml/I18n.qml`: reactive English-to-Polish dictionary covering 52 UI phrases plus known status values. No Qt translation system components need to be installed.
- `linux-app/qml/SettingsPane.qml`: two-state appearance and language controls using the shared theme tokens.
- `Theme.qml`: dark palette preserved verbatim, new light palette selected by a reactive `mode`.
- `ActionIcon.qml` and vector assets: Light-appropriate SVG tones and the Settings gear.
- `Main.qml` and the existing panes use the same theme/language objects, with no new note transaction or automatic transport activity.

## Verification

- Full Fedora test suite passes, including Qt Quick smoke tests of actual Settings button clicks, live Dark/Light palette changes, English/Polish UI text, no note-content mutation, and persistence across fresh QSettings instances.
- Localization source audit found **52/52 visible key phrases** present in the Polish mapping; all seven new light vector SVG variants parsed successfully.
- Native Qt Wayland launch of the installed candidate, run against a disposable XDG data/config root, completes without requiring the user's live notes.
- The existing GUI window remains untouched until the owner closes and reopens it.

## Constraints

This intentionally implements **two functional themes and two languages** rather than introducing the future Liquid Glass visual direction or a translation system for arbitrary note content. Full review of uncommon error strings and localized status summaries can be refined later, without blocking usable settings today.
