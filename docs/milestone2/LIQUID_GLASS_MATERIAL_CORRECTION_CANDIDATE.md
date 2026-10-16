# Liquid Glass: visual critique and experimental material correction

Date: 2026-10-16. **Status: experimental candidate; NOT installed.**

## The owner's screenshots and what they prove

At 02:07, the owner compared ReasonNotes with Dolphin (native Kvantum LiquidGlass) and Kitty (a terminal with its own `liquid-glass.slang` edge shader). ReasonNotes displayed distinct, broad horizontal bands of teal, dark green, dark purple and gray through its sidebar and Settings pane; Dolphin and Kitty showed smooth blue-gray translucent/glass material instead. The user's verdict on the installed `575c010` theme: "complete miss."

Root-cause candidate, grounded in ReasonNotes source: the theme introduced a transparent top-level ApplicationWindow and layered multiple alpha-blended full-height Rectangle gradients (sidebar, editor, Settings, sync drawer), while the compositor's background and color treatment are visible behind it. Those are not the same material rendering path as Kvantum and Kitty. Kitty's active configuration has `background_opacity 0.72`, `background_blur 1` and a shader with a 30 px top-edge highlight and a 22 px left-edge highlight; it does **not** shade the entire window through multiple full-height alpha gradients. Hyprland has blur enabled, but Wayland's current announced interfaces did not expose a straightforward KDE blur protocol to the Qt Quick client.

## Corrected trial

- Paint the Qt Quick root opaque **only when Liquid Glass is selected**; preserve existing Dark/Light palettes and alpha. This is deliberate to eliminate sharp wallpaper bleed-through when compositor blur is unavailable.
- Use solid reference-derived charcoal `window.color` for editor and Settings, cool blue-gray `button.color` sidebar, and Kvantum `base.color` for controls. Keep live generated Noctalia palette integration intact.
- Remove the full-height translucent gradients from Sidebar, EditorPane, SettingsPane and SyncDialog. Replace them with a subtle **28 px** edge reflection limited to the top of each pane, approximating Kitty's restrained shader idea without expensive shaders.
- Keep the sync drawer readable against the editor, do not adjust synchronization behavior, note content, user configuration, Noctalia or Hyprland.
- **Limitation:** this conservative candidate is not true blurred glass. It prioritizes legibility and absence of wallpaper stripes until a compositor-compatible background-blur method is proven and separately approved.

## Tests and acceptance

- QML checks should assert an opaque root/background under Liquid Glass, bounded 28px edge reflection, live Kvantum accent and both legacy themes' unchanged colors.
- Compare **live native Hyprland screenshots** of this candidate to the three owner references **before** promoting it to a release. Qt offscreen renders are insufficient; previous offscreen test looked smooth even though the real Wayland release banded.
- Do **not** install or replace existing app until the owner approves the direction. Candidate lives in a separate Git branch/worktree.
