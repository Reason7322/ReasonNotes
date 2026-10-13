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
from .kdeconnect_linux import KdeConnectLinux
from .receipt import ReceiptEnvelope, ReceiptError, ReceiptStore
from .repository import ConflictRequired, Repository
from .sync import scan_inbox, sync_linux


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
    resolve.add_argument("--target-device")
    resolve.add_argument("--modified-utc", required=True, type=_utc_timestamp)

    outbox = commands.add_parser("outbox")
    outbox.add_argument("--target-device", required=True)

    retry = commands.add_parser("retry-unconfirmed")
    retry.add_argument("--target-device", required=True)

    force = commands.add_parser("force-retry")
    force.add_argument("--target-device", required=True)
    force_identity = force.add_mutually_exclusive_group(required=True)
    force_identity.add_argument("--revision-id")
    force_identity.add_argument("--receipt-id")

    scan = commands.add_parser("scan")
    scan.add_argument("--inbox", required=True, type=Path)
    scan.add_argument("--local-device", default="linux")

    sync = commands.add_parser("sync")
    sync.add_argument("--inbox", required=True, type=Path)
    sync.add_argument("--local-device", default="linux")
    sync.add_argument("--target-device", default="android")
    sync.add_argument("--device-id", required=True)
    sync.add_argument("--retry-unconfirmed", action="store_true")
    sync.add_argument("--force-revision-id", action="append", default=[])
    sync.add_argument("--force-receipt-id", action="append", default=[])

    watch = commands.add_parser("watch")
    watch.add_argument("--local-device", default="linux")
    watch.add_argument("--target-device", default="android")
    watch.add_argument("--device-id", required=True)
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
            f"state={entry.state.value} attempts={entry.attempts} last-requested={last} "
            f"receipt={entry.receipt_id or '-'} "
            f"confirmed={entry.confirmed_utc.strftime('%Y-%m-%dT%H:%M:%SZ') if entry.confirmed_utc else '-'}",
            file=stdout,
        )
    receipt_store = ReceiptStore(repository.root)
    for entry in receipt_store.entries(target_device):
        last = (
            entry.last_requested_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
            if entry.last_requested_utc is not None
            else "-"
        )
        print(
            f"receipt={entry.receipt_id} revision={entry.revision_id} "
            f"target={entry.target_device} state={entry.state.value} "
            f"attempts={entry.attempts} last-requested={last}",
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
    receipt_store = ReceiptStore(repository.root)
    if mode == "force-retry":
        revision_ids = (
            ledger.eligible(
                arguments.target_device, force_ids=(arguments.revision_id,)
            )
            if arguments.revision_id
            else ()
        )
        receipt_ids = (
            receipt_store.eligible(
                arguments.target_device, force_ids=(arguments.receipt_id,)
            )
            if arguments.receipt_id
            else ()
        )
    else:
        revision_ids = ledger.eligible(
            arguments.target_device, retry_unconfirmed=True
        )
        receipt_ids = receipt_store.eligible(
            arguments.target_device, retry_unconfirmed=True
        )
    if not revision_ids and not receipt_ids:
        print(f"eligible=- mode={mode}", file=stdout)
    for revision_id in revision_ids:
        print(f"eligible={revision_id} mode={mode}", file=stdout)
    for receipt_id in receipt_ids:
        print(f"eligible-receipt={receipt_id} mode={mode}", file=stdout)
    return 0


def _print_scan(report, stdout) -> None:
    for revision_id in report.imported:
        print(f"imported={revision_id}", file=stdout)
    for revision_id in report.duplicates:
        print(f"duplicate={revision_id}", file=stdout)
    for revision_id in report.confirmed:
        print(f"confirmed={revision_id}", file=stdout)
    for receipt_id in report.duplicate_receipts:
        print(f"duplicate-receipt={receipt_id}", file=stdout)
    for receipt_id in report.receipts_queued:
        print(f"receipt-queued={receipt_id}", file=stdout)
    for source in report.quarantined:
        print(f"quarantined={json.dumps(source)}", file=stdout)
    for note_id in report.conflicts:
        print(f"conflict={note_id}", file=stdout)
    for error in report.errors:
        print(f"error={json.dumps(error)}", file=stdout)


def _sync(repository: Repository, arguments, stdout, adapter_factory) -> int:
    adapter = adapter_factory(arguments.device_id)
    report = sync_linux(
        repository,
        arguments.inbox,
        adapter,
        retry_unconfirmed=arguments.retry_unconfirmed,
        force_ids=tuple(arguments.force_revision_id),
        force_receipt_ids=tuple(arguments.force_receipt_id),
    )
    for revision_id in report.imports:
        print(f"imported={revision_id}", file=stdout)
    for revision_id in report.confirmations:
        print(f"confirmed={revision_id}", file=stdout)
    for note_id in report.conflicts:
        print(f"conflict={note_id}", file=stdout)
    for revision_id in report.pending:
        print(f"pending={revision_id}", file=stdout)
    for revision_id in report.requested:
        print(f"transfer-requested={revision_id}", file=stdout)
    for receipt_id in report.receipt_pending:
        print(f"receipt-pending={receipt_id}", file=stdout)
    for receipt_id in report.receipt_requested:
        print(f"receipt-transfer-requested={receipt_id}", file=stdout)
    for error in report.errors:
        print(f"error={json.dumps(error)}", file=stdout)
    return 0 if not report.errors else 1


def _watch(repository: Repository, arguments, stdin, stdout, adapter_factory) -> int:
    adapter = adapter_factory(arguments.device_id)
    ledger = DeliveryLedger(repository.root)
    receipt_store = ReceiptStore(repository.root)
    recovery = receipt_store.reconcile_confirmations(
        repository,
        ledger,
        local_device=arguments.local_device,
    )
    for revision_id in recovery.confirmed:
        print(f"recovered-confirmed={revision_id}", file=stdout)
    for error in recovery.errors:
        print(f"error={json.dumps(error)}", file=stdout)
    for path in adapter.received_paths(stdin):
        try:
            data = path.read_bytes()
            if data.startswith(b"---\nreasonnotes-receipt-format: 1\n"):
                receipt = ReceiptEnvelope.parse(data)
                outcome = receipt_store.accept(
                    data,
                    repository,
                    ledger,
                    local_device=arguments.local_device,
                    source_name=path.name,
                )
                print(
                    f"kdeconnect-transfer-complete={receipt.receipt_id} "
                    f"reasonnotes-{outcome.kind}={outcome.revision_id}",
                    file=stdout,
                )
                continue
            from .envelope import Revision

            revision = Revision.parse(data)
            observed = datetime.now(timezone.utc).replace(microsecond=0)
            ledger.record_event(
                "kdeconnect-transfer-complete",
                revision.revision_id,
                arguments.local_device,
                observed,
                f"received-path={path.name}",
            )
            outcome = repository.import_bytes(data, path.name)
            receipt_id = None
            if outcome.revision_id:
                durable = repository.revision(outcome.revision_id)
                if durable is not None and durable.origin_device != arguments.local_device:
                    receipt = receipt_store.queue(
                        durable,
                        arguments.local_device,
                        observed,
                    )
                    receipt_id = receipt.receipt_id
            print(
                f"kdeconnect-transfer-complete={revision.revision_id} "
                f"reasonnotes-{outcome.kind}={outcome.revision_id or '-'}"
                + (f" receipt-queued={receipt_id}" if receipt_id else ""),
                file=stdout,
            )
        except (OSError, EnvelopeError, DeliveryLedgerError, ReceiptError) as error:
            print(f"error={json.dumps(f'{path}: {error}')}", file=stdout)
    return 0


def main(
    argv=None,
    *,
    stdin=None,
    stdout=None,
    stderr=None,
    adapter_factory=KdeConnectLinux,
) -> int:
    stdin = sys.stdin if stdin is None else stdin
    stdout = sys.stdout if stdout is None else stdout
    stderr = sys.stderr if stderr is None else stderr
    arguments = _parser().parse_args(argv)
    repository = Repository.open(
        arguments.root,
        local_device=getattr(arguments, "local_device", None),
        target_device=getattr(arguments, "target_device", None),
    )
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
        if arguments.command == "scan":
            report = scan_inbox(repository, arguments.inbox, arguments.local_device)
            _print_scan(report, stdout)
            return 0 if not report.errors else 1
        if arguments.command == "sync":
            return _sync(repository, arguments, stdout, adapter_factory)
        if arguments.command == "watch":
            return _watch(repository, arguments, stdin, stdout, adapter_factory)
    except (
        ConflictSelectionError,
        ConflictRequired,
        DeliveryLedgerError,
        EnvelopeError,
        ReceiptError,
    ) as error:
        print(f"error={json.dumps(str(error))}", file=stderr)
        return 2
    raise AssertionError("unhandled command")

