"""Deterministic revision ancestry and head derivation."""

from collections.abc import Iterable
from dataclasses import dataclass
from uuid import UUID

from .envelope import Revision


@dataclass(frozen=True, slots=True)
class GraphStatus:
    head_ids: tuple[str, ...]
    pending_ids: tuple[str, ...]
    conflicted: bool


class RevisionGraph:
    """A read-only graph derived from immutable revision envelopes."""

    def __init__(self, revisions: Iterable[Revision]):
        self._revisions: dict[str, Revision] = {}
        for revision in revisions:
            existing = self._revisions.get(revision.revision_id)
            if existing is not None and existing != revision:
                raise ValueError("revision ID refers to different content")
            self._revisions[revision.revision_id] = revision
        self._complete_cache: dict[str, bool] = {}

    def _is_complete(self, revision_id: str, visiting: frozenset[str] = frozenset()) -> bool:
        cached = self._complete_cache.get(revision_id)
        if cached is not None:
            return cached
        revision = self._revisions.get(revision_id)
        if revision is None or revision_id in visiting:
            return False
        path = visiting | {revision_id}
        complete = all(
            parent in self._revisions
            and self._revisions[parent].note_id == revision.note_id
            and self._is_complete(parent, path)
            for parent in revision.parents
        )
        self._complete_cache[revision_id] = complete
        return complete

    def status(self, note_id: UUID) -> GraphStatus:
        note_revisions = tuple(
            revision for revision in self._revisions.values() if revision.note_id == note_id
        )
        complete = tuple(
            revision for revision in note_revisions if self._is_complete(revision.revision_id)
        )
        pending_ids = tuple(
            sorted(
                revision.revision_id
                for revision in note_revisions
                if not self._is_complete(revision.revision_id)
            )
        )
        head_ids = tuple(
            sorted(
                candidate.revision_id
                for candidate in complete
                if not any(
                    candidate.revision_id != other.revision_id
                    and self.is_ancestor(candidate.revision_id, other.revision_id)
                    for other in complete
                )
            )
        )
        return GraphStatus(
            head_ids=head_ids,
            pending_ids=pending_ids,
            conflicted=len(head_ids) > 1,
        )

    def heads(self, note_id: UUID) -> tuple[Revision, ...]:
        return tuple(self._revisions[revision_id] for revision_id in self.status(note_id).head_ids)

    def is_ancestor(self, ancestor_id: str, descendant_id: str) -> bool:
        ancestor = self._revisions.get(ancestor_id)
        descendant = self._revisions.get(descendant_id)
        if ancestor is None or descendant is None or ancestor.note_id != descendant.note_id:
            return False
        if ancestor_id == descendant_id:
            return False

        remaining = list(descendant.parents)
        visited: set[str] = set()
        while remaining:
            candidate = remaining.pop()
            if candidate == ancestor_id:
                return True
            if candidate in visited:
                continue
            visited.add(candidate)
            revision = self._revisions.get(candidate)
            if revision is not None and revision.note_id == ancestor.note_id:
                remaining.extend(revision.parents)
        return False
