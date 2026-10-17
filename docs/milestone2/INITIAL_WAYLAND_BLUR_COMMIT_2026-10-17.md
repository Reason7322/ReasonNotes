# Native Wayland blur must work on first ReasonNotes launch (no manual resize)

## Original observable user goal and deliverable
Fedora ReasonNotes Liquid Glass must open already frosted/translucent like floating Dolphin without manual resizing or moving the window. The sidebar is highest-priority. Preserve the prior exact Dolphin-matched material from 12aadc3, live Kvantum palette, native Wayland blur, dark/light modes, user note data and history, KDE Connect, permanent deletion, F5, Android and Hyprland settings. The deliverable is an installed tested Fedora desktop release and a user-verifiable first-launch result.

## Observed failure and suspected mechanism
The user provided two screenshots of the *same launch*: on initial launch ReasonNotes' 72%-alpha surfaces showed sharply detailed wallpaper with no frosted blur; after manual resize those same surfaces became nicely blurred. The previous controller registered ext-background-effect-v1 during attach, *before* Qt Quick's first frame reached the compositor, and requested only QWindow.requestUpdate (UpdateRequest event). A manual resize forces a fresh Qt Quick buffer commit, allowing the native effect to become visible.

## Change
- Keep the initial UI opaque while the first real Qt Quick frame is presented.
- The controller connects to QQuickWindow.frameSwapped and waits until that first presentation. It registers ext-background-effect-v1 only on the next GUI event-loop turn, not during QML creation before a surface has committed.
- After successful registration, only then activate the glass alpha and use QQuickWindow.update() for actual render/Wayland buffer commits. Send one immediate and one bounded 40-ms additional render request to cover compositor update ordering, with no resize, window movement, persistent timer, GPU polling or global compositor changes.
- If Wayland blur is unavailable or fails, surfaces remain fully opaque; if the app closes before first presentation, disconnect the listener. Pending single-shot callbacks are inert after close.
- Switching to Dark/Light still disables the native extension. UI and notes remain unaffected.

## Tests and caveat
- Added tests for deferred protocol registration, first-frame signal path, forced Qt Quick rendering without resize, and app closure before first frame. Four Wayland blur tests pass.
- Full Fedora Qt/Python suite: 235/235 passing. No note/sync/deletion or Android code touched.
- Need to deploy immutably, compare all Fedora user-data hashes before/after and keep the previous release. Because the current user app is running, do not kill or restart it; the user should close it normally and reopen the new release to confirm blur is visible immediately. The precise compositor appearance cannot be guaranteed by headless unit tests alone.
