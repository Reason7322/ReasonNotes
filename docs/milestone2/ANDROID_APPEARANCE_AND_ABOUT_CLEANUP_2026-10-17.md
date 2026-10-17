# Android appearance themes and Settings About cleanup

## End goal and observable acceptance criteria
The original ReasonNotes product is a responsive, reliable Fedora+Android note app with persistent immutable note histories for live notes, KDE Connect transfers, F5 desktop incoming scan, and permanent cross-device deletion while both apps are open. The Fedora desktop has already reached the user's declared feature-complete milestone. Android sync speed was fixed and verified on the real POCO X6 5G.

The current request includes two screenshots and asks to remove both their pictured elements, and to port the Fedora app's Light, Dark and Liquid Glass themes to Android. The second screenshot is an About card (safe presentational removal). The first is an actual conflicted note called "Concurrent third head": it is user data, not a screen widget; whether to permanently erase it and its history or merely hide its entry is an unresolved product decision. Until the user clarifies, do NOT delete, suppress, alter, or resolve the conflicted note; preservation is strictly required.

## Implemented
- Settings sections are Appearance, Sync, Connection, Notes. The unused About section and Version 0.1 card no longer render.
- Appearance -> Theme contains persisted Dark, Light and Liquid Glass options; switching themes immediately recreates Settings and refreshes the parent home activity on return.
- All five Android screens (home, editor, sync, conflict resolution and settings) share the chosen color palette, including background, text hierarchy, surfaces, cards, accents, success/error colors, native Material dialog theme and status/navigation bar icon contrast.
- Light palette follows Fedora roles: near-white #FAFAFC background, light slate cards, dark text. Dark uses current established palette. Liquid Glass uses translucent, high-contrast frosted slate surfaces and a diagonal gradient with subtle reflected highlights and cool-blue accent. This is an Android-specific frosted visual interpretation; it does not promise a native Wayland glass blur, which is a desktop-only capability.
- Existing pairing keys, device IDs, note-order preference, note storage, revision graphs, receipt and deletion logic are unchanged. Theme selection is isolated in the existing app preferences under theme-mode and defaults safely to Dark for existing installations.

## Validation
- Android JVM suite 141 passing. Added persistence and fallback tests for theme choice and Settings contract tests for About removal and three accessible choices.
- APK offline assembleDebug passed.
- Remains to be performed on device: install approved build in-place with exact pre/post private app-data checks, verify Settings UI and all three options visibly, verify theme survives app restart and does not disrupt existing synchronization, restore user's original chosen theme after test, and verify no AndroidRuntime crashes.
- No permanent deletion of the "Concurrent third head" conflict note is authorized until the intended semantics are clarified. The existing note must remain visible and its full history intact.
