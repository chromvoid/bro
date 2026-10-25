"""Durable operation keys, terminal results and lookup (spec §5.2).

A key is scoped by (installation, actor, operation_key) and bound to the tool,
its major version, the space and the canonical arguments. A terminal result is
never rewritten; a forgotten key is never reused because rows are not deleted.
"""

import json
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import text

from .envelopes import TERMINAL, result as result_body
from .errors import Rejected
from .storage import _schema


def utcnow():
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class OperationId:
    installation_id: str
    actor_id: str
    operation_key: str

    def params(self):
        return {"installation": self.installation_id, "actor": self.actor_id, "key": self.operation_key}


class Operations:
    def __init__(self, schema, clock=utcnow):
        self.s, self.clock = _schema(schema), clock

    async def ensure_actor(self, conn, installation_id, actor_id):
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, installation_id, actor_id)
        await conn.execute(text(f"INSERT INTO {self.s}.actors(installation_id, actor_id) VALUES (:installation, :actor) "
                                "ON CONFLICT DO NOTHING"), {"installation": installation_id, "actor": actor_id})

    async def claim(self, conn, op, *, tool_id, tool_major, tool_version, space_id, fingerprint, state, request=None, reason=None):
        """Registers the key, or returns the existing row. The same key with other arguments conflicts."""
        await self.ensure_actor(conn, op.installation_id, op.actor_id)
        inserted = await conn.scalar(text(
            f"""INSERT INTO {self.s}.operations(installation_id, actor_id, operation_key, tool_id, tool_major, tool_version,
                space_id, fingerprint, state, running_reason, request, created_at, updated_at)
            VALUES (:installation, :actor, :key, :tool, :major, :version, :space, :fingerprint, :state, :reason,
                CAST(:request AS jsonb), :now, :now)
            ON CONFLICT DO NOTHING RETURNING 1"""),
            {**op.params(), "tool": tool_id, "major": tool_major, "version": tool_version, "space": space_id,
             "fingerprint": fingerprint, "state": state, "reason": reason, "now": self.clock(),
             "request": json.dumps(request) if request is not None else None})
        if inserted:
            return None
        # ON CONFLICT waited for any competing insert to commit, so the row is visible now.
        row = await self.row(conn, op, lock=True)
        if row is None:
            raise RuntimeError("operation row vanished")
        if row["fingerprint"] != fingerprint or row["tool_id"] != tool_id or row["tool_major"] != tool_major:
            raise Rejected("idempotency_conflict", "Ключ операции уже использован с другими данными")
        return row

    async def row(self, conn, op, lock=False):
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, op.installation_id, op.actor_id)
        found = await conn.execute(text(
            f"SELECT * FROM {self.s}.operations WHERE installation_id=:installation AND actor_id=:actor AND operation_key=:key"
            + (" FOR UPDATE" if lock else "")), op.params())
        found = found.mappings().first()
        return dict(found) if found else None

    async def progress(self, conn, op, *, state, reason=None, progress=None):
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, op.installation_id, op.actor_id)
        await conn.execute(text(
            f"""UPDATE {self.s}.operations SET state=:state, running_reason=:reason, progress=CAST(:progress AS jsonb),
                updated_at=:now WHERE installation_id=:installation AND actor_id=:actor AND operation_key=:key
                AND state IN ('accepted','running')"""),
            {**op.params(), "state": state, "reason": reason, "now": self.clock(),
             "progress": json.dumps(progress) if progress is not None else None})

    async def complete(self, conn, op, body):
        if getattr(self, 'actor_guard', None):
            await self.actor_guard(conn, op.installation_id, op.actor_id)
        if body["state"] not in TERMINAL:
            raise ValueError("only terminal results are stored")
        now = self.clock()
        updated = await conn.execute(text(
            f"""UPDATE {self.s}.operations SET state=:state, result=CAST(:result AS jsonb), running_reason=NULL,
                updated_at=:now, terminal_at=:now
            WHERE installation_id=:installation AND actor_id=:actor AND operation_key=:key AND state IN ('accepted','running')"""),
            {**op.params(), "state": body["state"], "result": json.dumps(body), "now": now})
        if updated.rowcount != 1:
            raise RuntimeError("operation is already terminal or missing")

    @staticmethod
    def view(row):
        """The OperationResult a lookup returns for a stored row."""
        if row["state"] in TERMINAL:
            return row["result"]
        progress = dict(row["progress"] or {})
        if row["running_reason"]:
            progress.setdefault("reason", row["running_reason"])
        return result_body(row["operation_key"], row["state"], progress=progress or None)

    async def open(self, conn, older_than=None, *, exclude_erased=False):
        # Fenced actors stay pending until their signed erasure is retried. They
        # must not consume the recovery batch or restart any admitted work.
        visible = (f" AND NOT EXISTS (SELECT 1 FROM {self.s}.actor_erasures erased "
                   "WHERE erased.installation_id=operations.installation_id AND erased.actor_id=operations.actor_id)"
                   if exclude_erased else "")
        rows = await conn.execute(text(
            f"SELECT * FROM {self.s}.operations WHERE state IN ('accepted','running')"
            + (" AND updated_at < :before" if older_than else "") + visible + " ORDER BY created_at LIMIT 500"),
            {"before": older_than} if older_than else {})
        return [dict(row) for row in rows.mappings()]
