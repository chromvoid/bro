"""JSON Schema 2020-12 validation that never fetches remote schemas or echoes values."""

import re
from datetime import date
from uuid import UUID

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry

from .canonical import parse_decimal, parse_rfc3339

FORMATS = FormatChecker(())


@FORMATS.checks("date-time", raises=ValueError)
def _instant(value):
    return not isinstance(value, str) or bool(parse_rfc3339(value))


@FORMATS.checks("date", raises=ValueError)
def _date(value):
    if isinstance(value, str) and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise ValueError("invalid date")
    return not isinstance(value, str) or bool(date.fromisoformat(value))


@FORMATS.checks("uuid", raises=ValueError)
def _uuid(value):
    if isinstance(value, str) and not re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", value):
        raise ValueError("invalid uuid")
    return not isinstance(value, str) or bool(UUID(value))


@FORMATS.checks("decimal", raises=ValueError)
def _decimal(value):
    if isinstance(value, str) and not re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", value):
        raise ValueError("invalid decimal")
    return not isinstance(value, str) or parse_decimal(value) is not None


EMPTY = Registry()


def validator(schema):
    """A compiled validator; `$ref` resolves only inside the schema (no retrieval)."""
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FORMATS, registry=EMPTY)


def pointer(path):
    return "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in path) if path else ""


def field_errors(compiled, instance, limit=20):
    """Location and failing keyword only; instance values never appear in the result."""
    result = []
    for error in sorted(compiled.iter_errors(instance), key=lambda e: (list(map(str, e.absolute_path)), e.validator)):
        path = list(error.absolute_path)
        if error.validator == "required" and isinstance(error.validator_value, list):
            missing = [name for name in error.validator_value if isinstance(error.instance, dict) and name not in error.instance]
            result.extend({"path": pointer(path + [name]), "code": "required"} for name in missing)
        elif error.validator == "additionalProperties" and isinstance(error.instance, dict):
            known = set((error.schema.get("properties") or {}))
            patterns = [re.compile(p) for p in (error.schema.get("patternProperties") or {})]
            extra = [k for k in error.instance if k not in known and not any(p.search(k) for p in patterns)]
            result.extend({"path": pointer(path + [name]), "code": "unexpected"} for name in extra)
        else:
            result.append({"path": pointer(path), "code": str(error.validator)})
        if len(result) >= limit:
            break
    return result[:limit]


def closed_objects(schema, where="", problems=None):
    """Every object schema in an input schema must forbid unknown fields (spec §4.2)."""
    problems = [] if problems is None else problems
    if isinstance(schema, dict):
        declares_object = schema.get("type") == "object" or (isinstance(schema.get("type"), list) and "object" in schema["type"]) or "properties" in schema
        if declares_object and schema.get("additionalProperties") is not False and "unevaluatedProperties" not in schema:
            problems.append(where or "/")
        for key, value in schema.items():
            if key in ("properties", "$defs", "patternProperties", "dependentSchemas"):
                for name, child in (value or {}).items():
                    closed_objects(child, f"{where}/{key}/{name}", problems)
            elif key in ("items", "contains", "not", "if", "then", "else", "additionalProperties", "propertyNames"):
                closed_objects(value, f"{where}/{key}", problems)
            elif key in ("prefixItems", "allOf", "anyOf", "oneOf") and isinstance(value, list):
                for index, child in enumerate(value):
                    closed_objects(child, f"{where}/{key}/{index}", problems)
    return problems
