"""Per-actor ordered events with opaque cursors and an SSE stream (spec §9).

Events are signals to re-read through read tools; delivery may repeat and
clients deduplicate by `event_id`. An expired or unknown cursor produces
`resync_required` carrying the current head.
"""

import asyncio
import json
import logging
import re
from collections import Counter
from datetime import timedelta
from uuid import uuid4

from aiohttp import web
from sqlalchemy import text

from .canonical import rfc3339
from .errors import Rejected
from .operations import utcnow
from .storage import _schema

CURSOR = re.compile(r"e1\.([0-9]{1,18})")


def cursor(seq):
    return f"e1.{int(seq)}"


def parse_cursor(value):
    if value in (None, ""):
        return None
    match = CURSOR.fullmatch(value) if isinstance(value, str) and len(value) <= 64 else None
    if not match:
        raise Rejected("malformed_envelope", "Неверный курсор событий")
    return int(match.group(1))


class EventLog:
    def __init__(self, schema, clock=utcnow):
        self.s, self.clock = _schema(schema), clock
        self.wakers = set()

    async def append(self, conn, installation_id, actor_id, type, payload, *, operation_key=None, resource_id=None, revision=None):
        """Call inside the transaction that made the change; call `notify()` after it commits."""
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, installation_id, actor_id)
        await conn.execute(text(f"INSERT INTO {self.s}.actors(installation_id, actor_id) VALUES (:i, :a) ON CONFLICT DO NOTHING"),
                           {"i": installation_id, "a": actor_id})
        seq = await conn.scalar(text(f"UPDATE {self.s}.actors SET event_seq=event_seq+1 WHERE installation_id=:i AND actor_id=:a "
                                     "RETURNING event_seq"), {"i": installation_id, "a": actor_id})
        await conn.execute(text(
            f"""INSERT INTO {self.s}.events(installation_id, actor_id, seq, event_id, type, occurred_at, operation_key,
                resource_id, revision, payload)
            VALUES (:i, :a, :seq, :id, :type, :at, :key, :resource, :revision, CAST(:payload AS jsonb))"""),
            {"i": installation_id, "a": actor_id, "seq": seq, "id": uuid4(), "type": type, "at": self.clock(),
             "key": operation_key, "resource": resource_id, "revision": revision, "payload": json.dumps(payload)})
        return seq

    def notify(self):
        for wake in tuple(self.wakers):
            wake.set()

    async def head(self, conn, installation_id, actor_id):
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, installation_id, actor_id)
        return await conn.scalar(text(f"SELECT event_seq FROM {self.s}.actors WHERE installation_id=:i AND actor_id=:a"),
                                 {"i": installation_id, "a": actor_id}) or 0

    async def floor(self, conn, installation_id, actor_id):
        return await conn.scalar(text(f"SELECT min(seq) FROM {self.s}.events WHERE installation_id=:i AND actor_id=:a"),
                                 {"i": installation_id, "a": actor_id})

    async def after(self, conn, installation_id, actor_id, seq, limit=100):
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, installation_id, actor_id)
        rows = await conn.execute(text(
            f"""SELECT seq, event_id, type, occurred_at, operation_key, resource_id, revision, payload FROM {self.s}.events
            WHERE installation_id=:i AND actor_id=:a AND seq > :seq ORDER BY seq LIMIT :limit"""),
            {"i": installation_id, "a": actor_id, "seq": seq, "limit": limit})
        return [dict(row) for row in rows.mappings()]

    async def prune(self, conn, days):
        result = await conn.execute(text(f"DELETE FROM {self.s}.events WHERE occurred_at < :before"),
                                    {"before": self.clock() - timedelta(days=days)})
        return result.rowcount

    @staticmethod
    def envelope(installation_id, row):
        body = {"event_id": str(row["event_id"]), "installation_id": installation_id, "type": row["type"],
                "occurred_at": rfc3339(row["occurred_at"]), "cursor": cursor(row["seq"]), "payload": row["payload"]}
        for name in ("operation_key", "resource_id", "revision"):
            if row.get(name) is not None:
                body[name] = row[name]
        return body


class EventStream:
    """SSE for one actor at a time, bounded in count and lifetime so revoked grants stop taking effect."""

    def __init__(self, log, engine, *, max_connections=64, per_actor=4, lifetime=300, heartbeat=15, poll=2.0):
        self.log, self.engine = log, engine
        self.max_connections, self.per_actor, self.lifetime, self.heartbeat, self.poll = max_connections, per_actor, lifetime, heartbeat, poll
        self.active = Counter()
        self.tasks = set()

    async def shutdown(self, app=None):
        for task in tuple(self.tasks):
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)

    async def serve(self, request, installation_id, actor_id, start):
        key = (installation_id, actor_id)
        if sum(self.active.values()) >= self.max_connections or self.active[key] >= self.per_actor:
            raise Rejected("quota_exceeded", "Слишком много потоков событий", retry_after_ms=5000)
        wake = asyncio.Event()
        self.log.wakers.add(wake)
        self.active[key] += 1
        task = asyncio.current_task()
        self.tasks.add(task)
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-store",
                                               "X-Accel-Buffering": "no"})
        try:
            await response.prepare(request)
            loop = asyncio.get_running_loop()
            ends, beat = loop.time() + self.lifetime, loop.time()
            async with self.engine.connect() as conn:
                head = await self.log.head(conn, installation_id, actor_id)
                floor = await self.log.floor(conn, installation_id, actor_id)
                await conn.rollback()
            if start is None:
                seq = head
                await self._send(response, cursor(seq), "stream.ready", {"cursor": cursor(seq)})
            elif start > head or (floor is not None and start < floor - 1) or (floor is None and start < head):
                seq = head
                await self._send(response, cursor(seq), "resync_required", {"cursor": cursor(seq)})
            else:
                seq = start
            while loop.time() < ends:
                wake.clear()
                async with self.engine.connect() as conn:
                    rows = await self.log.after(conn, installation_id, actor_id, seq)
                    await conn.rollback()
                for row in rows:
                    body = self.log.envelope(installation_id, row)
                    await self._send(response, body["cursor"], row["type"], body)
                    seq = row["seq"]
                if rows:
                    beat = loop.time()
                    continue
                if loop.time() - beat >= self.heartbeat:
                    await asyncio.wait_for(response.write(b": heartbeat\n\n"), 5)
                    beat = loop.time()
                try:
                    await asyncio.wait_for(wake.wait(), min(self.poll, max(0.05, ends - loop.time())))
                except TimeoutError:
                    pass
            await asyncio.wait_for(response.write(b"retry: 1000\n\n"), 5)
        except (ConnectionResetError, TimeoutError):
            pass
        except Exception:
            logging.exception("plugin_event_stream_failed")
        finally:
            self.log.wakers.discard(wake)
            self.active[key] -= 1
            if self.active[key] <= 0:
                del self.active[key]
            self.tasks.discard(task)
        return response

    @staticmethod
    async def _send(response, event_id, name, body):
        data = json.dumps(body, separators=(",", ":"), ensure_ascii=False, default=str)
        await asyncio.wait_for(response.write(f"id: {event_id}\nevent: {name}\ndata: {data}\n\n".encode()), 5)
