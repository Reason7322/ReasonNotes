# ReasonNotes Liquid Glass blur works from launch: double-buffered Wayland surface commit

## Original end goal and observable acceptance
User wants Fedora ReasonNotes floating Liquid Glass appearance genuinely frosted and blurred like Dolphin, sidebar highest priority, immediately on every launch WITHOUT manual resizing. Preserve native Wayland, current live Kvantum colors, controls, whole-window material, desktop Dark/Light, notes, history, synchronization, F5, cross-device deletion, Android, YAMIS and Hyprland configuration. A successful static unit test does not alone meet the visual goal.

## Confirmed regression and mechanism
Prior attempt 33263bf registered blur after an initial opaque Qt frame; user observed opaque horizontal bands on fresh launch. That release was withdrawn by atomically restoring the launcher to 12aadc3, with 293/293 private data files intact. The older release allowed translucency immediately but still showed detailed wallpaper until resized.

The official ext-background-effect-v1 protocol explicitly says set_blur_region is pending, double-buffered state applied only on the next wl_surface.commit. Our native helper set the region and called wl_display_flush but never triggered wl_surface_commit; QWindow.requestUpdate also does not guarantee a new Wayland buffer commit. Resizing produced the missing commit.

## Minimal fix
- Build on original 12aadc3, NOT the failed first-frame-delay branch.
- Explicitly issue wl_surface_commit(state->surface) after setting native blur region, on Qt's existing Wayland connection and GUI thread, followed by wl_display_flush. No new wl_display, no GPU polling or permanent timers, no window resize or compositor settings change.
- On effect destruction, commit again to remove the double-buffered region.
- Request an RGBA 8-bit default Qt Quick window buffer BEFORE first window creation to prevent late-alpha transitions and rendering banding. No runtime toggling from initially opaque to translucent window format. Opaque Dark/Light surfaces remain opaque.
- No other app behavior altered.

## Test evidence
- Native protocol helper compiled on Fedora with -Wall -Wextra -Werror.
- Live Wayland test on actual Hyprland compositor confirmed a real valid background-effect object, protocol success code 0, and 8-bit alpha window format, with clean native effect teardown.
- New static contract tests require an explicit surface commit after blur region and before flushing, plus a commit during destruction and initial RGBA format request.
- Full Fedora/PySide6 suite 235/235 PASS.
- Created a transient HEADLESS-1 output for a nonintrusive real-compositor visual test, using an isolated temporary repository/config and no user notes. A fresh launch of the previous 12aadc3 build showed sharp geometric wallpaper patterns through the editor with no resize; a fresh launch of this candidate on the same HEADLESS-1 output blurred those patterns away immediately, and they remained blurred 1.1 seconds later. Read visually from captured output. No screenshot files or app data were retained. Temporary virtual output removed; only original DP-2 monitor remains, same focus. The tested app was not installed during this experiment.

## Deployment contract
Commit exact tested source, install via immutable Fedora release installer while no live ReasonNotes process, preserve old releases and rollback, compare all existing private user-data files bitwise before and after. Do not touch Android or user's compositor settings. Ask user to verify first open on original high-contrast Elden Ring wallpaper as final physical-monitor acceptance.
