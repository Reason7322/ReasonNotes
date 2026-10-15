# ReasonNotes desktop theme: user's real Kvantum LiquidGlass

Created 2026-10-16. Basis: the user's Fedora/Hyprland Qt configuration, **not** generic online "liquid glass" screenshots or an invented Apple-like frosted UI.

## Verified system material

- Qt desktop environment: `QT_QPA_PLATFORMTHEME=qt6ct`; `~/.config/qt6ct/qt6ct.conf` sets `style=kvantum`; `~/.config/Kvantum/kvantum.kvconfig` sets `theme=LiquidGlass`.
- Active theme generated at `~/.config/Kvantum/LiquidGlass/LiquidGlass.kvconfig`; Noctalia source template `~/.config/noctalia/templates/liquid-glass/LiquidGlass.kvconfig.in` is regenerated on wallpaper palette changes by `~/.config/noctalia/liquid-glass-dynamic.toml`.
- Theme comment: *A dark cool blue-gray translucent Liquid Glass theme.* Example current material: window `#1c2021`, 72%-alpha base `#2a2f32B8`, alt `#171a1c`, button `#2f3537`, light `#383f42`, accent `#98abb3`, text `#f2f2f3`.
- Kvantum config: `translucent_windows=true`, `composite=true`, `blurring=false`, `popup_blurring=true`; SVG uses layered `#2a2f32 → #343a3d → #171a1c` gradients. Global Hyprland blur is enabled (size 10, passes 3); the user has not authorized editing compositor or Qt styles.
- This Qt Quick application does **not** automatically inherit custom QWidget/Kvantum SVG material. We implement equivalent materials in ReasonNotes; no Kvantum/QT6CT, Noctalia, Hyprland, SVG theme, or external user file is modified.

## Implementation

- App Preferences allows a **third, persistent `glass`** choice alongside original Dark and Light. English/Polish choices and note data unchanged.
- New read-only `LiquidGlassPalette` QObject loads **actual generated Kvantum GeneralColors**, parses CSS `#RRGGBBAA` correctly (rather than Qt's `#AARRGGBB` convention), watches both file and parent directory for atomic wallpaper-driven regenerations, and notifies QML immediately. A malformed or missing theme file leaves last valid colors/fallbacks intact. No file copying or background polling.
- Original `Theme.qml` supports responsive blue-gray colors, pale text, alpha surfaces and restrained vertical gradients. Main app Qt Quick window uses a transparent buffer **only when Liquid Glass is selected** so the compositor can blend/blur behind it. Native Hyprland compositor owns blur; no expensive in-app Gaussian/fragment-shader blur. This is best-effort Wayland translucency, not a promise of exact Kvantum shader/refraction behavior on every compositor.
- Editor and sidebar have modest SVG-like top-to-bottom gradients. The right-side sync drawer has **nearly opaque** glass material so underlying note text cannot show through its header. Selected note, hover, search, status indicators and primary actions share the live accent.
- Separate neutral steel-blue SVG variants avoid GPU-consuming colorization shaders; unlike the palette's Qt colors, these particular strokes are static, not wallpaper-reactive. Existing Dark and Light assets are left unchanged.
- Error states retain red and success states green for recognizability and contrast; user-authored note text/revisions and sync state semantics are untouched.

## Checks and acceptance

- Tests: generated Kvantum colors parsed including alpha; directory/file watchers handle atomic replace; malformed file retains last-good colors; third setting survives restarts; live QML palette update reflected without restart; glass theme can be selected by actual Settings button click; original Dark/Light values unchanged; original note title and Markdown remain unchanged.
- Full Fedora Python+Qt tests passed (**182/182**).
- Qt offscreen acceptance previews of the actual UI (synthetic notes only) live at `ChatGPTProjects/ReasonNotes/acceptance/visual/liquid_glass_editor_preview.png` and `liquid_glass_drawer_preview.png`; warning list empty.
- Before/after live user repository revision counts are verified before installing new versioned Fedora desktop release. Don't close user's running version or send any sync traffic.
- If the user wishes, they can enable the theme manually in **Settings → Appearance → Liquid Glass** and revert immediately to original Dark or Light.
