"""Contract errors and their HTTP mapping (spec §5.1, §6.1)."""

RECOVERY = frozenset({"fix_arguments", "reauthenticate", "refresh_state", "lookup", "none"})

# Base codes plus the binding's own envelope failures; domain codes come from descriptors.
STATUS = {
    "malformed_envelope": 400,
    "unsupported_version": 400,
    "unauthenticated": 401,
    "forbidden": 403,
    "not_found": 404,
    "idempotency_conflict": 409,
    "revision_conflict": 409,
    "invalid_arguments": 422,
    "quota_exceeded": 429,
    "internal_error": 500,
    "dependency_unavailable": 503,
    "deadline_exceeded": 504,
}

DEFAULT_RECOVERY = {
    "malformed_envelope": "none",
    "unsupported_version": "refresh_state",
    "unauthenticated": "reauthenticate",
    "forbidden": "none",
    "not_found": "refresh_state",
    "idempotency_conflict": "none",
    "revision_conflict": "refresh_state",
    "invalid_arguments": "fix_arguments",
    "quota_exceeded": "none",
    "internal_error": "none",
    "dependency_unavailable": "none",
    "deadline_exceeded": "lookup",
}


class PluginError(Exception):
    """An error with a stable code and a message safe to show or log."""

    def __init__(self, code, message=None, *, recovery=None, field_errors=(), retry_after_ms=None, status=None):
        if not isinstance(code, str) or not code or len(code) > 64:
            raise ValueError("invalid error code")
        recovery = recovery or DEFAULT_RECOVERY.get(code, "none")
        if recovery not in RECOVERY:
            raise ValueError("invalid recovery")
        super().__init__(message or code)
        self.code, self.message, self.recovery = code, message or code, recovery
        self.field_errors = [dict(item) for item in field_errors]
        self.retry_after_ms = retry_after_ms
        self.status = status or STATUS.get(code, 400)

    def to_wire(self):
        error = {"code": self.code, "message": self.message, "recovery": self.recovery}
        if self.field_errors:
            error["field_errors"] = self.field_errors
        if self.retry_after_ms is not None:
            error["retry_after_ms"] = int(self.retry_after_ms)
        return error


class Rejected(PluginError):
    """Refused before admission: nothing was registered and no effect happened."""


class DomainFailure(PluginError):
    """An admitted operation that ended in `failed`; any confirmed effects are still reported."""

    def __init__(self, code, message=None, *, effects=(), **kwargs):
        super().__init__(code, message, **kwargs)
        self.effects = list(effects)


def from_wire(error, status=None):
    if not isinstance(error, dict) or not isinstance(error.get("code"), str):
        return PluginError("internal_error", "malformed error from peer", status=status)
    recovery = error.get("recovery") if error.get("recovery") in RECOVERY else None
    fields = [item for item in error.get("field_errors") or () if isinstance(item, dict)][:50]
    retry = error.get("retry_after_ms")
    return PluginError(
        error["code"][:64], str(error.get("message") or error["code"])[:500], recovery=recovery,
        field_errors=[{"path": str(item.get("path", ""))[:200], "code": str(item.get("code", ""))[:64]} for item in fields],
        retry_after_ms=retry if isinstance(retry, int) and not isinstance(retry, bool) and retry >= 0 else None,
        status=status,
    )
