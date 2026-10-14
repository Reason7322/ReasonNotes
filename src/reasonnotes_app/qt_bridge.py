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


class QtNotePresenter(QObject):
    """Qt-facing adapter; all application decisions remain in NotePresenter."""

    stateChanged = Signal()
    currentBodyChanged = Signal()
    conflictChanged = Signal()
    selectionChanged = Signal()

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
        self._sync_query = ""
        self._workers: set[_SyncWorker] = set()
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
        note_rows = [self._note_mapping(row) for row in self.presenter.notes]
        self._notes_model.replace(note_rows)
        sync_rows = []
        query = self._sync_query.casefold()
        for row in self.presenter.notes:
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
        candidates = (
            ()
            if self.presenter.conflict_view is None
            else self.presenter.conflict_view.candidates
        )
        self._conflict_model.replace(
            [self._conflict_mapping(candidate) for candidate in candidates]
        )

    def _notify(self, *, body: bool = False, conflict: bool = False) -> None:
        self._refresh_models()
        self.stateChanged.emit()
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

    @Property(str, notify=stateChanged)
    def activeNoteId(self):
        return self.presenter.active_note_id or ""

    @Property(str, notify=stateChanged)
    def saveState(self):
        return self.presenter.save_state

    @Property(str, notify=stateChanged)
    def saveError(self):
        return self.presenter.save_error

    @Property(str, notify=stateChanged)
    def syncState(self):
        return self.presenter.sync_state

    @Property(str, notify=stateChanged)
    def syncMessage(self):
        return self.presenter.sync_message

    @Property(bool, notify=conflictChanged)
    def hasConflict(self):
        return self.presenter.conflict_view is not None

    @Property(int, notify=selectionChanged)
    def syncSelectionCount(self):
        return len(self._selected_note_ids)

    @Slot(result=bool)
    def createNote(self):
        changed = self.presenter.create_note()
        self._notify(body=changed, conflict=True)
        return changed

    @Slot(str, result=bool)
    def selectNote(self, note_id: str):
        changed = self.presenter.select_note(note_id)
        self._notify(body=changed, conflict=True)
        return changed

    @Slot(str, result=bool)
    def editDraft(self, body: str):
        changed = self.presenter.edit_draft(body)
        self._notify()
        return changed

    @Slot(result=bool)
    def saveNow(self):
        saved = self.presenter.save_active()
        self._notify()
        return saved

    @Slot(result=bool)
    def autosave(self):
        saved = self.presenter.autosave()
        self._notify()
        return saved

    @Slot(result=bool)
    def deleteCurrent(self):
        deleted = self.presenter.delete_active()
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
            row.note_id for row in self.presenter.notes
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
        self._selected_note_ids.update(row.note_id for row in self.presenter.notes)
        self._notify()

    @Slot()
    def clearSyncSelection(self):
        self._selected_note_ids.clear()
        self._notify()

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
