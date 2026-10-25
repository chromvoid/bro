import copy
from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
import plistlib
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


MODULE_PATH = Path(__file__).resolve().parents[2] / "verify_archive.py"
SPEC = importlib.util.spec_from_file_location("ios_verify_archive", MODULE_PATH)
verify = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verify
SPEC.loader.exec_module(verify)


class ArtifactMetadataTests(unittest.TestCase):
    def setUp(self):
        # Older accounts may have a prefix distinct from their Team ID.
        self.publisher = verify.Publisher("TEAM123456", "dev.example.assistant", "SEED123456",
                                          "https://push.example.test", "https://example.test/support")
        self.now = datetime(2026, 10, 5, tzinfo=timezone.utc)

    def metadata(self, *, extension=False, distribution=False):
        bundle = self.publisher.bundle + (".notifications" if extension else "")
        info = {
            "CFBundleIdentifier": bundle,
            "CFBundlePackageType": "XPC!" if extension else "APPL",
            "CFBundleVersion": "108",
            "CFBundleShortVersionString": "0.1.0",
            "AssistantAppGroup": self.publisher.group,
            "AssistantKeychainGroup": self.publisher.keychain,
        }
        claims = {
            "application-identifier": f"{self.publisher.prefix}.{bundle}",
            verify.TEAM: self.publisher.team,
            verify.APP_GROUPS: [self.publisher.group],
            "keychain-access-groups": [self.publisher.keychain],
            "get-task-allow": not distribution,
        }
        if extension:
            info["NSExtension"] = {"NSExtensionPointIdentifier": "com.apple.usernotifications.service"}
        else:
            info.update({"AssistantAPNsEnvironment": "production", "AssistantOrigin": "",
                         "AssistantPushRelay": self.publisher.relay, "AssistantPublisherSupport": self.publisher.support})
            for name in ("NSCameraUsageDescription", "NSMicrophoneUsageDescription", "NSPhotoLibraryUsageDescription", "NSLocalNetworkUsageDescription",
                         "NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription"):
                info[name] = "Synthetic permission explanation"
            claims.update({"aps-environment": "production" if distribution else "development", verify.ATTEST: "production"})
        grants = copy.deepcopy(claims)
        grants["keychain-access-groups"] = [f"{self.publisher.prefix}.*"]
        if not extension:
            grants[verify.ATTEST] = ["development", "production"]
        profile = {
            "TeamIdentifier": [self.publisher.team],
            "ApplicationIdentifierPrefix": [self.publisher.prefix],
            "Platform": ["iOS"],
            "CreationDate": self.now - timedelta(days=1),
            "ExpirationDate": self.now + timedelta(days=30),
            "DeveloperCertificates": [b"synthetic public certificate"],
            "Entitlements": grants,
        }
        if not distribution:
            profile["ProvisionedDevices"] = ["synthetic-device"]
        signature = {"Identifier": bundle, "TeamIdentifier": self.publisher.team,
                     "Authority": ("Apple Distribution:" if distribution else "Apple Development:") + " Synthetic Publisher"}
        return info, claims, profile, signature, b"synthetic public certificate"

    def validate(self, data, *, extension=False, distribution=False):
        verify.validate_bundle(*data, self.publisher, extension=extension, distribution=distribution, now=self.now)

    def test_development_archive_accepts_profile_aps_and_distinct_prefix(self):
        self.validate(self.metadata())

    def test_fcm_archive_requires_identity_and_native_opt_in(self):
        publisher = verify.Publisher(self.publisher.team, self.publisher.bundle, self.publisher.prefix,
                                     self.publisher.relay, self.publisher.support, "fcm", "synthetic-push")
        info = {"AssistantFirebaseProjectID": "synthetic-push", "FirebaseAppDelegateProxyEnabled": False,
                "FirebaseMessagingAutoInitEnabled": False, "FirebaseMessagingInstallationIdEnabled": False}
        config = {"BUNDLE_ID": publisher.bundle, "PROJECT_ID": "synthetic-push", "GOOGLE_APP_ID": "synthetic-app",
                  "GCM_SENDER_ID": "123456", "API_KEY": "synthetic-public-key"}
        verify.validate_firebase(info, config, publisher)
        for key, value in [("BUNDLE_ID", "another.app"), ("PROJECT_ID", "another-publisher"), ("API_KEY", "")]:
            wrong = dict(config, **{key: value})
            with self.subTest(key=key), self.assertRaises(verify.VerificationError):
                verify.validate_firebase(info, wrong, publisher)
        for key in ("FirebaseAppDelegateProxyEnabled", "FirebaseMessagingAutoInitEnabled", "FirebaseMessagingInstallationIdEnabled"):
            with self.subTest(key=key), self.assertRaises(verify.VerificationError):
                verify.validate_firebase(dict(info, **{key: True}), config, publisher)
        with self.assertRaises(verify.VerificationError):
            verify.validate_firebase(info, None, publisher)
        verify.validate_firebase({}, None, self.publisher)
        with self.assertRaises(verify.VerificationError):
            verify.validate_firebase({}, config, self.publisher)

    def test_exported_app_and_extension_require_distribution(self):
        for extension in (False, True):
            with self.subTest(extension=extension):
                self.validate(self.metadata(extension=extension, distribution=True), extension=extension, distribution=True)

    def test_export_rejects_development_signature(self):
        data = self.metadata(distribution=True)
        data[3]["Authority"] = "Apple Development: Synthetic Publisher"
        with self.assertRaisesRegex(verify.VerificationError, "not distribution-signed"):
            self.validate(data, distribution=True)

    def test_export_rejects_development_aps_even_with_matching_profile(self):
        data = self.metadata(distribution=True)
        data[1]["aps-environment"] = data[2]["Entitlements"]["aps-environment"] = "development"
        with self.assertRaisesRegex(verify.VerificationError, "APNs environment must be production"):
            self.validate(data, distribution=True)

    def test_export_rejects_ad_hoc_and_enterprise_profiles(self):
        for key, value in [("ProvisionedDevices", ["synthetic-device"]), ("ProvisionsAllDevices", True)]:
            with self.subTest(key=key):
                data = self.metadata(distribution=True)
                data[2][key] = value
                with self.assertRaisesRegex(verify.VerificationError, "App Store provisioning"):
                    self.validate(data, distribution=True)

    def test_team_and_app_id_prefix_are_independent(self):
        for location in ("profile", "entitlement", "signature"):
            with self.subTest(location=location):
                data = self.metadata()
                if location == "profile":
                    data[2]["ApplicationIdentifierPrefix"] = [self.publisher.team]
                elif location == "entitlement":
                    data[1]["application-identifier"] = f"{self.publisher.team}.{self.publisher.bundle}"
                else:
                    data[3]["TeamIdentifier"] = self.publisher.prefix
                with self.assertRaises(verify.VerificationError):
                    self.validate(data)

    def test_rejects_signer_not_in_profile(self):
        data = self.metadata()
        data[2]["DeveloperCertificates"] = [b"another public certificate"]
        with self.assertRaisesRegex(verify.VerificationError, "signing certificate is not authorized"):
            self.validate(data)

    def test_profile_expiry_and_not_yet_valid(self):
        for key in ("CreationDate", "ExpirationDate"):
            with self.subTest(key=key):
                data = self.metadata()
                data[2][key] = self.now + timedelta(days=1) if key == "CreationDate" else self.now
                with self.assertRaises(verify.VerificationError):
                    self.validate(data)

    def test_profile_must_authorize_group_and_attest_claims(self):
        for claim in (verify.APP_GROUPS, verify.ATTEST, "aps-environment"):
            with self.subTest(claim=claim):
                data = self.metadata()
                del data[2]["Entitlements"][claim]
                with self.assertRaisesRegex(verify.VerificationError, "profile does not authorize"):
                    self.validate(data)

    def test_profile_keychain_wildcard_does_not_allow_other_prefix(self):
        data = self.metadata()
        data[2]["Entitlements"]["keychain-access-groups"] = [f"{self.publisher.team}.*"]
        with self.assertRaisesRegex(verify.VerificationError, "profile does not authorize keychain"):
            self.validate(data)

    def test_additional_shared_groups_and_wrong_compiled_group_are_rejected(self):
        for mutation in ("group", "keychain", "compiled"):
            with self.subTest(mutation=mutation):
                data = self.metadata()
                if mutation == "group":
                    data[1][verify.APP_GROUPS].append("group.dev.other")
                    data[2]["Entitlements"][verify.APP_GROUPS].append("group.dev.other")
                elif mutation == "keychain":
                    data[1]["keychain-access-groups"].append(f"{self.publisher.prefix}.another.shared")
                else:
                    data[0]["AssistantKeychainGroup"] = f"{self.publisher.team}.{self.publisher.bundle}.shared"
                with self.assertRaises(verify.VerificationError):
                    self.validate(data)

    def test_release_rejects_fixed_installation_and_wrong_publisher(self):
        for name, value in [("AssistantOrigin", "https://family.example.test"),
                            ("AssistantPushRelay", "https://other.example.test"),
                            ("AssistantPublisherSupport", "https://other.example.test/contact"),
                            ("NSAppTransportSecurity", {"NSAllowsArbitraryLoads": True})]:
            with self.subTest(name=name):
                data = self.metadata()
                data[0][name] = value
                with self.assertRaises(verify.VerificationError):
                    self.validate(data)

    def test_privacy_shared_defaults_requires_app_group_reason(self):
        manifest = {"NSPrivacyTracking": False, "NSPrivacyCollectedDataTypes": [],
                    "NSPrivacyAccessedAPITypes": [{"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
                                                  "NSPrivacyAccessedAPITypeReasons": ["CA92.1", "1C8F.1"]}]}
        verify.validate_privacy(manifest, extension=False)
        verify.validate_privacy(manifest, extension=True)
        manifest["NSPrivacyAccessedAPITypes"][0]["NSPrivacyAccessedAPITypeReasons"] = ["CA92.1"]
        with self.assertRaisesRegex(verify.VerificationError, "missing UserDefaults reasons"):
            verify.validate_privacy(manifest, extension=True)

    def test_unsigned_artifact_cannot_reach_profile_decoding(self):
        failure = subprocess.CompletedProcess(["codesign"], 1, b"", b"not signed at all")
        with patch.object(verify.subprocess, "run", return_value=failure) as command:
            with self.assertRaisesRegex(verify.VerificationError, "signature: verification command failed"):
                verify.signed_metadata(Path("Synthetic.app"), self.publisher)
        self.assertEqual(command.call_count, 1)
        arguments = command.call_args.args[0]
        self.assertIn("--strict", arguments)
        self.assertIn("--deep", arguments)
        self.assertEqual(arguments[arguments.index("-R") + 1],
                         f'=anchor apple generic and certificate leaf[subject.OU] = "{self.publisher.team}"')

    def test_signature_failure_reports_a_bounded_reason_without_workspace_path(self):
        bundle = Path("/private/synthetic-ci-workspace/Synthetic.app")
        failure = subprocess.CompletedProcess(["codesign"], 1, b"",
                                              (str(bundle) + ": code object is not signed at all\n" + "x" * 1000).encode())
        with patch.object(verify.subprocess, "run", return_value=failure):
            with self.assertRaises(verify.VerificationError) as error:
                verify.signed_metadata(bundle, self.publisher)
        self.assertIn("Synthetic.app: code object is not signed at all", str(error.exception))
        self.assertNotIn("synthetic-ci-workspace", str(error.exception))
        self.assertLess(len(str(error.exception)), 700)

    def test_provisioning_failure_never_prints_decoded_profile_diagnostics(self):
        private = b"synthetic-private-provisioned-device-id"
        failure = subprocess.CompletedProcess(["security"], 1, private, private)
        with patch.object(verify.subprocess, "run", return_value=failure):
            with self.assertRaises(verify.VerificationError) as error:
                verify.run(["security", "cms", "-D", "-i", "synthetic.mobileprovision"], "profile")
        self.assertEqual(str(error.exception), "profile: verification command failed")

    def test_signed_metadata_extracts_leaf_certificate_with_an_attached_prefix(self):
        certificate = b"synthetic public leaf certificate"
        claims = {"synthetic": "entitlements"}
        profile = {"synthetic": "provisioning"}
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "Synthetic.app"
            bundle.mkdir()
            (bundle / "embedded.mobileprovision").write_bytes(b"synthetic CMS")

            def invoke(arguments, **options):
                output = b""
                if "--entitlements" in arguments:
                    output = plistlib.dumps(claims)
                elif arguments[:2] == ["security", "cms"]:
                    output = plistlib.dumps(profile)
                elif any(arg.startswith("--extract-certificates") for arg in arguments):
                    self.assertEqual(len(arguments), 4)
                    self.assertEqual(arguments[-1], str(bundle))
                    self.assertTrue(arguments[2].startswith("--extract-certificates="))
                    prefix = arguments[2].split("=", 1)[1]
                    Path(prefix + "0").write_bytes(certificate)
                elif "--verbose=4" in arguments:
                    output = b"Identifier=dev.example.assistant\n"
                return subprocess.CompletedProcess(arguments, 0, output, b"")

            with patch.object(verify.subprocess, "run", side_effect=invoke) as command:
                result = verify.signed_metadata(bundle, self.publisher)
            self.assertEqual(command.call_count, 5)
        self.assertEqual(result, (claims, profile, {"Identifier": "dev.example.assistant"}, certificate))

    def test_missing_archive_fails_without_any_signing_command(self):
        with patch.object(verify.subprocess, "run") as command:
            with self.assertRaises(verify.VerificationError):
                verify.verify_artifact(Path("missing.xcarchive"), self.publisher, distribution=False)
        command.assert_not_called()

    def test_ipa_path_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "Synthetic.ipa"
            with zipfile.ZipFile(archive, "w") as ipa:
                ipa.writestr("../outside", "synthetic")
            with self.assertRaisesRegex(verify.VerificationError, "unsafe archive path"):
                verify.extract_ipa(archive, root / "extracted")
            self.assertFalse((root / "outside").exists())

    def test_exported_ipa_requires_distribution_gate(self):
        with self.assertRaisesRegex(verify.VerificationError, "requires --distribution"):
            verify.verify_artifact(Path("Synthetic.ipa"), self.publisher, distribution=False)

    def test_malformed_xml_is_a_deterministic_verification_error(self):
        with self.assertRaisesRegex(verify.VerificationError, "invalid property list"):
            verify.plist(b'<?xml version="1.0"?><plist><dict><key>unfinished', "synthetic metadata")


class FrameworkPreparationTests(unittest.TestCase):
    def test_framework_metadata_cannot_understate_its_binary_minimum(self):
        with self.assertRaisesRegex(verify.VerificationError, "below Mach-O"):
            verify.validate_framework_minimum({"MinimumOSVersion": "15.1"}, (16, 0, 0), (16, 0, 0))
        with self.assertRaisesRegex(verify.VerificationError, "exceeds app"):
            verify.validate_framework_minimum({"MinimumOSVersion": "17.0"}, (17, 0, 0), (16, 0, 0))
        verify.validate_framework_minimum({"MinimumOSVersion": "16.0"}, (16, 0, 0), (16, 0, 0))

    def test_macho_build_version_is_read_for_ios(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "binary"
            header = struct.pack("<8I", 0xFEEDFACF, 0x100000C, 0, 6, 1, 24, 0, 0)
            p.write_bytes(header + struct.pack("<6I", 0x32, 24, 2, (16 << 16) | (1 << 8), 27 << 16, 0))
            self.assertEqual(verify.macho_minimum(p), (16, 1, 0))
            p.write_bytes(header + struct.pack("<6I", 0x32, 24, 7, 16 << 16, 27 << 16, 0))
            with self.assertRaisesRegex(verify.VerificationError, "non-iOS"):
                verify.macho_minimum(p)

    def fat(self, slices, *, order=">", wide=False):
        magic = (b"\xca\xfe\xba\xbf" if wide else b"\xca\xfe\xba\xbe")
        if order == "<":
            magic = magic[::-1]
        entry = "IIQQII" if wide else "IIIII"
        offset = 8 + len(slices) * struct.calcsize(order + entry)
        table = b""
        for value in slices:
            fields = (0x100000C, 0, offset, len(value), 0) + ((0,) if wide else ())
            table += struct.pack(order + entry, *fields)
            offset += len(value)
        return magic + struct.pack(order + "I", len(slices)) + table + b"".join(slices)

    def macho(self, minimum=(16, 0, 0), platform=2):
        version = (minimum[0] << 16) | (minimum[1] << 8) | minimum[2]
        return (struct.pack("<8I", 0xFEEDFACF, 0x100000C, 0, 6, 1, 24, 0, 0)
                + struct.pack("<6I", 0x32, 24, platform, version, 27 << 16, 0))

    def test_thin_and_all_universal_archive_slices_are_static(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "binary"
            for value in (b"!<arch>\n", self.fat([b"!<arch>\n" * 2]),
                          self.fat([b"!<arch>\n"] * 2, order="<", wide=True)):
                p.write_bytes(value)
                self.assertTrue(verify.is_static_library(p))
            for value in (self.macho(), self.fat([b"!<arch>\n", self.macho()])):
                p.write_bytes(value)
                self.assertFalse(verify.is_static_library(p))

    def test_invalid_universal_slice_cannot_trigger_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "binary"
            p.write_bytes(b"\xca\xfe\xba\xbe" + struct.pack(">6I", 1, 0x100000C, 0, 4, 1000, 0))
            with self.assertRaisesRegex(verify.VerificationError, "slice bounds"):
                verify.is_static_library(p)

    def app(self, root, *, declared="15.1", minimum=(16, 0, 0), static=False, name="onnxruntime.framework"):
        app = root / "Synthetic.app"
        framework = app / "Frameworks" / name
        framework.mkdir(parents=True)
        (framework / "Info.plist").write_bytes(plistlib.dumps({"CFBundleExecutable": "onnxruntime",
                                                             "MinimumOSVersion": declared}, fmt=plistlib.FMT_BINARY))
        binary = self.fat([b"!<arch>\n" * 2]) if static else self.macho(minimum)
        (framework / "onnxruntime").write_bytes(binary)
        (app / "Info.plist").write_bytes(plistlib.dumps({"CFBundleExecutable": "Synthetic", "MinimumOSVersion": "16.0"}))
        (app / "Synthetic").write_bytes(self.macho())
        return app, framework

    def test_existing_signature_failure_preserves_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            app, framework = self.app(Path(directory))
            original = (framework / "Info.plist").read_bytes()
            with patch.object(verify, "signed_metadata", side_effect=verify.VerificationError("original signature")):
                with self.assertRaisesRegex(verify.VerificationError, "original signature"):
                    verify.prepare_frameworks(app, None)
            self.assertEqual((framework / "Info.plist").read_bytes(), original)

    def test_preparation_changes_only_plist_and_resigns_framework_then_app(self):
        with tempfile.TemporaryDirectory() as directory:
            app, framework = self.app(Path(directory))
            executable = (framework / "onnxruntime").read_bytes()
            app_executable = (app / "Synthetic").read_bytes()
            license_path = app / "ONNXRUNTIME-LICENSE.txt"
            license_path.write_text("synthetic license")
            certificate = b"synthetic public signing certificate"
            with patch.object(verify, "signed_metadata", return_value=({}, {}, {}, certificate)), \
                 patch.object(verify, "validate_bundle"), patch.object(verify, "run") as signing:
                verify.prepare_frameworks(app, None)
            metadata = (framework / "Info.plist").read_bytes()
            self.assertTrue(metadata.startswith(b"bplist00"))
            self.assertEqual(plistlib.loads(metadata)["MinimumOSVersion"], "16.0.0")
            self.assertEqual((framework / "onnxruntime").read_bytes(), executable)
            self.assertEqual((app / "Synthetic").read_bytes(), app_executable)
            self.assertEqual(license_path.read_text(), "synthetic license")
            self.assertEqual(signing.call_count, 2)
            for call, target in zip(signing.call_args_list, (framework, app)):
                arguments = call.args[0]
                self.assertEqual(arguments[:3], ["codesign", "--force", "--sign"])
                self.assertEqual(arguments[3], verify.hashlib.sha1(certificate).hexdigest().upper())
                self.assertIn("--preserve-metadata=identifier,entitlements,flags", arguments)
                self.assertEqual(arguments[-1], str(target))

    def test_preparation_never_lowers_required_ios_or_modifies_other_frameworks(self):
        for options in ({"minimum": (17, 0, 0)}, {"name": "Another.framework"}, {"static": True}):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                app, framework = self.app(Path(directory), **options)
                original = (framework / "Info.plist").read_bytes()
                with patch.object(verify, "signed_metadata") as signing:
                    with self.assertRaises(verify.VerificationError):
                        verify.prepare_frameworks(app, None)
                    signing.assert_not_called()
                self.assertEqual((framework / "Info.plist").read_bytes(), original)
                self.assertTrue(framework.exists())

    def test_matching_metadata_is_left_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            app, framework = self.app(Path(directory), declared="16.0")
            original = (framework / "Info.plist").read_bytes()
            with patch.object(verify, "run") as signing:
                verify.prepare_frameworks(app, None)
                signing.assert_not_called()
            self.assertEqual((framework / "Info.plist").read_bytes(), original)

    def test_verification_rejects_embedded_archive_before_signing(self):
        with tempfile.TemporaryDirectory() as directory:
            app, _ = self.app(Path(directory), static=True)
            with patch.object(verify, "signed_metadata") as signing:
                with self.assertRaisesRegex(verify.VerificationError, "static library embedded"):
                    verify.verify_app(app, None, distribution=True)
                signing.assert_not_called()


if __name__ == "__main__":
    unittest.main()
