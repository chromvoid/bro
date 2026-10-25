"""Actor delegation credential: compact JWS HS256 bound to one request (spec §6.1).

The platform signs; the service verifies. A bare `actor_id` in the JSON body
is never trusted: the token's `sub` must equal it, `bdh` must equal the hash
of the exact body bytes, and `purpose` must match the route.
"""

import hashlib
import hmac
import json
import time
from collections import OrderedDict
from dataclasses import dataclass

from .canonical import b64url, body_hash, strict_loads, unb64url
from .errors import Rejected

PURPOSES = frozenset({"invoke", "lookup", "events", "health", "configuration.get", "configuration.put", "configuration.delete", "privacy.erase"})
MAX_TTL = 60
SKEW = 30


def audience(installation_id):
    return "assistant-plugin:" + installation_id


def sign(claims, key, kid):
    if not isinstance(key, bytes) or len(key) < 32:
        raise ValueError("delegation key must have at least 32 bytes")
    header = {"alg": "HS256", "typ": "JWT", "kid": kid}
    head = b64url(json.dumps(header, separators=(",", ":"), sort_keys=True).encode())
    payload = b64url(json.dumps(claims, separators=(",", ":"), sort_keys=True, ensure_ascii=False).encode())
    signature = hmac.new(key, f"{head}.{payload}".encode("ascii"), hashlib.sha256).digest()
    return f"{head}.{payload}.{b64url(signature)}"


def claims(*, issuer, installation_id, purpose, request_id, actor_id=None, scopes=(), space_id=None,
           operation_key=None, body=None, trace_id=None, now=None, ttl=MAX_TTL):
    if purpose not in PURPOSES or not 1 <= ttl <= MAX_TTL:
        raise ValueError("invalid delegation claims")
    issued = int(time.time() if now is None else now)
    return {"iss": issuer, "aud": audience(installation_id), "sub": actor_id, "iat": issued, "exp": issued + ttl,
            "jti": request_id, "purpose": purpose, "scp": sorted(set(scopes)), "spc": space_id,
            "opk": operation_key, "bdh": body_hash(body) if body is not None else None, "tid": trace_id}


@dataclass(frozen=True)
class Delegation:
    installation_id: str
    actor_id: str | None
    scopes: frozenset
    space_id: str | None
    operation_key: str | None
    purpose: str
    request_id: str
    trace_id: str | None
    expires_at: int


def _unauthenticated(message="Недействительная делегация"):
    return Rejected("unauthenticated", message)


class Verifier:
    """Checks tokens for one installation; `keys` maps kid to secret (rotation keeps two)."""

    def __init__(self, installation_id, issuer, keys, *, clock=time.time, cache_size=20000):
        if not keys or any(not isinstance(k, bytes) or len(k) < 32 for k in keys.values()):
            raise ValueError("delegation keys must have at least 32 bytes")
        self.installation_id, self.issuer, self.keys, self.clock = installation_id, issuer, dict(keys), clock
        self.seen, self.cache_size = OrderedDict(), cache_size

    def verify(self, authorization, *, purpose, body=None, actor_required=True):
        if not isinstance(authorization, str) or not authorization.startswith("Bearer ") or len(authorization) > 8192:
            raise _unauthenticated()
        token = authorization[7:].strip()
        parts = token.split(".")
        if len(parts) != 3:
            raise _unauthenticated()
        try:
            header = strict_loads(unb64url(parts[0]), max_bytes=1024)
            payload = strict_loads(unb64url(parts[1]), max_bytes=8192)
            signature = unb64url(parts[2])
        except (ValueError, UnicodeError):
            raise _unauthenticated() from None
        if not isinstance(header, dict) or set(header) - {"alg", "typ", "kid"} or header.get("alg") != "HS256":
            raise _unauthenticated()
        key = self.keys.get(header.get("kid"))
        expected = hmac.new(key or b"\0" * 32, f"{parts[0]}.{parts[1]}".encode("ascii"), hashlib.sha256).digest()
        if key is None or not hmac.compare_digest(expected, signature):
            raise _unauthenticated()
        if not isinstance(payload, dict):
            raise _unauthenticated()
        now = self.clock()
        iat, exp = payload.get("iat"), payload.get("exp")
        if not all(isinstance(v, int) and not isinstance(v, bool) for v in (iat, exp)) \
                or exp - iat > MAX_TTL or exp <= now or iat > now + SKEW or iat < now - MAX_TTL - SKEW:
            raise _unauthenticated("Делегация истекла")
        if payload.get("iss") != self.issuer or payload.get("aud") != audience(self.installation_id):
            raise _unauthenticated()
        if payload.get("purpose") != purpose:
            raise Rejected("forbidden", "Делегация выдана для другого действия")
        expected_hash = body_hash(body) if body is not None else None
        if payload.get("bdh") != expected_hash:
            raise _unauthenticated("Делегация не относится к этому запросу")
        actor = payload.get("sub")
        if actor_required and (not isinstance(actor, str) or not actor):
            raise _unauthenticated()
        scopes = payload.get("scp")
        if not isinstance(scopes, list) or not all(isinstance(s, str) for s in scopes):
            raise _unauthenticated()
        jti = payload.get("jti")
        if not isinstance(jti, str) or not 1 <= len(jti) <= 200:
            raise _unauthenticated()
        self._remember(jti, exp, now)
        return Delegation(self.installation_id, actor, frozenset(scopes), payload.get("spc"), payload.get("opk"),
                          purpose, jti, payload.get("tid"), exp)

    def _remember(self, jti, exp, now):
        while self.seen and next(iter(self.seen.values())) <= now:
            self.seen.popitem(last=False)
        if jti in self.seen:
            raise _unauthenticated("Повтор делегации")
        if len(self.seen) >= self.cache_size:
            raise Rejected("quota_exceeded", "Слишком много запросов", retry_after_ms=1000)
        self.seen[jti] = exp


class Signer:
    """Platform side: one installation, the current key and its id."""

    def __init__(self, installation_id, issuer, key, kid):
        if not isinstance(key, bytes) or len(key) < 32:
            raise ValueError("delegation key must have at least 32 bytes")
        self.installation_id, self.issuer, self._key, self.kid = installation_id, issuer, key, kid

    def __repr__(self):
        return f"Signer({self.installation_id!r}, kid={self.kid!r})"

    def token(self, purpose, request_id, **kwargs):
        return sign(claims(issuer=self.issuer, installation_id=self.installation_id, purpose=purpose,
                           request_id=request_id, **kwargs), self._key, self.kid)
