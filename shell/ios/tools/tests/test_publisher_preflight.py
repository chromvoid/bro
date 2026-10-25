import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("publisher_preflight", ROOT / "publisher_preflight.py")
publisher = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = publisher
spec.loader.exec_module(publisher)


def environment():
    return {"IOS_TEAM_ID": "TEAM123456", "IOS_APP_ID_PREFIX": "PREFIX1234",
            "IOS_BUNDLE_ID": "dev.synthetic.assistant", "ASSISTANT_PUSH_RELAY": "https://push.synthetic.example",
            "ASSISTANT_PUBLISHER_SUPPORT_URL": "https://synthetic.example/support",
            "ASC_KEY_ID": "KEY1234567", "ASC_ISSUER_ID": "10000000-0000-0000-0000-000000000001",
            "ASC_KEY_P8": "synthetic-test-value-not-an-Apple-key"}


class PublisherPreflightTests(unittest.TestCase):
    def get(self, path):
        query = parse_qs(urlsplit(path).query)
        if path.startswith("/v1/bundleIds?"):
            return {"data": [{"attributes": {"identifier": query["filter[identifier]"][0],
                                               "platform": "IOS", "seedId": "PREFIX1234"}}]}
        return {"data": [{"attributes": {"bundleId": "dev.synthetic.assistant", "name": "Synthetic"}}]}

    def test_prefix_may_differ_from_team_and_both_identifiers_are_checked(self):
        config = publisher.configuration(environment())
        self.assertNotEqual(config.team, config.prefix)
        paths = []
        def get(path):
            paths.append(path)
            return self.get(path)
        self.assertEqual(len(publisher.preflight(config, get)), 3)
        self.assertEqual([urlsplit(path).path for path in paths], ["/v1/bundleIds", "/v1/bundleIds", "/v1/apps"])
        self.assertIn("dev.synthetic.assistant.notifications", paths[1])

    def test_mismatched_prefix_and_missing_extension_stop_preflight(self):
        config = publisher.configuration(environment())
        for missing in (False, True):
            def get(path):
                result = self.get(path)
                if ".notifications" in path:
                    if missing:
                        result["data"] = []
                    else:
                        result["data"][0]["attributes"]["seedId"] = config.team
                return result
            with self.subTest(missing=missing), self.assertRaises(publisher.PreflightError):
                publisher.preflight(config, get)

    def test_app_lookup_requires_the_exact_bundle(self):
        config = publisher.configuration(environment())
        def get(path):
            return {"data": [{"attributes": {"bundleId": "other.app"}}]} if path.startswith("/v1/apps?") else self.get(path)
        with self.assertRaises(publisher.PreflightError):
            publisher.preflight(config, get)

    def test_nonsecret_validation_rejects_unsafe_identifiers_and_urls(self):
        cases = {"IOS_TEAM_ID": ["team", "TEAM12345<"], "IOS_APP_ID_PREFIX": ["", "bad-prefix"],
                 "IOS_BUNDLE_ID": ["wildcard.*", "dev.example?filter=x", "dev.<example>", "a" * 256],
                 "ASSISTANT_PUSH_RELAY": ["http://push.example", "https://push.example/path", "https://push.example?secret=x", "https://user:secret@push.example", "https://push.example:65536", "https://%20.example"],
                 "ASSISTANT_PUBLISHER_SUPPORT_URL": ["", "http://example/support", "https:///support", "https://user@example/support", "https://example/\n", "https://example/\u200b"]}
        for name, values in cases.items():
            for value in values:
                with self.subTest(name=name, value=value), self.assertRaises(publisher.PreflightError):
                    publisher.configuration(dict(environment(), **{name: value}))

    def test_keyless_diagnostic_never_reads_credentials_or_contacts_apple(self):
        values = {k: v for k, v in environment().items() if not k.startswith("ASC_")}
        with patch.object(publisher, "token", side_effect=AssertionError("must not sign")), patch.object(publisher, "apple_get", side_effect=AssertionError("must not contact Apple")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(publisher.main(["config"], environment=values), 0)

    def test_missing_or_invalid_credentials_fail_before_signing(self):
        for name, value in [("ASC_KEY_P8", ""), ("ASC_KEY_ID", "wrong"), ("ASC_ISSUER_ID", "not-a-uuid")]:
            with self.subTest(name=name), patch.object(publisher, "token", side_effect=AssertionError("must not sign")), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(publisher.main(["check", "--key-file", "does-not-exist"], environment=dict(environment(), **{name: value})), 1)

    def test_http_transport_is_bounded_get_with_redirects_disabled(self):
        requests = []
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, maximum):
                self.maximum = maximum
                return b'{"data":[]}'
        response = Response()
        class Opener:
            def open(self, request, timeout):
                requests.append((request, timeout))
                return response
        with patch.object(publisher, "build_opener", return_value=Opener()) as opener:
            self.assertEqual(publisher.apple_get("/v1/apps?x=1", "synthetic-token"), {"data": []})
        request, timeout = requests[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.full_url, "https://api.appstoreconnect.apple.com/v1/apps?x=1")
        self.assertEqual(timeout, 20)
        self.assertEqual(response.maximum, 131_073)
        self.assertIsInstance(opener.call_args.args[0], publisher.NoRedirects)
        self.assertIsNone(publisher.NoRedirects().redirect_request(None, None, 302, "", {}, "https://evil.example"))

    def test_http_failures_and_invalid_responses_are_sanitized(self):
        for status in (401, 403, 302, 500):
            with self.subTest(status=status), patch.object(publisher, "build_opener") as opener:
                body = io.BytesIO(b"secret-response-body")
                opener.return_value.open.side_effect = HTTPError("https://apple.example", status, "secret-error", {}, body)
                with self.assertRaises(publisher.PreflightError) as error:
                    publisher.apple_get("/v1/apps", "secret-token")
                self.assertTrue(body.closed)
                self.assertNotIn("secret", str(error.exception))
        for response in [None, {}, {"data": {}}, {"data": [None]}, {"data": [{"attributes": None}]}]:
            with self.subTest(response=response), self.assertRaises(publisher.PreflightError):
                publisher.records(response)

    def test_bad_request_diagnostic_has_only_allowlisted_structural_labels(self):
        cases = [
            ("/v1/bundleIds/PRIVATE-ID/bundleIdCapabilities?limit=200&secret=private-query", None,
             "PARAMETER_ERROR.INVALID", {"parameter": "limit"}, "list-bundle-capabilities", "parameter=limit"),
            ("/v1/bundleIds", {}, "ENTITY_ERROR.ATTRIBUTE.INVALID", {"pointer": "/data/attributes/seedId"},
             "register-bundle-id", "pointer=/data/attributes/seedId"),
            ("/v1/apps?filter[bundleId]=private.bundle", None, "private-code", {"parameter": "private-parameter", "pointer": "/private/pointer"},
             "list-app-records", None),
        ]
        for path, payload, code, source, operation, structural in cases:
            values = {"errors": [{"id": "private-id", "code": code, "source": source,
                                  "title": "private-title", "detail": "private-detail"}]}
            body = io.BytesIO(json.dumps(values).encode())
            with self.subTest(operation=operation), patch.object(publisher, "build_opener") as opener:
                opener.return_value.open.side_effect = HTTPError("https://private.example", 400, "private-reason", {}, body)
                with self.assertRaises(publisher.PreflightError) as error:
                    publisher.apple_request(path, "private-token", payload=payload)
            message = str(error.exception)
            self.assertTrue(body.closed)
            self.assertIn("operation=" + operation, message)
            if structural:
                self.assertIn("code=" + code, message)
                self.assertIn(structural, message)
            else:
                self.assertNotIn("code=", message)
                self.assertNotIn("pointer=", message)
                self.assertNotIn("parameter=", message)
            self.assertNotIn("private", message.lower())

    def test_bad_request_body_is_bounded_and_malformed_content_is_not_logged(self):
        class Body(io.BytesIO):
            def read(self, maximum):
                self.maximum = maximum
                return super().read(maximum)
        for content in (b"private-not-json", b"\xff", b"x" * 8_193,
                        b'{"errors":{}}', b'{"errors":[null,{"source":null}]}'):
            body = Body(content)
            with self.subTest(size=len(content)), patch.object(publisher, "build_opener") as opener:
                opener.return_value.open.side_effect = HTTPError("https://private.example", 400, "private-reason", {}, body)
                with self.assertRaises(publisher.PreflightError) as error:
                    publisher.apple_get("/v1/bundleIds?secret=private", "private-token")
            self.assertEqual(body.maximum, 8_193)
            self.assertTrue(body.closed)
            self.assertEqual(str(error.exception), "App Store Connect answered HTTP 400 [operation=list-bundle-ids]")

    def test_bad_request_reports_at_most_three_error_records(self):
        values = {"errors": [{"code": "PARAMETER_ERROR.INVALID", "source": {"parameter": "limit"}}] * 8}
        body = io.BytesIO(json.dumps(values).encode())
        with patch.object(publisher, "build_opener") as opener:
            opener.return_value.open.side_effect = HTTPError("https://apple.example", 400, "private-reason", {}, body)
            with self.assertRaises(publisher.PreflightError) as error:
                publisher.apple_get("/v1/bundleIds", "private-token")
        self.assertEqual(str(error.exception).count("code="), 3)
        self.assertEqual(str(error.exception).count("parameter="), 3)
        self.assertTrue(body.closed)

    def test_capability_listing_uses_server_default_without_limit_query(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, maximum): return b'{"data":[]}'
        with patch.object(publisher, "build_opener") as opener:
            opener.return_value.open.return_value = Response()
            flags = publisher.capabilities(lambda path: publisher.apple_get(path, "synthetic-token"), "MAIN123456")
            request = opener.return_value.open.call_args.args[0]
        self.assertEqual(flags, set())
        self.assertEqual(request.get_method(), "GET")
        url = urlsplit(request.full_url)
        self.assertEqual(url.path, "/v1/bundleIds/MAIN123456/bundleIdCapabilities")
        self.assertEqual(url.query, "")

    def test_post_is_additive_bounded_and_does_not_follow_redirects(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, maximum):
                self.maximum = maximum
                return b'{"data":{}}'
        response = Response()
        with patch.object(publisher, "build_opener") as opener:
            opener.return_value.open.return_value = response
            body = {"data": {"type": "bundleIds"}}
            publisher.apple_post("/v1/bundleIds", "synthetic-token", body)
            request = opener.return_value.open.call_args.args[0]
            self.assertEqual(request.get_method(), "POST")
            self.assertEqual(json.loads(request.data), body)
            self.assertEqual(request.get_header("Content-type"), "application/json")
            self.assertEqual(response.maximum, 131_073)
            self.assertIsInstance(opener.call_args.args[0], publisher.NoRedirects)
        for path in ("https://untrusted.example/v1/bundleIds", "/v1/apps", "/v1/bundleIds/other"):
            with self.subTest(path=path), self.assertRaises(publisher.PreflightError):
                publisher.apple_post(path, "synthetic-token", {})

    def test_post_unknown_outcomes_require_reconciliation_and_denials_are_final(self):
        for status in (409, 429, 500, 503):
            with self.subTest(status=status), patch.object(publisher, "build_opener") as opener:
                body = io.BytesIO(b"secret-response-body")
                opener.return_value.open.side_effect = HTTPError("https://apple.example", status, "secret-error", {}, body)
                with self.assertRaises(publisher.MutationUnconfirmed) as error:
                    publisher.apple_post("/v1/bundleIds", "secret-token", {})
                self.assertTrue(body.closed)
                self.assertNotIn("secret", str(error.exception))
        for failure in (TimeoutError("secret"), OSError("secret")):
            with self.subTest(failure=type(failure).__name__), patch.object(publisher, "build_opener") as opener:
                opener.return_value.open.side_effect = failure
                with self.assertRaises(publisher.MutationUnconfirmed) as error:
                    publisher.apple_post("/v1/bundleIds", "secret-token", {})
                self.assertNotIn("secret", str(error.exception))
        for status in (400, 401, 403, 302):
            with self.subTest(status=status), patch.object(publisher, "build_opener") as opener:
                body = io.BytesIO(b"secret-response-body")
                opener.return_value.open.side_effect = HTTPError("https://apple.example", status, "secret-error", {}, body)
                with self.assertRaises(publisher.PreflightError) as error:
                    publisher.apple_post("/v1/bundleIds", "secret-token", {})
                self.assertTrue(body.closed)
                self.assertNotIsInstance(error.exception, publisher.MutationUnconfirmed)
                self.assertNotIn("secret", str(error.exception))

    def test_fcm_requires_project_and_matching_public_app_config(self):
        with self.assertRaises(publisher.PreflightError):
            publisher.configuration(dict(environment(), ASSISTANT_PUSH_TRANSPORT="fcm"))
        config = publisher.configuration(dict(environment(), ASSISTANT_PUSH_TRANSPORT="fcm", ASSISTANT_FCM_PROJECT_ID="synthetic-assistant"))
        values = {"BUNDLE_ID": config.bundle, "PROJECT_ID": config.fcm_project,
                  "GOOGLE_APP_ID": "1:123456:ios:synthetic", "GCM_SENDER_ID": "123456", "API_KEY": "synthetic-public-api-key"}
        with tempfile.TemporaryDirectory(prefix="publisher-fcm-test-") as directory:
            path = Path(directory) / "GoogleService-Info.plist"
            with self.assertRaises(publisher.PreflightError):
                publisher.firebase_configuration(config, path)
            path.write_bytes(plistlib.dumps(values))
            publisher.firebase_configuration(config, path)
            for key, value in (("BUNDLE_ID", "wrong.bundle"), ("PROJECT_ID", "wrong-project"), ("API_KEY", "$(PLACEHOLDER)")):
                path.write_bytes(plistlib.dumps(dict(values, **{key: value})))
                with self.subTest(key=key), self.assertRaises(publisher.PreflightError) as error:
                    publisher.firebase_configuration(config, path)
                self.assertNotIn(value, str(error.exception))

    def test_check_stays_read_only_and_diagnostics_hide_identifiers(self):
        output = io.StringIO()
        with patch.object(publisher, "token", return_value="synthetic-token"), patch.object(publisher, "apple_get", side_effect=lambda path, bearer: self.get(path)), patch.object(publisher, "apple_post", side_effect=AssertionError("check must not write")), contextlib.redirect_stdout(output):
            self.assertEqual(publisher.main(["check", "--key-file", "synthetic.p8"], environment=environment()), 0)
        self.assertNotIn(environment()["IOS_BUNDLE_ID"], output.getvalue())
        with patch.object(publisher, "firebase_configuration", side_effect=publisher.PreflightError("Synthetic FCM config failure")), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(publisher.main(["validate"], environment=environment()), 1)

    def test_jwt_uses_es256_fixed_width_signature_and_ten_minute_expiry(self):
        der = bytes.fromhex("3006020101020102")
        with patch.object(publisher.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=der)):
            jwt = publisher.token(Path("synthetic.p8"), "KEY1234567", environment()["ASC_ISSUER_ID"], now=100)
        header, claims, signature = [base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)) for part in jwt.split(".")]
        self.assertEqual(json.loads(header)["alg"], "ES256")
        self.assertEqual(json.loads(claims)["exp"], 700)
        self.assertEqual(signature, bytes(31) + b"\1" + bytes(31) + b"\2")
        for invalid in [b"", b"bad", der + b"\0", bytes.fromhex("3006020180020102")]:
            with self.subTest(invalid=invalid), self.assertRaises(publisher.PreflightError):
                publisher.raw_signature(invalid)


class PublisherSetupTests(unittest.TestCase):
    def setUp(self):
        self.config = publisher.configuration(environment())
        self.bundles = {self.config.bundle: "MAIN123456"}
        self.prefixes = {self.config.bundle: self.config.prefix}
        self.flags = {"MAIN123456": set()}
        self.writes = []
        self.reads = []
        self.app_exists = True
        self.unknown = False
        self.apply_write = True

    def get(self, path):
        self.reads.append(path)
        query = parse_qs(urlsplit(path).query)
        if path.startswith("/v1/apps?"):
            return {"data": [{"attributes": {"bundleId": self.config.bundle}}] if self.app_exists else []}
        if path.startswith("/v1/bundleIds?"):
            bundle = query["filter[identifier]"][0]
            return {"data": [{"type": "bundleIds", "id": self.bundles[bundle], "attributes": {
                "identifier": bundle, "platform": "IOS", "seedId": self.prefixes[bundle]}}] if bundle in self.bundles else []}
        resource = urlsplit(path).path.split("/")[3]
        return {"data": [{"type": "bundleIdCapabilities", "attributes": {"capabilityType": flag}} for flag in sorted(self.flags[resource])]}

    def post(self, path, body):
        self.writes.append((path, body))
        if self.apply_write:
            if path == "/v1/bundleIds":
                values = body["data"]["attributes"]
                self.bundles[values["identifier"]] = "EXT1234567"
                self.prefixes[values["identifier"]] = values["seedId"]
                self.flags["EXT1234567"] = set()
            else:
                resource = body["data"]["relationships"]["bundleId"]["data"]["id"]
                self.flags[resource].add(body["data"]["attributes"]["capabilityType"])
        if self.unknown:
            raise publisher.MutationUnconfirmed("Synthetic lost create response")
        return {"data": {}}

    def test_adds_only_missing_extension_and_flags_and_second_run_does_not_write(self):
        result = publisher.configure(self.config, self.get, self.post)
        self.assertEqual(len(self.writes), 4)
        self.assertEqual(self.writes[0][0], "/v1/bundleIds")
        self.assertEqual(self.writes[0][1]["data"]["attributes"], {
            "name": "Assistant Notification Service", "platform": "IOS", "seedId": self.config.prefix,
            "identifier": self.config.bundle + ".notifications"})
        self.assertEqual(self.flags["MAIN123456"], {"PUSH_NOTIFICATIONS", "APP_GROUPS"})
        self.assertEqual(self.flags["EXT1234567"], {"APP_GROUPS"})
        self.assertTrue(all(path in ("/v1/bundleIds", "/v1/bundleIdCapabilities") for path, _ in self.writes))
        self.assertNotIn(self.config.bundle, "\n".join(result))
        publisher.configure(self.config, self.get, self.post)
        self.assertEqual(len(self.writes), 4)

    def test_missing_main_app_or_mismatched_prefix_prevents_any_mutation(self):
        for failure in ("missing-main", "missing-app", "wrong-prefix", "wrong-extension-prefix"):
            self.setUp()
            if failure == "missing-main":
                self.bundles.clear()
            elif failure == "missing-app":
                self.app_exists = False
            elif failure == "wrong-prefix":
                self.prefixes[self.config.bundle] = self.config.team
            else:
                extension = self.config.bundle + ".notifications"
                self.bundles[extension] = "EXT1234567"
                self.prefixes[extension] = self.config.team
            with self.subTest(failure=failure), self.assertRaises(publisher.PreflightError) as error:
                publisher.configure(self.config, self.get, self.post)
            self.assertEqual(self.writes, [])
            self.assertNotIn(self.config.bundle, str(error.exception))

    def test_lost_write_responses_are_read_back_without_duplicate_creates(self):
        self.unknown = True
        publisher.configure(self.config, self.get, self.post)
        self.assertEqual(len(self.writes), 4)
        self.assertEqual(sum(path == "/v1/bundleIds" for path, _ in self.writes), 1)
        self.assertGreater(sum("bundleIdCapabilities" in path for path in self.reads), 3)

    def test_unconfirmed_extension_stops_without_automatic_create_retry(self):
        self.unknown = True
        self.apply_write = False
        with self.assertRaisesRegex(publisher.PreflightError, "creation was not confirmed"):
            publisher.configure(self.config, self.get, self.post)
        self.assertEqual(len(self.writes), 1)
        self.assertEqual(sum(self.config.bundle + ".notifications" in path for path in self.reads), 2)

    def test_unconfirmed_capability_stops_without_retry_or_later_writes(self):
        extension = self.config.bundle + ".notifications"
        self.bundles[extension] = "EXT1234567"
        self.prefixes[extension] = self.config.prefix
        self.flags["EXT1234567"] = set()
        self.apply_write = False
        self.unknown = True
        with self.assertRaisesRegex(publisher.PreflightError, "capability creation was not confirmed"):
            publisher.configure(self.config, self.get, self.post)
        self.assertEqual(len(self.writes), 1)

    def test_capability_permission_denial_never_reports_success(self):
        def denied(path, body):
            raise publisher.PreflightError("The ASC key cannot configure app registrations")
        with self.assertRaisesRegex(publisher.PreflightError, "cannot configure"):
            publisher.configure(self.config, self.get, denied)

    def test_capability_removed_during_setup_is_caught_by_final_verification(self):
        def get(path):
            if "bundleIdCapabilities" in path and len(self.writes) == 4:
                self.flags["MAIN123456"].discard("PUSH_NOTIFICATIONS")
            return self.get(path)
        with self.assertRaisesRegex(publisher.PreflightError, "changed during setup"):
            publisher.configure(self.config, get, self.post)

    def test_malformed_or_paginated_capabilities_prevent_mutations(self):
        for response in ({"data": [{"type": "wrong", "attributes": {"capabilityType": "APP_GROUPS"}}]},
                         {"data": [], "links": {"next": "https://untrusted.example"}}, {"data": [], "links": None}):
            def get(path):
                return response if "bundleIdCapabilities" in path else self.get(path)
            with self.subTest(response=response), self.assertRaises(publisher.PreflightError):
                publisher.configure(self.config, get, self.post)
            self.assertEqual(self.writes, [])

    def test_configure_command_reports_group_membership_limit_and_hides_config_values(self):
        output = io.StringIO()
        with patch.object(publisher, "token", return_value="synthetic-token"), patch.object(publisher, "apple_get", side_effect=lambda path, bearer: self.get(path)), patch.object(publisher, "apple_post", side_effect=lambda path, bearer, body: self.post(path, body)), contextlib.redirect_stdout(output):
            self.assertEqual(publisher.main(["configure", "--key-file", "synthetic.p8"], environment=environment()), 0)
        self.assertIn("membership are not checked", output.getvalue())
        for value in (self.config.bundle, self.config.prefix, self.config.team, "synthetic-token"):
            self.assertNotIn(value, output.getvalue())


class ReleaseOrchestrationTests(unittest.TestCase):
    def run_release(self, failure="", build="42", mode="release"):
        with tempfile.TemporaryDirectory(prefix="assistant-release-test-") as directory:
            root = Path(directory)
            commands = root / "commands"
            commands.mkdir()
            log = root / "commands.jsonl"
            stub = '''
import json, os, pathlib, plistlib, subprocess, sys
args = sys.argv[1:]
name = pathlib.Path(sys.argv[0]).name
with open(os.environ["TEST_COMMAND_LOG"], "a") as log:
    log.write(json.dumps([name, *args]) + "\\n")
if name == "python3":
    if args[0] == "publisher_preflight.py":
        if args[1] == "check": sys.exit(0)  # Apple responses covered separately above.
        sys.exit(subprocess.call([sys.executable, *args]))
    if args[0] == "verify_archive.py":
        distribution = "--distribution" in args
        failure = os.environ.get("TEST_VERIFY_FAILURE", "")
        if failure == ("distribution" if distribution else "archive"): sys.exit(1)
        sys.exit(0)
if name == "xcodebuild":
    if args[0] == "archive": pathlib.Path(args[args.index("-archivePath")+1]).mkdir(parents=True)
    if args[0] == "-exportArchive":
        options = plistlib.loads(pathlib.Path(args[args.index("-exportOptionsPlist")+1]).read_bytes())
        assert options["destination"] == "export"
        target = pathlib.Path(args[args.index("-exportPath")+1]); target.mkdir(parents=True)
        (target / "Synthetic.ipa").write_bytes(b"synthetic-not-a-real-app")
if name == "xcrun":
    assert args[:2] == ["altool", "--upload-package"]
    assert pathlib.Path(args[args.index("--p8-file-path")+1]).read_text().strip() == "synthetic-test-value-not-an-Apple-key"
'''
            for name in ("python3", "xcodebuild", "xcrun"):
                target = commands / name
                target.write_text("#!" + sys.executable + "\n" + stub)
                target.chmod(0o700)
            values = dict(environment(), PATH=str(commands) + os.pathsep + os.defpath,
                          IOS_BUILD_DIR=str(root / "output"), BUILD_NUMBER=build,
                          TEST_COMMAND_LOG=str(log), TEST_VERIFY_FAILURE=failure)
            result = subprocess.run(["/bin/sh", str(ROOT / "ci.sh"), mode], env=values,
                                    capture_output=True, text=True)
            rows = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            return result, rows

    def test_upload_uses_exact_ipa_only_after_both_verification_gates(self):
        result, rows = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        phases = [(row[0], row[1]) for row in rows]
        self.assertEqual(phases, [("python3", "publisher_preflight.py"), ("python3", "publisher_preflight.py"),
                                 ("xcodebuild", "archive"), ("python3", "verify_archive.py"),
                                 ("xcodebuild", "-exportArchive"), ("python3", "verify_archive.py"), ("xcrun", "altool")])
        checked_ipa = rows[-2][2]
        self.assertEqual(rows[-1][rows[-1].index("--upload-package") + 1], checked_ipa)
        self.assertIn("--distribution", rows[-2])
        self.assertIn("--prepare-frameworks", rows[3])
        self.assertNotIn("--prepare-frameworks", rows[-2])
        self.assertEqual(rows[-2][rows[-2].index("--prefix") + 1], "PREFIX1234")
        for row in (rows[2], rows[4]):
            self.assertIn("-allowProvisioningUpdates", row)
            self.assertEqual(row[row.index("-authenticationKeyID") + 1], "KEY1234567")
            self.assertTrue(row[row.index("-authenticationKeyPath") + 1].endswith("AuthKey_KEY1234567.p8"))
        self.assertIn("REGISTER_APP_GROUPS=YES", rows[2])

    def test_archive_verifies_profiles_without_export_or_upload(self):
        result, rows = self.run_release(mode="archive")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(row[0], row[1]) for row in rows],
                         [("python3", "publisher_preflight.py"), ("python3", "publisher_preflight.py"),
                          ("xcodebuild", "archive"), ("python3", "verify_archive.py")])
        self.assertIn("nothing uploaded", result.stdout)

    def test_failed_verification_stops_export_or_upload(self):
        for failure in ("archive", "distribution"):
            with self.subTest(failure=failure):
                result, rows = self.run_release(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(row[0] == "xcrun" for row in rows))
                self.assertEqual(any(row[:2] == ["xcodebuild", "-exportArchive"] for row in rows), failure == "distribution")

    def test_script_preflight_without_key_is_a_failure_not_a_green_skip(self):
        with tempfile.TemporaryDirectory(prefix="assistant-preflight-no-key-") as directory:
            values = dict(environment(), PATH=os.defpath, ASC_KEY_P8="", TMPDIR=directory)
            result = subprocess.run(["/bin/sh", str(ROOT / "ci.sh"), "preflight"], env=values, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("ASC_KEY_P8 is not set", result.stderr)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_invalid_build_number_stops_before_credentials_or_apple_checks(self):
        for build in ("0", "-1", "not-a-number"):
            with self.subTest(build=build):
                result, rows = self.run_release(build=build)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("BUILD_NUMBER", result.stderr)
                self.assertEqual(rows, [])


if __name__ == "__main__":
    unittest.main()
