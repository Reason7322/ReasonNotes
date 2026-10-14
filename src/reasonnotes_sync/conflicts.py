"""Explicit, deterministic conflict selection without text merging."""

from dataclasses import dataclass
from datetime import datetime
from typing import Literal
from uuid import UUID

from .envelope import Revision
from .repository import Repository


class ConflictSelectionError(ValueError):
    """Raised when a requested winner is absent or ambiguous."""


class ConflictChangedError(ConflictSelectionError):
    """Raised when the inspected conflict no longer matches current heads."""


@dataclass(frozen=True, slots=True)
class ConflictCandidate:
    revision_id: str
    origin_device: str
    deleted: bool
    preview: str


def list_conflict(
    repository: Repository, note_id: UUID
) -> tuple[ConflictCandidate, ...]:
    heads = repository.graph().heads(note_id)
    if len(heads) < 2:
        return ()
    return tuple(
        ConflictCandidate(
            revision_id=head.revision_id,
            origin_device=head.origin_device,
            deleted=head.deleted,
            preview="[deleted note]" if head.deleted else head.body[:240],
        )
        for head in heads
    )


def resolve_conflict(
    repository: Repository,
    note_id: UUID,
    choice: Literal["keep-linux", "keep-android", "skip"],
    resolver_device: str,
    modified_utc: datetime,
    *,
    revision_id: str | None = None,
    expected_head_ids: tuple[str, ...] | None = None,
) -> Revision | None:
    if choice not in ("keep-linux", "keep-android", "skip"):
        raise ConflictSelectionError(f"unsupported conflict choice: {choice}")
    if choice == "skip":
        return None

    heads = repository.graph().heads(note_id)
    if (
        expected_head_ids is not None
        and tuple(head.revision_id for head in heads) != expected_head_ids
    ):
        raise ConflictChangedError("conflict heads changed after inspection")
    if len(heads) < 2:
        raise ConflictSelectionError("note does not have a current conflict")
    expected_origin = "linux" if choice == "keep-linux" else "android"
    matching = tuple(head for head in heads if head.origin_device == expected_origin)

    if revision_id is not None:
        selected = next(
            (head for head in matching if head.revision_id == revision_id), None
        )
        if selected is None:
            raise ConflictSelectionError(
                "exact revision is not a current head from the selected device"
            )
    else:
        if len(matching) != 1:
            raise ConflictSelectionError(
                f"keep choice requires exactly one {expected_origin} head"
            )
        selected = matching[0]

    resolution = Revision.create(
        note_id=note_id,
        parents=tuple(head.revision_id for head in heads),
        origin_device=resolver_device,
        modified_utc=modified_utc,
        deleted=selected.deleted,
        body=selected.body,
    )
    repository.store_resolution(resolution)
    return resolution
