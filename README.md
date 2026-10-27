# ReasonNotes

ReasonNotes is an offline-first notes application for Android and Fedora with
explicit, manual synchronization through KDE Connect. This repository contains
the accepted Android application and is the umbrella project and
[release download page](https://github.com/Reason7322/ReasonNotes/releases/latest).
The accepted native Fedora source is maintained in
[ReasonNotesKvantum](https://github.com/Reason7322/ReasonNotesKvantum).

ReasonNotes has no account, cloud backend, analytics service, or automatic
background sync. Notes and synchronization state remain on your devices.

## Features

- Create and edit titled plain-text notes with autosave on Android.
- Search, pin, select, and bulk-delete notes.
- Selective or all-note synchronization between Android and Fedora.
- Explicit conflict choices when both devices edit the same note lineage.
- Durable acknowledgments: transfer requested, peer import, and confirmed
  receipt are kept as distinct states.
- Local-only deletion. Explicitly synchronizing the device that still has a
  note restores it to a device where it was deleted.
- Validated current-state envelopes, retryable transport, and retained local
  synchronization history.

## Manual KDE Connect synchronization

1. Install KDE Connect on Fedora and Android, pair the devices, and confirm
   they are reachable.
2. In Android ReasonNotes Settings, grant **All files access**. The app uses
   `/storage/emulated/0/ReasonNotes Exchange` as its controlled receive folder.
3. Keep ReasonNotes open on Android when receiving a new transfer.
4. Choose all notes or selected notes and press Sync on either device.
5. KDE Connect carries the generated JSON bundle. The receiver validates and
   durably imports it, then returns acknowledgments. Completion is shown only
   after the sender imports those acknowledgments.

ReasonNotes does not run a background Android service. While the app is
visible, a lightweight foreground poll notices files that KDE Connect delivers
after the activity resumes.

## Install on Android

Download these files from the
[latest GitHub Release](https://github.com/Reason7322/ReasonNotes/releases/latest):

- `reasonnotes-android-0.1.apk`
- `SHA256SUMS`

Verify the download, then open the APK with Android's package installer:

```sh
sha256sum -c SHA256SUMS
```

The APK requires Android 8.0 (API 26) or newer. It is signed with the accepted
development certificate and uses package ID `org.reasonnotes.probe`. Android
may ask you to allow installation from the app that opened the download.

After installation, install/pair KDE Connect and grant ReasonNotes **All files
access** before the first cross-device sync.

## Install on Fedora

Download `reasonnotes-0.1.0-1.cdf6323.fc44.x86_64.rpm` and `SHA256SUMS` from
the same release:

```sh
sha256sum -c SHA256SUMS
sudo dnf5 --no-gpgchecks install ./reasonnotes-0.1.0-1.cdf6323.fc44.x86_64.rpm
```

The RPM is unsigned; verify its published SHA-256 before installing. It targets
Fedora 44 x86_64 and depends on Python 3.14, PySide6 6.11, Qt 6 Wayland,
Kvantum, and KDE Connect. Fedora build instructions are in the
[desktop repository](https://github.com/Reason7322/ReasonNotesKvantum#build-the-fedora-rpm).

## Build the Android application

Requirements:

- JDK 17
- Android SDK with compile SDK 35
- Network access for the first Gradle dependency resolution, or a populated
  local Gradle cache

Build and test from the Android project directory:

```sh
cd android-probe
./gradlew test assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The accepted
public APK was built from runtime source revision `98b094f`; release-only
documentation commits do not change those bytes.

## Requirements and limitations

- Synchronization is explicit and foreground-oriented, not continuous cloud
  sync.
- KDE Connect pairing and reachability are required.
- Android requires the special **All files access** grant for the controlled
  exchange directory.
- The first public APK is development-certificate signed and is not an app
  store build.
- The Fedora RPM is unsigned and targets Fedora 44 x86_64/Wayland.
- Existing engineering documents and earlier desktop prototypes remain in Git
  history; `ReasonNotesKvantum` is the authoritative released Fedora source.

## License

ReasonNotes project-owned source is licensed under the [MIT License](LICENSE),
copyright 2026 Reason7322. Dependencies and retained third-party assets keep
their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).

Release provenance and exact hashes are recorded in
[docs/RELEASE_0.1.0.md](docs/RELEASE_0.1.0.md).
