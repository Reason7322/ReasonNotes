"""Scan-before-send Linux synchronization orchestration."""

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .delivery import DeliveryLedger, DeliveryState
from .repository import Repository


_FORMAT_MARKER = b"---\nreasonnotes-format: 1\n"


@dataclass(frozen=True, slots=True)
class ScanReport:
    imported: tuple[str, ...]
    duplicates: tuple[str, ...]
    quarantined: tuple[str, ...]
    conflicts: tuple[str, ...]
    errors: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class SyncReport:
    imports: tuple[str, ...]
    conflicts: tuple[str, ...]
    pending: tuple[str, ...]
    requested: tuple[str, ...]
    errors: tuple[str, ...]


def scan_inbox(repository: Repository, inbox: Path, local_device: str) -> ScanReport:
    del local_device  # The configured repository records the durable import observer.
    imported = []
    duplicates = []
    quarantined = []
    errors = []
    inbox = Path(inbox)
    if not inbox.is_dir():
        return ScanReport((), (), (), (), (f"inbox is not a directory: {inbox}",))
    for path in sorted(inbox.iterdir()):
        if not path.is_file() or path.suffix.lower() != ".md":
            continue
        try:
            data = path.read_bytes()
        except OSError as error:
            errors.append(f"{path}: {error}")
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
    requested = []
    errors = list(scan.errors)
    if eligible and not adapter.reachable():
        errors.append(f"KDE Connect device unavailable: {adapter.device_id}")
    elif eligible:
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
    pending = tuple(
        entry.revision_id
        for entry in ledger.entries(target_device)
        if entry.state == DeliveryState.PENDING
    )
    return SyncReport(
        imports=scan.imported,
        conflicts=scan.conflicts,
        pending=pending,
        requested=tuple(requested),
        errors=tuple(errors),
    )
