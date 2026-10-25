"""A synthetic read/write plugin service (spec §14) used to prove the SDK contract end to end."""

import asyncio
import hashlib
import hmac
from decimal import Decimal
from pathlib import Path

from sqlalchemy import text

from assistant_plugin import package, secretbox
from assistant_plugin.delegation import Verifier
from assistant_plugin.envelopes import effect
from assistant_plugin.errors import DomainFailure, Rejected
from assistant_plugin.service import Limits, Runtime, Tool

FIXTURE = Path(__file__).parent / "fixtures" / "echo"


def tables(schema):
    return [f"""CREATE TABLE {schema}.counters (installation_id text, actor_id text, value numeric NOT NULL DEFAULT 0,
                revision integer NOT NULL DEFAULT 0, PRIMARY KEY (installation_id, actor_id))""",
            f"""CREATE TABLE {schema}.settings (installation_id text, actor_id text, greeting text, revision integer NOT NULL,
                wrap_nonce bytea, wrapped_dek bytea, data_nonce bytea, ciphertext bytea, kek_id text,
                PRIMARY KEY (installation_id, actor_id))"""]


def build(engine, schema, keys, *, issuer="https://assistant.test", limits=Limits(), keyring=None, drain_gate=None):
    s = schema
    keyring = keyring or secretbox.Keyring({"k1": b"m" * 32}, "k1")

    async def say(ctx, args):
        if args.get("sleep_ms"):
            await asyncio.sleep(args["sleep_ms"] / 1000)
        if args.get("fail"):
            raise DomainFailure("refused", "Отказано по просьбе")
        return {"text": args["text"]}

    async def counter(conn, ctx, lock=True):
        await conn.execute(text(f"INSERT INTO {s}.counters(installation_id, actor_id) VALUES (:i, :a) ON CONFLICT DO NOTHING"),
                           {"i": ctx.installation_id, "a": ctx.actor_id})
        row = await conn.execute(text(f"SELECT value, revision FROM {s}.counters WHERE installation_id=:i AND actor_id=:a"
                                      + (" FOR UPDATE" if lock else "")), {"i": ctx.installation_id, "a": ctx.actor_id})
        return row.mappings().one()

    async def add(conn, ctx, args):
        current = await counter(conn, ctx)
        if current["revision"] != args["revision"]:
            raise Rejected("revision_conflict", "Счётчик уже изменён")
        value = current["value"] + Decimal(args["amount"])
        revision = current["revision"] + 1
        await conn.execute(text(f"UPDATE {s}.counters SET value=:v, revision=:r WHERE installation_id=:i AND actor_id=:a"),
                           {"v": value, "r": revision, "i": ctx.installation_id, "a": ctx.actor_id})
        await runtime.events.append(conn, ctx.installation_id, ctx.actor_id, "counter.changed", {"revision": revision},
                                    operation_key=ctx.operation_key, resource_id="counter", revision=revision)
        return {"value": str(value), "revision": revision}, [effect("counter", "counter", "updated", revision)]

    async def admit(conn, ctx, args):
        await counter(conn, ctx)
        return {"steps": args.get("steps", 1)}

    async def drain(job):
        for step in range(job.arguments.get("steps", 1)):
            if drain_gate is not None:
                await drain_gate.wait()
            await job.progress(step=step + 1)
        async with engine.begin() as conn:
            current = await counter(conn, job.ctx)
            revision = current["revision"] + 1
            await conn.execute(text(f"UPDATE {s}.counters SET value=0, revision=:r WHERE installation_id=:i AND actor_id=:a"),
                               {"r": revision, "i": job.ctx.installation_id, "a": job.ctx.actor_id})
            await runtime.events.append(conn, job.ctx.installation_id, job.ctx.actor_id, "counter.changed", {"revision": revision})
        return {"value": "0"}, [effect("counter", "counter", "updated", revision)]

    class Settings:
        fingerprint_key = keyring.derive(b"echo.config-fingerprint.v1")

        def fingerprint(self, action, ctx, request):
            material = {"public": request.get("public"), "expected_revision": request.get("expected_revision")}
            if "secret" in request:
                material["secret"] = hmac.new(self.fingerprint_key, request["secret"]["token"].encode(), hashlib.sha256).hexdigest()
            return material

        async def view(self, ctx):
            async with engine.connect() as conn:
                row = (await conn.execute(text(f"SELECT greeting, revision, ciphertext IS NOT NULL AS has_secret FROM {s}.settings "
                                               "WHERE installation_id=:i AND actor_id=:a"),
                                          {"i": ctx.installation_id, "a": ctx.actor_id})).mappings().first()
                await conn.rollback()
            if row is None:
                return {"contract": "assistant.configuration/v1", "status": "not_configured", "revision": 0, "public": {}, "secret_state": {}}
            return {"contract": "assistant.configuration/v1", "status": "configured", "revision": row["revision"],
                    "public": {"greeting": row["greeting"]} if row["greeting"] else {}, "secret_state": {"token": {"present": row["has_secret"]}}}

        async def prepare(self, action, ctx, request):
            if action == "put" and request.get("secret", {}).get("token") == "rejected-token":
                raise DomainFailure("credential_rejected", "Токен отклонён внешним сервисом")

            async def commit(conn):
                row = (await conn.execute(text(f"SELECT revision FROM {s}.settings WHERE installation_id=:i AND actor_id=:a FOR UPDATE"),
                                          {"i": ctx.installation_id, "a": ctx.actor_id})).first()
                current = row[0] if row else 0
                if request.get("expected_revision") is not None and request["expected_revision"] != current:
                    raise Rejected("revision_conflict", "Настройки уже изменены")
                if action == "delete":
                    await conn.execute(text(f"DELETE FROM {s}.settings WHERE installation_id=:i AND actor_id=:a"),
                                       {"i": ctx.installation_id, "a": ctx.actor_id})
                    return {"status": "not_configured", "revision": 0}, [effect("configuration", "echo", "deleted")]
                sealed = keyring.seal(request["secret"]["token"].encode(), f"{ctx.installation_id}|{ctx.actor_id}".encode(), ctx.actor_id) \
                    if "secret" in request else None
                await runtime.events.append(conn, ctx.installation_id, ctx.actor_id, "counter.changed", {"revision": current + 1})
                await conn.execute(text(
                    f"""INSERT INTO {s}.settings(installation_id, actor_id, greeting, revision, wrap_nonce, wrapped_dek, data_nonce, ciphertext, kek_id)
                    VALUES (:i, :a, :g, :r, :wn, :wd, :dn, :c, :k)
                    ON CONFLICT (installation_id, actor_id) DO UPDATE SET greeting=EXCLUDED.greeting, revision=EXCLUDED.revision,
                      wrap_nonce=COALESCE(EXCLUDED.wrap_nonce, {s}.settings.wrap_nonce), wrapped_dek=COALESCE(EXCLUDED.wrapped_dek, {s}.settings.wrapped_dek),
                      data_nonce=COALESCE(EXCLUDED.data_nonce, {s}.settings.data_nonce), ciphertext=COALESCE(EXCLUDED.ciphertext, {s}.settings.ciphertext),
                      kek_id=COALESCE(EXCLUDED.kek_id, {s}.settings.kek_id)"""),
                    {"i": ctx.installation_id, "a": ctx.actor_id, "g": request.get("public", {}).get("greeting"), "r": current + 1,
                     "wn": sealed.wrap_nonce if sealed else None, "wd": sealed.wrapped_dek if sealed else None,
                     "dn": sealed.data_nonce if sealed else None, "c": sealed.ciphertext if sealed else None,
                     "k": sealed.kek_id if sealed else None})
                return {"status": "configured", "revision": current + 1}, [effect("configuration", "echo", "updated", current + 1)]
            return commit

        async def recover(self, row):
            return None

    async def checks():
        async with engine.connect() as conn:
            await conn.scalar(text("SELECT 1"))
            await conn.rollback()
        return {"schema": {"status": "ready"}}, {"echo": {"status": "ready"}}

    runtime = Runtime(package.load(FIXTURE), "1.0.0", engine=engine, schema=schema,
                      tools={"echo.say": Tool(handler=say), "counter.add": Tool(handler=add),
                             "counter.drain": Tool(admit=admit, run=drain)},
                      verifiers={installation: Verifier(installation, issuer, {f"{installation}/{kid}": key for kid, key in ring.items()})
                                 for installation, ring in keys.items()},
                      configuration=Settings(), checks=checks, limits=limits)
    return runtime
