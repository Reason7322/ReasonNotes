# Dolphin Liquid Glass material match: entire desktop window, sidebar first

## Unchanged end goal and deliverable
Make desktop Fedora ReasonNotes match the appearance of the user's floating Dolphin window as closely as the Qt Quick rendering architecture permits. Specifically the entire material language—sidebar tint, background gradients, chrome/top tint, editor/Settings/conflict material, control surfaces, borders/separators, color and blur—with highest priority on the sidebar. Existing notes, immutable history, desktop editor interactions, F5, KDE Connect synchronization, automatic cross-device permanent deletion, selected note status, Light and Dark themes and the Android app must be unaffected.

## Evidence and source
- User-supplied side-by-side Dolphin/ReasonNotes screenshot, with the same Elden Ring wallpaper and floating windows, October 17. The Dolphin sidebar has a brighter cool-gray top fading through dark blue-gray into a nearly-black center/lower section and a slight lift at the bottom; ReasonNotes had an almost uniform neutral-charcoal sidebar.
- Installed Kvantum LiquidGlass config is dynamically read by ReasonNotes through the existing, read-only LiquidGlassPalette object. General colors window #1c2021, button #2f3537, light #383f42, base #2a2f32B8, accent #98abb3. The native theme's window-normal SVG uses 72% opacity, but its QWidget material cannot be reused directly by QML; the app uses a native Wayland blur request.
- A simple 82% translucent QML plane had made wallpaper details too distracting when floating. The preceding readability release c394800 used 97–98% opacity, but did not reproduce Dolphin's cool color variations.

## Implementation
- Theme.qml derives screenshot-calibrated cool tones by adding small per-channel offsets to the ACTIVE Kvantum QColor properties, preserving live Noctalia/Kvantum regenerations rather than freezing the whole theme.
- Sidebar.qml paints a seven-stop top-to-bottom gradient mimicking Dolphin's sidebar: cool gray top, blue/navy middle, charcoal base and a subtle tail lift. The entire sidebar is one seamless surface and retains the invisible resize target and narrow top-edge reflection.
- EditorPane.qml, SettingsPane.qml, and ConflictView.qml paint the SAME cooler, subtler three-stop Dolphin-style material. The controls and hover tone are lifted slightly, and border/separator contrast reduced in glass mode, preserving text, selection, and accent colors.
- All material stops remain 98.5–99.5% opaque for readable text over detailed wallpapers, with native compositor blur still enabled when available. This is an approximation of Dolphin's visually observed frosted material; Qt Quick is not the same painting engine as Kvantum QWidget, so an exact pixel-for-pixel match under all wallpapers is not promised.
- Without native blur the existing opaque Qt fallback and uniform surfaces remain. Light and Dark modes are unchanged. No window manager config, native blur helper, note code, APK, or private notes touched.

## Tests and remaining acceptance
- 233/233 Fedora Python/Qt tests passed, including QML tests checking actual seven gradient-stop colors/positions and the matching three-stop editor material on all three pages. Tests also check solid surfaces without blur, live source palette and note preservation.
- Before calling the engineering task complete: commit current clean source, install via official immutable Fedora installer, verify private app-data file hashes byte-for-byte, preserve rollback, optionally launch new app only if no existing running app is interrupted. User should compare the final floating window against Dolphin over the same wallpaper and report any remaining mismatch; that subjective live visual acceptance remains unverified by headless tests.
