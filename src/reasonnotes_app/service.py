"""Thin note application service backed only by immutable repository history."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Literal
import unicodedata
from uuid import UUID, uuid4

from reasonnotes_sync.conflicts import (
    ConflictChangedError,
    ConflictCandidate,
    list_conflict,
    resolve_conflict,
)
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import ConflictRequired, Repository


class NoteNotFoundError(KeyError):
    """Raised when a note has no live working value."""


class StaleConflictChoice(RuntimeError):
    """Raised when a resolution was not based on the current conflict heads."""


@dataclass(frozen=True, slots=True)
class NoteSummary:
    note_id: UUID
    title: str
    preview: str
    conflicted: bool
    modified_utc: datetime


def _search_key(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold()


def _title(body: str) -> str:
    for line in body.splitlines():
        if not line.strip():
            continue
        title = line.strip().lstrip("#").strip()
        return title or "Untitled"
    return "Untitled"


def _preview(body: str) -> str:
    return body.strip()[:240]


class NoteService:
    """Application operations over a :class:`Repository`.

    The repository remains the only durable state. Conflict snapshots are
    deliberately process-local safety tokens: after a restart or a changed
    head set, the caller must inspect the conflict again before resolving it.
    """

    def __init__(
        self,
        repository: Repository,
        *,
        origin_device: str = "linux",
        clock: Callable[[], datetime] | None = None,
        note_id_factory: Callable[[], UUID] = uuid4,
    ):
        self.repository = repository
        self.origin_device = origin_device
        self._clock = clock or (
            lambda: datetime.now(timezone.utc).replace(microsecond=0)
        )
        self._note_id_factory = note_id_factory
        self._conflict_snapshots: dict[UUID, tuple[str, ...]] = {}
        self._edit_bases: dict[UUID, str] = {}

    def _now(self) -> datetime:
        value = self._clock()
        if value.tzinfo is not None and value.utcoffset() is not None:
            value = value.astimezone(timezone.utc)
        return value.replace(microsecond=0)

    def _working_body(self, note_id: UUID) -> str | None:
        path = self.repository.notes_dir / f"{note_id}.md"
        try:
            return path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return None

    def _require_live_head(self, note_id: UUID) -> Revision:
        status = self.repository.graph().status(note_id)
        if status.conflicted:
            raise ConflictRequired("explicit conflict resolution is required")
        if not status.head_ids:
            if status.pending_ids:
                raise ConflictRequired("cannot edit a note with incomplete ancestry")
            raise NoteNotFoundError(str(note_id))
        head = self.repository.revision(status.head_ids[0])
        if head is None or head.deleted:
            raise NoteNotFoundError(str(note_id))
        return head

    def list_notes(self, query: str = "") -> list[NoteSummary]:
        graph = self.repository.graph()
        note_ids = sorted({revision.note_id for revision in self.repository.revisions()}, key=str)
        query_key = _search_key(query)
        summaries: list[NoteSummary] = []

        for note_id in note_ids:
            status = graph.status(note_id)
            heads = graph.heads(note_id)
            if not heads:
                continue
            if len(heads) == 1 and heads[0].deleted:
                continue

            working_body = (
                heads[0].body
                if len(heads) == 1
                else self._working_body(note_id)
            )
            body = working_body if working_body is not None else ""
            searchable = "\n".join(
                [body, *(head.body for head in heads if not head.deleted)]
            )
            if query_key and query_key not in _search_key(searchable):
                continue

            summaries.append(
                NoteSummary(
                    note_id=note_id,
                    title=(
                        _title(body)
                        if working_body is not None
                        else "Conflict"
                    ),
                    preview=_preview(body),
                    conflicted=status.conflicted,
                    modified_utc=max(head.modified_utc for head in heads),
                )
            )

        summaries.sort(key=lambda item: (item.modified_utc, str(item.note_id)), reverse=True)
        return summaries

    def open_note(self, note_id: UUID) -> str:
        graph = self.repository.graph()
        status = graph.status(note_id)
        if len(status.head_ids) == 1:
            head = graph.heads(note_id)[0]
            if head.deleted:
                raise NoteNotFoundError(str(note_id))
            self._edit_bases[note_id] = head.revision_id
            return head.body
        elif not status.conflicted:
            raise NoteNotFoundError(str(note_id))

        body = self._working_body(note_id)
        if body is None:
            raise NoteNotFoundError(str(note_id))
        return body

    def create_note(self, body: str) -> UUID:
        note_id = self._note_id_factory()
        revision = self.repository.create_revision(
            note_id,
            body,
            self.origin_device,
            self._now(),
        )
        self._edit_bases[note_id] = revision.revision_id
        return note_id

    def save_note(self, note_id: UUID, body: str) -> Revision:
        current = self._require_live_head(note_id)
        expected_head = self._edit_bases.get(note_id, current.revision_id)
        if current.revision_id != expected_head:
            raise ConflictRequired("note changed since it was opened")
        if current.body == body:
            return current
        revision = self.repository.create_revision(
            note_id,
            body,
            self.origin_device,
            self._now(),
            expected_head_ids=(expected_head,),
        )
        self._edit_bases[note_id] = revision.revision_id
        return revision

    def delete_note(self, note_id: UUID) -> None:
        """Permanently purge all managed history, including conflicted heads.

        A durable content-free fence survives until other paired instances
        have also purged, preventing old KDE Connect transfers resurrecting it.
        """
        self.repository.purge_note(note_id)
        self._edit_bases.pop(note_id, None)
        self._conflict_snapshots.pop(note_id, None)

    def conflict(self, note_id: UUID) -> tuple[ConflictCandidate, ...]:
        candidates = list_conflict(self.repository, note_id)
        if candidates:
            self._conflict_snapshots[note_id] = tuple(
                candidate.revision_id for candidate in candidates
            )
        else:
            self._conflict_snapshots.pop(note_id, None)
        return candidates

    def resolve(
        self,
        note_id: UUID,
        choice: Literal["keep-linux", "keep-android", "skip"],
        revision_id: str | None = None,
    ) -> Revision | None:
        if choice == "skip":
            self._conflict_snapshots.pop(note_id, None)
            return resolve_conflict(
                self.repository,
                note_id,
                choice,
                self.origin_device,
                self._now(),
            )

        snapshot = self._conflict_snapshots.get(note_id)
        if snapshot is None:
            raise StaleConflictChoice("inspect the current conflict before resolving it")
        try:
            resolution = resolve_conflict(
                self.repository,
                note_id,
                choice,
                self.origin_device,
                self._now(),
                revision_id=revision_id,
                expected_head_ids=snapshot,
            )
        except (ConflictChangedError, ConflictRequired) as error:
            raise StaleConflictChoice("conflict changed before resolution") from error
        finally:
            self._conflict_snapshots.pop(note_id, None)
        if resolution is not None and not resolution.deleted:
            self._edit_bases[note_id] = resolution.revision_id
        else:
            self._edit_bases.pop(note_id, None)
        return resolution
