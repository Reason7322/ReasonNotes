"""Bounded delivery intent tracking and non-equivalent event evidence."""

from dataclasses import dataclass
from datetime import datetime, timezone
from enum import StrEnum
import json
import os
from pathlib import Path
import re
import tempfile


_HASH_RE = re.compile(r"[0-9a-f]{64}")
_DEVICE_RE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")


class DeliveryState(StrEnum):
    PENDING = "pending"
    TRANSFER_REQUESTED = "transfer-requested"
    NEEDS_ATTENTION = "needs-attention"


class EventKind(StrEnum):
    TRANSFER_REQUESTED = "transfer-requested"
    KDECONNECT_TRANSFER_COMPLETE = "kdeconnect-transfer-complete"
    REASONNOTES_IMPORTED = "reasonnotes-imported"


class DeliveryLedgerError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class DeliveryEntry:
    revision_id: str
    target_device: str
    state: DeliveryState
    attempts: int
    last_requested_utc: datetime | None


@dataclass(frozen=True, slots=True)
class EventRecord:
    kind: EventKind
    revision_id: str
    observer_device: str
    observed_utc: datetime
    detail: str


def _validate_hash(value: str) -> str:
    if not isinstance(value, str) or not _HASH_RE.fullmatch(value):
        raise DeliveryLedgerError("revision ID must be a lowercase SHA-256 value")
    return value


def _validate_device(value: str) -> str:
    if not isinstance(value, str) or not _DEVICE_RE.fullmatch(value):
        raise DeliveryLedgerError("invalid device ID")
    return value


def _timestamp(value: datetime) -> str:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timezone.utc.utcoffset(value)
        or value.microsecond
    ):
        raise DeliveryLedgerError("event time must be UTC with whole seconds")
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_timestamp(value: str) -> datetime:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except (TypeError, ValueError) as error:
        raise DeliveryLedgerError("invalid persisted UTC timestamp") from error
    if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
        raise DeliveryLedgerError("noncanonical persisted UTC timestamp")
    return parsed


class DeliveryLedger:
    def __init__(self, root: Path, max_requests: int = 3):
        if not isinstance(max_requests, int) or max_requests < 1:
            raise DeliveryLedgerError("max requests must be a positive integer")
        self.root = Path(root)
        self.state_dir = self.root / "state"
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.delivery_path = self.state_dir / "delivery.json"
        self.events_path = self.state_dir / "events.jsonl"
        self.max_requests = max_requests

    @staticmethod
    def _fsync_directory(directory: Path) -> None:
        descriptor = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def _atomic_write(self, path: Path, data: bytes) -> None:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
        )
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
            self._fsync_directory(path.parent)
        finally:
            temporary.unlink(missing_ok=True)

    def _read(self) -> dict[tuple[str, str], DeliveryEntry]:
        if not self.delivery_path.exists():
            return {}
        try:
            document = json.loads(self.delivery_path.read_text(encoding="utf-8"))
            if document.get("format") != 1 or not isinstance(document.get("entries"), list):
                raise DeliveryLedgerError("unsupported delivery ledger format")
            entries: dict[tuple[str, str], DeliveryEntry] = {}
            for raw in document["entries"]:
                revision_id = _validate_hash(raw["revision_id"])
                target = _validate_device(raw["target_device"])
                state = DeliveryState(raw["state"])
                attempts = raw["attempts"]
                if not isinstance(attempts, int) or attempts < 0:
                    raise DeliveryLedgerError("invalid request attempt count")
                raw_last = raw["last_requested_utc"]
                last = None if raw_last is None else _parse_timestamp(raw_last)
                key = (revision_id, target)
                if key in entries:
                    raise DeliveryLedgerError("duplicate delivery ledger entry")
                entries[key] = DeliveryEntry(revision_id, target, state, attempts, last)
            return entries
        except (KeyError, TypeError, json.JSONDecodeError, OSError) as error:
            if isinstance(error, DeliveryLedgerError):
                raise
            raise DeliveryLedgerError("invalid delivery ledger") from error

    def _write(self, entries: dict[tuple[str, str], DeliveryEntry]) -> None:
        document = {
            "entries": [
                {
                    "attempts": entry.attempts,
                    "last_requested_utc": (
                        _timestamp(entry.last_requested_utc)
                        if entry.last_requested_utc is not None
                        else None
                    ),
                    "revision_id": entry.revision_id,
                    "state": entry.state.value,
                    "target_device": entry.target_device,
                }
                for entry in sorted(
                    entries.values(), key=lambda item: (item.target_device, item.revision_id)
                )
            ],
            "format": 1,
        }
        self._atomic_write(
            self.delivery_path,
            (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        )

    def register_local(self, revision_id: str, target_device: str) -> None:
        revision_id = _validate_hash(revision_id)
        target_device = _validate_device(target_device)
        entries = self._read()
        key = (revision_id, target_device)
        if key in entries:
            return
        entries[key] = DeliveryEntry(
            revision_id, target_device, DeliveryState.PENDING, 0, None
        )
        self._write(entries)

    def entries(self, target_device: str | None = None) -> tuple[DeliveryEntry, ...]:
        if target_device is not None:
            target_device = _validate_device(target_device)
        values = self._read().values()
        return tuple(
            sorted(
                (
                    entry
                    for entry in values
                    if target_device is None or entry.target_device == target_device
                ),
                key=lambda item: (item.target_device, item.revision_id),
            )
        )

    def entry(self, revision_id: str, target_device: str) -> DeliveryEntry | None:
        return self._read().get(
            (_validate_hash(revision_id), _validate_device(target_device))
        )

    def eligible(
        self,
        target_device: str,
        retry_unconfirmed: bool = False,
        force_ids=(),
    ) -> tuple[str, ...]:
        target_device = _validate_device(target_device)
        entries = self._read()
        forced = tuple(force_ids)
        if forced:
            forced_set = {_validate_hash(revision_id) for revision_id in forced}
            return tuple(
                sorted(
                    revision_id
                    for revision_id in forced_set
                    if (revision_id, target_device) in entries
                )
            )
        eligible_ids = []
        for entry in entries.values():
            if entry.target_device != target_device:
                continue
            if entry.state == DeliveryState.PENDING:
                eligible_ids.append(entry.revision_id)
            elif (
                retry_unconfirmed
                and entry.state == DeliveryState.TRANSFER_REQUESTED
                and entry.attempts < self.max_requests
            ):
                eligible_ids.append(entry.revision_id)
        return tuple(sorted(eligible_ids))

    def record_requested(
        self,
        revision_id: str,
        target_device: str,
        observed_utc: datetime,
        observer_device: str = "local",
    ) -> None:
        revision_id = _validate_hash(revision_id)
        target_device = _validate_device(target_device)
        _timestamp(observed_utc)
        entries = self._read()
        key = (revision_id, target_device)
        existing = entries.get(key)
        if existing is None:
            raise DeliveryLedgerError("revision is not registered for this target")
        attempts = existing.attempts + 1
        state = (
            DeliveryState.NEEDS_ATTENTION
            if attempts >= self.max_requests
            else DeliveryState.TRANSFER_REQUESTED
        )
        entries[key] = DeliveryEntry(
            revision_id, target_device, state, attempts, observed_utc
        )
        self._write(entries)
        self.record_event(
            EventKind.TRANSFER_REQUESTED,
            revision_id,
            observer_device,
            observed_utc,
            f"target-device={target_device}",
        )

    def record_event(
        self,
        kind: EventKind,
        revision_id: str,
        observer_device: str,
        observed_utc: datetime,
        detail: str,
    ) -> None:
        try:
            kind = EventKind(kind)
        except ValueError as error:
            raise DeliveryLedgerError("invalid event kind") from error
        revision_id = _validate_hash(revision_id)
        observer_device = _validate_device(observer_device)
        timestamp = _timestamp(observed_utc)
        if not isinstance(detail, str) or "\n" in detail or "\r" in detail:
            raise DeliveryLedgerError("event detail must be one line")
        record = {
            "detail": detail,
            "kind": kind.value,
            "observed_utc": timestamp,
            "observer_device": observer_device,
            "revision_id": revision_id,
        }
        with self.events_path.open("ab") as handle:
            handle.write((json.dumps(record, sort_keys=True) + "\n").encode("utf-8"))
            handle.flush()
            os.fsync(handle.fileno())
        self._fsync_directory(self.state_dir)

    def events(self) -> tuple[EventRecord, ...]:
        if not self.events_path.exists():
            return ()
        events = []
        try:
            for line in self.events_path.read_text(encoding="utf-8").splitlines():
                raw = json.loads(line)
                events.append(
                    EventRecord(
                        kind=EventKind(raw["kind"]),
                        revision_id=_validate_hash(raw["revision_id"]),
                        observer_device=_validate_device(raw["observer_device"]),
                        observed_utc=_parse_timestamp(raw["observed_utc"]),
                        detail=raw["detail"],
                    )
                )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError, OSError) as error:
            raise DeliveryLedgerError("invalid delivery event log") from error
        return tuple(events)
