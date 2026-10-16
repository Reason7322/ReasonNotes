"""Canonical durable-import receipt envelopes and receipt persistence."""

from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Literal
from uuid import UUID

from .delivery import DeliveryLedger, DeliveryLedgerError, DeliveryState
from .envelope import Revision


_HASH_RE = re.compile(r"[0-9a-f]{64}")
_DEVICE_RE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
_TIMESTAMP_RE = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"
)
_FIELD_ORDER = (
    "reasonnotes-receipt-format",
    "receipt-id",
    "note-id",
    "revision-id",
    "acknowledging-device",
    "destination-device",
    "imported-utc",
)


class ReceiptError(ValueError):
    """Raised when a receipt cannot be represented or validated safely."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _validated_note_id(value: UUID) -> UUID:
    if not isinstance(value, UUID):
        raise ReceiptError("note ID must be a UUID")
    return value


def _validated_hash(value: str, label: str) -> str:
    if not isinstance(value, str) or not _HASH_RE.fullmatch(value):
        raise ReceiptError(f"{label} must be a lowercase SHA-256 value")
    return value


def _validated_device(value: str, label: str) -> str:
    if not isinstance(value, str) or not _DEVICE_RE.fullmatch(value):
        raise ReceiptError(f"invalid {label} device ID")
    return value


def _canonical_timestamp(value: datetime) -> str:
    if not isinstance(value, datetime):
        raise ReceiptError("import time must be a datetime")
    if value.tzinfo is None or value.utcoffset() is None:
        raise ReceiptError("import time must be UTC")
    if value.utcoffset() != timezone.utc.utcoffset(value):
        raise ReceiptError("import time must be UTC")
    if value.microsecond:
        raise ReceiptError("import time must use whole seconds")
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _canonical_bytes(
    *,
    receipt_id: str | None,
    note_id: UUID,
    revision_id: str,
    acknowledging_device: str,
    destination_device: str,
    imported_utc: datetime,
) -> bytes:
    fields = ["---", "reasonnotes-receipt-format: 1"]
    if receipt_id is not None:
        fields.append(f"receipt-id: {receipt_id}")
    fields.extend(
        (
            f"note-id: {note_id}",
            f"revision-id: {revision_id}",
            f"acknowledging-device: {acknowledging_device}",
            f"destination-device: {destination_device}",
            f"imported-utc: {_canonical_timestamp(imported_utc)}",
            "---",
        )
    )
    return ("\n".join(fields) + "\n").encode("utf-8")


def _identity_bytes(
    *,
    note_id: UUID,
    revision_id: str,
    acknowledging_device: str,
    destination_device: str,
    imported_utc: datetime,
) -> bytes:
    return (
        "\n".join(
            (
                "reasonnotes-receipt-format: 1",
                f"note-id: {note_id}",
                f"revision-id: {revision_id}",
                f"acknowledging-device: {acknowledging_device}",
                f"destination-device: {destination_device}",
                f"imported-utc: {_canonical_timestamp(imported_utc)}",
            )
        )
        + "\n"
    ).encode("utf-8")


@dataclass(frozen=True, slots=True)
class ReceiptEnvelope:
    note_id: UUID
    revision_id: str
    receipt_id: str
    acknowledging_device: str
    destination_device: str
    imported_utc: datetime

    @classmethod
    def create(
        cls,
        note_id: UUID,
        revision_id: str,
        acknowledging_device: str,
        destination_device: str,
        imported_utc: datetime,
    ) -> "ReceiptEnvelope":
        note_id = _validated_note_id(note_id)
        revision_id = _validated_hash(revision_id, "revision ID")
        acknowledging_device = _validated_device(
            acknowledging_device, "acknowledging"
        )
        destination_device = _validated_device(destination_device, "destination")
        if acknowledging_device == destination_device:
            raise ReceiptError("receipt devices must be distinct")
        timestamp = _canonical_timestamp(imported_utc)
        normalized_time = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
        unsigned = _identity_bytes(
            note_id=note_id,
            revision_id=revision_id,
            acknowledging_device=acknowledging_device,
            destination_device=destination_device,
            imported_utc=normalized_time,
        )
        return cls(
            note_id=note_id,
            revision_id=revision_id,
            receipt_id=_sha256(unsigned),
            acknowledging_device=acknowledging_device,
            destination_device=destination_device,
            imported_utc=normalized_time,
        )

    @classmethod
    def parse(cls, data: bytes) -> "ReceiptEnvelope":
        if not isinstance(data, bytes):
            raise ReceiptError("receipt must be bytes")
        if b"\r" in data:
            raise ReceiptError("carriage returns are not canonical")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ReceiptError("receipt is not valid UTF-8") from error
        if not text.startswith("---\n"):
            raise ReceiptError("missing opening receipt marker")
        header, separator, body = text[4:].partition("\n---\n")
        if not separator:
            raise ReceiptError("missing closing receipt marker")
        if body:
            raise ReceiptError("receipt body must be empty")

        metadata: dict[str, str] = {}
        order: list[str] = []
        for line in header.split("\n"):
            if ": " not in line:
                raise ReceiptError("malformed receipt metadata line")
            key, value = line.split(": ", 1)
            if key in metadata:
                raise ReceiptError(f"repeated receipt metadata key: {key}")
            if key not in _FIELD_ORDER:
                raise ReceiptError(f"unknown receipt metadata key: {key}")
            metadata[key] = value
            order.append(key)
        if tuple(order) != _FIELD_ORDER or set(metadata) != set(_FIELD_ORDER):
            raise ReceiptError("receipt metadata is missing or not canonical")
        if metadata["reasonnotes-receipt-format"] != "1":
            raise ReceiptError("unsupported ReasonNotes receipt format")

        try:
            note_id = UUID(metadata["note-id"])
        except ValueError as error:
            raise ReceiptError("invalid receipt note UUID") from error
        if str(note_id) != metadata["note-id"]:
            raise ReceiptError("receipt note UUID is not canonical")
        receipt_id = _validated_hash(metadata["receipt-id"], "receipt ID")
        revision_id = _validated_hash(metadata["revision-id"], "revision ID")
        acknowledging_device = _validated_device(
            metadata["acknowledging-device"], "acknowledging"
        )
        destination_device = _validated_device(
            metadata["destination-device"], "destination"
        )
        if acknowledging_device == destination_device:
            raise ReceiptError("receipt devices must be distinct")

        raw_timestamp = metadata["imported-utc"]
        if not _TIMESTAMP_RE.fullmatch(raw_timestamp):
            raise ReceiptError("invalid receipt UTC timestamp")
        try:
            imported_utc = datetime.strptime(
                raw_timestamp, "%Y-%m-%dT%H:%M:%SZ"
            ).replace(tzinfo=timezone.utc)
        except ValueError as error:
            raise ReceiptError("invalid receipt UTC timestamp") from error

        expected_id = _sha256(
            _identity_bytes(
                note_id=note_id,
                revision_id=revision_id,
                acknowledging_device=acknowledging_device,
                destination_device=destination_device,
                imported_utc=imported_utc,
            )
        )
        if receipt_id != expected_id:
            raise ReceiptError("receipt SHA-256 mismatch")
        receipt = cls(
            note_id=note_id,
            revision_id=revision_id,
            receipt_id=receipt_id,
            acknowledging_device=acknowledging_device,
            destination_device=destination_device,
            imported_utc=imported_utc,
        )
        if receipt.serialize() != data:
            raise ReceiptError("receipt is not canonical")
        return receipt

    def serialize(self) -> bytes:
        return _canonical_bytes(
            receipt_id=self.receipt_id,
            note_id=self.note_id,
            revision_id=self.revision_id,
            acknowledging_device=self.acknowledging_device,
            destination_device=self.destination_device,
            imported_utc=self.imported_utc,
        )

    def transport_filename(self) -> str:
        return f"{self.receipt_id}.receipt.md"


@dataclass(frozen=True, slots=True)
class ReceiptDeliveryEntry:
    receipt_id: str
    revision_id: str
    target_device: str
    state: DeliveryState
    attempts: int
    last_requested_utc: datetime | None


@dataclass(frozen=True, slots=True)
class ReceiptImportOutcome:
    kind: Literal["confirmed", "duplicate"]
    receipt_id: str
    revision_id: str


@dataclass(frozen=True, slots=True)
class ReceiptReconcileReport:
    confirmed: tuple[str, ...]
    errors: tuple[str, ...]


class ReceiptStore:
    """Durable receipt inbox plus bounded, non-reflecting receipt outbox."""

    def __init__(self, root: Path, max_requests: int = 3):
        if not isinstance(max_requests, int) or max_requests < 1:
            raise ReceiptError("max receipt requests must be a positive integer")
        self.root = Path(root)
        self.receipts_dir = self.root / "receipts"
        self.inbox_dir = self.receipts_dir / "inbox"
        self.outbox_dir = self.receipts_dir / "outbox"
        self.state_dir = self.root / "state"
        self.delivery_path = self.state_dir / "receipt-delivery.json"
        self.delivery_lock_path = self.state_dir / "receipt-delivery.lock"
        self.max_requests = max_requests
        for directory in (
            self.root,
            self.receipts_dir,
            self.inbox_dir,
            self.outbox_dir,
            self.state_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)
        self._reconcile_outbox()

    @contextmanager
    def _delivery_lock(self):
        with self.delivery_lock_path.open("a+b") as handle:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    @staticmethod
    def _fsync_directory(directory: Path) -> None:
        descriptor = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def _atomic_write(self, path: Path, data: bytes, *, replace: bool) -> bool:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
        )
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            if replace:
                os.replace(temporary, path)
                self._fsync_directory(path.parent)
                return True
            try:
                os.link(temporary, path)
            except FileExistsError:
                if path.read_bytes() != data:
                    raise ReceiptError("stored receipt disagrees with receipt ID")
                return False
            self._fsync_directory(path.parent)
            return True
        finally:
            temporary.unlink(missing_ok=True)

    def _read_entries(self) -> dict[tuple[str, str], ReceiptDeliveryEntry]:
        if not self.delivery_path.exists():
            return {}
        try:
            document = json.loads(self.delivery_path.read_text(encoding="utf-8"))
            if document.get("format") != 1 or not isinstance(
                document.get("entries"), list
            ):
                raise ReceiptError("unsupported receipt delivery format")
            entries = {}
            for raw in document["entries"]:
                receipt_id = _validated_hash(raw["receipt_id"], "receipt ID")
                revision_id = _validated_hash(raw["revision_id"], "revision ID")
                target = _validated_device(raw["target_device"], "target")
                state = DeliveryState(raw["state"])
                if state == DeliveryState.CONFIRMED:
                    raise ReceiptError("receipt delivery cannot require a receipt")
                attempts = raw["attempts"]
                if not isinstance(attempts, int) or attempts < 0:
                    raise ReceiptError("invalid receipt request attempt count")
                raw_last = raw["last_requested_utc"]
                last = None
                if raw_last is not None:
                    if not isinstance(raw_last, str) or not _TIMESTAMP_RE.fullmatch(
                        raw_last
                    ):
                        raise ReceiptError("invalid receipt request timestamp")
                    last = datetime.strptime(
                        raw_last, "%Y-%m-%dT%H:%M:%SZ"
                    ).replace(tzinfo=timezone.utc)
                key = (receipt_id, target)
                if key in entries:
                    raise ReceiptError("duplicate receipt delivery entry")
                entries[key] = ReceiptDeliveryEntry(
                    receipt_id, revision_id, target, state, attempts, last
                )
            return entries
        except (KeyError, TypeError, ValueError, json.JSONDecodeError, OSError) as error:
            if isinstance(error, ReceiptError):
                raise
            raise ReceiptError("invalid receipt delivery state") from error

    def _write_entries(
        self, entries: dict[tuple[str, str], ReceiptDeliveryEntry]
    ) -> None:
        document = {
            "entries": [
                {
                    "attempts": entry.attempts,
                    "last_requested_utc": (
                        _canonical_timestamp(entry.last_requested_utc)
                        if entry.last_requested_utc is not None
                        else None
                    ),
                    "receipt_id": entry.receipt_id,
                    "revision_id": entry.revision_id,
                    "state": entry.state.value,
                    "target_device": entry.target_device,
                }
                for entry in sorted(
                    entries.values(),
                    key=lambda item: (item.target_device, item.receipt_id),
                )
            ],
            "format": 1,
        }
        self._atomic_write(
            self.delivery_path,
            (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8"),
            replace=True,
        )

    def _reconcile_outbox(self) -> None:
        with self._delivery_lock():
            entries = self._read_entries()
            changed = False
            for path in sorted(self.outbox_dir.glob("*/*.receipt.md")):
                receipt = ReceiptEnvelope.parse(path.read_bytes())
                if path.parent.name != receipt.destination_device:
                    raise ReceiptError("receipt outbox path has wrong destination")
                key = (receipt.receipt_id, receipt.destination_device)
                if key not in entries:
                    entries[key] = ReceiptDeliveryEntry(
                        receipt.receipt_id,
                        receipt.revision_id,
                        receipt.destination_device,
                        DeliveryState.PENDING,
                        0,
                        None,
                    )
                    changed = True
            if changed:
                self._write_entries(entries)

    def inbox_path(self, receipt_id: str) -> Path:
        receipt_id = _validated_hash(receipt_id, "receipt ID")
        return self.inbox_dir / f"{receipt_id}.receipt.md"

    def outbox_path(self, receipt_id: str) -> Path:
        receipt_id = _validated_hash(receipt_id, "receipt ID")
        matching = tuple(
            self.outbox_dir.glob(f"*/{receipt_id}.receipt.md")
        )
        if len(matching) != 1:
            raise ReceiptError("receipt is missing from outbox")
        return matching[0]

    def forget_revisions(self, revision_ids: set[str]) -> None:
        """Remove obsolete receipt metadata and queued envelopes for purged notes."""
        if not revision_ids:
            return
        ids = {_validated_hash(revision, "revision ID") for revision in revision_ids}
        with self._delivery_lock():
            entries = self._read_entries()
            retained = {
                key: entry for key, entry in entries.items()
                if entry.revision_id not in ids
            }
            if len(retained) != len(entries):
                self._write_entries(retained)
            for path in self.outbox_dir.glob("*/*.receipt.md"):
                receipt = ReceiptEnvelope.parse(path.read_bytes())
                if receipt.revision_id in ids:
                    path.unlink()
                    self._fsync_directory(path.parent)
            for path in self.inbox_dir.glob("*.receipt.md"):
                receipt = ReceiptEnvelope.parse(path.read_bytes())
                if receipt.revision_id in ids:
                    path.unlink()
                    self._fsync_directory(path.parent)

    def queue(
        self,
        revision: Revision,
        acknowledging_device: str,
        imported_utc: datetime,
    ) -> ReceiptEnvelope:
        acknowledging_device = _validated_device(
            acknowledging_device, "acknowledging"
        )
        with self._delivery_lock():
            entries = self._read_entries()
            existing = tuple(
                entry
                for entry in entries.values()
                if entry.revision_id == revision.revision_id
                and entry.target_device == revision.origin_device
            )
            if existing:
                if len(existing) != 1:
                    raise ReceiptError("multiple receipts queued for one revision")
                return ReceiptEnvelope.parse(
                    self.outbox_path(existing[0].receipt_id).read_bytes()
                )
            receipt = ReceiptEnvelope.create(
                note_id=revision.note_id,
                revision_id=revision.revision_id,
                acknowledging_device=acknowledging_device,
                destination_device=revision.origin_device,
                imported_utc=imported_utc,
            )
            path = (
                self.outbox_dir
                / receipt.destination_device
                / receipt.transport_filename()
            )
            self._atomic_write(path, receipt.serialize(), replace=False)
            entries[(receipt.receipt_id, receipt.destination_device)] = (
                ReceiptDeliveryEntry(
                    receipt.receipt_id,
                    receipt.revision_id,
                    receipt.destination_device,
                    DeliveryState.PENDING,
                    0,
                    None,
                )
            )
            self._write_entries(entries)
            return receipt

    def entry(
        self, receipt_id: str, target_device: str
    ) -> ReceiptDeliveryEntry | None:
        key = (
            _validated_hash(receipt_id, "receipt ID"),
            _validated_device(target_device, "target"),
        )
        with self._delivery_lock():
            return self._read_entries().get(key)

    def entries(
        self, target_device: str | None = None
    ) -> tuple[ReceiptDeliveryEntry, ...]:
        if target_device is not None:
            target_device = _validated_device(target_device, "target")
        with self._delivery_lock():
            return tuple(
                sorted(
                    (
                        entry
                        for entry in self._read_entries().values()
                        if target_device is None or entry.target_device == target_device
                    ),
                    key=lambda item: (item.target_device, item.receipt_id),
                )
            )

    def eligible(
        self,
        target_device: str,
        retry_unconfirmed: bool = False,
        force_ids=(),
    ) -> tuple[str, ...]:
        target_device = _validated_device(target_device, "target")
        with self._delivery_lock():
            entries = self._read_entries()
            forced = tuple(force_ids)
            if forced:
                forced_set = {
                    _validated_hash(receipt_id, "receipt ID") for receipt_id in forced
                }
                return tuple(
                    sorted(
                        receipt_id
                        for receipt_id in forced_set
                        if (receipt_id, target_device) in entries
                    )
                )
            return tuple(
                sorted(
                    entry.receipt_id
                    for entry in entries.values()
                    if entry.target_device == target_device
                    and (
                        entry.state == DeliveryState.PENDING
                        or (
                            retry_unconfirmed
                            and entry.state == DeliveryState.TRANSFER_REQUESTED
                            and entry.attempts < self.max_requests
                        )
                    )
                )
            )

    def record_requested(
        self, receipt_id: str, target_device: str, observed_utc: datetime
    ) -> None:
        receipt_id = _validated_hash(receipt_id, "receipt ID")
        target_device = _validated_device(target_device, "target")
        _canonical_timestamp(observed_utc)
        with self._delivery_lock():
            entries = self._read_entries()
            key = (receipt_id, target_device)
            existing = entries.get(key)
            if existing is None:
                raise ReceiptError("receipt is not registered for this target")
            attempts = existing.attempts + 1
            state = (
                DeliveryState.NEEDS_ATTENTION
                if attempts >= self.max_requests
                else DeliveryState.TRANSFER_REQUESTED
            )
            entries[key] = ReceiptDeliveryEntry(
                existing.receipt_id,
                existing.revision_id,
                existing.target_device,
                state,
                attempts,
                observed_utc,
            )
            self._write_entries(entries)

    @staticmethod
    def _validate_confirmation(
        receipt: ReceiptEnvelope,
        repository,
        ledger: DeliveryLedger,
        local_device: str,
    ) -> None:
        local_device = _validated_device(local_device, "local")
        if receipt.destination_device != local_device:
            raise ReceiptError("receipt is addressed to another device")
        revision = repository.revision(receipt.revision_id)
        if revision is None:
            raise ReceiptError("receipt names an unknown revision")
        if revision.note_id != receipt.note_id:
            raise ReceiptError("receipt note and revision identities disagree")
        entry = ledger.entry(receipt.revision_id, receipt.acknowledging_device)
        if entry is None:
            raise ReceiptError("receipt peer has no delivery entry")
        if entry.attempts < 1:
            raise ReceiptError("revision was never requested to receipt peer")
        # A peer can acknowledge an already imported revision again. Receipt
        # identity also includes imported_utc, so this second, otherwise
        # valid acknowledgment has a different hash. Preserve the original
        # ledger confirmation; do not weaken DeliveryLedger.record_confirmed.

    @staticmethod
    def _redundant_confirmation(
        receipt: ReceiptEnvelope, ledger: DeliveryLedger
    ) -> bool:
        entry = ledger.entry(receipt.revision_id, receipt.acknowledging_device)
        return bool(
            entry
            and entry.state == DeliveryState.CONFIRMED
            and entry.receipt_id != receipt.receipt_id
        )

    def accept(
        self,
        data: bytes,
        repository,
        ledger: DeliveryLedger,
        *,
        local_device: str,
        source_name: str,
        confirmed_utc: datetime | None = None,
    ) -> ReceiptImportOutcome:
        del source_name  # Transport names are never trusted for identity.
        receipt = ReceiptEnvelope.parse(data)
        self._validate_confirmation(receipt, repository, ledger, local_device)
        if self._redundant_confirmation(receipt, ledger):
            return ReceiptImportOutcome("duplicate", receipt.receipt_id, receipt.revision_id)
        created = self._atomic_write(
            self.inbox_path(receipt.receipt_id), receipt.serialize(), replace=False
        )
        observed = confirmed_utc or datetime.now(timezone.utc).replace(microsecond=0)
        changed = ledger.record_confirmed(
            receipt.revision_id,
            receipt.acknowledging_device,
            receipt.receipt_id,
            observed,
            observer_device=local_device,
        )
        return ReceiptImportOutcome(
            "confirmed" if created and changed else "duplicate",
            receipt.receipt_id,
            receipt.revision_id,
        )

    def reconcile_confirmations(
        self,
        repository,
        ledger: DeliveryLedger,
        *,
        local_device: str,
        confirmed_utc: datetime | None = None,
    ) -> ReceiptReconcileReport:
        confirmed = []
        errors = []
        observed = confirmed_utc or datetime.now(timezone.utc).replace(microsecond=0)
        for path in sorted(self.inbox_dir.glob("*.receipt.md")):
            try:
                receipt = ReceiptEnvelope.parse(path.read_bytes())
                self._validate_confirmation(receipt, repository, ledger, local_device)
                if self._redundant_confirmation(receipt, ledger):
                    continue
                if ledger.record_confirmed(
                    receipt.revision_id,
                    receipt.acknowledging_device,
                    receipt.receipt_id,
                    observed,
                    observer_device=local_device,
                ):
                    confirmed.append(receipt.revision_id)
            except (OSError, ReceiptError, DeliveryLedgerError) as error:
                errors.append(f"{path}: {error}")
        return ReceiptReconcileReport(tuple(confirmed), tuple(errors))
