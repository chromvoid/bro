"""Platform side of `assistant.http-json/v1`: signed calls with bounded, strictly parsed responses.

Transport failures are reported with whether the request could have reached the
service: `NotSent` (connection never established) is safe to report as not
dispatched; `Uncertain` means a write's outcome must be recovered by lookup.
"""

import json
import ssl
from uuid import uuid4

import aiohttp
from yarl import URL

from .canonical import strict_loads
from .envelopes import EVENT, HEALTH, LOOKUP, MANIFEST_RESPONSE, REJECTED, RESULT, check
from .errors import PluginError, Rejected, from_wire

MAX_RESPONSE = 1024 * 1024
MAX_EVENT = 64 * 1024


class TransportError(Exception):
    pass


class NotSent(TransportError):
    """The connection was never established; the service cannot have seen the request."""


class Uncertain(TransportError):
    """The request may have reached the service; its outcome is unknown."""


def base_url(value):
    url = URL(value)
    if url.scheme not in ("https", "http") or not url.host or url.user or url.password or url.query_string or url.fragment:
        raise ValueError("binding base URL must be a plain http(s) origin and path")
    if url.scheme == "http" and url.host not in ("127.0.0.1", "::1", "localhost"):
        raise ValueError("plain HTTP is allowed only on loopback")
    return url


def ssl_context(ca_file):
    context = ssl.create_default_context(cafile=str(ca_file))
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


class BindingClient:
    def __init__(self, url, signer, *, ca_file=None, connect_timeout=2.0, max_response=MAX_RESPONSE, session=None):
        self.url, self.signer, self.max_response = base_url(url), signer, max_response
        self.ssl = ssl_context(ca_file) if ca_file else (None if self.url.scheme == "https" else False)
        self.connect_timeout = connect_timeout
        self._session, self._owned = session, session is None

    def __repr__(self):
        return f"BindingClient({str(self.url)!r})"

    async def session(self):
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                trust_env=False, cookie_jar=aiohttp.DummyCookieJar(), auto_decompress=True,
                connector=aiohttp.TCPConnector(limit=32, ssl=self.ssl))
        return self._session

    async def close(self):
        if self._owned and self._session is not None:
            await self._session.close()

    def _headers(self, purpose, request_id, body=None, **claims):
        headers = {"Authorization": "Bearer " + self.signer.token(purpose, request_id, body=body, **claims),
                   "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        return headers

    async def _read(self, response):
        chunks, size = [], 0
        async for chunk in response.content.iter_chunked(16384):
            size += len(chunk)
            if size > self.max_response:
                raise Uncertain("response too large")
            chunks.append(chunk)
        try:
            return strict_loads(b"".join(chunks), max_bytes=self.max_response)
        except (ValueError, UnicodeError):
            raise Uncertain("malformed response") from None

    async def request(self, method, path, purpose, *, body=None, timeout=25.0, request_id=None, **claims):
        """Returns (status, parsed JSON). Pre-admission rejections are raised as `Rejected`."""
        request_id = request_id or str(uuid4())
        raw = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode() if body is not None else None
        headers = self._headers(purpose, request_id, raw, **claims)
        session = await self.session()
        timing = aiohttp.ClientTimeout(total=timeout, sock_connect=self.connect_timeout)
        try:
            async with session.request(method, self.url / path.lstrip("/"), data=raw, headers=headers, timeout=timing,
                                       allow_redirects=False) as response:
                parsed = await self._read(response)
                status = response.status
        except (aiohttp.ClientConnectorError, aiohttp.ClientSSLError) as error:
            raise NotSent(type(error).__name__) from None
        except (aiohttp.ClientError, TimeoutError) as error:
            raise Uncertain(type(error).__name__) from None
        if status >= 400:
            if isinstance(parsed, dict) and parsed.get("contract") == "assistant.rejected/v1":
                try:
                    check(REJECTED, parsed)
                except Rejected:
                    raise Uncertain("malformed rejection") from None
                error = from_wire(parsed["error"], status)
                raise Rejected(error.code, error.message, recovery=error.recovery, field_errors=error.field_errors,
                               retry_after_ms=error.retry_after_ms, status=status)
            raise (Uncertain if status >= 500 else PluginError)(f"unexpected status {status}")
        return status, parsed

    async def manifest(self):
        _, body = await self.request("GET", "/manifest", "health", timeout=5.0)
        return check(MANIFEST_RESPONSE, body)

    async def health(self):
        try:
            _, body = await self.request("GET", "/health", "health", timeout=5.0)
        except Rejected as error:
            raise Uncertain(error.code) from None
        return check(HEALTH, body)

    async def invoke(self, envelope, *, scopes, timeout):
        status, body = await self.request(
            "POST", "/invoke", "invoke", body=envelope, timeout=timeout, request_id=envelope["request_id"],
            actor_id=envelope["context"]["actor_id"], scopes=scopes, space_id=envelope["context"].get("space_id"),
            operation_key=envelope["operation_key"], trace_id=envelope["context"]["trace_id"])
        try:
            check(RESULT, body)
        except Rejected:
            raise Uncertain("malformed result") from None
        if body["operation_key"] != envelope["operation_key"] or (status == 202) != (body["state"] in ("accepted", "running")):
            raise Uncertain("result does not match the request")
        return status, body

    async def lookup(self, installation_id, actor_id, operation_key, *, scopes, timeout=5.0):
        _, body = await self.request("POST", "/operations/lookup", "lookup", timeout=timeout,
                                     body={"installation_id": installation_id, "operation_key": operation_key},
                                     actor_id=actor_id, scopes=scopes, operation_key=operation_key)
        check(LOOKUP, body)
        if body["operation_key"] != operation_key:
            raise Uncertain("lookup does not match the request")
        return body

    async def configuration(self, method, *, actor_id, scopes, body=None, timeout=25.0, space_id=None, trace_id=None):
        purpose = {"GET": "configuration.get", "PUT": "configuration.put", "DELETE": "configuration.delete"}[method]
        return await self.request(method, "/configuration", purpose, body=body, timeout=timeout,
                                  request_id=(body or {}).get("request_id"), actor_id=actor_id, scopes=scopes,
                                  space_id=space_id, operation_key=(body or {}).get("operation_key"), trace_id=trace_id)

    async def erase_actor(self, actor_id, deletion_id, *, timeout=25.0):
        _, body = await self.request('POST', '/privacy/erase-actor', 'privacy.erase', timeout=timeout,
            body={'contract': 'assistant.actor-erasure-request/v1', 'deletion_id': deletion_id},
            actor_id=actor_id, scopes=['privacy.erase'])
        if not isinstance(body, dict) or body.get('contract') != 'assistant.actor-erasure/v1' or \
                body.get('status') != 'completed' or body.get('deletion_id') != deletion_id or \
                not isinstance(body.get('receipt_id'), str) or not isinstance(body.get('completed_at'), str):
            raise Uncertain('malformed actor erasure receipt')
        return {key: body[key] for key in ('contract', 'deletion_id', 'status', 'receipt_id', 'completed_at')}

    async def events(self, *, actor_id, scopes, cursor=None, read_timeout=45.0):
        """Yields (event name, parsed data) from the service's SSE stream until it closes."""
        request_id = str(uuid4())
        headers = self._headers("events", request_id, actor_id=actor_id, scopes=scopes)
        headers["Accept"] = "text/event-stream"
        session = await self.session()
        url = self.url / "events"
        if cursor:
            url = url.with_query(cursor=cursor)
        timing = aiohttp.ClientTimeout(total=None, sock_connect=self.connect_timeout, sock_read=read_timeout)
        try:
            async with session.get(url, headers=headers, timeout=timing, allow_redirects=False) as response:
                if response.status != 200:
                    raise Uncertain(f"events status {response.status}")
                name, data = None, []
                while True:
                    line = await response.content.readline()
                    if not line:
                        return
                    if len(line) > MAX_EVENT:
                        raise Uncertain("event too large")
                    line = line.decode("utf-8").rstrip("\r\n")
                    if line.startswith(":") or line.startswith("id:") or line.startswith("retry:"):
                        continue
                    if line.startswith("event:"):
                        name = line[6:].strip()
                    elif line.startswith("data:"):
                        data.append(line[5:].lstrip())
                    elif line == "" and name:
                        payload = strict_loads("\n".join(data).encode(), max_bytes=MAX_EVENT)
                        if name not in ("stream.ready", "resync_required"):
                            check(EVENT, payload)
                        yield name, payload
                        name, data = None, []
        except aiohttp.ClientConnectorError as error:
            raise NotSent(type(error).__name__) from None
        except (aiohttp.ClientError, TimeoutError, ValueError, Rejected) as error:
            raise Uncertain(type(error).__name__) from None
