import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("swift"), "Swift toolchain required by iOS build")
class FirebaseBuildConfigurationTests(unittest.TestCase):
    def test_opt_in_checks_identity_and_apns_removes_stale_plist(self):
        script = Path(__file__).resolve().parents[1] / "validate-firebase-config.swift"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Firebase" / "GoogleService-Info.plist"
            source.parent.mkdir()
            target = root / "output" / "Bro.app" / "GoogleService-Info.plist"
            config = {"BUNDLE_ID": "dev.synthetic.assistant", "PROJECT_ID": "synthetic-push",
                      "GOOGLE_APP_ID": "1:123:ios:synthetic", "GCM_SENDER_ID": "123", "API_KEY": "synthetic-public-key-never-print"}
            source.write_bytes(plistlib.dumps(config))
            environment = {**os.environ, "SRCROOT": str(root), "TARGET_BUILD_DIR": str(root / "output"),
                           "UNLOCALIZED_RESOURCES_FOLDER_PATH": "Bro.app", "PRODUCT_BUNDLE_IDENTIFIER": "dev.synthetic.assistant",
                           "ASSISTANT_PUSH_TRANSPORT": "fcm", "ASSISTANT_FCM_PROJECT_ID": "synthetic-push",
                           "ASSISTANT_FIREBASE_CONFIG_PATH": str(source)}

            def invoke():
                result = subprocess.run(["swift", str(script)], env=environment, capture_output=True, text=True)
                self.assertNotIn(config["API_KEY"], result.stdout + result.stderr)
                return result

            self.assertEqual(invoke().returncode, 0)
            self.assertEqual(plistlib.loads(target.read_bytes()), config)
            environment["ASSISTANT_FCM_PROJECT_ID"] = "another-project"
            self.assertNotEqual(invoke().returncode, 0)
            self.assertEqual(plistlib.loads(target.read_bytes()), config)
            environment["ASSISTANT_FCM_PROJECT_ID"] = "synthetic-push"
            environment["PRODUCT_BUNDLE_IDENTIFIER"] = "another.app"
            self.assertNotEqual(invoke().returncode, 0)
            environment["ASSISTANT_PUSH_TRANSPORT"] = "apns"
            self.assertEqual(invoke().returncode, 0)
            self.assertFalse(target.exists())
            environment["ASSISTANT_PUSH_TRANSPORT"] = "fcm"
            source.unlink()
            self.assertNotEqual(invoke().returncode, 0)


if __name__ == "__main__":
    unittest.main()
