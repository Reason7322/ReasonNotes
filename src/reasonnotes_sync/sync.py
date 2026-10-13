"""Scan-before-send Linux synchronization orchestration."""

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .delivery import DeliveryLedger, DeliveryState
from .receipt import ReceiptError, ReceiptStore
from .repository import Repository


_FORMAT_MARKER = b"---\nreasonnotes-format: 1\n"
_RECEIPT_FORMAT_MARKER = b"---\nreasonnotes-receipt-format: 1\n"


@dataclass(frozen=True, slots=True)
class ScanReport:
    imported: tuple[str, ...]
    duplicates: tuple[str, ...]
    confirmed: tuple[str, ...]
    duplicate_receipts: tuple[str, ...]
    receipts_queued: tuple[str, ...]
    quarantined: tuple[str, ...]
    conflicts: tuple[str, ...]
    errors: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class SyncReport:
    imports: tuple[str, ...]
    confirmations: tuple[str, ...]
    conflicts: tuple[str, ...]
    pending: tuple[str, ...]
    requested: tuple[str, ...]
    receipt_pending: tuple[str, ...]
    receipt_requested: tuple[str, ...]
    errors: tuple[str, ...]


def scan_inbox(repository: Repository, inbox: Path, local_device: str) -> ScanReport:
    imported = []
    duplicates = []
    confirmed = []
    duplicate_receipts = []
    receipts_queued = []
    quarantined = []
    errors = []
    ledger = DeliveryLedger(repository.root)
    receipt_store = ReceiptStore(repository.root)
    reconciliation = receipt_store.reconcile_confirmations(
        repository,
        ledger,
        local_device=local_device,
    )
    confirmed.extend(reconciliation.confirmed)
    errors.extend(reconciliation.errors)
    inbox = Path(inbox)
    if not inbox.is_dir():
        return ScanReport(
            (),
            (),
            tuple(confirmed),
            (),
            (),
            (),
            (),
            tuple((*errors, f"inbox is not a directory: {inbox}")),
        )
    for path in sorted(inbox.iterdir()):
        if not path.is_file() or path.suffix.lower() != ".md":
            continue
        try:
            data = path.read_bytes()
        except OSError as error:
            errors.append(f"{path}: {error}")
            continue
        if data.startswith(_RECEIPT_FORMAT_MARKER):
            try:
                outcome = receipt_store.accept(
                    data,
                    repository,
                    ledger,
                    local_device=local_device,
                    source_name=path.name,
                )
            except ReceiptError as error:
                errors.append(f"{path}: {error}")
                continue
            if outcome.kind == "confirmed":
                confirmed.append(outcome.revision_id)
            else:
                duplicate_receipts.append(outcome.receipt_id)
            continue
        if not data.startswith(_FORMAT_MARKER):
            continue
        outcome = repository.import_bytes(data, path.name)
        if outcome.kind == "imported" and outcome.revision_id:
            imported.append(outcome.revision_id)
        elif outcome.kind == "duplicate" and outcome.revision_id:
            duplicates.append(outcome.revision_id)
        else:
            quarantined.append(path.name)
            errors.append(f"{path}: {outcome.detail}")
            continue
        if outcome.revision_id:
            revision = repository.revision(outcome.revision_id)
            if revision is not None and revision.origin_device != local_device:
                already_queued = any(
                    entry.revision_id == revision.revision_id
                    for entry in receipt_store.entries(revision.origin_device)
                )
                receipt = receipt_store.queue(
                    revision,
                    local_device,
                    datetime.now(timezone.utc).replace(microsecond=0),
                )
                if not already_queued:
                    receipts_queued.append(receipt.receipt_id)
    graph = repository.graph()
    conflicts = tuple(
        sorted(
            str(note_id)
            for note_id in {revision.note_id for revision in repository.revisions()}
            if graph.status(note_id).conflicted
        )
    )
    return ScanReport(
        tuple(imported),
        tuple(duplicates),
        tuple(dict.fromkeys(confirmed)),
        tuple(duplicate_receipts),
        tuple(receipts_queued),
        tuple(quarantined),
        conflicts,
        tuple(errors),
    )


def sync_linux(
    repository: Repository,
    inbox: Path,
    adapter,
    retry_unconfirmed: bool = False,
    force_ids=(),
    force_receipt_ids=(),
) -> SyncReport:
    local_device = repository.local_device or "linux"
    target_device = repository.target_device
    if target_device is None:
        raise ValueError("repository must configure a target device for sync")
    scan = scan_inbox(repository, inbox, local_device)
    ledger = DeliveryLedger(repository.root)
    eligible = ledger.eligible(
        target_device,
        retry_unconfirmed=retry_unconfirmed,
        force_ids=force_ids,
    )
    receipt_store = ReceiptStore(repository.root)
    eligible_receipts = receipt_store.eligible(
        target_device,
        retry_unconfirmed=retry_unconfirmed,
        force_ids=force_receipt_ids,
    )
    requested = []
    receipt_requested = []
    errors = list(scan.errors)
    if (eligible or eligible_receipts) and not adapter.reachable():
        errors.append(f"KDE Connect device unavailable: {adapter.device_id}")
    elif eligible or eligible_receipts:
        for revision_id in eligible:
            revision = repository.revision(revision_id)
            if revision is None:
                errors.append(f"outbox revision missing from history: {revision_id}")
                continue
            path = (
                repository.history_dir
                / str(revision.note_id)
                / f"{revision.revision_id}.md"
            )
            result = adapter.request_transfer(path)
            if not result.accepted:
                errors.append(f"{revision_id}: {result.detail}")
                continue
            observed = datetime.now(timezone.utc).replace(microsecond=0)
            ledger.record_requested(
                revision_id,
                target_device,
                observed,
                observer_device=local_device,
            )
            requested.append(revision_id)
        for receipt_id in eligible_receipts:
            try:
                path = receipt_store.outbox_path(receipt_id)
            except ReceiptError as error:
                errors.append(f"receipt {receipt_id}: {error}")
                continue
            result = adapter.request_transfer(path)
            if not result.accepted:
                errors.append(f"receipt {receipt_id}: {result.detail}")
                continue
            observed = datetime.now(timezone.utc).replace(microsecond=0)
            receipt_store.record_requested(receipt_id, target_device, observed)
            receipt_requested.append(receipt_id)
    pending = tuple(
        entry.revision_id
        for entry in ledger.entries(target_device)
        if entry.state == DeliveryState.PENDING
    )
    receipt_pending = tuple(
        entry.receipt_id
        for entry in receipt_store.entries(target_device)
        if entry.state == DeliveryState.PENDING
    )
    return SyncReport(
        imports=scan.imported,
        confirmations=scan.confirmed,
        conflicts=scan.conflicts,
        pending=pending,
        requested=tuple(requested),
        receipt_pending=receipt_pending,
        receipt_requested=tuple(receipt_requested),
        errors=tuple(errors),
    )
