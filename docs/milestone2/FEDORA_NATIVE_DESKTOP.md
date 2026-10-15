# Fedora-native ReasonNotes desktop launcher — 2026-10-15

## Purpose

The Qt 6 / PySide6 / Qt Quick implementation was already functional, but only
through a developer entry point requiring `--root`, `--inbox`, and a
32-character `--device-id`. Fedora had no ReasonNotes desktop-entry or
installed launcher. This checkpoint solves regular application launch and
XDG desktop integration **without altering sync, receipt or note-history
semantics**.

## User application behavior

- The native `linux-app/reasonnotes.py` can now be launched with no arguments.
- Default user repository: `$XDG_DATA_HOME/ReasonNotes/repository` (normally
  `~/.local/share/ReasonNotes/repository`). This is **not** any prior disposable
  test repository, Android state, development checkout, or arbitrary Downloads.
- Private inbox: `$XDG_DATA_HOME/ReasonNotes/inbox`. A second
  `--received-dir` remains an opt-in explicit path, so historic incoming
  files are not silently consumed by a routine editing session.
- KDE Connect discovery: choose one and only one currently reachable paired
  peer returned by `kdeconnect-cli --list-available --id-only`; time limit
  three seconds. An explicit `--device-id` overrides discovery. If no unique
  peer is available, the note editor still opens, and the Sync button reports
  a human-readable configuration error rather than blocking application start.
- Startup performs **no automatic network transfer or import**.
- Window/app use the same native `QGuiApplication`, Qt Quick Controls,
  installed Fedora `python3-pyside6` library and Wayland compositor.
  `QGuiApplication.setDesktopFileName` sets the XDG application ID.
- `--root`, `--inbox`, `--received-dir`, `--device-id`, and
  `--target-device` remain available for controlled/reproducible use.

## User-level installation

The scoped installer `scripts/install-fedora-desktop.py` uses Python standard
libraries and the installed system PySide6. No sudo, pip, system RPM changes,
data migrations, temporary test imports or cleanup of previous releases.

It creates:

- Versioned source snapshot:
  `~/.local/opt/reasonnotes/releases/<commit-sha>/`
- Stable executable:
  `~/.local/bin/reasonnotes`
- XDG desktop menu entry:
  `~/.local/share/applications/org.reasonnotes.ReasonNotes.desktop`
- Scalable native icon:
  `~/.local/share/icons/hicolor/scalable/apps/org.reasonnotes.ReasonNotes.svg`

The installer refuses to overwrite an existing release with the same SHA and
leaves existing note data and previous release snapshots untouched. Source
snapshots and note-data roots are deliberately separate. This is a native
user-installed Fedora GUI application, **not yet a system-wide RPM package**.
Future updates can deploy a new versioned snapshot without deleting user notes.

## Verification

- Original QML smoke suite and full Python tests pass.
- Added focused tests for default zero-argument startup, XDG directory
  determination, single-peer discovery, disconnected/multiple-peer behavior,
  real QML start/exit in offscreen mode, and temporary user-desktop installation
  preserving a preexisting mock note.
- Actual Qt Wayland `--check-launch` test passed against the owner's running
  Hyprland compositor (Wayland socket `/run/user/1000/wayland-1`) using only a
  disposable test repository; confirmed application Qt/QML startup returns 0.
- No Android or Fedora normal-note synchronization changes are included.
- User-facing visual/pointer acceptance of the installed app remains a
  separate checkpoint after launch.
