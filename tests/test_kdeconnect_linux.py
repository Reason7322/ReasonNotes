from dataclasses import dataclass
from pathlib import Path
import unittest

from reasonnotes_sync.kdeconnect_linux import KdeConnectLinux


DEVICE_ID = "52548cf9710c43cab2fa73ed4671aea9"


@dataclass
class Result:
    returncode: int = 0
    stdout: str = ""
    stderr: str = ""


class FakeRunner:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def __call__(self, arguments, *, env):
        self.calls.append((tuple(arguments), env.copy()))
        return self.results.pop(0)


class KdeConnectLinuxTests(unittest.TestCase):
    def test_reachability_uses_exact_command_environment_and_device_id(self):
        runner = FakeRunner(Result(stdout=f"{DEVICE_ID}\nother-device\n"))
        adapter = KdeConnectLinux(DEVICE_ID, runner)

        self.assertTrue(adapter.reachable())
        arguments, environment = runner.calls[0]
        self.assertEqual(arguments, ("kdeconnect-cli", "--list-available", "--id-only"))
        self.assertEqual(environment["XDG_RUNTIME_DIR"], "/run/user/1000")
        self.assertEqual(
            environment["DBUS_SESSION_BUS_ADDRESS"], "unix:path=/run/user/1000/bus"
        )

    def test_unavailable_or_failed_device_check_is_false(self):
        self.assertFalse(
            KdeConnectLinux(DEVICE_ID, FakeRunner(Result(stdout="other\n"))).reachable()
        )
        self.assertFalse(
            KdeConnectLinux(DEVICE_ID, FakeRunner(Result(returncode=1))).reachable()
        )

    def test_request_transfer_preserves_path_with_spaces_and_only_accepts_request(self):
        runner = FakeRunner(Result(stdout="sent\n"))
        adapter = KdeConnectLinux(DEVICE_ID, runner)
        path = Path("/tmp/ReasonNotes Exchange/note one.md")

        request = adapter.request_transfer(path)

        self.assertEqual(
            runner.calls[0][0],
            ("kdeconnect-cli", "--device", DEVICE_ID, "--share", str(path)),
        )
        self.assertTrue(request.accepted)
        self.assertFalse(request.completion_observed)
        self.assertIn("request", request.detail)

    def test_failed_transfer_request_is_not_accepted(self):
        request = KdeConnectLinux(
            DEVICE_ID, FakeRunner(Result(returncode=2, stderr="unreachable"))
        ).request_transfer(Path("/tmp/note.md"))

        self.assertFalse(request.accepted)
        self.assertFalse(request.completion_observed)
        self.assertIn("unreachable", request.detail)

    def test_received_paths_decode_only_share_object_file_urls(self):
        adapter = KdeConnectLinux(DEVICE_ID, FakeRunner())
        lines = (
            f"/modules/kdeconnect/devices/{DEVICE_ID}/share: "
            "org.kde.kdeconnect.device.share.shareReceived "
            "('file:///home/reason/Downloads/note%20one.md',)",
            f"/modules/kdeconnect/devices/{DEVICE_ID}/share: "
            "org.kde.kdeconnect.device.share.shareReceived ('https://example.test/note.md',)",
            f"/modules/kdeconnect/devices/other/share: "
            "org.kde.kdeconnect.device.share.shareReceived "
            "('file:///home/reason/Downloads/other.md',)",
            "unrelated monitor output",
        )

        self.assertEqual(
            tuple(adapter.received_paths(lines)),
            (Path("/home/reason/Downloads/note one.md"),),
        )


if __name__ == "__main__":
    unittest.main()
