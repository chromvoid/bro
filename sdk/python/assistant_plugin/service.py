"""Service runtime: binds package descriptors to handlers and applies the contract rules.

Handlers:
- read tool:          `handler(ctx, args) -> data`
- transactional write: `handler(conn, ctx, args) -> (data, effects)`; runs inside the
  transaction that also stores the key and the receipt
- async write:        `admit(conn, ctx, args) -> progress | None` and `run(job) -> (data, effects)`;
  `run` must be safe to repeat after a restart (best effort + reconciliation)

`Rejected` from a handler means nothing happened (4xx). `DomainFailure` after
admission ends the operation in `failed` and is stored under its key.
"""

import asyncio
import json
import logging
from collections import Counter
from dataclasses import dataclass
from datetime import datetime

from .canonical import fingerprint, parse_rfc3339, rfc3339, to_json
from .envelopes import (CONFIGURATION_REQUEST, INVOCATION, LOOKUP_REQUEST, TERMINAL, check, effect, lookup as lookup_body,
                        major, receipt, result)
from .errors import DomainFailure, PluginError, Rejected
from .events import EventLog
from .operations import OperationId, Operations, utcnow
from .schemas import field_errors, validator

CONFIGURATION_TOOL = "configuration"


@dataclass(frozen=True)
class Context:
    installation_id: str
    actor_id: str
    space_id: str | None
    locale: str | None
    timezone: str | None
    trace_id: str
    deadline_at: datetime
    request_id: str
    operation_key: str | None
    scopes: frozenset

    def remaining(self, now):
        return (self.deadline_at - now).total_seconds()


@dataclass(frozen=True)
class Tool:
    handler: object = None
    admit: object = None
    run: object = None
    recover: object = None


@dataclass
class Job:
    """Background work for an admitted async operation."""
    op: OperationId
    ctx: Context
    arguments: dict
    runtime: object

    async def progress(self, **values):
        async with self.runtime.engine.begin() as conn:
            await self.runtime.operations.progress(conn, self.op, state="running", progress=values)


@dataclass(frozen=True)
class Limits:
    inflight_per_actor: int = 4
    inflight_total: int = 32
    max_response_bytes: int = 1024 * 1024
    stale_after_seconds: int = 60


class Runtime:
    def __init__(self, package, service_version, *, engine, schema, tools, verifiers, configuration=None,
                 checks=None, limits=Limits(), clock=utcnow):
        self.package, self.service_version, self.engine = package, service_version, engine
        self.operations, self.events = Operations(schema, clock), EventLog(schema, clock)
        self.verifiers, self.configuration, self.checks, self.limits, self.clock = verifiers, configuration, checks, limits, clock
        self.inflight = Counter()
        self.jobs = set()
        self.actor_jobs = {}
        self.actor_requests = {}
        self.erasure = None
        self.tools, self.validators = {}, {}
        for (tool_id, tool_major), descriptor in package.tools.items():
            tool = tools.get(tool_id)
            if tool is None:
                raise ValueError(f"no handler for tool {tool_id}")
            asynchronous = descriptor["execution"]["mode"] == "async"
            if (asynchronous and (tool.admit is None or tool.run is None)) or (not asynchronous and tool.handler is None):
                raise ValueError(f"handler shape does not match execution mode of {tool_id}")
            self.tools[(tool_id, tool_major)] = tool
            self.validators[(tool_id, tool_major)] = (validator(descriptor["input_schema"]), validator(descriptor["output_schema"]))
        if set(tools) - {tool_id for tool_id, _ in package.tools}:
            raise ValueError("handlers registered for tools missing from the package")
        if (configuration is None) != (package.configuration is None):
            raise ValueError("configuration handler and package configuration must be declared together")
        if package.configuration:
            self.config_validators = {name: validator(package.configuration[name]) for name in ("public_schema", "secret_schema")}

    # -- invocation ---------------------------------------------------------------------------------------------

    def _context(self, delegation, envelope):
        ctx = envelope["context"]
        if envelope["installation_id"] != delegation.installation_id or ctx["actor_id"] != delegation.actor_id \
                or envelope["operation_key"] != delegation.operation_key or ctx.get("space_id") != delegation.space_id:
            raise Rejected("unauthenticated", "Делегация не соответствует запросу")
        deadline = parse_rfc3339(ctx["deadline_at"])
        if deadline <= self.clock():
            raise Rejected("deadline_exceeded", "Срок запроса истёк", recovery="none")
        return Context(envelope["installation_id"], ctx["actor_id"], ctx.get("space_id"), ctx.get("locale"), ctx.get("timezone"),
                       ctx["trace_id"], deadline, envelope["request_id"], envelope["operation_key"], delegation.scopes)

    def _descriptor(self, tool_id, version):
        requested = [int(part) for part in version.split(".")]
        descriptor = self.package.tool(tool_id, requested[0])
        if descriptor is None:
            raise Rejected("unsupported_version", "Инструмент этой версии не поддерживается")
        offered = [int(part) for part in descriptor["version"].split(".")]
        if requested[1:2] and requested[1] > offered[1]:
            raise Rejected("unsupported_version", "Сервис поддерживает более раннюю версию инструмента")
        return descriptor

    def _permitted(self, descriptor, scopes):
        if not set(descriptor["scopes"]) <= set(scopes):
            raise Rejected("forbidden", "Недостаточно прав")

    def _admit(self, ctx):
        key = (ctx.installation_id, ctx.actor_id)
        if self.inflight[key] >= self.limits.inflight_per_actor or sum(self.inflight.values()) >= self.limits.inflight_total:
            raise Rejected("quota_exceeded", "Слишком много одновременных запросов", retry_after_ms=1000)
        self.inflight[key] += 1
        return key

    def _release(self, key):
        self.inflight[key] -= 1
        if self.inflight[key] <= 0:
            del self.inflight[key]

    def _budget(self, ctx, descriptor):
        return max(0.001, min(ctx.remaining(self.clock()), descriptor["execution"]["max_duration_ms"] / 1000))

    def _output(self, key, descriptor, data):
        """Output must match the declared schema and size; a violation is our bug, reported as internal_error.

        Handlers may return Decimal and datetime values; they are converted to their exact wire text first."""
        raw = to_json(data)
        data = json.loads(raw)
        errors = field_errors(self.validators[key][1], data)
        size = len(raw.encode())
        limit = min(descriptor.get("limits", {}).get("max_response_bytes", self.limits.max_response_bytes), self.limits.max_response_bytes)
        if errors or size > limit:
            logging.error("plugin_output_contract_violation tool=%s errors=%s size=%s", key[0], [e["path"] for e in errors], size)
            raise PluginError("internal_error", "Сервис вернул данные вне контракта")
        return data

    async def invoke(self, delegation, envelope):
        return await self._actor_request(delegation, self._invoke, envelope)

    async def _actor_request(self, delegation, handler, *arguments, check_after=True):
        task = asyncio.current_task()
        previous = self.actor_requests.get(task)
        self.actor_requests[task] = (delegation.installation_id, delegation.actor_id)
        try:
            if self.erasure is not None:
                await self.erasure.check(delegation.installation_id, delegation.actor_id)
            body = await handler(delegation, *arguments)
            if check_after and self.erasure is not None:
                await self.erasure.check(delegation.installation_id, delegation.actor_id)
            return body
        finally:
            if previous is None:
                self.actor_requests.pop(task, None)
            else:
                self.actor_requests[task] = previous

    async def _invoke(self, delegation, envelope):
        check(INVOCATION, envelope)
        ctx = self._context(delegation, envelope)
        descriptor = self._descriptor(envelope["tool_id"], envelope["tool_version"])
        key = (descriptor["id"], major(descriptor["version"]))
        self._permitted(descriptor, delegation.scopes)
        arguments = envelope["arguments"]
        errors = field_errors(self.validators[key][0], arguments)
        if errors:
            raise Rejected("invalid_arguments", "Аргументы не соответствуют схеме", field_errors=errors)
        slot = self._admit(ctx)
        try:
            if descriptor["effect"] == "read":
                return 200, await self._read(key, descriptor, ctx, arguments)
            op = OperationId(ctx.installation_id, ctx.actor_id, ctx.operation_key)
            material = {"tool": key[0], "major": key[1], "space": ctx.space_id, "arguments": arguments}
            if descriptor["execution"]["mode"] == "async":
                return await self._start(key, descriptor, ctx, op, arguments, fingerprint(material))
            return await self._write(key, descriptor, ctx, op, arguments, fingerprint(material))
        finally:
            self._release(slot)

    async def _read(self, key, descriptor, ctx, arguments):
        try:
            async with asyncio.timeout(self._budget(ctx, descriptor)):
                data = await self.tools[key].handler(ctx, arguments)
            return result(ctx.operation_key, "completed", data=self._output(key, descriptor, data))
        except DomainFailure as failure:
            return result(ctx.operation_key, "failed", error=failure.to_wire())
        except TimeoutError:
            return result(ctx.operation_key, "failed", error=PluginError("deadline_exceeded", "Не успели за отведённое время",
                                                                         recovery="none").to_wire())
        except PluginError as error:
            if isinstance(error, Rejected):
                raise
            return result(ctx.operation_key, "failed", error=error.to_wire())

    def _claim_args(self, key, descriptor, ctx, digest):
        return dict(tool_id=key[0], tool_major=key[1], tool_version=descriptor["version"], space_id=ctx.space_id, fingerprint=digest)

    async def _stored(self, row):
        body = self.operations.view(row)
        return (200 if row["state"] in TERMINAL else 202), body

    async def _write(self, key, descriptor, ctx, op, arguments, digest):
        try:
            async with asyncio.timeout(self._budget(ctx, descriptor)):
                async with self.engine.begin() as conn:
                    existing = await self.operations.claim(conn, op, state="running", **self._claim_args(key, descriptor, ctx, digest))
                    if existing:
                        return await self._stored(existing)
                    data, effects = await self.tools[key].handler(conn, ctx, arguments)
                    body = result(op.operation_key, "completed", data=self._output(key, descriptor, data),
                                  receipts=[receipt(op.operation_key, effects, self.service_version, self.clock())])
                    await self.operations.complete(conn, op, body)
        except DomainFailure as failure:
            return await self._record_failure(key, descriptor, ctx, op, digest, failure)
        except TimeoutError:
            raise Rejected("deadline_exceeded", "Исход записи неизвестен: проверьте по ключу", recovery="lookup") from None
        self.events.notify()
        return 200, body

    async def _record_failure(self, key, descriptor, ctx, op, digest, failure, state="running"):
        async with self.engine.begin() as conn:
            existing = await self.operations.claim(conn, op, state=state, **self._claim_args(key, descriptor, ctx, digest))
            if existing and existing["state"] in TERMINAL:
                return await self._stored(existing)
            receipts = [receipt(op.operation_key, failure.effects, self.service_version, self.clock())] if failure.effects else []
            body = result(op.operation_key, "failed", error=failure.to_wire(), receipts=receipts)
            await self.operations.complete(conn, op, body)
        return 200, body

    async def _start(self, key, descriptor, ctx, op, arguments, digest):
        request = {"arguments": arguments, "context": {"space_id": ctx.space_id, "locale": ctx.locale, "timezone": ctx.timezone,
                                                      "trace_id": ctx.trace_id, "scopes": sorted(ctx.scopes)}}
        admission = descriptor["execution"]["admission_timeout_ms"] / 1000
        try:
            async with asyncio.timeout(max(0.001, min(ctx.remaining(self.clock()), admission))):
                async with self.engine.begin() as conn:
                    existing = await self.operations.claim(conn, op, state="accepted", request=request,
                                                           **self._claim_args(key, descriptor, ctx, digest))
                    if existing:
                        return await self._stored(existing)
                    progress = await self.tools[key].admit(conn, ctx, arguments)
                    if progress:
                        await self.operations.progress(conn, op, state="accepted", progress=progress)
        except DomainFailure as failure:
            return await self._record_failure(key, descriptor, ctx, op, digest, failure, state="accepted")
        except TimeoutError:
            raise Rejected("deadline_exceeded", "Исход приёма неизвестен: проверьте по ключу", recovery="lookup") from None
        self._spawn(key, Job(op, ctx, arguments, self))
        self.events.notify()
        return 202, result(op.operation_key, "accepted", progress=progress or None)

    def _spawn(self, key, job):
        task = asyncio.get_running_loop().create_task(self._run(key, job))
        self.jobs.add(task)
        self.actor_jobs[task] = (job.ctx.installation_id, job.ctx.actor_id)
        def finished(done):
            self.jobs.discard(done)
            self.actor_jobs.pop(done, None)
        task.add_done_callback(finished)

    async def cancel_actor(self, installation, actor):
        tasks = {task for mapping in (self.actor_jobs, self.actor_requests)
                 for task, owner in tuple(mapping.items()) if owner == (installation, actor)
                 and task is not asyncio.current_task()}
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def _run(self, key, job, attempts=3):
        for attempt in range(attempts):
            try:
                async with self.engine.begin() as conn:
                    await self.operations.progress(conn, job.op, state="running")
                data, effects = await self.tools[key].run(job)
                descriptor = self.package.tool(*key)
                body = result(job.op.operation_key, "completed", data=self._output(key, descriptor, data),
                              receipts=[receipt(job.op.operation_key, effects, self.service_version, self.clock())])
                break
            except DomainFailure as failure:
                receipts = [receipt(job.op.operation_key, failure.effects, self.service_version, self.clock())] if failure.effects else []
                body = result(job.op.operation_key, "failed", error=failure.to_wire(), receipts=receipts)
                break
            except asyncio.CancelledError:
                raise
            except Exception as failure:
                if isinstance(failure, Rejected) and failure.code == "actor_erased":
                    return
                logging.exception("plugin_async_operation_failed tool=%s attempt=%s", key[0], attempt + 1)
                await asyncio.sleep(min(30, 2 ** attempt))
        else:  # outcome unknown: keep it running for reconciliation on the next start rather than claim a result
            try:
                async with self.engine.begin() as conn:
                    await self.operations.progress(conn, job.op, state="running", reason="reconciliation_pending")
            except Rejected as failure:
                if failure.code != "actor_erased":
                    raise
            return
        try:
            async with self.engine.begin() as conn:
                await self.operations.complete(conn, job.op, body)
        except Rejected as failure:
            if failure.code != "actor_erased":
                raise
            return
        self.events.notify()

    async def resume(self):
        """After a restart: continue async work and settle interrupted synchronous writes."""
        async with self.engine.connect() as conn:
            rows = await self.operations.open(conn, exclude_erased=self.erasure is not None)
            await conn.rollback()
        for row in rows:
            try:
                await self._resume(row)
            except Rejected as failure:
                # A fence can commit after the recovery query. It is permanent;
                # only erase-actor may touch this actor again, including retries.
                if failure.code != "actor_erased":
                    raise

    async def _resume(self, row):
        op = OperationId(row["installation_id"], row["actor_id"], row["operation_key"])
        if self.erasure is not None:
            await self.erasure.check(op.installation_id, op.actor_id)
        key = (row["tool_id"], row["tool_major"])
        tool = self.tools.get(key)
        request = row["request"] or {}
        if tool is not None and tool.run is not None and request:
            saved = request.get("context", {})
            ctx = Context(op.installation_id, op.actor_id, saved.get("space_id"), saved.get("locale"), saved.get("timezone"),
                          saved.get("trace_id") or "resume", datetime.max.replace(tzinfo=self.clock().tzinfo), "resume",
                          op.operation_key, frozenset(saved.get("scopes", ())))
            self._spawn(key, Job(op, ctx, request.get("arguments", {}), self))
            return
        recover = (self.configuration.recover if row["tool_id"] == CONFIGURATION_TOOL and self.configuration else
                   tool.recover if tool else None)
        body = await recover(row) if recover else None
        if body is None:
            body = result(op.operation_key, "failed", error=PluginError(
                "interrupted", "Операция прервана до фиксации; изменений нет", recovery="refresh_state").to_wire())
        async with self.engine.begin() as conn:
            await self.operations.complete(conn, op, body)

    async def shutdown(self):
        for task in tuple(self.jobs):
            task.cancel()
        await asyncio.gather(*self.jobs, return_exceptions=True)

    # -- lookup ------------------------------------------------------------------------------------------------

    async def lookup(self, delegation, request):
        return await self._actor_request(delegation, self._lookup, request)

    async def _lookup(self, delegation, request):
        check(LOOKUP_REQUEST, request)
        if request["installation_id"] != delegation.installation_id or delegation.operation_key not in (None, request["operation_key"]):
            raise Rejected("unauthenticated", "Делегация не соответствует запросу")
        op = OperationId(delegation.installation_id, delegation.actor_id, request["operation_key"])
        async with self.engine.connect() as conn:
            row = await self.operations.row(conn, op)
            await conn.rollback()
        if row is None:
            return lookup_body(op.operation_key, "not_found")
        descriptor = self.package.tool(row["tool_id"], row["tool_major"])
        scopes = (self.package.configuration["scopes"]["read"] if row["tool_id"] == CONFIGURATION_TOOL and self.package.configuration
                  else descriptor["scopes"] if descriptor else None)
        if scopes is None or not set(scopes) <= delegation.scopes:
            return lookup_body(op.operation_key, "not_found")  # knowing a key never reveals a result
        return lookup_body(op.operation_key, "found", result=self.operations.view(row))

    # -- configuration -----------------------------------------------------------------------------------------

    def _configuration_scopes(self, delegation, access):
        if self.configuration is None:
            raise Rejected("not_found", "У плагина нет настроек")
        if not set(self.package.configuration["scopes"][access]) <= delegation.scopes:
            raise Rejected("forbidden", "Недостаточно прав")

    async def configuration_view(self, delegation):
        return await self._actor_request(delegation, self._configuration_view)

    async def _configuration_view(self, delegation):
        self._configuration_scopes(delegation, "read")
        ctx = Context(delegation.installation_id, delegation.actor_id, delegation.space_id, None, None,
                      delegation.trace_id or delegation.request_id, datetime.max.replace(tzinfo=self.clock().tzinfo),
                      delegation.request_id, None, delegation.scopes)
        return await self.configuration.view(ctx)

    async def configuration_change(self, delegation, request, action):
        return await self._actor_request(delegation, self._configuration_change, request, action)

    async def _configuration_change(self, delegation, request, action):
        self._configuration_scopes(delegation, "write")
        check(CONFIGURATION_REQUEST, request)
        ctx = self._context(delegation, request)
        if action == "put":
            problems = field_errors(self.config_validators["public_schema"], request.get("public", {}))
            if "secret" in request:
                problems += [{**e, "path": "/secret" + e["path"]} for e in field_errors(self.config_validators["secret_schema"], request["secret"])]
            if problems:
                raise Rejected("invalid_arguments", "Настройки не соответствуют схеме", field_errors=problems)
        op = OperationId(ctx.installation_id, ctx.actor_id, ctx.operation_key)
        digest = fingerprint({"action": action, **self.configuration.fingerprint(action, ctx, request)})
        claim = dict(tool_id=CONFIGURATION_TOOL, tool_major=1, tool_version="1.0.0", space_id=ctx.space_id, fingerprint=digest)
        slot = self._admit(ctx)
        try:
            async with self.engine.begin() as conn:
                existing = await self.operations.claim(conn, op, state="running", reason="validating", **claim)
            if existing:
                return await self._stored(existing)
            committing, body = False, None
            try:
                async with asyncio.timeout(max(0.001, ctx.remaining(self.clock()))):
                    commit = await self.configuration.prepare(action, ctx, request)  # external checks only, no effects
                    committing = True
                    async with self.engine.begin() as conn:
                        await self.operations.ensure_actor(conn, ctx.installation_id, ctx.actor_id)
                        data, effects = await commit(conn)
                        body = result(op.operation_key, "completed", data=json.loads(to_json(data)),
                                      receipts=[receipt(op.operation_key, effects, self.service_version, self.clock())])
                        await self.operations.complete(conn, op, body)
            except TimeoutError:
                if committing:  # the commit may or may not have reached the database
                    raise Rejected("deadline_exceeded", "Исход неизвестен: проверьте по ключу", recovery="lookup") from None
                body = result(op.operation_key, "failed", error=PluginError(
                    "deadline_exceeded", "Проверка не уложилась в срок; изменений нет", recovery="none").to_wire())
                async with self.engine.begin() as conn:
                    await self.operations.complete(conn, op, body)
            except PluginError as failure:
                body = result(op.operation_key, "failed", error=failure.to_wire())
                async with self.engine.begin() as conn:
                    await self.operations.complete(conn, op, body)
            finally:
                if committing and body is not None and body["state"] == "completed":
                    hook = getattr(self.configuration, "committed", None)
                else:
                    hook = getattr(self.configuration, "abandoned", None)
                if hook is not None:
                    await hook(ctx)
            self.events.notify()
            return 200, body
        finally:
            self._release(slot)

    # -- health ------------------------------------------------------------------------------------------------

    async def health(self):
        checks, capabilities = ({}, {}) if self.checks is None else await self.checks()
        states = [value.get("status") for value in checks.values()]
        status = "unavailable" if "failed" in states else "degraded" if "degraded" in states or any(
            value.get("status") != "ready" for value in capabilities.values()) else "ready"
        return {"contract": "assistant.health/v1", "status": status,
                "package": {"id": self.package.id, "version": self.package.version, "digest": self.package.digest},
                "service_version": self.service_version, "checks": checks, "capabilities": capabilities,
                "checked_at": rfc3339(self.clock())}


def effects_of(*items):
    return [effect(*item) for item in items]
