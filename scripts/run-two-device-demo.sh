#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 TEMPORARY_ROOT" >&2
  exit 2
fi

demo_root=$1
mkdir -p "$demo_root/linux" "$demo_root/android"
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd -- "$script_dir/.." && pwd)

PYTHONPATH="$project_root/src" python3 - "$demo_root" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import sys
from uuid import UUID

from reasonnotes_sync.conflicts import resolve_conflict
from reasonnotes_sync.delivery import DeliveryLedger
from reasonnotes_sync.repository import Repository


root = Path(sys.argv[1])
note_id = UUID("55555555-aaaa-4aaa-8aaa-555555555555")


def at(second):
    return datetime(2026, 10, 12, 17, 0, second, tzinfo=timezone.utc)


def exchange(source, target):
    for revision in source.revisions():
        outcome = target.import_bytes(revision.serialize(), "demo-delivery.md")
        print(f"exchange {revision.origin_device}->{target.local_device} {revision.revision_id} {outcome.kind}")


def heads(repository):
    return ",".join(repository.graph().status(note_id).head_ids)


linux = Repository.open(root / "linux", local_device="linux", target_device="android")
android = Repository.open(root / "android", local_device="android", target_device="linux")
base = linux.create_revision(note_id, "base\n", "linux", at(0))
print(f"create linux base={base.revision_id}")
exchange(linux, android)
linux_edit = linux.create_revision(note_id, "Linux chosen version\n", "linux", at(1))
android_edit = android.create_revision(note_id, "Android competing version\n", "android", at(2))
print(f"offline linux={linux_edit.revision_id}")
print(f"offline android={android_edit.revision_id}")
exchange(linux, android)
exchange(android, linux)
print(f"conflict linux-heads={heads(linux)}")
print(f"conflict android-heads={heads(android)}")
resolution = resolve_conflict(linux, note_id, "keep-linux", "linux", at(3))
print(f"choice=keep-linux resolution={resolution.revision_id} parents={','.join(resolution.parents)}")
exchange(linux, android)
linux_entry = DeliveryLedger(linux.root).entry(resolution.revision_id, "android")
print(f"delivery-state={linux_entry.state.value} attempts={linux_entry.attempts}")
print(f"final-linux-heads={heads(linux)}")
print(f"final-android-heads={heads(android)}")
print(f"final-revision={resolution.revision_id}")
print(f"final-body-sha256={resolution.body_sha256}")
if heads(linux) != heads(android):
    raise SystemExit("repositories did not converge")
PY

