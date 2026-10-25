"""Redaction for logs and traces: the same secret rules as `server/telemetry/privacy.py`, held equal by its tests."""

import logging
import re

SECRET_KEY = re.compile(r'authorization|cookie|password|secret|capability|attestation|assertion|ciphertext|apns[_-]?token|receipt[_-]?token|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|dashboard[_-]?key|device[_-]?key|x-bapi|^sign$|signature', re.I)
PATTERNS = (
    re.compile(r'(?i)\bBearer\s+[^\s"\',}]+'),
    re.compile(r'\b(?:sk-[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)'),
    re.compile(r'(?i)(?:postgres(?:ql)?|https?)://[^\s/@]+:[^\s/@]+@[^\s"\']+'),
    re.compile(r'(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|receipt[_-]?token|(?:send|management)[_-]?capability|password|secret|x-bapi-[a-z-]+|\bsign)["\']?\s*[:=]\s*["\']?[^\s,"\'}]+'),
)


def sanitize(value, depth=0):
    if depth > 12:
        return '[depth limit]'
    if isinstance(value, dict):
        if value.get('op') == 'auth':  # exchange websocket login frames carry a key and signature in `args`
            value = {**value, 'args': '[redacted]'}
        return {str(k): '[redacted]' if SECRET_KEY.search(str(k)) else sanitize(v, depth + 1)
                for k, v in list(value.items())[:100]}
    if isinstance(value, (list, tuple)):
        return [sanitize(v, depth + 1) for v in value[:100]]
    if isinstance(value, str):
        for pattern in PATTERNS:
            value = pattern.sub('[redacted]', value)
        return value[:4000] + ('… [truncated]' if len(value) > 4000 else '')
    if value is None or isinstance(value, (int, float, bool)):
        return value
    return str(value)[:200]


class RedactingFilter(logging.Filter):
    """Rewrites every record's message and arguments through `sanitize`."""

    def filter(self, record):
        try:
            message = record.getMessage()
        except Exception:
            message = str(record.msg)
        record.msg, record.args = sanitize(message), ()
        return True
