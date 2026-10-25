"""Wire envelopes of `assistant.plugin/v1` (spec §5, §6.1, §9) and strict parsers for them."""

from uuid import uuid4

from .canonical import rfc3339
from .errors import Rejected
from .schemas import field_errors, validator

ID = {"type": "string", "minLength": 1, "maxLength": 200, "pattern": r"^[\x21-\x7e]+$"}
KEY = {"type": "string", "minLength": 1, "maxLength": 160, "pattern": r"^[A-Za-z0-9._:~-]+$"}
INSTANT = {"type": "string", "format": "date-time", "maxLength": 40}
VERSION = {"type": "string", "pattern": r"^[0-9]+(?:\.[0-9]+){0,2}$", "maxLength": 20}
STATES = ("accepted", "running", "completed", "failed", "cancelled")
TERMINAL = frozenset({"completed", "failed", "cancelled"})

CONTEXT = {
    "type": "object", "additionalProperties": False,
    "required": ["actor_id", "trace_id", "deadline_at"],
    "properties": {
        "actor_id": ID, "space_id": {"anyOf": [ID, {"type": "null"}]},
        "locale": {"type": "string", "maxLength": 35}, "timezone": {"type": "string", "maxLength": 64},
        "trace_id": ID, "deadline_at": INSTANT,
    },
}

ERROR = {
    "type": "object", "required": ["code", "message", "recovery"],
    "properties": {
        "code": {"type": "string", "maxLength": 64}, "message": {"type": "string", "maxLength": 500},
        "recovery": {"enum": ["fix_arguments", "reauthenticate", "refresh_state", "lookup", "none"]},
        "field_errors": {"type": "array", "maxItems": 50, "items": {
            "type": "object", "required": ["path", "code"],
            "properties": {"path": {"type": "string", "maxLength": 200}, "code": {"type": "string", "maxLength": 64}}}},
        "retry_after_ms": {"type": "integer", "minimum": 0},
    },
}

EFFECT = {
    "type": "object", "required": ["resource_type", "resource_id", "change"],
    "properties": {
        "resource_type": {"type": "string", "maxLength": 100}, "resource_id": ID,
        "change": {"enum": ["created", "updated", "deleted", "external"]},
        "revision": {"type": ["integer", "null"], "minimum": 0}, "undo": {"type": "object"},
    },
}

RECEIPT = {
    "type": "object", "required": ["receipt_id", "operation_key", "committed_at", "service_version", "effects"],
    "properties": {
        "receipt_id": ID, "operation_key": KEY, "committed_at": INSTANT, "service_version": VERSION,
        "effects": {"type": "array", "maxItems": 200, "items": EFFECT},
    },
}

INVOCATION = {
    "type": "object", "additionalProperties": False,
    "required": ["contract", "request_id", "installation_id", "tool_id", "tool_version", "operation_key", "context", "arguments"],
    "properties": {
        "contract": {"const": "assistant.invocation/v1"}, "request_id": ID, "installation_id": ID,
        "tool_id": {"type": "string", "pattern": r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*$", "maxLength": 100},
        "tool_version": VERSION, "operation_key": KEY, "context": CONTEXT, "arguments": {"type": "object"},
    },
}

RESULT = {
    "type": "object",
    "required": ["contract", "operation_key", "state", "receipts", "effects"],
    "properties": {
        "contract": {"const": "assistant.result/v1"}, "operation_key": KEY, "state": {"enum": list(STATES)},
        "data": {}, "receipts": {"type": "array", "items": RECEIPT}, "effects": {"type": "array", "items": EFFECT},
        "error": ERROR, "progress": {"type": "object"}, "retry_after_ms": {"type": "integer", "minimum": 0},
        "metrics": {"type": "object"},
    },
    "allOf": [
        {"if": {"properties": {"state": {"const": "completed"}}}, "then": {"required": ["data"]}},
        {"if": {"properties": {"state": {"const": "failed"}}}, "then": {"required": ["error"]}},
    ],
}

REJECTED = {
    "type": "object", "required": ["contract", "request_id", "error"],
    "properties": {"contract": {"const": "assistant.rejected/v1"}, "request_id": {"type": ["string", "null"]}, "error": ERROR},
}

LOOKUP_REQUEST = {
    "type": "object", "additionalProperties": False, "required": ["installation_id", "operation_key"],
    "properties": {"installation_id": ID, "operation_key": KEY},
}

LOOKUP = {
    "type": "object", "required": ["contract", "operation_key", "status"],
    "properties": {
        "contract": {"const": "assistant.lookup/v1"}, "operation_key": KEY,
        "status": {"enum": ["found", "not_found", "unavailable"]}, "result": RESULT, "error": ERROR,
    },
    "allOf": [
        {"if": {"properties": {"status": {"const": "found"}}}, "then": {"required": ["result"]}},
        {"if": {"properties": {"status": {"const": "unavailable"}}}, "then": {"required": ["error"]}},
    ],
}

EVENT = {
    "type": "object", "required": ["event_id", "installation_id", "type", "occurred_at", "cursor", "payload"],
    "properties": {
        "event_id": ID, "installation_id": ID, "type": {"type": "string", "maxLength": 100}, "occurred_at": INSTANT,
        "cursor": {"type": "string", "maxLength": 256, "pattern": r"^[A-Za-z0-9._:~-]+$"}, "payload": {"type": "object"},
        "operation_key": KEY, "resource_id": ID, "revision": {"type": "integer", "minimum": 0},
    },
}

HEALTH = {
    "type": "object", "required": ["contract", "status", "package", "service_version", "checks", "capabilities", "checked_at"],
    "properties": {
        "contract": {"const": "assistant.health/v1"}, "status": {"enum": ["ready", "degraded", "unavailable"]},
        "package": {"type": "object", "required": ["id", "version", "digest"]}, "service_version": VERSION,
        "checks": {"type": "object"}, "capabilities": {"type": "object"}, "checked_at": INSTANT,
    },
}

MANIFEST_RESPONSE = {
    "type": "object", "required": ["contract", "package_digest", "manifest", "files", "service_version"],
    "properties": {
        "contract": {"const": "assistant.package-manifest/v1"}, "package_digest": {"type": "string", "pattern": r"^sha256:[0-9a-f]{64}$"},
        "manifest": {"type": "object"}, "service_version": VERSION,
        "files": {"type": "array", "items": {"type": "object", "required": ["path", "sha256"]}},
    },
}

CONFIGURATION_REQUEST = {
    "type": "object", "additionalProperties": False,
    "required": ["contract", "request_id", "installation_id", "operation_key", "context"],
    "properties": {
        "contract": {"const": "assistant.configuration-request/v1"}, "request_id": ID, "installation_id": ID,
        "operation_key": KEY, "context": CONTEXT, "expected_revision": {"type": ["integer", "null"], "minimum": 0},
        "public": {"type": "object"}, "secret": {"type": "object"}, "options": {"type": "object"},
    },
}

_COMPILED = {}


def check(schema, value, code="malformed_envelope", message="Неверный формат сообщения"):
    compiled = _COMPILED.get(id(schema)) or _COMPILED.setdefault(id(schema), validator(schema))
    errors = field_errors(compiled, value)
    if errors:
        raise Rejected(code, message, field_errors=errors)
    return value


def major(version):
    return int(str(version).split(".", 1)[0])


def result(operation_key, state, *, data=None, receipts=(), error=None, progress=None, retry_after_ms=None, metrics=None, include_data=None):
    if state not in STATES:
        raise ValueError("invalid state")
    body = {"contract": "assistant.result/v1", "operation_key": operation_key, "state": state,
            "receipts": list(receipts), "effects": [effect for receipt in receipts for effect in receipt["effects"]]}
    if state == "completed" or include_data:
        body["data"] = data
    if error is not None:
        body["error"] = error
    if progress is not None:
        body["progress"] = progress
    if retry_after_ms is not None:
        body["retry_after_ms"] = int(retry_after_ms)
    if metrics:
        body["metrics"] = metrics
    return body


def receipt(operation_key, effects, service_version, committed_at, receipt_id=None):
    return {"receipt_id": str(receipt_id or uuid4()), "operation_key": operation_key,
            "committed_at": rfc3339(committed_at), "service_version": service_version, "effects": list(effects)}


def effect(resource_type, resource_id, change, revision=None, undo=None):
    body = {"resource_type": resource_type, "resource_id": str(resource_id), "change": change}
    if revision is not None:
        body["revision"] = int(revision)
    if undo is not None:
        body["undo"] = undo
    return body


def rejected(request_id, error):
    return {"contract": "assistant.rejected/v1", "request_id": request_id, "error": error.to_wire() if hasattr(error, "to_wire") else error}


def lookup(operation_key, status, result=None, error=None):
    body = {"contract": "assistant.lookup/v1", "operation_key": operation_key, "status": status}
    if result is not None:
        body["result"] = result
    if error is not None:
        body["error"] = error.to_wire() if hasattr(error, "to_wire") else error
    return body
