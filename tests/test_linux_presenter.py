from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_app.presenter import NotePresenter
from reasonnotes_app.service import NoteService
from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import SyncReport


NOTE_A = UUID("aaaaaaaa-1111-4111-8111-111111111111")
NOTE_B = UUID("bbbbbbbb-2222-4222-8222-222222222222")
NOTE_C = UUID("cccccccc-3333-4333-8333-333333333333")
T0 = datetime(2026, 10, 14, 13, 0, tzinfo=timezone.utc)


class Clock:
    def __init__(self):
        self.value = T0

    def __call__(self):
        result = self.value
        self.value += timedelta(seconds=1)
        return result


def empty_report(**changes) -> SyncReport:
    values = {
        "imports": (),
        "confirmations": (),
        "conflicts": (),
        "pending": (),
        "requested": (),
        "receipt_pending": (),
        "receipt_requested": (),
        "errors": (),
    }
    values.update(changes)
    return SyncReport(**values)


class SyncRunner:
    def __init__(self, report=None, callback=None):
        self.report = report or empty_report()
        self.callback = callback
        self.calls = []

    def __call__(self, selected_note_ids):
        self.calls.append(selected_note_ids)
        if self.callback is not None:
            self.callback()
        return self.report


class NotePresenterTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = Repository.open(
            self.root,
            local_device="linux",
            target_device="android",
        )
        ids = iter((NOTE_A, NOTE_B, NOTE_C))
        self.service = NoteService(
            self.repository,
            origin_device="linux",
            clock=Clock(),
            note_id_factory=lambda: next(ids),
        )
        self.runner = SyncRunner()
        self.presenter = NotePresenter(self.service, self.runner)

    def test_create_load_save_and_unchanged_autosave(self):
        self.assertTrue(self.presenter.create_note())
        note_id = self.presenter.active_note_id
        self.assertEqual(str(NOTE_A), note_id)
        self.assertEqual("Saved", self.presenter.save_state)

        self.presenter.edit_draft("# First\nBody")
        self.assertEqual("Dirty", self.presenter.save_state)
        self.assertTrue(self.presenter.autosave())
        revision_count = len(self.repository.revisions())

        self.presenter.edit_draft("# First\nBody")
        self.assertTrue(self.presenter.autosave())
        self.assertEqual(revision_count, len(self.repository.revisions()))
        self.assertEqual("# First\nBody", self.service.open_note(NOTE_A))

        restarted = NotePresenter(
            NoteService(Repository.open(self.root), origin_device="linux"),
            self.runner,
        )
        self.assertTrue(restarted.select_note(str(NOTE_A)))
        self.assertEqual("# First\nBody", restarted.draft)

    def test_unbound_draft_cannot_close_and_new_note_preserves_it(self):
        self.assertFalse(self.presenter.edit_draft("Recovered startup draft"))

        self.assertEqual("Recovered startup draft", self.presenter.draft)
        self.assertTrue(self.presenter.dirty)
        self.assertFalse(self.presenter.close_requested())
        self.assertTrue(self.presenter.create_note())
        self.assertEqual("Recovered startup draft", self.service.open_note(NOTE_A))
        self.assertFalse(self.presenter.dirty)

    def test_switch_flushes_active_draft_before_loading_another_note(self):
        first = self.service.create_note("# First")
        second = self.service.create_note("# Second")
        self.presenter.refresh()
        self.assertTrue(self.presenter.select_note(str(first)))
        self.presenter.edit_draft("# First changed")

        self.assertTrue(self.presenter.select_note(str(second)))

        self.assertEqual("# First changed", self.service.open_note(first))
        self.assertEqual(str(second), self.presenter.active_note_id)
        self.assertEqual("# Second", self.presenter.draft)

    def test_reselecting_active_note_never_discards_dirty_draft(self):
        note_id = self.service.create_note("Saved body")
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        self.presenter.edit_draft("Unsaved body")

        self.assertTrue(self.presenter.select_note(str(note_id)))

        self.assertEqual("Unsaved body", self.presenter.draft)
        self.assertTrue(self.presenter.dirty)
        self.assertEqual("Saved body", self.service.open_note(note_id))

    def test_failed_save_preserves_exact_draft_and_blocks_switch_and_sync(self):
        first = self.service.create_note("Base")
        second = self.service.create_note("Other")
        self.presenter.refresh()
        self.presenter.select_note(str(first))
        base = self.repository.graph().heads(first)[0]
        self.presenter.edit_draft("Unsaved local draft")
        remote = Revision.create(
            first,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            False,
            "Remote edit",
        )
        self.repository.import_bytes(remote.serialize(), "remote.md")

        self.assertFalse(self.presenter.save_active())
        self.assertEqual("Unsaved local draft", self.presenter.draft)
        self.assertTrue(self.presenter.dirty)
        self.assertEqual("Error", self.presenter.save_state)
        self.assertFalse(self.presenter.select_note(str(second)))
        self.assertFalse(self.presenter.sync())
        self.assertEqual([], self.runner.calls)
        self.assertEqual(str(first), self.presenter.active_note_id)

    def test_sync_race_preserves_saved_draft_and_opens_full_conflict(self):
        note_id = self.service.create_note("Base")
        base = self.repository.graph().heads(note_id)[0]
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        self.presenter.edit_draft("Local full body " + "L" * 300)
        remote = Revision.create(
            note_id,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            False,
            "Android full body " + "A" * 300,
        )
        runner = SyncRunner(
            report=empty_report(conflicts=(str(note_id),)),
            callback=lambda: self.repository.import_bytes(
                remote.serialize(), "remote.md"
            ),
        )
        self.presenter.sync_runner = runner

        self.assertTrue(self.presenter.sync((str(note_id),)))

        self.assertEqual("Local full body " + "L" * 300, self.presenter.draft)
        self.assertFalse(self.presenter.dirty)
        self.assertEqual((NOTE_A,), runner.calls[0])
        conflict = self.presenter.conflict_view
        self.assertIsNotNone(conflict)
        self.assertEqual(2, len(conflict.candidates))
        self.assertIn(
            "Android full body " + "A" * 300,
            {candidate.body for candidate in conflict.candidates},
        )
        self.assertEqual("Completed", self.presenter.sync_state)
        self.assertIn("transfer requested 0", self.presenter.sync_message)

    def test_autosave_detects_conflict_imported_after_local_write(self):
        note_id = self.service.create_note("Base")
        base = self.repository.graph().heads(note_id)[0]
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        self.presenter.edit_draft("Local edit")
        remote = Revision.create(
            note_id,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            False,
            "Remote edit",
        )
        original_save = self.service.save_note

        def save_then_import(selected_note_id, body):
            revision = original_save(selected_note_id, body)
            self.repository.import_bytes(remote.serialize(), "remote.md")
            return revision

        self.service.save_note = save_then_import

        self.assertTrue(self.presenter.autosave())

        self.assertTrue(self.repository.graph().status(note_id).conflicted)
        self.assertIsNotNone(self.presenter.conflict_view)
        self.assertEqual(
            {"Local edit", "Remote edit"},
            {candidate.body for candidate in self.presenter.conflict_view.candidates},
        )
        self.assertTrue(self.presenter.select_note(str(note_id)))
        self.assertIsNotNone(self.presenter.conflict_view)

    def test_dirty_draft_is_not_replaced_when_background_sync_completes(self):
        note_id = self.service.create_note("Saved")
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        self.assertTrue(self.presenter.prepare_sync(None))
        self.presenter.edit_draft("Typed while synchronization ran")

        self.presenter.complete_sync(empty_report(imports=("a" * 64,)))

        self.assertEqual("Typed while synchronization ran", self.presenter.draft)
        self.assertTrue(self.presenter.dirty)
        self.assertIsNone(self.presenter.conflict_view)
        self.assertIn("imported 1", self.presenter.sync_message)

    def test_skip_preserves_dirty_conflict_draft_and_blocks_further_editing(self):
        note_id = self.service.create_note("Base")
        base = self.repository.graph().heads(note_id)[0]
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        self.presenter.edit_draft("Saved local edit")
        self.assertTrue(self.presenter.save_active())
        self.assertTrue(self.presenter.prepare_sync(None))
        self.presenter.edit_draft("Unsaved during sync")
        remote = Revision.create(
            note_id,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            False,
            "Remote edit",
        )
        self.repository.import_bytes(remote.serialize(), "remote.md")
        self.presenter.complete_sync(empty_report(imports=(remote.revision_id,)))
        self.assertIsNotNone(self.presenter.conflict_view)

        self.assertTrue(self.presenter.resolve_conflict("skip"))

        self.assertIsNone(self.presenter.conflict_view)
        self.assertEqual(str(note_id), self.presenter.active_note_id)
        self.assertEqual("Unsaved during sync", self.presenter.draft)
        self.assertTrue(self.presenter.dirty)
        self.assertFalse(self.presenter.can_edit)
        self.assertFalse(self.presenter.edit_draft("Must not replace draft"))
        self.assertEqual("Unsaved during sync", self.presenter.draft)

    def test_clean_draft_is_preserved_until_user_reloads_remote_fast_forward(self):
        note_id = self.service.create_note("Displayed")
        displayed = self.repository.graph().heads(note_id)[0]
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        self.assertTrue(self.presenter.prepare_sync(None))
        remote = Revision.create(
            note_id,
            (displayed.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            False,
            "Remote replacement",
        )
        self.repository.import_bytes(remote.serialize(), "remote.md")

        self.presenter.complete_sync(empty_report(imports=(remote.revision_id,)))

        self.assertEqual("Displayed", self.presenter.draft)
        self.assertEqual("Error", self.presenter.save_state)
        self.assertIn("changed during synchronization", self.presenter.save_error)

        self.assertTrue(self.presenter.select_note(str(note_id)))
        self.assertEqual("Remote replacement", self.presenter.draft)
        self.assertEqual("Saved", self.presenter.save_state)

    def test_unicode_search_and_delete_exclude_tombstone(self):
        german = self.service.create_note("# Straße")
        self.service.create_note("# Other\nCafe\u0301")
        self.presenter.refresh("STRASSE")
        self.assertEqual([str(german)], [row.note_id for row in self.presenter.notes])

        self.presenter.select_note(str(german))
        self.assertTrue(self.presenter.delete_active())

        self.presenter.refresh("")
        self.assertNotIn(str(german), [row.note_id for row in self.presenter.notes])
        self.assertIsNone(self.presenter.active_note_id)

    def test_delivery_status_never_calls_requested_or_pending_synced(self):
        note_id = self.service.create_note("Local")
        revision = self.repository.graph().heads(note_id)[0]
        ledger = DeliveryLedger(self.root)

        self.presenter.refresh()
        self.assertEqual("Pending", self.presenter.notes[0].delivery_status)

        ledger.record_requested(
            revision.revision_id,
            "android",
            T0 + timedelta(seconds=10),
        )
        self.presenter.refresh()
        self.assertEqual(
            "Transfer requested",
            self.presenter.notes[0].delivery_status,
        )

        ledger.record_confirmed(
            revision.revision_id,
            "android",
            "f" * 64,
            T0 + timedelta(seconds=11),
        )
        self.presenter.refresh()
        self.assertEqual("Confirmed", self.presenter.notes[0].delivery_status)
        self.assertNotIn("Synced", self.presenter.notes[0].delivery_status)

    def _make_conflict(self):
        note_id = self.service.create_note("Base")
        base = self.repository.graph().heads(note_id)[0]
        linux = self.service.save_note(note_id, "Linux " + "L" * 300)
        android = Revision.create(
            note_id,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            False,
            "Android " + "A" * 300,
        )
        self.repository.import_bytes(android.serialize(), "android.md")
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))
        return note_id, base, linux, android

    def test_conflict_uses_full_history_content_and_skip_writes_nothing(self):
        note_id, _, linux, android = self._make_conflict()
        conflict = self.presenter.conflict_view

        self.assertEqual(
            [linux.revision_id, android.revision_id],
            [candidate.revision_id for candidate in conflict.candidates],
        )
        self.assertEqual(306, len(conflict.candidates[0].body))
        self.assertEqual(308, len(conflict.candidates[1].body))
        revision_count = len(self.repository.revisions())

        self.assertTrue(self.presenter.resolve_conflict("skip"))
        self.assertEqual(revision_count, len(self.repository.revisions()))
        self.assertTrue(self.repository.graph().status(note_id).conflicted)
        self.assertIsNone(self.presenter.conflict_view)
        self.assertIsNone(self.presenter.active_note_id)

    def test_exact_resolution_and_stale_action_refresh_candidates(self):
        note_id, base, _, android = self._make_conflict()
        later = Revision.create(
            note_id,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=31),
            True,
            "",
        )
        self.repository.import_bytes(later.serialize(), "android-delete.md")
        revision_count = len(self.repository.revisions())

        self.assertFalse(
            self.presenter.resolve_conflict(
                "keep-android",
                android.revision_id,
            )
        )
        self.assertEqual("Error", self.presenter.save_state)
        self.assertEqual(revision_count, len(self.repository.revisions()))
        refreshed = self.presenter.conflict_view
        self.assertEqual(3, len(refreshed.candidates))
        self.assertTrue(any(candidate.deleted for candidate in refreshed.candidates))

        chosen = next(
            candidate
            for candidate in refreshed.candidates
            if candidate.origin_device == "android" and not candidate.deleted
        )
        self.assertTrue(
            self.presenter.resolve_conflict(
                "keep-android",
                chosen.revision_id,
            )
        )
        self.assertFalse(self.repository.graph().status(note_id).conflicted)
        self.assertEqual(chosen.body, self.presenter.draft)

    def test_keeping_deleted_conflict_head_clears_active_note(self):
        note_id = self.service.create_note("Base")
        base = self.repository.graph().heads(note_id)[0]
        self.service.save_note(note_id, "Linux edit")
        deleted = Revision.create(
            note_id,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=30),
            True,
            "",
        )
        self.repository.import_bytes(deleted.serialize(), "android-delete.md")
        self.presenter.refresh()
        self.presenter.select_note(str(note_id))

        self.assertTrue(
            self.presenter.resolve_conflict("keep-android", deleted.revision_id)
        )

        self.assertIsNone(self.presenter.active_note_id)
        self.assertIsNone(self.presenter.conflict_view)
        self.assertEqual("", self.presenter.draft)
        self.assertNotIn(str(note_id), [row.note_id for row in self.presenter.notes])
        heads = self.repository.graph().heads(note_id)
        self.assertEqual(1, len(heads))
        self.assertTrue(heads[0].deleted)


if __name__ == "__main__":
    unittest.main()
