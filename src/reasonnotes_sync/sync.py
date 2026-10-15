"""Scan-before-send Linux synchronization orchestration."""

from collections.abc import Collection
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import re
from uuid import UUID

from .bundle import (
    BundleError,
    MAX_ARCHIVE_BYTES,
    is_bundle_name,
    read_bundle,
)
from .delivery import DeliveryLedger, DeliveryState
from .receipt import ReceiptError, ReceiptStore
from .repository import Repository


_FORMAT_MARKER = b"---\nreasonnotes-format: 1\n"
_RECEIPT_FORMAT_MARKER = b"---\nreasonnotes-receipt-format: 1\n"


def _hash_name(value: str) -> bool:
    return len(value) == 64 and all(char in "0123456789abcdef" for char in value)


def _extensionless_kde_name(name: str) -> bool:
    # KDE Connect can receive multi-share FileProvider URIs with a numeric
    # transfer name rather than the original .md filename.
    return _hash_name(name) or (name.isascii() and name.isdigit() and 12 <= len(name) <= 16)


def _incoming_files(directory: Path, *, downloads: bool):
    """Yield likely received envelopes; do not touch unrelated downloaded files."""
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.is_symlink():
            continue
        name = path.name
        if path.suffix.lower() == ".md":
            stem = path.stem
            if downloads:
                # KDE Connect appends " (1)" when an existing filename collides.
                match = re.fullmatch(r"(.+) \(([1-9][0-9]*)\)", stem)
                if match:
                    stem = match.group(1)
                canonical_id = stem.removesuffix(".receipt")
                if not (_hash_name(canonical_id) or stem.startswith("reasonnotes-")):
                    continue
        elif path.suffix.lower() == ".zip":
            if not is_bundle_name(name):
                continue
        elif not (not path.suffix and _extensionless_kde_name(name)):
            continue
        yield path


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


def scan_inbox(
    repository: Repository,
    inbox: Path,
    local_device: str,
    received_dir: Path | None = None,
) -> ScanReport:
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
    directories = [(inbox, False)]
    if received_dir is not None and Path(received_dir).resolve() != inbox.resolve():
        directories.append((Path(received_dir), True))
    candidates = []
    for directory, is_downloads in directories:
        if not directory.is_dir():
            errors.append(f"received directory is not a directory: {directory}")
            continue
        candidates.extend(_incoming_files(directory, downloads=is_downloads))

    incoming = []
    for path in candidates:
        try:
            if path.suffix.lower() == ".zip" and path.stat().st_size > MAX_ARCHIVE_BYTES:
                raise BundleError("bundle exceeds archive size limit")
            data = path.read_bytes()
            members = read_bundle(data, path.name) if is_bundle_name(path.name) else ((path.name, data),)
            incoming.extend((f"{path}!{name}", name, item) for name, item in members)
        except (OSError, BundleError) as error:
            errors.append(f"{path}: {error}")

    for source, source_name, data in incoming:
        if data.startswith(_RECEIPT_FORMAT_MARKER):
            try:
                outcome = receipt_store.accept(
                    data,
                    repository,
                    ledger,
                    local_device=local_device,
                    source_name=source_name,
                )
            except ReceiptError as error:
                errors.append(f"{source}: {error}")
                continue
            if outcome.kind == "confirmed":
                confirmed.append(outcome.revision_id)
            else:
                duplicate_receipts.append(outcome.receipt_id)
            continue
        if not data.startswith(_FORMAT_MARKER):
            continue
        outcome = repository.import_bytes(data, source_name)
        if outcome.kind == "imported" and outcome.revision_id:
            imported.append(outcome.revision_id)
        elif outcome.kind == "duplicate" and outcome.revision_id:
            duplicates.append(outcome.revision_id)
        else:
            quarantined.append(source_name)
            errors.append(f"{source}: {outcome.detail}")
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
    selected_note_ids: Collection[UUID] | None = None,
    received_dir: Path | None = None,
) -> SyncReport:
    local_device = repository.local_device or "linux"
    target_device = repository.target_device
    if target_device is None:
        raise ValueError("repository must configure a target device for sync")
    scan = scan_inbox(repository, inbox, local_device, received_dir=received_dir)
    ledger = DeliveryLedger(repository.root)
    forced_revision_ids = tuple(force_ids)
    all_eligible = ledger.eligible(
        target_device,
        retry_unconfirmed=retry_unconfirmed,
        force_ids=forced_revision_ids,
    )
    selected = (
        None
        if selected_note_ids is None or forced_revision_ids
        else frozenset(selected_note_ids)
    )
    if selected is not None and any(not isinstance(note_id, UUID) for note_id in selected):
        raise ValueError("selected note IDs must be UUID values")
    missing_revisions = tuple(
        revision_id
        for revision_id in all_eligible
        if repository.revision(revision_id) is None
    )
    eligible = tuple(
        revision_id
        for revision_id in all_eligible
        if (
            (revision := repository.revision(revision_id)) is not None
            and (selected is None or revision.note_id in selected)
        )
    )
    receipt_store = ReceiptStore(repository.root)
    eligible_receipts = receipt_store.eligible(
        target_device,
        retry_unconfirmed=retry_unconfirmed,
        force_ids=force_receipt_ids,
    )
    requested = []
    receipt_requested = []
    errors = [
        *scan.errors,
        *(
            f"outbox revision missing from history: {revision_id}"
            for revision_id in missing_revisions
        ),
    ]
    if (eligible or eligible_receipts) and not adapter.reachable():
        errors.append(f"KDE Connect device unavailable: {adapter.device_id}")
    elif eligible or eligible_receipts:
        for revision_id in eligible:
            revision = repository.revision(revision_id)
            assert revision is not None
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
