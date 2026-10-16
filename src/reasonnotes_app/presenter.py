"""UI-independent desktop presentation state for ReasonNotes."""

from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState
from reasonnotes_sync.sync import SyncReport

from .service import NoteService


@dataclass(frozen=True, slots=True)
class NoteRow:
    note_id: str
    title: str
    preview: str
    conflicted: bool
    modified_utc: datetime
    delivery_status: str


@dataclass(frozen=True, slots=True)
class ConflictVersion:
    revision_id: str
    origin_device: str
    deleted: bool
    body: str


@dataclass(frozen=True, slots=True)
class ConflictView:
    note_id: str
    candidates: tuple[ConflictVersion, ...]


class NotePresenter:
    """Coordinates application actions while keeping QML free of persistence."""

    def __init__(
        self,
        service: NoteService,
        sync_runner: Callable[[tuple[UUID, ...] | None], SyncReport] | None = None,
    ):
        self.service = service
        self.repository = service.repository
        self.sync_runner = sync_runner
        self.query = ""
        self.notes: tuple[NoteRow, ...] = ()
        self.active_note_id: str | None = None
        self.draft = ""
        self.dirty = False
        self.save_state = "Idle"
        self.save_error = ""
        self.sync_state = "Idle"
        self.sync_message = ""
        self.conflict_view: ConflictView | None = None
        self._skipped_conflict_draft = False
        self._active_head_ids: tuple[str, ...] = ()
        self.refresh()

    def _delivery_statuses(self) -> dict[UUID, str]:
        target = self.repository.target_device
        if target is None:
            return {}
        # Load immutable revisions once and resolve ledger IDs in memory.
        # Repository.revision() scans the entire history for each ID; calling
        # it for every delivery entry caused O(entries * revisions) disk I/O
        # on every UI refresh and note switch.
        revision_notes = {
            revision.revision_id: revision.note_id
            for revision in self.repository.revisions()
        }
        states: dict[UUID, list[DeliveryState]] = {}
        for entry in DeliveryLedger(self.repository.root).entries(target):
            note_id = revision_notes.get(entry.revision_id)
            if note_id is not None:
                states.setdefault(note_id, []).append(entry.state)

        result = {}
        for note_id, note_states in states.items():
            if DeliveryState.NEEDS_ATTENTION in note_states:
                status = "Needs attention"
            elif DeliveryState.PENDING in note_states:
                status = "Pending"
            elif DeliveryState.TRANSFER_REQUESTED in note_states:
                status = "Transfer requested"
            elif note_states and all(
                state == DeliveryState.CONFIRMED for state in note_states
            ):
                status = "Confirmed"
            else:
                status = "Local"
            result[note_id] = status
        return result

    def refresh(self, query: str | None = None) -> tuple[NoteRow, ...]:
        if query is not None:
            self.query = query
        delivery = self._delivery_statuses()
        self.notes = tuple(
            NoteRow(
                note_id=str(summary.note_id),
                title=summary.title,
                preview=summary.preview,
                conflicted=summary.conflicted,
                modified_utc=summary.modified_utc,
                delivery_status=delivery.get(summary.note_id, "Local"),
            )
            for summary in self.service.list_notes(self.query)
        )
        return self.notes

    def set_search(self, query: str) -> tuple[NoteRow, ...]:
        return self.refresh(query)

    def sync_notes(self) -> tuple[NoteRow, ...]:
        """Return an unfiltered sync catalogue, including live tombstones."""
        delivery = self._delivery_statuses()
        rows = {
            str(summary.note_id): NoteRow(
                note_id=str(summary.note_id),
                title=summary.title,
                preview=summary.preview,
                conflicted=summary.conflicted,
                modified_utc=summary.modified_utc,
                delivery_status=delivery.get(summary.note_id, "Local"),
            )
            for summary in self.service.list_notes("")
        }
        graph = self.repository.graph()
        note_ids = {revision.note_id for revision in self.repository.revisions()}
        for note_id in note_ids:
            if str(note_id) in rows:
                continue
            heads = graph.heads(note_id)
            if len(heads) != 1 or not heads[0].deleted:
                continue
            rows[str(note_id)] = NoteRow(
                note_id=str(note_id),
                title="Deleted note",
                preview="Pending deletion",
                conflicted=False,
                modified_utc=heads[0].modified_utc,
                delivery_status=delivery.get(note_id, "Local"),
            )
        return tuple(
            sorted(
                rows.values(),
                key=lambda row: (row.modified_utc, row.note_id),
                reverse=True,
            )
        )

    def _set_save_error(self, error: BaseException | str) -> None:
        self.save_state = "Error"
        self.save_error = str(error)

    @property
    def can_edit(self) -> bool:
        if self.active_note_id is None or self.conflict_view is not None:
            return False
        return not self.repository.graph().status(
            UUID(self.active_note_id)
        ).conflicted

    def create_note(self) -> bool:
        initial_body = ""
        if self.active_note_id is None and self.dirty:
            initial_body = self.draft
        elif not self.save_active():
            return False
        try:
            note_id = self.service.create_note(initial_body)
        except Exception as error:
            self._set_save_error(error)
            return False
        self.active_note_id = str(note_id)
        self._active_head_ids = self.repository.graph().status(note_id).head_ids
        self.draft = initial_body
        self.dirty = False
        self.conflict_view = None
        self._skipped_conflict_draft = False
        self.save_state = "Saved"
        self.save_error = ""
        self.refresh()
        return True

    def select_note(self, note_id: str) -> bool:
        selected = UUID(note_id)
        status = self.repository.graph().status(selected)
        if (
            self.active_note_id == note_id
            and not status.conflicted
            and (self.dirty or status.head_ids == self._active_head_ids)
        ):
            return True
        if self.active_note_id != note_id and not self.save_active():
            return False
        if status.conflicted:
            if self.active_note_id != note_id:
                try:
                    self.draft = self.service.open_note(selected)
                except Exception:
                    self.draft = ""
                self.dirty = False
            self.active_note_id = note_id
            self.inspect_conflict(note_id)
            return True
        try:
            body = self.service.open_note(selected)
        except Exception as error:
            self._set_save_error(error)
            return False
        self.active_note_id = note_id
        self._active_head_ids = status.head_ids
        self.draft = body
        self.dirty = False
        self.conflict_view = None
        self._skipped_conflict_draft = False
        self.save_state = "Saved"
        self.save_error = ""
        return True

    def edit_draft(self, body: str) -> bool:
        if self.active_note_id is None:
            self.draft = body
            self.dirty = True
            self._set_save_error("create or select a note before editing")
            return False
        # Conflict views are raised on selection/sync/save, and the durable
        # save checks current repository heads. Avoid rebuilding the entire
        # revision graph on every single keystroke in the draft editor.
        if self.conflict_view is not None or self._skipped_conflict_draft:
            self._set_save_error("resolve the conflict before editing")
            return False
        self.draft = body
        self.dirty = True
        self.save_state = "Dirty"
        self.save_error = ""
        return True

    def save_active(self) -> bool:
        if self.active_note_id is None:
            if self.dirty:
                self._set_save_error("unbound draft must be attached to a new note")
                return False
            return True
        if not self.dirty:
            return True
        if self.conflict_view is not None:
            self._set_save_error("resolve the conflict before saving")
            return False
        self.save_state = "Saving"
        try:
            revision = self.service.save_note(
                UUID(self.active_note_id),
                self.draft,
            )
        except Exception as error:
            self._set_save_error(error)
            return False
        self.dirty = False
        self._active_head_ids = (revision.revision_id,)
        self.save_state = "Saved"
        self.save_error = ""
        self.refresh()
        status = self.repository.graph().status(UUID(self.active_note_id))
        if status.conflicted:
            self.inspect_conflict(self.active_note_id)
        return True

    def autosave(self) -> bool:
        return self.save_active()

    def close_requested(self) -> bool:
        return self.save_active()

    def delete_active(self) -> bool:
        if self.active_note_id is None:
            return False
        # Deleting must not save a pending draft into history just before
        # erasing it. The deletion fence is the authoritative operation.
        note_id = UUID(self.active_note_id)
        try:
            self.service.delete_note(note_id)
        except Exception as error:
            self._set_save_error(error)
            return False
        self.active_note_id = None
        self._active_head_ids = ()
        self.draft = ""
        self.dirty = False
        self.conflict_view = None
        self.save_state = "Idle"
        self.save_error = ""
        self.refresh()
        return True

    def delete_notes(self, note_ids: Iterable[str]) -> tuple[str, ...]:
        """Purge each explicitly selected live note through the existing fence.

        Each successful deletion is committed independently by the repository.
        On partial failure, report an error and leave other notes untouched.
        Never silently save a draft from a note being permanently deleted.
        """
        ids = tuple(dict.fromkeys(note_ids))
        if not ids:
            return ()
        live = {str(note.note_id) for note in self.service.list_notes("")}
        if any(note_id not in live for note_id in ids):
            self._set_save_error("Selection changed; select the notes again before deleting")
            return ()
        deleted: list[str] = []
        failed = None
        for note_id in ids:
            try:
                self.service.delete_note(UUID(note_id))
            except Exception as error:
                # A durable deletion fence may already have been committed
                # before a later cleanup step failed. Never keep a fenced
                # note in the editor or the selected list, and never report
                # the interrupted cleanup as a successful batch.
                if self.repository.is_purged(UUID(note_id)):
                    deleted.append(note_id)
                failed = error
                break
            deleted.append(note_id)
        if self.active_note_id in deleted:
            self.active_note_id = None
            self._active_head_ids = ()
            self.draft = ""
            self.dirty = False
            self.conflict_view = None
            self._skipped_conflict_draft = False
        if failed is not None:
            self._set_save_error(
                f"Deleted {len(deleted)} of {len(ids)} notes; remaining deletion failed: {failed}"
            )
        elif deleted and self.active_note_id is None and not self.dirty:
            self.save_state = "Idle"
            self.save_error = ""
        self.refresh()
        return tuple(deleted)

    @staticmethod
    def _selection(
        selected_note_ids: Iterable[str | UUID] | None,
    ) -> tuple[UUID, ...] | None:
        if selected_note_ids is None:
            return None
        return tuple(
            note_id if isinstance(note_id, UUID) else UUID(note_id)
            for note_id in selected_note_ids
        )

    def prepare_sync(self, selected_note_ids: Iterable[str | UUID] | None) -> bool:
        if not self.save_active():
            return False
        self.sync_state = "Running"
        self.sync_message = "Scanning incoming data before transfer requests"
        return True

    def execute_sync(
        self,
        selected_note_ids: Iterable[str | UUID] | None,
    ) -> SyncReport:
        if self.sync_runner is None:
            raise RuntimeError("synchronization is not configured")
        return self.sync_runner(self._selection(selected_note_ids))

    def complete_sync(self, report: SyncReport) -> None:
        self.sync_state = "Error" if report.errors else "Completed"
        parts = (
            f"imported {len(report.imports)}",
            f"transfer requested {len(report.requested)}",
            f"confirmed {len(report.confirmations)}",
            f"receipts requested {len(report.receipt_requested)}",
        )
        self.sync_message = "; ".join(parts)
        if report.errors:
            self.sync_message += "; " + "; ".join(report.errors)
        self.refresh()
        if self.active_note_id is None or self.dirty:
            if self.active_note_id is None:
                return
        note_id = UUID(self.active_note_id)
        status = self.repository.graph().status(note_id)
        if status.conflicted:
            self.inspect_conflict(self.active_note_id)
            if self.dirty:
                self._set_save_error(
                    "note changed during synchronization; draft preserved"
                )
            return
        if status.head_ids == self._active_head_ids:
            return
        self._set_save_error(
            "note changed during synchronization; draft preserved"
        )

    def fail_sync(self, error: BaseException | str) -> None:
        self.sync_state = "Error"
        self.sync_message = str(error)
        self.refresh()

    def sync(self, selected_note_ids: Iterable[str | UUID] | None = None) -> bool:
        if not self.prepare_sync(selected_note_ids):
            return False
        try:
            report = self.execute_sync(selected_note_ids)
        except Exception as error:
            self.fail_sync(error)
            return False
        self.complete_sync(report)
        return not report.errors

    def inspect_conflict(self, note_id: str) -> ConflictView | None:
        parsed = UUID(note_id)
        candidates = self.service.conflict(parsed)
        if not candidates:
            self.conflict_view = None
            return None
        origin_rank = {"linux": 0, "android": 1}
        versions = []
        for candidate in candidates:
            revision = self.repository.revision(candidate.revision_id)
            if revision is None:
                continue
            versions.append(
                ConflictVersion(
                    revision_id=revision.revision_id,
                    origin_device=revision.origin_device,
                    deleted=revision.deleted,
                    body=revision.body,
                )
            )
        versions.sort(
            key=lambda item: (
                origin_rank.get(item.origin_device, 2),
                item.origin_device,
                item.revision_id,
            )
        )
        self.conflict_view = ConflictView(note_id, tuple(versions))
        self._active_head_ids = self.repository.graph().status(parsed).head_ids
        return self.conflict_view

    def resolve_conflict(
        self,
        choice: str,
        revision_id: str | None = None,
    ) -> bool:
        if self.conflict_view is None:
            self._set_save_error("no conflict is currently open")
            return False
        note_id = UUID(self.conflict_view.note_id)
        try:
            resolution = self.service.resolve(note_id, choice, revision_id)
        except Exception as error:
            self._set_save_error(error)
            self.inspect_conflict(str(note_id))
            return False
        if resolution is None:
            self.conflict_view = None
            self._skipped_conflict_draft = self.dirty
            if self.dirty:
                self._set_save_error(
                    "unresolved conflict; draft preserved read-only"
                )
            else:
                self.active_note_id = None
                self._active_head_ids = ()
                self.draft = ""
                self.save_state = "Idle"
                self.save_error = ""
            self.refresh()
            return True
        self.conflict_view = None
        self._skipped_conflict_draft = False
        if resolution.deleted:
            self.active_note_id = None
            self._active_head_ids = ()
            self.draft = ""
            self.dirty = False
            self.save_state = "Idle"
            self.save_error = ""
            self.refresh()
            return True
        self.active_note_id = str(note_id)
        self._active_head_ids = self.repository.graph().status(note_id).head_ids
        self.draft = self.service.open_note(note_id)
        self.dirty = False
        self.save_state = "Saved"
        self.save_error = ""
        self.refresh()
        return True
