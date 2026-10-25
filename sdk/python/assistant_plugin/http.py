"""The `assistant.http-json/v1` binding for a Runtime, plus the configuration extension."""

import logging

from aiohttp import web

from .canonical import strict_loads, to_json, unb64url
from .envelopes import rejected
from .errors import PluginError, Rejected
from .events import EventStream, parse_cursor

MAX_BODY = 1024 * 1024
RUNTIME = web.AppKey("plugin_runtime", object)
STREAM = web.AppKey("plugin_event_stream", EventStream)


def respond(status, body):
    return web.Response(status=status, body=to_json(body).encode(), content_type="application/json",
                        headers={"Cache-Control": "no-store"})


def failure(error, request_id=None):
    return respond(error.status, rejected(request_id, error))


def _verifier(runtime, authorization):
    """The kid is `<installation_id>/<key id>`; each installation has its own verifier."""
    try:
        header = strict_loads(unb64url(authorization[7:].split(".", 1)[0]), max_bytes=1024)
        installation = str(header.get("kid", "")).split("/", 1)[0]
    except (ValueError, AttributeError, IndexError, TypeError):
        raise Rejected("unauthenticated", "Недействительная делегация") from None
    verifier = runtime.verifiers.get(installation)
    if verifier is None:
        raise Rejected("unauthenticated", "Недействительная делегация")
    return verifier


def authenticate(runtime, request, purpose, body=None, actor_required=True):
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer "):
        raise Rejected("unauthenticated", "Нужна делегация платформы")
    return _verifier(runtime, authorization).verify(authorization, purpose=purpose, body=body, actor_required=actor_required)


async def _body(request):
    raw = await request.read()
    try:
        return raw, strict_loads(raw, max_bytes=MAX_BODY)
    except (ValueError, UnicodeError):
        raise Rejected("malformed_envelope", "Тело запроса не является корректным JSON") from None


def _guard(handler):
    async def wrapped(request):
        try:
            return await handler(request)
        except PluginError as error:
            if not isinstance(error, Rejected):
                error = Rejected(error.code, error.message, recovery=error.recovery, status=error.status)
            return failure(error)
        except web.HTTPRequestEntityTooLarge:
            return failure(Rejected("malformed_envelope", "Сообщение слишком большое", status=413))
        except web.HTTPException:
            raise
        except Exception:
            logging.exception("plugin_binding_failed route=%s", request.path)
            return failure(Rejected("internal_error", "Внутренняя ошибка сервиса"))
    return wrapped


def create_app(runtime, *, stream=None):
    app = web.Application(client_max_size=MAX_BODY)
    app[RUNTIME] = runtime
    app[STREAM] = stream or EventStream(runtime.events, runtime.engine)

    @_guard
    async def manifest(request):
        package = runtime.package
        return respond(200, {"contract": "assistant.package-manifest/v1", "package_digest": package.digest,
                             "manifest": package.manifest, "files": package.listing(), "service_version": runtime.service_version})

    @_guard
    async def health(request):
        body = await runtime.health()
        return respond(503 if body["status"] == "unavailable" else 200, body)

    @_guard
    async def invoke(request):
        raw, envelope = await _body(request)
        delegation = authenticate(runtime, request, "invoke", raw)
        status, body = await runtime.invoke(delegation, envelope)
        return respond(status, body)

    @_guard
    async def lookup(request):
        raw, body = await _body(request)
        delegation = authenticate(runtime, request, "lookup", raw)
        return respond(200, await runtime.lookup(delegation, body))

    @_guard
    async def events(request):
        delegation = authenticate(runtime, request, "events")
        start = parse_cursor(request.query.get("cursor") or request.headers.get("Last-Event-ID"))
        async def stream_actor(actor):
            return await app[STREAM].serve(request, actor.installation_id, actor.actor_id, start)
        return await runtime._actor_request(delegation, stream_actor, check_after=False)

    @_guard
    async def configuration_get(request):
        delegation = authenticate(runtime, request, "configuration.get")
        return respond(200, await runtime.configuration_view(delegation))

    @_guard
    async def erase_actor(request):
        raw, body = await _body(request)
        delegation = authenticate(runtime, request, 'privacy.erase', raw)
        if runtime.erasure is None:
            raise Rejected('not_supported', 'Сервис не поддерживает удаление аккаунта', status=501)
        return respond(200, await runtime.erasure.erase(delegation, body))

    def change(action):
        @_guard
        async def handler(request):
            raw, body = await _body(request)
            delegation = authenticate(runtime, request, f"configuration.{action}", raw)
            status, result = await runtime.configuration_change(delegation, body, action)
            return respond(status, result)
        return handler

    async def lifecycle(app):
        await runtime.resume()
        yield
        await app[STREAM].shutdown()
        await runtime.shutdown()

    app.router.add_get("/manifest", manifest, allow_head=False)
    app.router.add_get("/health", health, allow_head=False)
    app.router.add_post("/invoke", invoke)
    app.router.add_post("/operations/lookup", lookup)
    app.router.add_get("/events", events, allow_head=False)
    app.router.add_get("/configuration", configuration_get, allow_head=False)
    app.router.add_put("/configuration", change("put"))
    app.router.add_delete("/configuration", change("delete"))
    app.router.add_post('/privacy/erase-actor', erase_actor)
    app.cleanup_ctx.append(lifecycle)
    return app
