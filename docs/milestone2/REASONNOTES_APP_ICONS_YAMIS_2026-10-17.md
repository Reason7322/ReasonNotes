# ReasonNotes app icons for Android, desktop, and YAMIS

## Original observable goal
The user picked the glossy-blue overlapping notes + circular sync-arrow icon from four generated candidates and requested it become the actual ReasonNotes application icon on Android and Fedora. The Fedora icon must also blend with their current Noctalia dock using the YAMIS icon theme. This means installing the icon resources, not simply generating preview artwork.

## Implementation
- Artwork source: chosen two-note/sync-arrow concept, downsampled for launcher use to 384px and transferred with a validated SHA256 checksum. The source WEBP transport image was verified byte-for-byte and decoded successfully before asset generation. The PNG file under linux-app/icons/reasonnotes.png stores the optimized lossless PNG used by the app.
- Desktop fallback: hicolor PNGs in actual 32, 48, 64, 128, 256 and 512px sizes, installed for application ID org.reasonnotes.ReasonNotes. The old hicolor/scalable SVG is removed ONLY when its exact bytes match the previous stock ReasonNotes placeholder. A customized existing SVG is preserved.
- YAMIS-specific desktop override: linux-app/icons/reasonnotes-yamis.svg is an original, background-free currentColor vector drawn as two stacked notes with sync arrows. It matches the installed YAMIS theme's monochrome application icon vocabulary rather than using an incongruous blue rounded square. The installer writes only the ReasonNotes application icon in the installed YAMIS theme; it does not modify other YAMIS icons or global theme settings.
- Android: manifest identifies @mipmap/ic_launcher and @mipmap/ic_launcher_round, legacy icon PNGs exist for every density, API 26+ adaptive icon foreground/background resources are included with padding to respect Android mask safe-zone, plus an Android monochrome thematic launcher icon. App package and signing identity remain unchanged.
- The desktop launcher retains its icon name, desktop ID, paths, and application startup WMClass; no note, history, pairing, sync or deletion logic was touched.

## Checks
- Android offline Gradle testDebugUnitTest assembleDebug succeeded: 154 JVM tests, zero failures.
- Android AAPT confirms the compiled application icon references mipmap-anydpi-v26/ic_launcher.xml at all densities.
- YAMIS SVG rendered via Qt SVG at 16, 32, 64 and 128px with non-empty pixels, and XML parser validated both adaptive Android launcher resources.
- Added Fedora temporary-home installer tests for installing all exact-size PNGs, YAMIS theme override, desktop file identity and preservation of pre-existing customized icons: 2/2 pass.
- Next: commit, verify exact installed APK signing cert, take private user-data integrity snapshot before installing Android, install in place, verify APK byte identity and user notes preserved; install new desktop release from committed SHA using the official script, confirm YAMIS/PNG paths, preserve rollback, run QIcon.fromTheme smoke test and refresh icon-theme cache if needed. User should confirm icon appearance in Android launcher and Noctalia dock.
