"""Private, localhost-only foreground sync contract tests."""
from datetime import datetime, timezone
from http.client import HTTPConnection
from pathlib import Path
from tempfile import TemporaryDirectory
import secrets
import time
import unittest
from uuid import UUID

from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.foreground_purge import (
    PurgeRelay, decode_note_ids, encode_note_ids, shared_key, signature,
)
from reasonnotes_sync.repository import Repository

NOTE = UUID("4a9017cb-d038-4fa3-889d-693315f61f11")
OTHER = UUID("1a3917cb-d038-4fa3-889d-693315f61f22")


class PurgeRelayTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Repository.open(Path(self.temp.name) / "repository")
        self.key = secrets.token_bytes(32)
        self.relay = PurgeRelay(self.repo, self.key, host="127.0.0.1", port=0)
        self.relay.start()
        self.addCleanup(self.relay.stop)

    def request(self, method="GET", body=b"", *, valid=True, nonce=None, age=0):
        when = str(int(time.time()) - age)
        nonce = nonce or secrets.token_hex(16)
        signature_value = signature(self.key if valid else b"0" * 32,
                                    method, "/v1/purges", when, nonce, body)
        conn = HTTPConnection("127.0.0.1", self.relay.port, timeout=3)
        conn.request(method, "/v1/purges", body=body,
                     headers={"X-RN-Time": when, "X-RN-Nonce": nonce,
                              "X-RN-Sig": signature_value})
        response = conn.getresponse()
        status, payload = response.status, response.read()
        conn.close()
        return status, payload

    def test_key_generation_is_private_and_stable(self):
        path = Path(self.temp.name) / "settings" / "key.txt"
        first = shared_key(path)
        self.assertEqual(first, shared_key(path))
        self.assertEqual(32, len(first))
        self.assertEqual(path.stat().st_mode & 0o077, 0)

    def test_authenticated_sync_is_idempotent_and_wipes_both_persisted_versions(self):
        first = self.repo.create_revision(
            NOTE, "private original", "linux",
            datetime(2026, 10, 16, 13, tzinfo=timezone.utc))
        second = self.repo.create_revision(
            NOTE, "private edit", "linux",
            datetime(2026, 10, 16, 14, tzinfo=timezone.utc))
        before = self.repo.history_dir / str(NOTE)
        self.assertEqual(2, len(tuple(before.glob("*.md"))))
        status, response = self.request("POST", encode_note_ids([NOTE]))
        self.assertEqual(status, 200)
        self.assertEqual(decode_note_ids(response), {NOTE})
        self.assertFalse(before.exists())
        self.assertIsNone(self.repo.revision(first.revision_id))
        self.assertIsNone(self.repo.revision(second.revision_id))
        self.assertEqual(self.repo.import_bytes(first.serialize(), "stale.md").kind, "purged")
        self.assertEqual(self.request("POST", encode_note_ids([NOTE]))[0], 200)
        self.assertEqual(self.repo.purged_ids(), frozenset({NOTE}))

    def test_unauthorized_replay_and_expired_requests_cannot_purge(self):
        self.repo.create_revision(
            NOTE, "keep secret", "linux",
            datetime(2026, 10, 16, 13, tzinfo=timezone.utc))
        payload = encode_note_ids([NOTE])
        self.assertEqual(self.request("POST", payload, valid=False)[0], 401)
        self.assertEqual(self.request("POST", payload, age=1000)[0], 401)
        nonce = secrets.token_hex(16)
        self.assertEqual(self.request("GET", nonce=nonce)[0], 200)
        self.assertEqual(self.request("POST", payload, nonce=nonce)[0], 409)
        self.assertFalse(self.repo.is_purged(NOTE))
        self.assertEqual(self.repo.graph().status(NOTE).head_ids != (), True)

    def test_server_response_is_authentic_and_bound_to_request(self):
        method, path = "POST", "/v1/purges"
        body = encode_note_ids([OTHER])
        when = str(int(time.time()))
        nonce = secrets.token_hex(16)
        conn = HTTPConnection("127.0.0.1", self.relay.port, timeout=3)
        conn.request(method, path, body=body, headers={
            "X-RN-Time": when,
            "X-RN-Nonce": nonce,
            "X-RN-Sig": signature(self.key, method, path, when, nonce, body),
        })
        response = conn.getresponse()
        result = response.read()
        self.assertEqual(response.status, 200)
        self.assertEqual(
            response.getheader("X-RN-Response-Sig"),
            signature(self.key, "RESPONSE", path, when, nonce, result))
        self.assertNotEqual(
            response.getheader("X-RN-Response-Sig"),
            signature(self.key, "RESPONSE", path, when, nonce, encode_note_ids([])))
        conn.close()

    def test_unrelated_note_remains_and_bad_payload_is_rejected(self):
        live = self.repo.create_revision(
            OTHER, "stay", "linux",
            datetime(2026, 10, 16, 13, tzinfo=timezone.utc))
        self.assertEqual(self.request("POST", b"not-an-uuid\n")[0], 422)
        self.assertEqual(self.request("POST", encode_note_ids([NOTE]))[0], 200)
        self.assertEqual(self.repo.revision(live.revision_id), live)
        self.assertEqual(self.request()[0], 200)


if __name__ == "__main__":
    unittest.main()
