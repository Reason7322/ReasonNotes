from datetime import datetime, timedelta, timezone
import unittest
from uuid import UUID

from reasonnotes_sync.envelope import EnvelopeError, Revision


NOTE_ID = UUID("12345678-1234-5678-9234-567812345678")
PARENT_ONE = "1" * 64
PARENT_TWO = "a" * 64
MODIFIED = datetime(2026, 10, 12, 9, 10, 11, tzinfo=timezone.utc)
BODY = "# Café\n\nこんにちは 👋\n"
BODY_SHA256 = "7ce18b0fadcb42d61eccde4d97e5c242322dd652b5a72bd4831c1862ea5e8590"
REVISION_ID = "936fb0af2f314bdef568a6575dc6b0dfe1fe9dfc51e56cd30fae9920d3578aef"

CANONICAL_ENVELOPE = (
    "---\n"
    "reasonnotes-format: 1\n"
    "note-id: 12345678-1234-5678-9234-567812345678\n"
    f"revision-id: {REVISION_ID}\n"
    f"parents: {PARENT_ONE},{PARENT_TWO}\n"
    "origin-device: linux\n"
    "modified-utc: 2026-10-12T09:10:11Z\n"
    "deleted: false\n"
    f"body-sha256: {BODY_SHA256}\n"
    "---\n"
    f"{BODY}"
).encode("utf-8")


class RevisionEnvelopeTests(unittest.TestCase):
    def make_revision(self, **overrides):
        values = {
            "note_id": NOTE_ID,
            "parents": (PARENT_TWO, PARENT_ONE),
            "origin_device": "linux",
            "modified_utc": MODIFIED,
            "deleted": False,
            "body": BODY,
        }
        values.update(overrides)
        return Revision.create(**values)

    def test_round_trip_preserves_unicode_markdown_and_sorted_parents(self):
        revision = self.make_revision()

        self.assertEqual(revision.revision_id, REVISION_ID)
        self.assertEqual(revision.body_sha256, BODY_SHA256)
        self.assertEqual(revision.parents, (PARENT_ONE, PARENT_TWO))
        self.assertEqual(revision.serialize(), CANONICAL_ENVELOPE)
        self.assertEqual(Revision.parse(CANONICAL_ENVELOPE), revision)

    def test_revision_id_covers_metadata_parent_set_and_body(self):
        original = self.make_revision()
        variants = (
            self.make_revision(note_id=UUID("22345678-1234-5678-9234-567812345678")),
            self.make_revision(parents=(PARENT_ONE,)),
            self.make_revision(origin_device="android"),
            self.make_revision(
                modified_utc=datetime(2026, 10, 12, 9, 10, 12, tzinfo=timezone.utc)
            ),
            self.make_revision(body=BODY + "changed\n"),
        )

        for variant in variants:
            with self.subTest(variant=variant):
                self.assertNotEqual(variant.revision_id, original.revision_id)

    def test_rejects_truncated_body_hash_mismatch(self):
        truncated = CANONICAL_ENVELOPE.removesuffix("👋\n".encode("utf-8"))

        with self.assertRaisesRegex(EnvelopeError, "body SHA-256"):
            Revision.parse(truncated)

    def test_rejects_malformed_uuid_hash_timestamp_boolean_and_device_id(self):
        replacements = {
            "uuid": (b"12345678-1234-5678-9234-567812345678", b"not-a-uuid"),
            "hash": (REVISION_ID.encode(), b"G" * 64),
            "timestamp": (b"2026-10-12T09:10:11Z", b"2026-10-12 09:10:11"),
            "boolean": (b"deleted: false", b"deleted: False"),
            "device": (b"origin-device: linux", b"origin-device: Linux phone"),
        }

        for field, (old, new) in replacements.items():
            with self.subTest(field=field), self.assertRaises(EnvelopeError):
                Revision.parse(CANONICAL_ENVELOPE.replace(old, new, 1))

    def test_transport_filename_is_not_trusted_during_parse(self):
        parsed_after_arbitrary_rename = Revision.parse(CANONICAL_ENVELOPE)

        self.assertEqual(parsed_after_arbitrary_rename.note_id, NOTE_ID)
        self.assertEqual(parsed_after_arbitrary_rename.revision_id, REVISION_ID)
        self.assertEqual(
            parsed_after_arbitrary_rename.transport_filename(),
            f"reasonnotes-{NOTE_ID}-{REVISION_ID}.md",
        )

    def test_tombstone_requires_empty_body(self):
        with self.assertRaisesRegex(EnvelopeError, "tombstone"):
            self.make_revision(deleted=True)

        tombstone = self.make_revision(deleted=True, body="")
        self.assertTrue(tombstone.deleted)
        self.assertEqual(tombstone.body, "")
        self.assertEqual(Revision.parse(tombstone.serialize()), tombstone)

    def test_rejects_noncanonical_or_ambiguous_envelopes(self):
        cases = {
            "unknown-key": CANONICAL_ENVELOPE.replace(
                b"origin-device: linux\n", b"extra: value\norigin-device: linux\n"
            ),
            "repeated-key": CANONICAL_ENVELOPE.replace(
                b"origin-device: linux\n",
                b"origin-device: linux\norigin-device: linux\n",
            ),
            "carriage-return": CANONICAL_ENVELOPE.replace(b"---\n", b"---\r\n", 1),
            "non-utf8": CANONICAL_ENVELOPE + b"\xff",
            "duplicate-parent": CANONICAL_ENVELOPE.replace(
                f"parents: {PARENT_ONE},{PARENT_TWO}".encode(),
                f"parents: {PARENT_ONE},{PARENT_ONE}".encode(),
            ),
        }

        for case, data in cases.items():
            with self.subTest(case=case), self.assertRaises(EnvelopeError):
                Revision.parse(data)

    def test_create_rejects_naive_non_utc_or_subsecond_timestamp(self):
        timestamps = (
            datetime(2026, 10, 12, 9, 10, 11),
            datetime(
                2026,
                10,
                12,
                10,
                10,
                11,
                tzinfo=timezone(timedelta(hours=1)),
            ),
            datetime(2026, 10, 12, 9, 10, 11, 1, tzinfo=timezone.utc),
        )

        for timestamp in timestamps:
            with self.subTest(timestamp=timestamp), self.assertRaises(EnvelopeError):
                self.make_revision(modified_utc=timestamp)


if __name__ == "__main__":
    unittest.main()
