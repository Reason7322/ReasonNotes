"""Durable immutable revision storage and generated-state reconciliation."""

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Literal
from uuid import UUID

from .envelope import EnvelopeError, Revision
from .graph import RevisionGraph


class ConflictRequired(RuntimeError):
    """Raised when an ordinary edit would implicitly choose among heads."""


@dataclass(frozen=True, slots=True)
class ImportOutcome:
    kind: Literal["imported", "duplicate", "quarantined"]
    revision_id: str | None
    detail: str


@dataclass(frozen=True, slots=True)
class ReconcileReport:
    note_count: int
    revision_count: int
    conflict_count: int
    pending_count: int
    errors: tuple[str, ...]


class Repository:
    """A repository whose immutable history is the source of truth."""

    def __init__(self, root: Path):
        self.root = root
        self.history_dir = root / "history"
        self.state_dir = root / "state"
        self.notes_dir = root / "notes"
        self.conflicts_dir = root / "conflicts"
        self.quarantine_dir = root / "quarantine"

    @classmethod
    def open(cls, root: Path) -> "Repository":
        repository = cls(Path(root))
        repository._ensure_layout()
        repository.reconcile()
        return repository

    def _ensure_layout(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        for directory in (
            self.history_dir,
            self.state_dir,
            self.notes_dir,
            self.conflicts_dir,
            self.quarantine_dir,
        ):
            directory.mkdir(exist_ok=True)

    @staticmethod
    def _fsync_directory(directory: Path) -> None:
        descriptor = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def _atomic_write(self, path: Path, data: bytes) -> None:
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
            os.replace(temporary, path)
            self._fsync_directory(path.parent)
        finally:
            if temporary.exists():
                temporary.unlink()

    def _remove_generated(self, path: Path) -> None:
        try:
            path.unlink()
        except FileNotFoundError:
            return
        self._fsync_directory(path.parent)

    def _history_path(self, revision: Revision) -> Path:
        return self.history_dir / str(revision.note_id) / f"{revision.revision_id}.md"

    def _write_history(self, revision: Revision) -> bool:
        path = self._history_path(revision)
        parent_existed = path.parent.exists()
        path.parent.mkdir(parents=True, exist_ok=True)
        if not parent_existed:
            self._fsync_directory(self.history_dir)
        data = revision.serialize()
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{revision.revision_id}.", suffix=".tmp", dir=path.parent
        )
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            try:
                os.link(temporary, path)
            except FileExistsError:
                if path.read_bytes() != data:
                    raise RuntimeError("existing history content disagrees with revision ID")
                return False
            self._fsync_directory(path.parent)
            return True
        finally:
            temporary.unlink(missing_ok=True)
            self._fsync_directory(path.parent)

    def _load_revisions(self) -> tuple[tuple[Revision, ...], tuple[str, ...]]:
        revisions: list[Revision] = []
        errors: list[str] = []
        for path in sorted(self.history_dir.glob("*/*.md")):
            try:
                revision = Revision.parse(path.read_bytes())
                expected = self._history_path(revision)
                if path != expected:
                    raise EnvelopeError("history path does not match envelope identity")
            except (EnvelopeError, OSError) as error:
                errors.append(f"{path}: {error}")
                continue
            revisions.append(revision)
        return tuple(revisions), tuple(errors)

    def revisions(self) -> tuple[Revision, ...]:
        revisions, _ = self._load_revisions()
        return revisions

    def graph(self) -> RevisionGraph:
        return RevisionGraph(self.revisions())

    def revision(self, revision_id: str) -> Revision | None:
        return next(
            (item for item in self.revisions() if item.revision_id == revision_id), None
        )

    def _read_previous_index(self) -> dict:
        path = self.state_dir / "index.json"
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError, OSError, UnicodeDecodeError):
            return {}
        return value if isinstance(value, dict) else {}

    def _write_index(self, index: dict) -> None:
        data = (json.dumps(index, indent=2, sort_keys=True) + "\n").encode("utf-8")
        self._atomic_write(self.state_dir / "index.json", data)

    def _write_working_view(self, note_id: UUID, selected: Revision | None) -> None:
        path = self.notes_dir / f"{note_id}.md"
        if selected is None or selected.deleted:
            self._remove_generated(path)
            return
        self._atomic_write(path, selected.body.encode("utf-8"))

    def _write_conflict(self, note_id: UUID, heads: tuple[Revision, ...]) -> None:
        path = self.conflicts_dir / f"{note_id}.json"
        if len(heads) < 2:
            self._remove_generated(path)
            return
        record = {
            "heads": [
                {
                    "body_sha256": head.body_sha256,
                    "deleted": head.deleted,
                    "origin_device": head.origin_device,
                    "preview": head.body[:240],
                    "revision_id": head.revision_id,
                }
                for head in heads
            ],
            "note_id": str(note_id),
        }
        self._atomic_write(
            path, (json.dumps(record, indent=2, sort_keys=True) + "\n").encode("utf-8")
        )

    @staticmethod
    def _prior_working_revision(previous_index: dict, note_id: UUID) -> str | None:
        notes = previous_index.get("notes", {})
        if not isinstance(notes, dict):
            return None
        note = notes.get(str(note_id), {})
        if not isinstance(note, dict):
            return None
        revision_id = note.get("working_revision")
        return revision_id if isinstance(revision_id, str) else None

    @staticmethod
    def _fallback_common_ancestor(
        graph: RevisionGraph,
        note_revisions: tuple[Revision, ...],
        heads: tuple[Revision, ...],
    ) -> Revision | None:
        common = tuple(
            candidate
            for candidate in note_revisions
            if all(
                candidate.revision_id == head.revision_id
                or graph.is_ancestor(candidate.revision_id, head.revision_id)
                for head in heads
            )
        )
        maximal = tuple(
            candidate
            for candidate in common
            if not any(
                candidate.revision_id != other.revision_id
                and graph.is_ancestor(candidate.revision_id, other.revision_id)
                for other in common
            )
        )
        return maximal[0] if len(maximal) == 1 else None

    def reconcile(self) -> ReconcileReport:
        revisions, errors = self._load_revisions()
        graph = RevisionGraph(revisions)
        by_id = {revision.revision_id: revision for revision in revisions}
        note_ids = tuple(sorted({revision.note_id for revision in revisions}, key=str))
        previous_index = self._read_previous_index()
        selected_by_note: dict[UUID, Revision | None] = {}
        heads_by_note: dict[UUID, tuple[Revision, ...]] = {}
        notes_index: dict[str, dict] = {}

        for note_id in note_ids:
            status = graph.status(note_id)
            heads = graph.heads(note_id)
            note_revisions = tuple(
                revision for revision in revisions if revision.note_id == note_id
            )
            selected: Revision | None
            if len(heads) == 1:
                selected = heads[0]
            elif len(heads) > 1:
                prior_id = self._prior_working_revision(previous_index, note_id)
                prior = by_id.get(prior_id) if prior_id else None
                if prior is not None and prior.note_id == note_id and any(
                    prior.revision_id == head.revision_id
                    or graph.is_ancestor(prior.revision_id, head.revision_id)
                    for head in heads
                ):
                    selected = prior
                else:
                    selected = self._fallback_common_ancestor(graph, note_revisions, heads)
            else:
                selected = None

            selected_by_note[note_id] = selected
            heads_by_note[note_id] = heads
            notes_index[str(note_id)] = {
                "conflicted": status.conflicted,
                "heads": list(status.head_ids),
                "pending": list(status.pending_ids),
                "revisions": sorted(
                    revision.revision_id for revision in note_revisions
                ),
                "working_revision": selected.revision_id if selected else None,
            }

        index = {"format": 1, "notes": notes_index}
        self._write_index(index)
        for note_id in note_ids:
            self._write_conflict(note_id, heads_by_note[note_id])
        for note_id in note_ids:
            self._write_working_view(note_id, selected_by_note[note_id])

        valid_names = {f"{note_id}.md" for note_id in note_ids}
        for path in self.notes_dir.glob("*.md"):
            if path.name not in valid_names:
                self._remove_generated(path)
        conflict_names = {
            f"{note_id}.json"
            for note_id, heads in heads_by_note.items()
            if len(heads) > 1
        }
        for path in self.conflicts_dir.glob("*.json"):
            if path.name not in conflict_names:
                self._remove_generated(path)

        return ReconcileReport(
            note_count=len(note_ids),
            revision_count=len(revisions),
            conflict_count=sum(len(heads) > 1 for heads in heads_by_note.values()),
            pending_count=sum(len(graph.status(note_id).pending_ids) for note_id in note_ids),
            errors=errors,
        )

    def _quarantine(self, data: bytes, source_name: str, detail: str) -> ImportOutcome:
        digest = hashlib.sha256(data).hexdigest()
        safe_name = re.sub(r"[^A-Za-z0-9._-]+", "_", Path(source_name).name) or "input"
        path = self.quarantine_dir / f"{digest}-{safe_name}.invalid"
        try:
            with path.open("xb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            self._fsync_directory(self.quarantine_dir)
        except FileExistsError:
            pass
        return ImportOutcome("quarantined", None, detail)

    def import_bytes(self, data: bytes, source_name: str) -> ImportOutcome:
        try:
            revision = Revision.parse(data)
        except EnvelopeError as error:
            return self._quarantine(data, source_name, str(error))

        created = self._write_history(revision)
        self.reconcile()
        if not created:
            return ImportOutcome("duplicate", revision.revision_id, "revision already imported")
        return ImportOutcome("imported", revision.revision_id, "revision imported durably")

    def create_revision(
        self,
        note_id: UUID,
        body: str,
        origin_device: str,
        modified_utc,
        deleted: bool = False,
    ) -> Revision:
        graph = self.graph()
        status = graph.status(note_id)
        if status.conflicted:
            raise ConflictRequired("explicit conflict resolution is required")
        if not status.head_ids and status.pending_ids:
            raise ConflictRequired("cannot edit a note with incomplete ancestry")
        parents = status.head_ids
        revision = Revision.create(
            note_id=note_id,
            parents=parents,
            origin_device=origin_device,
            modified_utc=modified_utc,
            deleted=deleted,
            body=body,
        )
        self._store_local_revision(revision)
        return revision

    def _store_local_revision(self, revision: Revision) -> None:
        self._write_history(revision)
        self.reconcile()

    def store_resolution(self, revision: Revision) -> None:
        status = self.graph().status(revision.note_id)
        if not status.conflicted:
            raise ConflictRequired("note no longer has a conflict")
        if revision.parents != status.head_ids:
            raise ConflictRequired("resolution does not acknowledge every current head")
        self._store_local_revision(revision)
