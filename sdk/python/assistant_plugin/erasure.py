"""Optional privacy extension: permanent actor fencing and durable erasure receipts."""
from uuid import UUID, uuid4

from sqlalchemy import text

from .canonical import rfc3339
from .errors import Rejected
from .storage import _schema


def ddl(schema):
    s = _schema(schema)
    return f'''CREATE TABLE {s}.actor_erasures (
      installation_id text NOT NULL,actor_id text NOT NULL,deletion_id uuid NOT NULL,
      state text NOT NULL DEFAULT 'pending' CHECK(state IN('pending','completed')),
      receipt_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
      PRIMARY KEY(installation_id,actor_id),CHECK((state='completed')=(completed_at IS NOT NULL)))'''


class ActorErasure:
    """Handler protocol: quiesce(installation,actor), erase(conn,installation,actor)."""

    def __init__(self, runtime, handler):
        self.runtime, self.handler, self.schema = runtime, handler, runtime.operations.s
        runtime.erasure = self
        runtime.operations.actor_guard = self.guard
        runtime.events.actor_guard = self.guard

    async def guard(self, conn, installation, actor):
        # The same transactional fence guards writes and deletion. A late
        # configuration probe or background operation cannot resurrect data.
        await conn.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:key,0))'),
                           {'key': 'privacy:' + installation + ':' + actor})
        if await conn.scalar(text(f'SELECT 1 FROM {self.schema}.actor_erasures WHERE installation_id=:i AND actor_id=:a'),
                             {'i': installation, 'a': actor}):
            raise Rejected('actor_erased', 'Учётная запись удалена', status=410)

    async def check(self, installation, actor):
        async with self.runtime.engine.begin() as conn:
            await self.guard(conn, installation, actor)

    async def erase(self, delegation, body):
        if 'privacy.erase' not in delegation.scopes or delegation.purpose != 'privacy.erase':
            raise Rejected('forbidden', 'Нужна делегация удаления аккаунта')
        if not isinstance(body, dict) or set(body) != {'contract', 'deletion_id'} or body['contract'] != 'assistant.actor-erasure-request/v1':
            raise Rejected('malformed_envelope', 'Неверный запрос удаления')
        try:
            identity = UUID(body['deletion_id'])
        except (ValueError, TypeError, AttributeError):
            raise Rejected('malformed_envelope', 'Неверный идентификатор удаления') from None
        installation, actor = delegation.installation_id, delegation.actor_id
        params = {'i': installation, 'a': actor, 'id': identity}
        async with self.runtime.engine.begin() as conn:
            await conn.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:key,0))'), {'key': 'privacy:' + installation + ':' + actor})
            await conn.execute(text(f'''INSERT INTO {self.schema}.actor_erasures(installation_id,actor_id,deletion_id,receipt_id)
              VALUES(:i,:a,:id,:receipt) ON CONFLICT DO NOTHING'''), {**params, 'receipt': uuid4()})
            row = (await conn.execute(text(f'SELECT * FROM {self.schema}.actor_erasures WHERE installation_id=:i AND actor_id=:a'), params)).mappings().one()
        if row['deletion_id'] != identity:
            raise Rejected('idempotency_conflict', 'Для этого аккаунта уже запрошено удаление')
        if row['state'] == 'completed':
            return self.receipt(row)
        await self.runtime.cancel_actor(installation, actor)
        await self.handler.quiesce(installation, actor)
        async with self.runtime.engine.begin() as conn:
            await conn.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:key,0))'), {'key': 'privacy:' + installation + ':' + actor})
            row = (await conn.execute(text(f'SELECT * FROM {self.schema}.actor_erasures WHERE installation_id=:i AND actor_id=:a'), params)).mappings().one()
            if row['state'] == 'completed':
                return self.receipt(row)
            await self.handler.erase(conn, installation, actor)
            for table in ('events', 'operations', 'actors'):
                await conn.execute(text(f'DELETE FROM {self.schema}.{table} WHERE installation_id=:i AND actor_id=:a'), params)
            row = (await conn.execute(text(f'''UPDATE {self.schema}.actor_erasures SET state='completed',completed_at=:now
              WHERE installation_id=:i AND actor_id=:a RETURNING *'''), {**params, 'now': self.runtime.clock()})).mappings().one()
        self.runtime.events.notify()
        return self.receipt(row)

    @staticmethod
    def receipt(row):
        return {'contract': 'assistant.actor-erasure/v1', 'deletion_id': str(row['deletion_id']),
                'status': 'completed', 'receipt_id': str(row['receipt_id']), 'completed_at': rfc3339(row['completed_at'])}
