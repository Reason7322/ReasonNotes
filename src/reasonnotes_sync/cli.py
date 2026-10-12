"""Minimal line-oriented CLI for inspecting and resolving revision conflicts."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
from uuid import UUID

from .conflicts import ConflictSelectionError, list_conflict, resolve_conflict
from .delivery import DeliveryLedger, DeliveryLedgerError
from .envelope import EnvelopeError
from .repository import ConflictRequired, Repository


def _uuid(value: str) -> UUID:
    try:
        note_id = UUID(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a UUID") from error
    if str(note_id) != value:
        raise argparse.ArgumentTypeError("must be a canonical UUID")
    return note_id


def _utc_timestamp(value: str) -> datetime:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be UTC YYYY-MM-DDTHH:MM:SSZ") from error
    if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
        raise argparse.ArgumentTypeError("must be canonical UTC YYYY-MM-DDTHH:MM:SSZ")
    return parsed


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="reasonnotes-sync")
    parser.add_argument("--root", required=True, type=Path)
    commands = parser.add_subparsers(dest="command", required=True)

    status = commands.add_parser("status")
    status.add_argument("--note-id", type=_uuid)

    conflicts = commands.add_parser("conflicts")
    conflicts.add_argument("--note-id", required=True, type=_uuid)

    resolve = commands.add_parser("resolve")
    resolve.add_argument("--note-id", required=True, type=_uuid)
    resolve.add_argument(
        "--choice", required=True, choices=("keep-linux", "keep-android", "skip")
    )
    resolve.add_argument("--revision-id")
    resolve.add_argument("--device-id", required=True)
    resolve.add_argument("--modified-utc", required=True, type=_utc_timestamp)

    outbox = commands.add_parser("outbox")
    outbox.add_argument("--target-device", required=True)

    retry = commands.add_parser("retry-unconfirmed")
    retry.add_argument("--target-device", required=True)

    force = commands.add_parser("force-retry")
    force.add_argument("--target-device", required=True)
    force.add_argument("--revision-id", required=True)
    return parser


def _status(repository: Repository, note_id: UUID | None, stdout) -> int:
    graph = repository.graph()
    if note_id is None:
        note_ids = tuple(sorted({revision.note_id for revision in repository.revisions()}, key=str))
    else:
        note_ids = (note_id,)
    for current in note_ids:
        status = graph.status(current)
        heads = ",".join(status.head_ids) or "-"
        pending = ",".join(status.pending_ids) or "-"
        print(
            f"note={current} heads={heads} pending={pending} "
            f"conflicted={'true' if status.conflicted else 'false'}",
            file=stdout,
        )
    return 0


def _conflicts(repository: Repository, note_id: UUID, stdout) -> int:
    for candidate in list_conflict(repository, note_id):
        print(
            f"revision={candidate.revision_id} device={candidate.origin_device} "
            f"deleted={'true' if candidate.deleted else 'false'} "
            f"preview={json.dumps(candidate.preview, ensure_ascii=False)}",
            file=stdout,
        )
    return 0


def _resolve(repository: Repository, arguments, stdout) -> int:
    if arguments.choice == "skip":
        resolve_conflict(
            repository,
            arguments.note_id,
            arguments.choice,
            arguments.device_id,
            arguments.modified_utc,
            revision_id=arguments.revision_id,
        )
        print(f"resolution=skipped note={arguments.note_id}", file=stdout)
        return 0

    expected_origin = "linux" if arguments.choice == "keep-linux" else "android"
    heads = repository.graph().heads(arguments.note_id)
    if arguments.revision_id:
        selected_id = arguments.revision_id
    else:
        matching = tuple(head for head in heads if head.origin_device == expected_origin)
        selected_id = matching[0].revision_id if len(matching) == 1 else "-"
    resolution = resolve_conflict(
        repository,
        arguments.note_id,
        arguments.choice,
        arguments.device_id,
        arguments.modified_utc,
        revision_id=arguments.revision_id,
    )
    assert resolution is not None
    print(
        f"resolution=created revision={resolution.revision_id} selected={selected_id} "
        f"parents={','.join(resolution.parents)}",
        file=stdout,
    )
    return 0


def _outbox(repository: Repository, target_device: str, stdout) -> int:
    ledger = DeliveryLedger(repository.root)
    for entry in ledger.entries(target_device):
        last = (
            entry.last_requested_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
            if entry.last_requested_utc is not None
            else "-"
        )
        print(
            f"revision={entry.revision_id} target={entry.target_device} "
            f"state={entry.state.value} attempts={entry.attempts} last-requested={last}",
            file=stdout,
        )
    for event in ledger.events():
        observed = event.observed_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
        print(
            f"event={event.kind.value} revision={event.revision_id} "
            f"observer={event.observer_device} observed={observed} "
            f"detail={json.dumps(event.detail)}",
            file=stdout,
        )
    return 0


def _eligible(repository: Repository, arguments, mode: str, stdout) -> int:
    ledger = DeliveryLedger(repository.root)
    if mode == "force-retry":
        revision_ids = ledger.eligible(
            arguments.target_device, force_ids=(arguments.revision_id,)
        )
    else:
        revision_ids = ledger.eligible(
            arguments.target_device, retry_unconfirmed=True
        )
    if not revision_ids:
        print(f"eligible=- mode={mode}", file=stdout)
    for revision_id in revision_ids:
        print(f"eligible={revision_id} mode={mode}", file=stdout)
    return 0


def main(argv=None, *, stdout=None, stderr=None) -> int:
    stdout = sys.stdout if stdout is None else stdout
    stderr = sys.stderr if stderr is None else stderr
    arguments = _parser().parse_args(argv)
    repository = Repository.open(arguments.root)
    try:
        if arguments.command == "status":
            return _status(repository, arguments.note_id, stdout)
        if arguments.command == "conflicts":
            return _conflicts(repository, arguments.note_id, stdout)
        if arguments.command == "resolve":
            return _resolve(repository, arguments, stdout)
        if arguments.command == "outbox":
            return _outbox(repository, arguments.target_device, stdout)
        if arguments.command == "retry-unconfirmed":
            return _eligible(repository, arguments, "retry-unconfirmed", stdout)
        if arguments.command == "force-retry":
            return _eligible(repository, arguments, "force-retry", stdout)
    except (
        ConflictSelectionError,
        ConflictRequired,
        DeliveryLedgerError,
        EnvelopeError,
    ) as error:
        print(f"error={json.dumps(str(error))}", file=stderr)
        return 2
    raise AssertionError("unhandled command")

