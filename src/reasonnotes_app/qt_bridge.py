"""PySide6 models and signals that expose :mod:`presenter` to QML."""

from typing import Any

from PySide6.QtCore import (
    QAbstractListModel,
    QByteArray,
    QModelIndex,
    QObject,
    Property,
    QRunnable,
    Qt,
    QThreadPool,
    Signal,
    Slot,
)

from .note_document import change_content, change_title, split_note
from .presenter import ConflictVersion, NotePresenter, NoteRow


class MappingListModel(QAbstractListModel):
    """Small event-driven list model with stable named roles."""

    def __init__(self, role_names: tuple[str, ...], parent: QObject | None = None):
        super().__init__(parent)
        self._role_names = role_names
        self._roles = {
            Qt.ItemDataRole.UserRole + index: QByteArray(name.encode("utf-8"))
            for index, name in enumerate(role_names)
        }
        self._rows: list[dict[str, Any]] = []

    def roleNames(self):
        return self._roles

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self._rows)

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or not 0 <= index.row() < len(self._rows):
            return None
        name = self._roles.get(role)
        if name is None:
            return None
        return self._rows[index.row()].get(bytes(name).decode("utf-8"))

    def replace(self, rows: list[dict[str, Any]]) -> None:
        self.beginResetModel()
        self._rows = rows
        self.endResetModel()

    @Slot(int, result="QVariantMap")
    def row(self, index: int) -> dict[str, Any]:
        if not 0 <= index < len(self._rows):
            return {}
        return dict(self._rows[index])


class _WorkerSignals(QObject):
    completed = Signal(object)
    failed = Signal(str)


class _SyncWorker(QRunnable):
    def __init__(self, presenter: NotePresenter, selection):
        super().__init__()
        self.presenter = presenter
        self.selection = selection
        self.signals = _WorkerSignals()

    @Slot()
    def run(self):
        try:
            report = self.presenter.execute_sync(self.selection)
        except Exception as error:
            self.signals.failed.emit(str(error))
            return
        self.signals.completed.emit(report)


class _IncomingWorker(QRunnable):
    """Scan received files on a worker without touching Qt models off thread."""

    def __init__(self, scanner):
        super().__init__()
        self.scanner = scanner
        self.signals = _WorkerSignals()

    @Slot()
    def run(self):
        try:
            report = self.scanner()
        except Exception as error:
            self.signals.failed.emit(str(error))
            return
        self.signals.completed.emit(report)


class QtNotePresenter(QObject):
    """Qt-facing adapter; all application decisions remain in NotePresenter."""

    stateChanged = Signal()
    saveStatusChanged = Signal()
    currentBodyChanged = Signal()
    conflictChanged = Signal()
    selectionChanged = Signal()
    incomingScanFinished = Signal(object)
    incomingScanFailed = Signal(str)
    incomingRefreshMessageChanged = Signal()

    def __init__(
        self,
        presenter: NotePresenter,
        *,
        thread_pool: QThreadPool | None = None,
        parent: QObject | None = None,
    ):
        super().__init__(parent)
        self.presenter = presenter
        self._thread_pool = thread_pool or QThreadPool.globalInstance()
        self._selected_note_ids: set[str] = set()
        self._bulk_selected_ids: set[str] = set()
        self._pending_delete_ids: tuple[str, ...] = ()
        self._pending_delete_bulk = False
        self._sync_query = ""
        self._workers: set[_SyncWorker] = set()
        self._incoming_worker: _IncomingWorker | None = None
        self._incoming_scanner = None
        self._incoming_manual_worker: _IncomingWorker | None = None
        self._incoming_refresh_queued = False
        self._incoming_refresh_message = ""
        self._notes_model = MappingListModel(
            (
                "noteId",
                "title",
                "preview",
                "conflicted",
                "modifiedUtc",
                "deliveryStatus",
            ),
            self,
        )
        self._sync_model = MappingListModel(
            (
                "noteId",
                "title",
                "preview",
                "selected",
                "deliveryStatus",
            ),
            self,
        )
        self._conflict_model = MappingListModel(
            ("revisionId", "originDevice", "deleted", "body"),
            self,
        )
        self._refresh_models()

    @staticmethod
    def _note_mapping(row: NoteRow) -> dict[str, Any]:
        return {
            "noteId": row.note_id,
            "title": row.title,
            "preview": row.preview,
            "conflicted": row.conflicted,
            "modifiedUtc": row.modified_utc.isoformat(),
            "deliveryStatus": row.delivery_status,
        }

    @staticmethod
    def _conflict_mapping(row: ConflictVersion) -> dict[str, Any]:
        return {
            "revisionId": row.revision_id,
            "originDevice": row.origin_device,
            "deleted": row.deleted,
            "body": row.body,
        }

    def _refresh_models(self) -> None:
        # Selection is independent from search visibility but cannot outlive
        # remote permanent deletion. Do not ever reselect purged notes.
        if self._bulk_selected_ids:
            live = {
                str(row.note_id) for row in self.presenter.service.list_notes("")
            }
            self._bulk_selected_ids.intersection_update(live)
        note_rows = [self._note_mapping(row) for row in self.presenter.notes]
        self._notes_model.replace(note_rows)
        sync_rows = []
        query = self._sync_query.casefold()
        for row in self.presenter.sync_notes():
            if query and query not in f"{row.title}\n{row.preview}".casefold():
                continue
            sync_rows.append(
                {
                    "noteId": row.note_id,
                    "title": row.title,
                    "preview": row.preview,
                    "selected": row.note_id in self._selected_note_ids,
                    "deliveryStatus": row.delivery_status,
                }
            )
        self._sync_model.replace(sync_rows)
        # A selection only needs the already displayed delivery status.
        # Never recompute the entire sync catalogue from a QML property read.
        self._delivery_status_by_note = {
            row["noteId"]: row["deliveryStatus"] for row in sync_rows
        }
        self._refresh_conflict_model()

    def _refresh_conflict_model(self) -> None:
        candidates = (
            ()
            if self.presenter.conflict_view is None
            else self.presenter.conflict_view.candidates
        )
        self._conflict_model.replace(
            [self._conflict_mapping(candidate) for candidate in candidates]
        )

    def _notify(
        self, *, body: bool = False, conflict: bool = False,
        refresh_models: bool = True,
    ) -> None:
        if refresh_models:
            self._refresh_models()
        elif conflict:
            # Conflict candidates can change on an otherwise clean switch.
            # Refresh only this small model, not all notes and sync history.
            self._refresh_conflict_model()
        self.stateChanged.emit()
        self.saveStatusChanged.emit()
        if body:
            self.currentBodyChanged.emit()
        if conflict:
            self.conflictChanged.emit()
        self.selectionChanged.emit()

    @Property(QObject, constant=True)
    def notesModel(self):
        return self._notes_model

    @Property(QObject, constant=True)
    def syncModel(self):
        return self._sync_model

    @Property(QObject, constant=True)
    def conflictModel(self):
        return self._conflict_model

    @Property(str, notify=currentBodyChanged)
    def currentBody(self):
        return self.presenter.draft

    @Property(str, notify=currentBodyChanged)
    def currentTitle(self):
        return split_note(self.presenter.draft).title

    @Property(str, notify=currentBodyChanged)
    def currentContent(self):
        return split_note(self.presenter.draft).content

    @Property(str, notify=stateChanged)
    def activeNoteId(self):
        return self.presenter.active_note_id or ""

    @Property(str, notify=saveStatusChanged)
    def saveState(self):
        return self.presenter.save_state

    @Property(str, notify=saveStatusChanged)
    def saveError(self):
        return self.presenter.save_error

    @Property(str, notify=stateChanged)
    def syncState(self):
        return self.presenter.sync_state

    @Property(str, notify=stateChanged)
    def syncMessage(self):
        return self.presenter.sync_message

    @Property(str, notify=stateChanged)
    def activeDeliveryStatus(self):
        active_note_id = self.presenter.active_note_id
        if active_note_id is None:
            return "No note selected"
        return self._delivery_status_by_note.get(active_note_id, "Local")

    @Property(bool, notify=conflictChanged)
    def hasConflict(self):
        return self.presenter.conflict_view is not None

    @Property(bool, notify=stateChanged)
    def canEdit(self):
        return self.presenter.can_edit

    @Property(int, notify=selectionChanged)
    def syncSelectionCount(self):
        return len(self._selected_note_ids)

    @Property(int, notify=selectionChanged)
    def bulkSelectionCount(self):
        return len(self._bulk_selected_ids)

    @Property("QVariantList", notify=selectionChanged)
    def bulkSelectedNoteIds(self):
        return sorted(self._bulk_selected_ids)

    @Property(int, notify=selectionChanged)
    def pendingDeleteCount(self):
        return len(self._pending_delete_ids)

    @Property(bool, notify=selectionChanged)
    def pendingDeleteIsBulk(self):
        return self._pending_delete_bulk

    @Slot(str, result=bool)
    def toggleBulkNote(self, note_id: str):
        if note_id not in {row.note_id for row in self.presenter.notes}:
            return False
        if note_id in self._bulk_selected_ids:
            self._bulk_selected_ids.remove(note_id)
        else:
            self._bulk_selected_ids.add(note_id)
        self.selectionChanged.emit()
        return True

    @Slot()
    def clearBulkSelection(self):
        if self._bulk_selected_ids:
            self._bulk_selected_ids.clear()
            self.selectionChanged.emit()

    @Slot(result=int)
    def prepareDelete(self):
        # Freeze exact identities BEFORE opening a destructive dialog.
        # Selecting another note while the dialog is open cannot expand it.
        self._pending_delete_bulk = bool(self._bulk_selected_ids)
        if self._bulk_selected_ids:
            self._pending_delete_ids = tuple(sorted(self._bulk_selected_ids))
        elif self.presenter.active_note_id is not None:
            self._pending_delete_ids = (self.presenter.active_note_id,)
        else:
            self._pending_delete_ids = ()
        self.selectionChanged.emit()
        return len(self._pending_delete_ids)

    @Slot()
    def cancelDelete(self):
        if self._pending_delete_ids or self._pending_delete_bulk:
            self._pending_delete_ids = ()
            self._pending_delete_bulk = False
            self.selectionChanged.emit()

    @Slot(result=bool)
    def confirmDelete(self):
        ids = self._pending_delete_ids
        was_bulk = self._pending_delete_bulk
        self._pending_delete_ids = ()
        self._pending_delete_bulk = False
        if not ids:
            self.selectionChanged.emit()
            return False
        if len(ids) == 1 and self.presenter.active_note_id == ids[0] and not was_bulk:
            deleted = (ids[0],) if self.presenter.delete_active() else ()
        else:
            deleted = self.presenter.delete_notes(ids)
        self._bulk_selected_ids.difference_update(deleted)
        self._notify(body=bool(deleted), conflict=True)
        return len(deleted) == len(ids) and self.presenter.save_state != "Error"

    @Slot(result=bool)
    def createNote(self):
        changed = self.presenter.create_note()
        self._notify(body=changed, conflict=True)
        return changed

    @Slot(str, result=bool)
    def selectNote(self, note_id: str):
        before = self.presenter.notes
        was_dirty = self.presenter.dirty
        changed = self.presenter.select_note(note_id)
        if changed:
            self._bulk_selected_ids.clear()
        # Selecting an already saved note never changes either catalogue.
        # A dirty-note switch may flush a revision and change sidebar order,
        # title and delivery status, so update lists only in that case.
        self._notify(
            body=changed, conflict=True,
            refresh_models=(
                self.presenter.notes != before
                or (was_dirty and not self.presenter.dirty)
            ),
        )
        return changed

    def _notify_draft_edit(self) -> None:
        # Typing changes only the in-memory draft and its save indicator.
        # A full refresh traverses immutable history and delivery ledgers;
        # doing that for every character blocks the Qt UI thread.
        self.saveStatusChanged.emit()

    @Slot(str, result=bool)
    def editDraft(self, body: str):
        changed = self.presenter.edit_draft(body)
        self._notify_draft_edit()
        return changed

    @Slot(str, result=bool)
    def editTitle(self, title: str):
        changed = self.presenter.edit_draft(
            change_title(self.presenter.draft, title)
        )
        self._notify_draft_edit()
        return changed

    @Slot(str, result=bool)
    def editContent(self, content: str):
        changed = self.presenter.edit_draft(
            change_content(self.presenter.draft, content)
        )
        self._notify_draft_edit()
        return changed

    @Slot(result=bool)
    def saveNow(self):
        saved = self.presenter.save_active()
        self._notify()
        return saved

    @Slot(result=bool)
    def autosave(self):
        was_dirty = self.presenter.dirty
        saved = self.presenter.autosave()
        # A previous note's idle timer may still fire after a switch.
        # A clean draft produces no revision and needs no model refresh.
        if was_dirty or not saved:
            self._notify()
        return saved

    @Slot(result=bool)
    def deleteCurrent(self):
        original = self.presenter.active_note_id
        deleted = self.presenter.delete_active()
        if deleted and original is not None:
            self._bulk_selected_ids.discard(original)
        self._notify(body=deleted, conflict=True)
        return deleted

    @Slot(str)
    def search(self, query: str):
        self.presenter.set_search(query)
        self._notify()

    @Slot(result=bool)
    def closeRequested(self):
        if self.presenter.sync_state == "Running":
            return False
        saved = self.presenter.close_requested()
        self._notify()
        return saved

    @Slot()
    def openSyncDialog(self):
        self._selected_note_ids.intersection_update(
            row.note_id for row in self.presenter.sync_notes()
        )
        self._sync_query = ""
        self._notify()

    @Slot(str)
    def searchSyncNotes(self, query: str):
        self._sync_query = query
        self._notify()

    @Slot(str, bool)
    def setSyncSelected(self, note_id: str, selected: bool):
        if selected:
            self._selected_note_ids.add(note_id)
        else:
            self._selected_note_ids.discard(note_id)
        self._notify()

    @Slot()
    def selectAllSyncNotes(self):
        self._selected_note_ids.update(
            row.note_id for row in self.presenter.sync_notes()
        )
        self._notify()

    @Slot()
    def clearSyncSelection(self):
        self._selected_note_ids.clear()
        self._notify()

    @Property(str, notify=incomingRefreshMessageChanged)
    def incomingRefreshMessage(self) -> str:
        return self._incoming_refresh_message

    def _set_incoming_message(self, message: str) -> None:
        if message != self._incoming_refresh_message:
            self._incoming_refresh_message = message
            self.incomingRefreshMessageChanged.emit()

    def configure_incoming_scan(self, scanner) -> None:
        """Give the window the same validated scanner as startup."""
        self._incoming_scanner = scanner

    @Slot(result=bool)
    def refreshIncoming(self) -> bool:
        """Scan local received files on F5 without requesting a transfer."""
        if self._incoming_scanner is None:
            self._set_incoming_message("Incoming refresh unavailable")
            return False
        if self._incoming_worker is not None:
            self._incoming_refresh_queued = True
            self._set_incoming_message("Refresh queued…")
            return True
        return self.start_incoming_scan(self._incoming_scanner, manual=True)

    def start_incoming_scan(self, scanner, *, manual: bool = False) -> bool:
        """Import incoming revisions on worker; UI refresh on GUI thread."""
        if self._incoming_worker is not None:
            return False
        worker = _IncomingWorker(scanner)
        self._incoming_worker = worker
        if manual:
            self._incoming_manual_worker = worker
            self._set_incoming_message("Checking incoming notes…")
        worker.signals.completed.connect(
            lambda report, current=worker: self._incoming_completed(current, report)
        )
        worker.signals.failed.connect(
            lambda reason, current=worker: self._incoming_failed(current, reason)
        )
        self._thread_pool.start(worker)
        return True

    @Slot(object)
    def _incoming_completed(self, worker, report):
        if self._incoming_worker is not worker:
            return
        self._incoming_worker = None
        self.presenter.refresh()
        self._notify(conflict=True)
        self.incomingScanFinished.emit(report)
        if self._incoming_manual_worker is worker:
            self._incoming_manual_worker = None
            warnings = getattr(report, "errors", ())
            imported = len(getattr(report, "imported", ()))
            confirmed = len(getattr(report, "confirmed", ()))
            if warnings:
                self._set_incoming_message(
                    f"Refresh completed with {len(warnings)} warning(s)"
                )
            elif imported or confirmed:
                self._set_incoming_message(
                    f"Received {imported} revision(s), confirmed {confirmed}"
                )
            else:
                self._set_incoming_message("Notes are up to date")
        self._start_queued_incoming_refresh()

    @Slot(str)
    def _incoming_failed(self, worker, reason):
        if self._incoming_worker is not worker:
            return
        self._incoming_worker = None
        self.incomingScanFailed.emit(reason)
        if self._incoming_manual_worker is worker:
            self._incoming_manual_worker = None
            self._set_incoming_message("Could not refresh incoming notes")
        self._start_queued_incoming_refresh()

    def _start_queued_incoming_refresh(self) -> None:
        if self._incoming_refresh_queued:
            self._incoming_refresh_queued = False
            self.start_incoming_scan(self._incoming_scanner, manual=True)

    def _start_sync(self, selection) -> bool:
        if self.presenter.sync_state == "Running":
            return False
        if not self.presenter.prepare_sync(selection):
            self._notify()
            return False
        worker = _SyncWorker(self.presenter, selection)
        self._workers.add(worker)
        worker.signals.completed.connect(
            lambda report, current=worker: self._sync_completed(current, report)
        )
        worker.signals.failed.connect(
            lambda error, current=worker: self._sync_failed(current, error)
        )
        self._notify()
        self._thread_pool.start(worker)
        return True

    @Slot(result=bool)
    def syncAll(self):
        return self._start_sync(None)

    @Slot(result=bool)
    def syncSelected(self):
        if not self._selected_note_ids:
            return False
        return self._start_sync(tuple(sorted(self._selected_note_ids)))

    @Slot(object)
    def _sync_completed(self, worker, report):
        self._workers.discard(worker)
        self.presenter.complete_sync(report)
        self._notify(conflict=True)

    @Slot(object)
    def _sync_failed(self, worker, error):
        self._workers.discard(worker)
        self.presenter.fail_sync(error)
        self._notify()

    @Slot(str, str, result=bool)
    def resolveConflict(self, choice: str, revision_id: str):
        resolved = self.presenter.resolve_conflict(choice, revision_id or None)
        self._notify(body=resolved, conflict=True)
        return resolved

    @Slot(result=bool)
    def skipConflict(self):
        resolved = self.presenter.resolve_conflict("skip")
        self._notify(conflict=True)
        return resolved
