"""A committed privacy fence survives restart without resuming domain actions."""

import asyncio
from dataclasses import replace
import json
import os
from pathlib import Path
import secrets
import sys
import unittest
from uuid import uuid4

sys.path[:0] = [str(Path(__file__).resolve().parents[2]), str(Path(__file__).resolve().parents[1])]

from aiohttp.test_utils import TestServer
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

from assistant_plugin import storage
from assistant_plugin.client import BindingClient, Uncertain
from assistant_plugin.delegation import Signer
from assistant_plugin.erasure import ActorErasure, ddl as erasure_ddl
from assistant_plugin.envelopes import result
from assistant_plugin.errors import DomainFailure, Rejected
from assistant_plugin.http import create_app
from assistant_plugin.operations import OperationId

import echo_service

CONFIG = os.environ.get("SDK_TEST_DATABASE_CONFIG_FILE")
if not CONFIG:
    raise unittest.SkipTest("SDK_TEST_DATABASE_CONFIG_FILE is required for the PostgreSQL tier")
INSTALLATION, KEY = "echo-main", b"d" * 32


class ErasureRecovery(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        settings = json.loads(Path(CONFIG).read_text())
        self.schema = "erasure_" + secrets.token_hex(4)
        self.migrator = create_async_engine(settings["migrator_dsn"])
        app_role = settings["dsn"].split("://", 1)[1].split(":", 1)[0]
        async with self.migrator.begin() as conn:
            await conn.execute(text(f"CREATE SCHEMA {self.schema}"))
            for statement in storage.ddl(self.schema) + echo_service.tables(self.schema) + [erasure_ddl(self.schema)]:
                await conn.execute(text(statement))
            await conn.execute(text(f"GRANT USAGE ON SCHEMA {self.schema} TO {app_role}"))
            await conn.execute(text(f"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {self.schema} TO {app_role}"))
        self.engine = create_async_engine(settings["dsn"], pool_size=5, max_overflow=0)
        self.actor = "user-" + secrets.token_hex(4)
        self.recovered, self.ran = [], []
        self.quiesce_fails = True
        self.signer = Signer(INSTALLATION, "https://assistant.test", KEY, f"{INSTALLATION}/k1")
        await self.start()

    async def start(self):
        self.runtime = echo_service.build(self.engine, self.schema, {INSTALLATION: {"k1": KEY}})
        owner = self

        class Erase:
            async def quiesce(self, installation, actor):
                if owner.quiesce_fails:
                    raise DomainFailure("dependency_unavailable", "Временная ошибка очистки")

            async def erase(self, conn, installation, actor):
                for table in ("counters", "settings"):
                    await conn.execute(text(f"DELETE FROM {owner.schema}.{table} WHERE installation_id=:i AND actor_id=:a"),
                                       {"i": installation, "a": actor})

        ActorErasure(self.runtime, Erase())

        async def recover(row):
            self.recovered.append((row["actor_id"], row["tool_id"]))
            return None

        self.runtime.configuration.recover = recover
        self.runtime.tools[("counter.add", 1)] = replace(self.runtime.tools[("counter.add", 1)], recover=recover)
        original = self.runtime.tools[("counter.drain", 1)]

        async def run(job):
            self.ran.append(job.ctx.actor_id)
            return await original.run(job)

        self.runtime.tools[("counter.drain", 1)] = replace(original, run=run)
        self.server = TestServer(create_app(self.runtime), host="127.0.0.1")
        await self.server.start_server()
        self.client = BindingClient(f"http://127.0.0.1:{self.server.port}", self.signer)

    async def stop(self):
        await self.client.close()
        await self.server.close()

    async def asyncTearDown(self):
        await self.stop()
        await self.engine.dispose()
        async with self.migrator.begin() as conn:
            await conn.execute(text(f"DROP SCHEMA {self.schema} CASCADE"))
        await self.migrator.dispose()

    async def claim(self, actor, key, tool, request=None):
        async with self.engine.begin() as conn:
            await self.runtime.operations.claim(conn, OperationId(INSTALLATION, actor, key), tool_id=tool,
                                                tool_major=1, tool_version="1.0.0", space_id=None,
                                                fingerprint=key, state="accepted" if request else "running", request=request)

    async def fence(self, actor, deletion):
        async with self.engine.begin() as conn:
            await conn.execute(text(f"INSERT INTO {self.schema}.actor_erasures(installation_id,actor_id,deletion_id,receipt_id) "
                                    "VALUES(:i,:a,:id,:receipt)"),
                               {"i": INSTALLATION, "a": actor, "id": deletion, "receipt": uuid4()})

    async def test_pending_erasure_restart_skips_all_actions_and_allows_signed_retry(self):
        await self.claim(self.actor, "configuration", "configuration")
        await self.claim(self.actor, "write", "counter.add")
        await self.claim(self.actor, "async", "counter.drain", {"arguments": {"steps": 1}, "context": {"scopes": ["echo.write"]}})
        # More than one recovery batch must not starve an unrelated actor.
        async with self.engine.begin() as conn:
            await conn.execute(text(f"INSERT INTO {self.schema}.operations(installation_id,actor_id,operation_key,tool_id,tool_major,"
                                    "tool_version,fingerprint,state,created_at,updated_at) "
                                    "SELECT :i,:a,'old-'||n,'counter.add',1,'1.0.0','old-'||n,'running',now(),now() "
                                    "FROM generate_series(1,500) n"), {"i": INSTALLATION, "a": self.actor})
        await self.claim("other-user", "write", "counter.add")
        deletion = str(uuid4())
        with self.assertRaises(Rejected) as unavailable:
            await self.client.erase_actor(self.actor, deletion)
        self.assertEqual(unavailable.exception.code, "dependency_unavailable")
        await self.stop()
        await self.start()  # startup itself used to fail on Operations.complete's privacy guard
        self.assertEqual((await self.client.health())["status"], "ready")
        self.assertEqual(self.recovered, [("other-user", "counter.add")])
        self.assertEqual(self.ran, [])
        async with self.engine.connect() as conn:
            self.assertEqual(await conn.scalar(text(f"SELECT count(*) FROM {self.schema}.operations WHERE actor_id=:a"),
                                               {"a": self.actor}), 503)
            self.assertEqual(await conn.scalar(text(f"SELECT state FROM {self.schema}.actor_erasures WHERE actor_id=:a"),
                                               {"a": self.actor}), "pending")
        self.quiesce_fails = False
        receipt = await self.client.erase_actor(self.actor, deletion)
        self.assertEqual(receipt["status"], "completed")
        self.assertEqual(await self.client.erase_actor(self.actor, deletion), receipt)
        async with self.engine.connect() as conn:
            for table in ("actors", "operations", "events", "counters", "settings"):
                self.assertEqual(await conn.scalar(text(f"SELECT count(*) FROM {self.schema}.{table} WHERE actor_id=:a"),
                                                   {"a": self.actor}), 0)

    async def test_fence_between_recovery_scan_and_commit_does_not_break_startup(self):
        await self.claim(self.actor, "write", "counter.add")
        original = self.runtime.tools[("counter.add", 1)]

        async def recover(row):
            await self.fence(row["actor_id"], uuid4())
            return None

        self.runtime.tools[("counter.add", 1)] = replace(original, recover=recover)
        await self.runtime.resume()
        async with self.engine.connect() as conn:
            self.assertEqual(await conn.scalar(text(f"SELECT state FROM {self.schema}.operations WHERE actor_id=:a"),
                                               {"a": self.actor}), "running")
        self.quiesce_fails = False
        async with self.engine.connect() as conn:
            deletion = await conn.scalar(text(f"SELECT deletion_id FROM {self.schema}.actor_erasures WHERE actor_id=:a"),
                                         {"a": self.actor})
        self.assertEqual((await self.client.erase_actor(self.actor, str(deletion)))["status"], "completed")

    async def test_async_erasure_race_finishes_without_retry_or_background_errors(self):
        request = {"arguments": {"steps": 1}, "context": {"scopes": ["echo.write"]}}
        await self.claim(self.actor, "async", "counter.drain", request)
        original = self.runtime.tools[("counter.drain", 1)]
        calls = []

        async def run(job):
            calls.append(job.ctx.actor_id)
            await self.fence(job.ctx.actor_id, uuid4())
            await job.progress(step=1)

        self.runtime.tools[("counter.drain", 1)] = replace(original, run=run)
        await self.runtime.resume()
        tasks = tuple(self.runtime.jobs)
        self.assertEqual(len(tasks), 1)
        await asyncio.wait_for(asyncio.gather(*tasks), 5)
        self.assertEqual(calls, [self.actor])
        async with self.engine.connect() as conn:
            self.assertEqual((await conn.execute(text(f"SELECT state,running_reason,result FROM {self.schema}.operations "
                                                     "WHERE actor_id=:a"), {"a": self.actor})).one(), ("running", None, None))

    async def assert_suspended_read_erased(self, invoke, original, install, expected):
        ready, release, cancelled, finished = (asyncio.Event() for _ in range(4))
        captured = []

        async def suspended(*args):
            body = await original(*args)  # query connection and advisory lock have been released
            captured.append(body)
            ready.set()
            try:
                await release.wait()
            except asyncio.CancelledError:
                cancelled.set()
                raise
            finally:
                finished.set()
            return body

        install(suspended)
        reading = asyncio.create_task(invoke())
        try:
            await asyncio.wait_for(ready.wait(), 5)
            self.assertEqual(captured, [expected])
            self.quiesce_fails = False
            receipt = await asyncio.wait_for(self.client.erase_actor(self.actor, str(uuid4())), 5)
            self.assertEqual(receipt["status"], "completed")
            # The handler must finish before the erasure receipt, with no old
            # account result delivered from the in-memory suspended read.
            self.assertTrue(cancelled.is_set())
            self.assertTrue(finished.is_set())
            with self.assertRaises((Rejected, Uncertain)):
                await asyncio.wait_for(reading, 5)
        finally:
            release.set()
            await asyncio.gather(reading, return_exceptions=True)

    async def test_http_configuration_view_is_cancelled_before_erasure_receipt(self):
        async with self.engine.begin() as conn:
            await self.runtime.operations.ensure_actor(conn, INSTALLATION, self.actor)
            await conn.execute(text(f"INSERT INTO {self.schema}.settings(installation_id,actor_id,greeting,revision) "
                                    "VALUES(:i,:a,'private synthetic greeting',1)"), {"i": INSTALLATION, "a": self.actor})
        expected = {"contract": "assistant.configuration/v1", "status": "configured", "revision": 1,
                    "public": {"greeting": "private synthetic greeting"}, "secret_state": {"token": {"present": False}}}
        await self.assert_suspended_read_erased(
            lambda: self.client.configuration("GET", actor_id=self.actor, scopes=["echo.read"], timeout=5),
            self.runtime.configuration.view, lambda handler: setattr(self.runtime.configuration, "view", handler), expected)

    async def test_http_lookup_private_result_is_cancelled_before_erasure_receipt(self):
        await self.claim(self.actor, "private-result", "counter.add")
        body = result("private-result", "completed", data={"value": "9385.12", "revision": 1})
        async with self.engine.begin() as conn:
            await self.runtime.operations.complete(conn, OperationId(INSTALLATION, self.actor, "private-result"), body)
        expected = {"contract": "assistant.lookup/v1", "operation_key": "private-result", "status": "found", "result": body}
        await self.assert_suspended_read_erased(
            lambda: self.client.lookup(INSTALLATION, self.actor, "private-result", scopes=["echo.read", "echo.write"], timeout=5),
            self.runtime._lookup, lambda handler: setattr(self.runtime, "_lookup", handler), expected)
