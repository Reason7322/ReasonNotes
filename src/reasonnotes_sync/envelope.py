"""Canonical, human-readable immutable note revision envelopes."""

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import re
from uuid import UUID


_HASH_RE = re.compile(r"[0-9a-f]{64}")
_DEVICE_RE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
_TIMESTAMP_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
_FIELD_ORDER = (
    "reasonnotes-format",
    "note-id",
    "revision-id",
    "parents",
    "origin-device",
    "modified-utc",
    "deleted",
    "body-sha256",
)


class EnvelopeError(ValueError):
    """Raised when a revision cannot be represented or validated safely."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_timestamp(value: datetime) -> str:
    if not isinstance(value, datetime):
        raise EnvelopeError("modified time must be a datetime")
    if value.tzinfo is None or value.utcoffset() is None:
        raise EnvelopeError("modified time must be UTC")
    if value.utcoffset() != timezone.utc.utcoffset(value):
        raise EnvelopeError("modified time must be UTC")
    if value.microsecond:
        raise EnvelopeError("modified time must use whole seconds")
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _validated_note_id(value: UUID) -> UUID:
    if not isinstance(value, UUID):
        raise EnvelopeError("note ID must be a UUID")
    return value


def _validated_parents(values) -> tuple[str, ...]:
    try:
        parents = tuple(values)
    except TypeError as error:
        raise EnvelopeError("parents must be an iterable of SHA-256 values") from error
    if any(not isinstance(parent, str) or not _HASH_RE.fullmatch(parent) for parent in parents):
        raise EnvelopeError("parent must be a lowercase SHA-256 value")
    if len(set(parents)) != len(parents):
        raise EnvelopeError("duplicate parent revision")
    return tuple(sorted(parents))


def _validated_origin_device(value: str) -> str:
    if not isinstance(value, str) or not _DEVICE_RE.fullmatch(value):
        raise EnvelopeError("invalid origin device ID")
    return value


def _canonical_bytes(
    *,
    note_id: UUID,
    revision_id: str | None,
    parents: tuple[str, ...],
    origin_device: str,
    modified_utc: datetime,
    deleted: bool,
    body_sha256: str,
    body: str,
) -> bytes:
    fields = [
        "---",
        "reasonnotes-format: 1",
        f"note-id: {note_id}",
    ]
    if revision_id is not None:
        fields.append(f"revision-id: {revision_id}")
    fields.extend(
        (
            f"parents: {','.join(parents)}",
            f"origin-device: {origin_device}",
            f"modified-utc: {_canonical_timestamp(modified_utc)}",
            f"deleted: {'true' if deleted else 'false'}",
            f"body-sha256: {body_sha256}",
            "---",
        )
    )
    return ("\n".join(fields) + "\n" + body).encode("utf-8")


@dataclass(frozen=True, slots=True)
class Revision:
    """A validated immutable ReasonNotes note revision."""

    note_id: UUID
    revision_id: str
    parents: tuple[str, ...]
    origin_device: str
    modified_utc: datetime
    deleted: bool
    body_sha256: str
    body: str

    @classmethod
    def create(
        cls,
        note_id: UUID,
        parents,
        origin_device: str,
        modified_utc: datetime,
        deleted: bool,
        body: str,
    ) -> "Revision":
        note_id = _validated_note_id(note_id)
        parents = _validated_parents(parents)
        origin_device = _validated_origin_device(origin_device)
        timestamp = _canonical_timestamp(modified_utc)
        if type(deleted) is not bool:
            raise EnvelopeError("deleted must be a boolean")
        if not isinstance(body, str):
            raise EnvelopeError("body must be text")
        if "\r" in body:
            raise EnvelopeError("body must not contain carriage returns")
        if deleted and body:
            raise EnvelopeError("tombstone body must be empty")

        normalized_time = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
        body_sha256 = _sha256(body.encode("utf-8"))
        unsigned = _canonical_bytes(
            note_id=note_id,
            revision_id=None,
            parents=parents,
            origin_device=origin_device,
            modified_utc=normalized_time,
            deleted=deleted,
            body_sha256=body_sha256,
            body=body,
        )
        return cls(
            note_id=note_id,
            revision_id=_sha256(unsigned),
            parents=parents,
            origin_device=origin_device,
            modified_utc=normalized_time,
            deleted=deleted,
            body_sha256=body_sha256,
            body=body,
        )

    @classmethod
    def parse(cls, data: bytes) -> "Revision":
        if not isinstance(data, bytes):
            raise EnvelopeError("envelope must be bytes")
        if b"\r" in data:
            raise EnvelopeError("carriage returns are not canonical")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise EnvelopeError("envelope is not valid UTF-8") from error
        if not text.startswith("---\n"):
            raise EnvelopeError("missing opening envelope marker")
        header, separator, body = text[4:].partition("\n---\n")
        if not separator:
            raise EnvelopeError("missing closing envelope marker")

        metadata: dict[str, str] = {}
        order: list[str] = []
        for line in header.split("\n"):
            if ": " not in line:
                raise EnvelopeError("malformed metadata line")
            key, value = line.split(": ", 1)
            if key in metadata:
                raise EnvelopeError(f"repeated metadata key: {key}")
            if key not in _FIELD_ORDER:
                raise EnvelopeError(f"unknown metadata key: {key}")
            metadata[key] = value
            order.append(key)
        if tuple(order) != _FIELD_ORDER or set(metadata) != set(_FIELD_ORDER):
            raise EnvelopeError("metadata fields are missing or not canonical")
        if metadata["reasonnotes-format"] != "1":
            raise EnvelopeError("unsupported ReasonNotes format")

        try:
            note_id = UUID(metadata["note-id"])
        except ValueError as error:
            raise EnvelopeError("invalid note UUID") from error
        if str(note_id) != metadata["note-id"]:
            raise EnvelopeError("note UUID is not canonical")

        revision_id = metadata["revision-id"]
        body_sha256 = metadata["body-sha256"]
        if not _HASH_RE.fullmatch(revision_id):
            raise EnvelopeError("invalid revision SHA-256")
        if not _HASH_RE.fullmatch(body_sha256):
            raise EnvelopeError("invalid body SHA-256")

        raw_parents = metadata["parents"]
        parents = () if not raw_parents else tuple(raw_parents.split(","))
        parents = _validated_parents(parents)
        if raw_parents != ",".join(parents):
            raise EnvelopeError("parents are not canonically sorted")
        origin_device = _validated_origin_device(metadata["origin-device"])

        raw_timestamp = metadata["modified-utc"]
        if not _TIMESTAMP_RE.fullmatch(raw_timestamp):
            raise EnvelopeError("invalid UTC timestamp")
        try:
            modified_utc = datetime.strptime(raw_timestamp, "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc
            )
        except ValueError as error:
            raise EnvelopeError("invalid UTC timestamp") from error

        raw_deleted = metadata["deleted"]
        if raw_deleted not in ("true", "false"):
            raise EnvelopeError("invalid deletion flag")
        deleted = raw_deleted == "true"
        if deleted and body:
            raise EnvelopeError("tombstone body must be empty")
        actual_body_sha256 = _sha256(body.encode("utf-8"))
        if actual_body_sha256 != body_sha256:
            raise EnvelopeError("body SHA-256 mismatch")

        expected_revision_id = _sha256(
            _canonical_bytes(
                note_id=note_id,
                revision_id=None,
                parents=parents,
                origin_device=origin_device,
                modified_utc=modified_utc,
                deleted=deleted,
                body_sha256=body_sha256,
                body=body,
            )
        )
        if expected_revision_id != revision_id:
            raise EnvelopeError("revision SHA-256 mismatch")

        revision = cls(
            note_id=note_id,
            revision_id=revision_id,
            parents=parents,
            origin_device=origin_device,
            modified_utc=modified_utc,
            deleted=deleted,
            body_sha256=body_sha256,
            body=body,
        )
        if revision.serialize() != data:
            raise EnvelopeError("envelope is not canonical")
        return revision

    def serialize(self) -> bytes:
        return _canonical_bytes(
            note_id=self.note_id,
            revision_id=self.revision_id,
            parents=self.parents,
            origin_device=self.origin_device,
            modified_utc=self.modified_utc,
            deleted=self.deleted,
            body_sha256=self.body_sha256,
            body=self.body,
        )

    def transport_filename(self) -> str:
        return f"reasonnotes-{self.note_id}-{self.revision_id}.md"
