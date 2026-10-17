#!/usr/bin/env python3
"""Install the native Qt/Wayland ReasonNotes app for this Fedora user.

No sudo, pip install, package-manager mutation, live-note imports, or deletion
of prior revisions. Immutable release directory, stable XDG app shortcut.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


SOURCE = Path(__file__).resolve().parents[1]
DESKTOP_ID = "org.reasonnotes.ReasonNotes"
ALLOWED_VERSION = re.compile(r"[0-9a-f]{7,40}")


def atomic_text(path: Path, content: str, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".reasonnotes-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def install_release(version: str, home: Path) -> tuple[Path, Path]:
    if not ALLOWED_VERSION.fullmatch(version):
        raise ValueError("version must be an exact hexadecimal Git revision")

    base = home / ".local" / "opt" / "reasonnotes" / "releases"
    base.mkdir(parents=True, exist_ok=True)
    release = base / version
    if release.exists():
        raise FileExistsError(
            f"{release} already exists; refusing to replace installed code"
        )
    # The release is built entirely independently of any user's note store.
    stage = Path(tempfile.mkdtemp(prefix=".reasonnotes-stage-", dir=base))
    try:
        shutil.copytree(SOURCE / "linux-app", stage / "linux-app",
                        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.so"))
        for package in ("reasonnotes_app", "reasonnotes_sync"):
            shutil.copytree(SOURCE / "src" / package, stage / "src" / package,
                            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        (stage / "VERSION").write_text(version + "\n", encoding="utf-8")
        # Native Wayland blur is optional: compile in the private release stage
        # from the system protocol file. Missing build tools leave the app
        # fully functional with its opaque Liquid Glass fallback.
        try:
            subprocess.run(
                [sys.executable, str(SOURCE / "scripts" / "build-wayland-blur.py"),
                 "--target", str(stage / "linux-app" / "native")],
                check=True,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            print("WARNING: native Wayland blur unavailable; using solid fallback:", error)
        stage.rename(release)
    finally:
        if stage.exists():
            shutil.rmtree(stage)

    executable = home / ".local" / "bin" / "reasonnotes"
    launch = release / "linux-app" / "reasonnotes.py"
    atomic_text(
        executable,
        "#!/bin/sh\nexec /usr/bin/python3 '" +
        str(launch).replace("'", "'\\''") + "' \"$@\"\n",
        0o755,
    )

    applications = home / ".local" / "share" / "applications"
    desktop_file = applications / (DESKTOP_ID + ".desktop")
    atomic_text(
        desktop_file,
        "[Desktop Entry]\n"
        "Type=Application\n"
        "Name=ReasonNotes\n"
        "GenericName=Markdown Notes\n"
        "Comment=Private offline-first Markdown notes\n"
        "Exec=" + str(executable) + "\n"
        "TryExec=" + str(executable) + "\n"
        "Icon=" + DESKTOP_ID + "\n"
        "Terminal=false\n"
        "Categories=Office;Utility;\n"
        "StartupNotify=true\n"
        "StartupWMClass=" + DESKTOP_ID + "\n",
    )
    # The colorful app identity is used in hicolor (or fallback themes).
    # Real dimension-specific PNGs avoid depending on SVG raster decoding.
    icons = home / ".local" / "share" / "icons"
    source_icons = release / "linux-app" / "icons"
    for size in (32, 48, 64, 128, 256, 512):
        target = icons / "hicolor" / f"{size}x{size}" / "apps"
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_icons / "hicolor" / f"{size}x{size}" / "reasonnotes.png",
                     target / (DESKTOP_ID + ".png"))

    # The previous release shipped a scalable placeholder. Drop it only if it
    # still matches our original, otherwise respect a user-modified icon.
    old_icon = icons / "hicolor" / "scalable" / "apps" / (DESKTOP_ID + ".svg")
    old_source = source_icons / "reasonnotes.svg"
    if old_icon.is_file() and old_icon.read_bytes() == old_source.read_bytes():
        old_icon.unlink()

    # YAMIS is a symbolic monochrome theme (currentColor). Adding an
    # application-specific override changes no other icons in that theme.
    yamis = icons / "YAMIS"
    if (yamis / "index.theme").is_file():
        yamis_apps = yamis / "apps" / "scalable"
        yamis_apps.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_icons / "reasonnotes-yamis.svg",
                     yamis_apps / (DESKTOP_ID + ".svg"))
    return executable, desktop_file


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--version", help="hexadecimal source checkpoint", required=False
    )
    args = parser.parse_args()
    try:
        from PySide6 import __version__
        print("Native Fedora PySide6:", __version__)
    except ImportError as error:
        raise SystemExit(
            "Fedora dependency missing: install python3-pyside6 before installing ReasonNotes"
        ) from error
    if args.version:
        version = args.version
    else:
        version = subprocess.check_output(
            ["git", "-C", str(SOURCE), "rev-parse", "HEAD"],
            text=True,
        ).strip()
    launcher, desktop = install_release(version, Path.home())
    print("INSTALLED_LAUNCHER=" + str(launcher))
    print("INSTALLED_DESKTOP_FILE=" + str(desktop))
    print("APPLICATION_VERSION=" + version)
    print("USER_NOTES_UNTOUCHED=yes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
