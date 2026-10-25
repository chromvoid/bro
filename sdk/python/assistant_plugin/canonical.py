"""Canonical JSON, exact decimals and RFC 3339 instants shared by both sides of a binding.

`wire` and `canonical` mirror `server/values.py` byte for byte, so a
fingerprint computed by the platform matches one computed by a service.
"""

import base64
import hashlib
import json
import re
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from uuid import UUID


def wire(value):
    if isinstance(value, (Decimal, UUID, date, datetime)):
        return (
            str(value) if not isinstance(value, (date, datetime)) else value.isoformat()
        )
    if isinstance(value, dict):
        return {k: wire(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [wire(v) for v in value]
    return value


def canonical(value):
    return json.dumps(
        wire(value),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def fingerprint(value):
    return "sha256:" + sha256_hex(canonical(value).encode())


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def unb64url(text):
    if not isinstance(text, str) or not re.fullmatch(r"[A-Za-z0-9_-]*", text) or len(text) % 4 == 1:
        raise ValueError("invalid base64url")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def body_hash(body):
    return b64url(hashlib.sha256(body).digest())


def parse_decimal(value):
    """An exact finite decimal from a string or int; floats are refused as lossy."""
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        raise ValueError("decimal must be a string")
    try:
        result = Decimal(value.strip() if isinstance(value, str) else value)
    except InvalidOperation:
        raise ValueError("invalid decimal") from None
    if not result.is_finite():
        raise ValueError("decimal must be finite")
    return result


def decimal_str(value):
    """Fixed-point text without exponent or trailing zeros; zero is always "0"."""
    value = parse_decimal(value) if not isinstance(value, Decimal) else value
    if not value.is_finite():
        raise ValueError("decimal must be finite")
    if value.is_zero():
        return "0"
    # normalize() rounds in the caller's decimal context; wire formatting must preserve every digit.
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def utc(value):
    if value.tzinfo is None:
        raise ValueError("instant must carry a timezone")
    return value.astimezone(timezone.utc)


def rfc3339(value):
    value = utc(value)
    return value.strftime("%Y-%m-%dT%H:%M:%S") + (f".{value.microsecond // 1000:03d}" if value.microsecond else "") + "Z"


INSTANT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})")


def parse_rfc3339(text):
    if not isinstance(text, str) or not INSTANT.fullmatch(text):
        raise ValueError("invalid RFC 3339 instant")
    head, _, rest = text.partition(".")
    if rest:  # Python keeps microseconds only; truncate longer fractions deterministically
        digits = re.match(r"\d+", rest).group()
        text = head + "." + digits[:6].ljust(6, "0") + rest[len(digits):]
    return utc(datetime.fromisoformat(text.replace("Z", "+00:00")))


def to_json(value):
    """Response JSON: decimals as exact text, instants in UTC, floats refused."""
    return json.dumps(_plain(value), separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def _plain(value):
    if isinstance(value, bool) or value is None or isinstance(value, (int, str)):
        return value
    if isinstance(value, float):
        raise TypeError("floats are not allowed on the wire")
    if isinstance(value, Decimal):
        return decimal_str(value)
    if isinstance(value, datetime):
        return rfc3339(value)
    if isinstance(value, (date, UUID)):
        return str(value) if isinstance(value, UUID) else value.isoformat()
    if isinstance(value, dict):
        return {str(k): _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    raise TypeError(f"unsupported wire type {type(value).__name__}")


def strict_loads(raw, *, max_bytes=1024 * 1024):
    """Parse JSON rejecting duplicate keys, NaN/Infinity and oversized input."""
    if len(raw) > max_bytes:
        raise ValueError("payload too large")

    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    def constant(name):
        raise ValueError("non-finite number")

    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)
