#!/usr/bin/env python3
"""Compile the optional Wayland background-effect client from system protocols.

Builds inside the supplied target directory; does not install packages or
change compositor state. Installer uses this in a private release staging dir.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

PROTOCOL = Path("/usr/share/wayland-protocols/staging/ext-background-effect/ext-background-effect-v1.xml")

def build(target: Path) -> Path:
    source = target / "reasonnotes_wayland_blur.c"
    if not source.is_file():
        raise FileNotFoundError(f"ReasonNotes Wayland source missing: {source}")
    if not PROTOCOL.is_file():
        raise FileNotFoundError(f"Wayland protocol missing: {PROTOCOL}")
    for binary in ("wayland-scanner", "cc", "pkg-config"):
        if not shutil.which(binary):
            raise FileNotFoundError(f"Native build dependency unavailable: {binary}")
    flags = subprocess.check_output(
        ["pkg-config", "--cflags", "--libs", "wayland-client"], text=True
    ).split()
    target.mkdir(parents=True, exist_ok=True)
    output = target / "libreasonnotes_wayland_blur.so"
    with tempfile.TemporaryDirectory(prefix="rn-wayland-build-") as temp:
        work = Path(temp)
        header = work / "ext-background-effect-v1-client-protocol.h"
        generated = work / "ext-background-effect-v1-protocol.c"
        subprocess.run(
            ["wayland-scanner", "client-header", str(PROTOCOL), str(header)],
            check=True,
        )
        subprocess.run(
            ["wayland-scanner", "private-code", str(PROTOCOL), str(generated)],
            check=True,
        )
        subprocess.run(
            ["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
             "-fPIC", "-shared", "-I", str(work), "-o", str(output),
             str(source), str(generated), *flags],
            check=True,
        )
    output.chmod(0o755)
    return output

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=Path, required=True)
    args = parser.parse_args()
    print("BUILT_NATIVE_WAYLAND_BLUR=" + str(build(args.target)))
