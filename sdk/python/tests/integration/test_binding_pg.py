"""Black-box conformance of the SDK binding against a disposable PostgreSQL (spec §13)."""

import asyncio
import json
import logging
import os
import secrets
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from aiohttp.test_utils import TestServer
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

from assistant_plugin import storage
from assistant_plugin.canonical import rfc3339
from assistant_plugin.client import BindingClient, NotSent
from assistant_plugin.delegation import Signer
from assistant_plugin.errors import Rejected
from assistant_plugin.http import create_app

import echo_service

CONFIG = os.environ.get("SDK_TEST_DATABASE_CONFIG_FILE")
if not CONFIG:
    raise unittest.SkipTest("SDK_TEST_DATABASE_CONFIG_FILE is required for the PostgreSQL tier")
KEY = b"d" * 32
INSTALLATION = "echo-main"


def config():
    return json.loads(Path(CONFIG).read_text())


class Binding(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        settings = config()
        self.schema = "echo_" + secrets.token_hex(4)
        self.migrator = create_async_engine(settings["migrator_dsn"])
        app_role = settings["dsn"].split("://", 1)[1].split(":", 1)[0]
        async with self.migrator.begin() as conn:
            await conn.execute(text(f"CREATE SCHEMA {self.schema}"))
            for statement in storage.ddl(self.schema) + echo_service.tables(self.schema):
                await conn.execute(text(statement))
            await conn.execute(text(f"GRANT USAGE ON SCHEMA {self.schema} TO {app_role}"))
            await conn.execute(text(f"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {self.schema} TO {app_role}"))
        self.engine = create_async_engine(settings["dsn"], pool_size=5, max_overflow=0)
        self.gate = asyncio.Event()
        self.gate.set()
        self.server = await self.start()
        self.signer = Signer(INSTALLATION, "https://assistant.test", KEY, f"{INSTALLATION}/k1")
        self.client = BindingClient(f"http://127.0.0.1:{self.server.port}", self.signer)
        self.actor = "user-" + secrets.token_hex(4)

    async def start(self):
        self.runtime = echo_service.build(self.engine, self.schema, {INSTALLATION: {"k1": KEY}}, drain_gate=self.gate)
        server = TestServer(create_app(self.runtime), host="127.0.0.1")
        await server.start_server()
        return server

    async def restart(self):
        await self.client.close()
        await self.server.close()
        self.server = await self.start()
        self.client = BindingClient(f"http://127.0.0.1:{self.server.port}", self.signer)

    async def asyncTearDown(self):
        await self.client.close()
        await self.server.close()
        await self.engine.dispose()
        async with self.migrator.begin() as conn:
            await conn.execute(text(f"DROP SCHEMA {self.schema} CASCADE"))
        await self.migrator.dispose()

    def envelope(self, tool, arguments, key=None, actor=None, version="1.0.0", deadline=30):
        return {"contract": "assistant.invocation/v1", "request_id": str(uuid4()), "installation_id": INSTALLATION,
                "tool_id": tool, "tool_version": version, "operation_key": key or ("op-" + secrets.token_hex(6)),
                "context": {"actor_id": actor or self.actor, "trace_id": "trace-1",
                            "deadline_at": rfc3339(datetime.now(timezone.utc) + timedelta(seconds=deadline))},
                "arguments": arguments}

    async def invoke(self, tool, arguments, scopes=("echo.read", "echo.write"), **options):
        return await self.client.invoke(self.envelope(tool, arguments, **options), scopes=list(scopes), timeout=10)

    async def counter(self, actor=None):
        async with self.engine.connect() as conn:
            row = (await conn.execute(text(f"SELECT value, revision FROM {self.schema}.counters WHERE actor_id=:a"),
                                      {"a": actor or self.actor})).first()
        return (str(row[0]), row[1]) if row else None

    async def test_manifest_and_health_describe_the_pinned_package(self):
        manifest = await self.client.manifest()
        self.assertEqual(manifest["package_digest"], self.runtime.package.digest)
        self.assertEqual((await self.client.health())["status"], "ready")

    async def test_reads_complete_fail_or_are_rejected_before_admission(self):
        status, body = await self.invoke("echo.say", {"text": "привет"})
        self.assertEqual((status, body["state"], body["data"]), (200, "completed", {"text": "привет"}))
        status, body = await self.invoke("echo.say", {"text": "x", "fail": True})
        self.assertEqual((status, body["state"], body["error"]["code"]), (200, "failed", "refused"))
        cases = [({"text": ""}, {}, "invalid_arguments", 422), ({"text": "x", "extra": 1}, {}, "invalid_arguments", 422),
                 ({"text": "x"}, {"scopes": ("echo.write",)}, "forbidden", 403),
                 ({"text": "x"}, {"version": "2.0.0"}, "unsupported_version", 400),
                 ({"text": "x"}, {"version": "1.9.0"}, "unsupported_version", 400)]
        for arguments, options, code, http in cases:
            with self.subTest(code=code, arguments=arguments):
                with self.assertRaises(Rejected) as caught:
                    await self.invoke("echo.say", arguments, **options)
                self.assertEqual((caught.exception.code, caught.exception.status), (code, http))
        with self.assertRaises(Rejected) as caught:
            await self.invoke("echo.say", {"text": "x"}, deadline=-1)
        self.assertEqual(caught.exception.code, "deadline_exceeded")
        status, body = await self.invoke("echo.say", {"text": "x", "sleep_ms": 3000})
        self.assertEqual((body["state"], body["error"]["code"]), ("failed", "deadline_exceeded"))

    async def test_invalid_arguments_never_reveal_values(self):
        with self.assertRaises(Rejected) as caught:
            await self.invoke("counter.add", {"amount": "secret-looking-value", "revision": 0})
        self.assertNotIn("secret-looking-value", json.dumps(caught.exception.field_errors))
        self.assertIsNone(await self.counter())

    async def test_actor_in_body_must_match_the_delegation(self):
        envelope = self.envelope("echo.say", {"text": "x"})
        raw = json.dumps(envelope).encode()
        token = self.signer.token("invoke", envelope["request_id"], actor_id="someone-else", body=raw,
                                  operation_key=envelope["operation_key"], scopes=["echo.read"])
        session = await self.client.session()
        async with session.post(f"http://127.0.0.1:{self.server.port}/invoke", data=raw,
                                headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}) as response:
            self.assertEqual(response.status, 401)
        async with session.post(f"http://127.0.0.1:{self.server.port}/invoke", data=raw) as response:
            self.assertEqual(response.status, 401)

    async def test_write_is_idempotent_by_key_and_conflicts_on_other_arguments(self):
        status, first = await self.invoke("counter.add", {"amount": "2.50", "revision": 0}, key="add-1")
        self.assertEqual((status, first["state"], first["data"]), (200, "completed", {"value": "2.50", "revision": 1}))
        self.assertEqual(first["effects"], [{"resource_type": "counter", "resource_id": "counter", "change": "updated", "revision": 1}])
        status, again = await self.invoke("counter.add", {"amount": "2.50", "revision": 0}, key="add-1")
        self.assertEqual(again["receipts"][0]["receipt_id"], first["receipts"][0]["receipt_id"])
        with self.assertRaises(Rejected) as caught:
            await self.invoke("counter.add", {"amount": "3", "revision": 0}, key="add-1")
        self.assertEqual((caught.exception.code, caught.exception.status), ("idempotency_conflict", 409))
        self.assertEqual(await self.counter(), ("2.50", 1))

    async def test_parallel_identical_writes_have_one_effect_and_one_receipt(self):
        results = await asyncio.gather(*[self.invoke("counter.add", {"amount": "1", "revision": 0}, key="same")
                                         for _ in range(4)], return_exceptions=True)
        receipts = {r[1]["receipts"][0]["receipt_id"] for r in results if not isinstance(r, Exception)}
        self.assertEqual(len(receipts), 1)
        self.assertTrue(all(isinstance(r, tuple) or (isinstance(r, Rejected) and r.code == "quota_exceeded") for r in results))
        self.assertEqual(await self.counter(), ("1", 1))

    async def test_revision_conflict_changes_nothing_and_registers_no_key(self):
        await self.invoke("counter.add", {"amount": "1", "revision": 0})
        with self.assertRaises(Rejected) as caught:
            await self.invoke("counter.add", {"amount": "1", "revision": 0}, key="stale")
        self.assertEqual(caught.exception.code, "revision_conflict")
        lookup = await self.client.lookup(INSTALLATION, self.actor, "stale", scopes=["echo.read", "echo.write"])
        self.assertEqual(lookup["status"], "not_found")
        self.assertEqual(await self.counter(), ("1", 1))

    async def test_lookup_after_restart_returns_the_original_receipt_only_to_its_actor(self):
        _, first = await self.invoke("counter.add", {"amount": "1", "revision": 0}, key="kept")
        await self.restart()
        found = await self.client.lookup(INSTALLATION, self.actor, "kept", scopes=["echo.read", "echo.write"])
        self.assertEqual((found["status"], found["result"]["receipts"]), ("found", first["receipts"]))
        for actor, scopes in (("intruder", ["echo.read", "echo.write"]), (self.actor, ["echo.read"])):
            other = await self.client.lookup(INSTALLATION, actor, "kept", scopes=scopes)
            self.assertEqual(other["status"], "not_found")
        _, repeat = await self.invoke("counter.add", {"amount": "1", "revision": 0}, key="kept")
        self.assertEqual(repeat["receipts"], first["receipts"])
        self.assertEqual(await self.counter(), ("1", 1))

    async def test_async_operation_is_accepted_and_survives_a_restart(self):
        await self.invoke("counter.add", {"amount": "5", "revision": 0})
        self.gate.clear()
        status, accepted = await self.invoke("counter.drain", {"steps": 2}, key="drain-1")
        self.assertEqual((status, accepted["state"]), (202, "accepted"))
        status, again = await self.invoke("counter.drain", {"steps": 2}, key="drain-1")
        self.assertEqual(status, 202)
        await self.restart()   # the job was cancelled mid-way; resume() must finish it
        self.gate.set()
        for _ in range(100):
            found = await self.client.lookup(INSTALLATION, self.actor, "drain-1", scopes=["echo.read", "echo.write"])
            if found["result"]["state"] == "completed":
                break
            await asyncio.sleep(0.05)
        self.assertEqual((found["result"]["state"], found["result"]["data"]), ("completed", {"value": "0"}))
        self.assertEqual(await self.counter(), ("0", 2))

    async def test_events_are_per_actor_ordered_and_resync_on_foreign_cursor(self):
        stream = self.client.events(actor_id=self.actor, scopes=["echo.read"])
        name, ready = await anext(stream)
        self.assertEqual(name, "stream.ready")
        other = self.client.events(actor_id="other-actor", scopes=["echo.read"])
        self.assertEqual((await anext(other))[0], "stream.ready")
        await self.invoke("counter.add", {"amount": "1", "revision": 0})
        name, event = await asyncio.wait_for(anext(stream), 5)
        self.assertEqual((name, event["payload"], event["installation_id"]), ("counter.changed", {"revision": 1}, INSTALLATION))
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(anext(other), 0.5)
        await stream.aclose()
        await other.aclose()
        resumed = self.client.events(actor_id=self.actor, scopes=["echo.read"], cursor=ready["cursor"])
        self.assertEqual((await asyncio.wait_for(anext(resumed), 5))[1]["event_id"], event["event_id"])
        await resumed.aclose()
        ahead = self.client.events(actor_id=self.actor, scopes=["echo.read"], cursor="e1.999")
        name, body = await asyncio.wait_for(anext(ahead), 5)
        self.assertEqual((name, body["cursor"]), ("resync_required", event["cursor"]))
        await ahead.aclose()

    async def test_configuration_keeps_secrets_write_only_and_encrypted(self):
        async def change(method, key, **body):
            request = {"contract": "assistant.configuration-request/v1", "request_id": str(uuid4()), "installation_id": INSTALLATION,
                       "operation_key": key, "context": {"actor_id": self.actor, "trace_id": "t",
                                                         "deadline_at": rfc3339(datetime.now(timezone.utc) + timedelta(seconds=20))}, **body}
            return await self.client.configuration(method, actor_id=self.actor, scopes=["echo.read", "echo.write"], body=request)

        with self.assertLogs(level=logging.DEBUG) as logs:
            logging.getLogger("canary").debug("start")
            status, stored = await change("PUT", "cfg-1", public={"greeting": "hi"}, secret={"token": "canary-token-123"})
        self.assertEqual((status, stored["state"], stored["data"]["revision"]), (200, "completed", 1))
        self.assertNotIn("canary-token-123", "\n".join(logs.output))
        _, view = await self.client.configuration("GET", actor_id=self.actor, scopes=["echo.read"])
        self.assertEqual(view["secret_state"], {"token": {"present": True}})
        self.assertNotIn("canary-token-123", json.dumps(view))
        async with self.engine.connect() as conn:
            dump = json.dumps([str(row) for row in (await conn.execute(text(f"SELECT * FROM {self.schema}.settings"))).all()]
                              + [str(row) for row in (await conn.execute(text(f"SELECT * FROM {self.schema}.operations"))).all()])
        self.assertNotIn("canary-token-123", dump)
        _, replay = await change("PUT", "cfg-1", public={"greeting": "hi"}, secret={"token": "canary-token-123"})
        self.assertEqual(replay["receipts"], stored["receipts"])
        with self.assertRaises(Rejected) as caught:
            await change("PUT", "cfg-1", public={"greeting": "hi"}, secret={"token": "another-token-456"})
        self.assertEqual(caught.exception.code, "idempotency_conflict")
        _, refused = await change("PUT", "cfg-2", secret={"token": "rejected-token"})
        self.assertEqual((refused["state"], refused["error"]["code"]), ("failed", "credential_rejected"))
        _, stale = await change("PUT", "cfg-3", expected_revision=0, public={})
        self.assertEqual(stale["error"]["code"], "revision_conflict")
        with self.assertRaises(Rejected) as caught:
            await change("PUT", "cfg-4", secret={"token": "short"})
        self.assertEqual(caught.exception.field_errors, [{"path": "/secret/token", "code": "minLength"}])
        _, removed = await change("DELETE", "cfg-5", expected_revision=1)
        self.assertEqual(removed["state"], "completed")
        _, view = await self.client.configuration("GET", actor_id=self.actor, scopes=["echo.read"])
        self.assertEqual(view["status"], "not_configured")

    async def test_unreachable_service_is_reported_as_not_sent(self):
        dead = BindingClient("http://127.0.0.1:9", self.signer)
        with self.assertRaises(NotSent):
            await dead.invoke(self.envelope("echo.say", {"text": "x"}), scopes=["echo.read"], timeout=2)
        await dead.close()


if __name__ == "__main__":
    unittest.main()
