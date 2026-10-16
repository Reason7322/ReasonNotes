# ReasonNotes Liquid Glass: native Hyprland compositor blur

Date: 2026-10-16. Owner's critique: original Liquid Glass implementation produced broad horizontal wallpaper bands when compared with real Dolphin/Kvantum and Kitty; a subsequent opaque correction removed bands but looked too flat.

## Verified cause and supported path

- Running compositor: Hyprland 0.56.2, blur enabled (size 10, passes 3), Qt 6.11.2 via Fedora 44 / PySide6, display `wayland-1`.
- Contrary to the earlier assertion about missing KDE blur interfaces, `wayland-info` **does advertise** `ext_background_effect_manager_v1` (version 1) on this very session. The protocol XML is available at `/usr/share/wayland-protocols/staging/ext-background-effect/ext-background-effect-v1.xml`.
- Qt 6.11 Wayland client's `QQuickWindow.winId()` returns an actual `wl_surface *` (verified with `wl_proxy_get_class` → `wl_surface`; only Qt 6.9+ is allowed).
- Qt's public `QGuiApplication.nativeInterface().display()` and `.compositor()` expose its own `wl_display*` and `wl_compositor*`. The native effect uses those on **the same Wayland connection**. It must NOT open a separate connection or hand foreign proxies to another client.
- A standalone, hidden Qt Quick window successfully negotiated blur capability, instantiated an `ext_background_effect_surface_v1` for its wl_surface, requested blur over a clipped rectangle, and returned status 0; toggling on/off/on in the integrated Python controller returned True/False/True without crashes.

## Implementation

- `linux-app/native/reasonnotes_wayland_blur.c`: small C bridge. A private Wayland event queue avoids dispatching Qt's unrelated Wayland events during registry discovery. Verifies compositor's advertised blur capability before requesting effect. Region is clipped to the surface's current size by compositor and is large enough to cover resizes.
- `scripts/build-wayland-blur.py`: regenerates protocol code with the **locally installed** `wayland-scanner`, builds a small local shared object using `libwayland-client`; generated code and .so aren't tracked in Git.
- `scripts/install-fedora-desktop.py`: copies the candidate release into a new immutable version directory, optionally compiles the helper inside its private staging area, and leaves all existing user note files alone. If native build dependencies are missing, release still installs with the **opaque fallback**.
- `src/reasonnotes_app/wayland_blur.py`: Qt QObject managing the effect. Only requests it when appearance is Liquid Glass on actual Wayland / Qt 6.9+. Listens to appearance setting changes, disables blur when switching away, destroys the native handle before the window and display disappear. If any dependency/protocol/capability is unavailable, reports a warning and leaves the readable opaque surface.
- `Theme.qml` and `Main.qml`: only when native blur is successfully active does the window turn transparent and the main pane/sidebar become smoothly translucent (0.82 and 0.84 alpha). The earlier full-height stripe-producing alpha gradients remain removed. Control surfaces use compatible Kvantum colors, and a restrained **28 px** top reflection remains.
- All user-authored notes, synchronization code, Android apps, Hyprland rules, Kitty config, Noctalia files and Kvantum user theme were **not modified**.

## Verification

- 184/184 Fedora unit/QML tests passed in offscreen mode, including preference changes, consistency of dark/light, dynamic palette updates and fallback to opaque surface when blur isn't active.
- Native protocol probe against Hyprland returned status 0 with non-null effect handle on the actual Wayland connection.
- Integrated Python controller on this live Hyprland session: `COMPOSITOR_NATIVE_BLUR_ACTIVE True`, `BLUR_AFTER_DARK False`, `BLUR_REENABLED True`; no QML rendering required or user session configuration changed.
- Candidate `--check-launch` on native Wayland with isolated XDG user data/config and preference set to glass succeeded.
- Final visual acceptance must be obtained from the **actual live compositor**, not an offscreen preview (offscreen Qt images do not contain Hyprland background blur). Before showing the owner, install in a reversible versioned release and have them launch with the existing QSettings Liquid Glass preference; verify against their provided Dolphin and Kitty screenshots.
- If the owner finds remaining differences, inspect and adjust material tint/opacity (currently 82%/84%) rather than reintroducing full-window gradients or expensive Qt Quick blur shaders.
