from datetime import datetime, timezone
import unittest
from uuid import UUID

from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.graph import RevisionGraph


NOTE_ID = UUID("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa")
UNKNOWN_PARENT = "f" * 64


def make_revision(
    body: str,
    *,
    parents=(),
    device="linux",
    second=0,
    note_id=NOTE_ID,
    deleted=False,
):
    return Revision.create(
        note_id=note_id,
        parents=parents,
        origin_device=device,
        modified_utc=datetime(2026, 10, 12, 10, 0, second, tzinfo=timezone.utc),
        deleted=deleted,
        body=body,
    )


class RevisionGraphTests(unittest.TestCase):
    def test_duplicate_revision_is_one_head(self):
        root = make_revision("root")

        graph = RevisionGraph((root, root))

        self.assertEqual(graph.status(NOTE_ID).head_ids, (root.revision_id,))
        self.assertEqual(graph.heads(NOTE_ID), (root,))

    def test_linear_fast_forward_uses_graph_ancestry_not_timestamp(self):
        root = make_revision("root", second=20)
        child = make_revision("child", parents=(root.revision_id,), second=10)

        graph = RevisionGraph((child, root))

        self.assertTrue(graph.is_ancestor(root.revision_id, child.revision_id))
        self.assertFalse(graph.is_ancestor(child.revision_id, root.revision_id))
        self.assertEqual(graph.status(NOTE_ID).head_ids, (child.revision_id,))

    def test_concurrent_revisions_are_two_maximal_heads(self):
        root = make_revision("root")
        linux = make_revision("linux edit", parents=(root.revision_id,), device="linux", second=1)
        android = make_revision(
            "android edit", parents=(root.revision_id,), device="android", second=2
        )

        status = RevisionGraph((android, root, linux)).status(NOTE_ID)

        self.assertEqual(status.head_ids, tuple(sorted((linux.revision_id, android.revision_id))))
        self.assertEqual(status.pending_ids, ())
        self.assertTrue(status.conflicted)

    def test_unknown_parent_keeps_revision_pending_and_does_not_hide_head(self):
        root = make_revision("root")
        pending = make_revision("not ready", parents=(UNKNOWN_PARENT,), second=1)

        status = RevisionGraph((pending, root)).status(NOTE_ID)

        self.assertEqual(status.head_ids, (root.revision_id,))
        self.assertEqual(status.pending_ids, (pending.revision_id,))
        self.assertFalse(status.conflicted)

    def test_out_of_order_parent_arrival_activates_child(self):
        parent = make_revision("parent")
        child = make_revision("child", parents=(parent.revision_id,), second=1)

        before = RevisionGraph((child,)).status(NOTE_ID)
        after = RevisionGraph((child, parent)).status(NOTE_ID)

        self.assertEqual(before.head_ids, ())
        self.assertEqual(before.pending_ids, (child.revision_id,))
        self.assertEqual(after.head_ids, (child.revision_id,))
        self.assertEqual(after.pending_ids, ())

    def test_multi_parent_resolution_collapses_acknowledged_heads(self):
        root = make_revision("root")
        linux = make_revision("linux", parents=(root.revision_id,), second=1)
        android = make_revision(
            "android", parents=(root.revision_id,), device="android", second=2
        )
        resolution = make_revision(
            "linux",
            parents=(android.revision_id, linux.revision_id),
            device="linux",
            second=3,
        )

        graph = RevisionGraph((resolution, android, root, linux))

        self.assertEqual(graph.status(NOTE_ID).head_ids, (resolution.revision_id,))
        self.assertFalse(graph.status(NOTE_ID).conflicted)
        self.assertTrue(graph.is_ancestor(linux.revision_id, resolution.revision_id))
        self.assertTrue(graph.is_ancestor(android.revision_id, resolution.revision_id))

    def test_late_ancestor_after_resolution_remains_non_head(self):
        root = make_revision("root")
        linux = make_revision("linux", parents=(root.revision_id,), second=1)
        android = make_revision(
            "android", parents=(root.revision_id,), device="android", second=2
        )
        resolution = make_revision(
            "linux",
            parents=(linux.revision_id, android.revision_id),
            second=3,
        )

        without_root = RevisionGraph((resolution, linux, android)).status(NOTE_ID)
        with_late_root = RevisionGraph((resolution, linux, android, root)).status(NOTE_ID)

        self.assertEqual(without_root.head_ids, ())
        self.assertEqual(
            without_root.pending_ids,
            tuple(sorted((resolution.revision_id, linux.revision_id, android.revision_id))),
        )
        self.assertEqual(with_late_root.head_ids, (resolution.revision_id,))
        self.assertEqual(with_late_root.pending_ids, ())


if __name__ == "__main__":
    unittest.main()
