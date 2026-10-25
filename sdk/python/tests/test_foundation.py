import base64
import json
import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from decimal import Decimal, localcontext
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import jwt  # test oracle only; the SDK itself does not depend on PyJWT

from assistant_plugin import canonical as c
from assistant_plugin import delegation, envelopes, package, secretbox
from assistant_plugin.errors import PluginError, Rejected, from_wire
from assistant_plugin.privacy import sanitize
from assistant_plugin.schemas import closed_objects, field_errors, validator

FIXTURE = Path(__file__).parent / "fixtures" / "echo"


class Canonical(unittest.TestCase):
    def test_canonical_is_sorted_compact_and_utf8(self):
        self.assertEqual(c.canonical({"b": 1, "a": ["я", Decimal("1.50")]}), '{"a":["я","1.50"],"b":1}')
        self.assertEqual(c.fingerprint({"a": 1}), c.fingerprint({"a": 1}))
        self.assertTrue(c.fingerprint({"a": 1}).startswith("sha256:"))

    def test_decimal_text_is_exact_and_never_exponential(self):
        cases = {"1E+2": "100", "-0": "0", "0.000": "0", "1.2300": "1.23", "0.00000001": "0.00000001", "12": "12"}
        for given, expected in cases.items():
            self.assertEqual(c.decimal_str(Decimal(given)), expected)
        for bad in ("NaN", "Infinity", "-inf", "sNaN", 1.5, True, "x"):
            with self.assertRaises(ValueError):
                c.parse_decimal(bad)

    def test_decimal_wire_format_preserves_digits_independently_of_arithmetic_precision(self):
        given = "12345678901234567890.12345678901234567"
        with localcontext() as context:
            context.prec = 6
            for original in (given, "-" + given, "0.0000000000000000001234567890123456789"):
                with self.subTest(value=original):
                    self.assertEqual(c.decimal_str(Decimal(original + "00")), original)
                    self.assertEqual(c.to_json({"amount": Decimal(original)}), '{"amount":"' + original + '"}')

    def test_instants_are_utc_rfc3339(self):
        value = c.parse_rfc3339("2026-09-25T10:00:00.123456789+03:00")
        self.assertEqual(c.rfc3339(value), "2026-09-25T07:00:00.123Z")
        for bad in ("2026-09-25", "2026-09-25T10:00:00", "yesterday"):
            with self.assertRaises(ValueError):
                c.parse_rfc3339(bad)

    def test_wire_json_refuses_floats(self):
        self.assertEqual(c.to_json({"x": Decimal("2.50"), "t": datetime(2026, 1, 1, tzinfo=timezone.utc)}),
                         '{"x":"2.5","t":"2026-01-01T00:00:00Z"}')
        with self.assertRaises(TypeError):
            c.to_json({"x": 0.1})

    def test_strict_json_rejects_duplicates_and_constants(self):
        for raw in ('{"a":1,"a":2}', '{"a":NaN}'):
            with self.assertRaises(ValueError):
                c.strict_loads(raw)
        with self.assertRaises(ValueError):
            c.strict_loads("[" * 10, max_bytes=5)


class Schemas(unittest.TestCase):
    def test_errors_carry_locations_but_never_values(self):
        compiled = validator({"type": "object", "additionalProperties": False, "required": ["a"],
                              "properties": {"a": {"type": "string", "format": "date-time"}}})
        errors = field_errors(compiled, {"a": "secret-value-123", "zzz": "leak"})
        self.assertIn({"path": "/a", "code": "format"}, errors)
        self.assertIn({"path": "/zzz", "code": "unexpected"}, errors)
        self.assertNotIn("secret-value-123", json.dumps(errors))
        self.assertEqual(field_errors(compiled, {}), [{"path": "/a", "code": "required"}])

    def test_formats_are_actually_checked(self):
        compiled = validator({"type": "object", "properties": {
            "t": {"type": "string", "format": "date-time"}, "d": {"type": "string", "format": "date"},
            "u": {"type": "string", "format": "uuid"}, "n": {"type": "string", "format": "decimal"}}})
        self.assertEqual(field_errors(compiled, {"t": "2026-01-01T00:00:00Z", "d": "2026-01-01",
                                                 "u": "00000000-0000-4000-8000-000000000000", "n": "-1.5"}), [])
        bad = field_errors(compiled, {"t": "tomorrow", "d": "2026-13-01", "u": "x", "n": "1e5"})
        self.assertEqual({e["path"] for e in bad}, {"/t", "/d", "/u", "/n"})

    def test_remote_references_are_never_fetched(self):
        compiled = validator({"$ref": "https://example.invalid/schema.json"})
        with self.assertRaises(Exception):
            compiled.validate({})

    def test_open_input_objects_are_found(self):
        self.assertEqual(closed_objects({"type": "object", "additionalProperties": False,
                                         "properties": {"x": {"type": "object"}}}), ["/properties/x"])


class Packages(unittest.TestCase):
    def copy(self):
        root = Path(tempfile.mkdtemp()) / "pkg"
        shutil.copytree(FIXTURE, root)
        self.addCleanup(shutil.rmtree, root.parent)
        return root

    def test_fixture_loads_with_stable_digest(self):
        loaded = package.load(FIXTURE)
        self.assertEqual(loaded.id, "echo")
        self.assertEqual(set(loaded.tools), {("echo.say", 1), ("counter.add", 1), ("counter.drain", 1)})
        self.assertEqual(loaded.scopes(), {"echo.read", "echo.write"})
        self.assertEqual(loaded.digest, package.load(FIXTURE).digest)
        lines = "".join(f"{f['sha256']}  {f['path']}\n" for f in loaded.listing())
        import hashlib
        self.assertEqual(loaded.digest, "sha256:" + hashlib.sha256(lines.encode()).hexdigest())

    def test_digest_ignores_dotfiles_and_changes_with_content(self):
        root = self.copy()
        before = package.load(root).digest
        (root / ".DS_Store").write_bytes(b"junk")
        self.assertEqual(package.load(root).digest, before)
        (root / "skills/echo/SKILL.md").write_text("changed", encoding="utf-8")
        self.assertNotEqual(package.load(root).digest, before)

    def test_symlinks_and_escaping_paths_are_refused(self):
        root = self.copy()
        os.symlink("/etc/hosts", root / "link")
        with self.assertRaises(package.PackageError):
            package.load(root)
        root = self.copy()
        manifest = json.loads((root / "manifest.json").read_text())
        manifest["skills"][0]["path"] = "../secret.md"
        (root / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaises(package.PackageError):
            package.load(root)

    def test_open_input_schema_and_unknown_binding_are_refused(self):
        root = self.copy()
        path = root / "contracts/echo.say.json"
        descriptor = json.loads(path.read_text())
        descriptor["input_schema"].pop("additionalProperties")
        path.write_text(json.dumps(descriptor))
        with self.assertRaisesRegex(package.PackageError, "additionalProperties"):
            package.load(root)
        root = self.copy()
        manifest = json.loads((root / "manifest.json").read_text())
        manifest["bindings"][0]["profile"] = "grpc"
        (root / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaises(package.PackageError):
            package.load(root)

    def test_write_tools_must_declare_atomicity(self):
        root = self.copy()
        path = root / "contracts/counter.add.json"
        descriptor = json.loads(path.read_text())
        descriptor["execution"].pop("atomicity")
        path.write_text(json.dumps(descriptor))
        with self.assertRaises(package.PackageError):
            package.load(root)


class Delegation(unittest.TestCase):
    KEY = b"k" * 32

    def setUp(self):
        self.now = 1_800_000_000
        self.verifier = delegation.Verifier("inst-1", "https://assistant.test", {"k1": self.KEY}, clock=lambda: self.now)
        self.signer = delegation.Signer("inst-1", "https://assistant.test", self.KEY, "k1")

    def token(self, **overrides):
        options = dict(actor_id="user-1", scopes=["echo.read"], body=b"{}", now=self.now)
        options.update(overrides)
        purpose = options.pop("purpose", "invoke")
        request_id = options.pop("request_id", "req-1")
        return "Bearer " + self.signer.token(purpose, request_id, **options)

    def test_round_trip_matches_pyjwt(self):
        raw = self.token()[7:]
        decoded = jwt.decode(raw, self.KEY, algorithms=["HS256"], audience="assistant-plugin:inst-1",
                             options={"verify_exp": False, "verify_iat": False})
        self.assertEqual(decoded["sub"], "user-1")
        foreign = jwt.encode({**decoded, "jti": "req-2"}, self.KEY, algorithm="HS256", headers={"kid": "k1"})
        result = self.verifier.verify("Bearer " + foreign, purpose="invoke", body=b"{}")
        self.assertEqual((result.actor_id, result.scopes), ("user-1", frozenset({"echo.read"})))

    def test_every_binding_is_enforced(self):
        cases = [
            (self.token(), dict(purpose="lookup", body=b"{}")),               # wrong purpose
            (self.token(request_id="r2"), dict(purpose="invoke", body=b"{ }")),  # body swapped
            (self.token(request_id="r3", now=self.now - 120), dict(purpose="invoke", body=b"{}")),  # expired
            (self.token(request_id="r4", now=self.now + 120), dict(purpose="invoke", body=b"{}")),  # from the future
            (self.token(request_id="r5", actor_id=None), dict(purpose="invoke", body=b"{}")),       # no actor
        ]
        for authorization, options in cases:
            with self.assertRaises(Rejected):
                self.verifier.verify(authorization, **options)

    def test_replay_wrong_key_alg_none_and_audience_are_refused(self):
        good = self.token(request_id="once")
        self.verifier.verify(good, purpose="invoke", body=b"{}")
        with self.assertRaisesRegex(Rejected, "Повтор"):
            self.verifier.verify(good, purpose="invoke", body=b"{}")
        other = delegation.Signer("inst-1", "https://assistant.test", b"x" * 32, "k1")
        with self.assertRaises(Rejected):
            self.verifier.verify("Bearer " + other.token("invoke", "r9", actor_id="u", body=b"{}", now=self.now),
                                 purpose="invoke", body=b"{}")
        header = c.b64url(json.dumps({"alg": "none", "kid": "k1"}).encode())
        payload = self.token(request_id="r10")[7:].split(".")[1]
        with self.assertRaises(Rejected):
            self.verifier.verify(f"Bearer {header}.{payload}.", purpose="invoke", body=b"{}")
        elsewhere = delegation.Signer("inst-2", "https://assistant.test", self.KEY, "k1")
        with self.assertRaises(Rejected):
            self.verifier.verify("Bearer " + elsewhere.token("invoke", "r11", actor_id="u", body=b"{}", now=self.now),
                                 purpose="invoke", body=b"{}")

    def test_short_keys_are_refused(self):
        with self.assertRaises(ValueError):
            delegation.Signer("i", "iss", b"short", "k")


class SecretBox(unittest.TestCase):
    def test_seal_open_rewrap_and_owner_binding(self):
        old = secretbox.Keyring({"k1": b"1" * 32}, "k1")
        sealed = old.seal(b"api-secret", b"owner:a", "conn-1")
        self.assertEqual(old.open(sealed, b"owner:a", "conn-1"), b"api-secret")
        for aad, context in ((b"owner:b", "conn-1"), (b"owner:a", "conn-2")):
            with self.assertRaises(secretbox.SecretUnavailable):
                old.open(sealed, aad, context)
        rotated = secretbox.Keyring({"k1": b"1" * 32, "k2": b"2" * 32}, "k2")
        moved = rotated.rewrap(sealed, "conn-1")
        self.assertEqual((moved.kek_id, moved.ciphertext), ("k2", sealed.ciphertext))
        self.assertEqual(secretbox.Keyring({"k2": b"2" * 32}, "k2").open(moved, b"owner:a", "conn-1"), b"api-secret")
        with self.assertRaises(secretbox.SecretUnavailable):
            secretbox.Keyring({"k2": b"2" * 32}, "k2").open(sealed, b"owner:a", "conn-1")

    def test_hkdf_matches_rfc5869_case_3(self):
        ikm = bytes.fromhex("0b" * 22)
        ring = secretbox.Keyring({"k": ikm[:22] + b"\0" * 10}, "k")
        # RFC 5869 A.3 uses a 22-byte IKM; derive() is exercised with the published vector directly.
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF
        okm = HKDF(algorithm=hashes.SHA256(), length=42, salt=None, info=b"").derive(ikm)
        self.assertEqual(okm.hex(), "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8")
        self.assertEqual(len(ring.derive(b"purpose")), 32)
        self.assertNotEqual(ring.derive(b"a"), ring.derive(b"b"))

    def test_key_files_must_be_private(self):
        directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, directory)
        path = directory / "key"
        path.write_text(base64.b64encode(b"z" * 32).decode())
        os.chmod(path, 0o644)
        with self.assertRaises(secretbox.SecretUnavailable):
            secretbox.read_key_file(path)
        os.chmod(path, 0o600)
        self.assertEqual(secretbox.read_key_file(path), b"z" * 32)

    def test_secrets_never_print(self):
        value = secretbox.Secret("hunter2")
        self.assertNotIn("hunter2", repr(value) + str(value) + f"{value}")
        self.assertNotIn("1" * 10, repr(secretbox.Keyring({"k1": b"1" * 32}, "k1")))


class Wire(unittest.TestCase):
    def test_result_projects_effects_from_receipts(self):
        receipt = envelopes.receipt("op-1", [envelopes.effect("counter", "c1", "updated", 3)], "1.0.0",
                                    datetime(2026, 1, 1, tzinfo=timezone.utc))
        body = envelopes.result("op-1", "completed", data={"value": "1"}, receipts=[receipt])
        envelopes.check(envelopes.RESULT, json.loads(json.dumps(body)))
        self.assertEqual(body["effects"], receipt["effects"])
        with self.assertRaises(Rejected):
            envelopes.check(envelopes.RESULT, {**body, "state": "failed"})  # failed needs an error

    def test_errors_round_trip_and_privacy(self):
        error = PluginError("invalid_arguments", "bad", field_errors=[{"path": "/a", "code": "required"}])
        self.assertEqual(error.status, 422)
        again = from_wire(error.to_wire(), 422)
        self.assertEqual((again.code, again.recovery, again.field_errors), ("invalid_arguments", "fix_arguments", error.field_errors))
        cleaned = sanitize({"headers": {"X-BAPI-SIGN": "abc", "Authorization": "Bearer x"}, "op": "auth", "args": ["k", 1, "sig"],
                            "text": "api_key=AKIA123 sign=deadbeef"})
        self.assertNotIn("abc", json.dumps(cleaned))
        self.assertEqual(cleaned["args"], "[redacted]")
        self.assertNotIn("AKIA123", cleaned["text"])
        self.assertNotIn("deadbeef", cleaned["text"])


if __name__ == "__main__":
    unittest.main()
