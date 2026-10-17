# Material correction: genuine translucent blurred glass like Dolphin

## Full original observable user goal
The complete Fedora ReasonNotes Liquid Glass window—including editor, controls, borders, and above all the sidebar—should reproduce the actual floating Dolphin/Kvantum LiquidGlass look and function over the user's wallpaper. Not just similar colors: it must have the same visible frosted translucency and blurred background. Preserve all original product functionality, notes/history, F5, live deletion and KDE Connect, Android, YAMIS and user Hyprland settings.

## Why the previous version failed
The prior release 0551901 matched screenshot-derived color gradients but its panels still used 0.985 alpha or greater. That hid the wallpaper and nearly all observable compositor blur. Dolphin's installed /home/reason/.config/Kvantum/LiquidGlass/LiquidGlass.svg has window-normal style opacity 0.72, and its live Kvantum config says blur_translucent=true. The screenshot clearly shows a translucent blurred sidebar, not an opaque gradient.

## Implementation
When the native Wayland ext-background-effect-v1 blur request has succeeded, restore 0.72 alpha for all sidebar and editor gradient stops and primary controls. This alpha matches the actual native Kvantum window-normal SVG material. Keep the existing live Kvantum color tint, restrained top reflections, and normal floating-window shadow behavior. When the blur extension is missing/fails, colors remain solid and gradients collapse to uniform backgrounds so wallpaper details do not bleed through unblurred. Keep Dark and Light modes unchanged. Never touch compositor settings or Android.

## Verification
All 233 Fedora tests pass including 54 QML focused tests. The QML test asserts 72% true material opacity while blur is active, 100% surfaces and no material gradients when blur is inactive, preservation of notes, and unchanged behavior of live theme colors and mode switching. Full user acceptance still requires a visual comparison with Dolphin over the same wallpaper. Do not claim pixel-perfect appearance without that comparison. Native ext-background-effect-v1 blur region uses the existing app helper, not a new shader or fake screenshot blur.
