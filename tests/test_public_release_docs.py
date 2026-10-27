from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class PublicReleaseDocumentationTests(unittest.TestCase):
    def test_readme_documents_first_sync_and_supported_storage_paths(self):
        readme = (ROOT / "README.md").read_text()

        for required in (
            "Settings → Computer",
            "kdeconnect-cli -l",
            "ReasonNotes Exchange",
            "Android 8–10",
            "Android 11",
            "grep 'reasonnotes-android-0.1.apk$' SHA256SUMS",
        ):
            self.assertIn(required, readme)

    def test_future_apk_carries_apache_license_and_notice(self):
        licenses = ROOT / "android-probe/app/src/main/assets/licenses"
        apache = licenses / "LICENSE-Apache-2.0.txt"
        notice = licenses / "NOTICE.txt"

        self.assertTrue(apache.is_file())
        self.assertIn("Apache License", apache.read_text())
        self.assertIn("Version 2.0, January 2004", apache.read_text())
        self.assertTrue(notice.is_file())
        self.assertIn("AndroidX", notice.read_text())
        self.assertIn("Gson", notice.read_text())
        self.assertIn(
            "android-probe/app/src/main/assets/licenses/LICENSE-Apache-2.0.txt",
            (ROOT / "THIRD_PARTY_NOTICES.md").read_text(),
        )


if __name__ == "__main__":
    unittest.main()
