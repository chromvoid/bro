"""Plugin package loading, validation and the pinned digest (spec §3, §4).

Digest: every regular file under the package root except dotfiles, sorted by
POSIX path; lines `"<sha256>  <path>\\n"` as `sha256sum` prints them; the
package digest is `sha256:` + SHA-256 of those lines concatenated.
"""

import hashlib
import json
import os
import re
import stat
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

from . import BINDING, CONTRACT
from .schemas import closed_objects, field_errors, validator

MAX_FILE = 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024
MAX_FILES = 1000
TOOL_ID = r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*$"
SCOPE = r"^[a-z][a-z0-9_]*\.[a-z][a-z0-9_.]*$"


class PackageError(ValueError):
    pass


def _entries(required):
    return {"type": "array", "maxItems": 200, "items": {"type": "object", "required": required}}


MANIFEST = {
    "type": "object",
    "required": ["contract", "id", "version", "title", "description", "capabilities"],
    "properties": {
        "contract": {"const": CONTRACT},
        "id": {"type": "string", "pattern": r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*$", "maxLength": 100},
        "version": {"type": "string", "pattern": r"^[0-9]+\.[0-9]+\.[0-9]+$"},
        "title": {"type": "string", "minLength": 1, "maxLength": 100},
        "description": {"type": "string", "minLength": 1, "maxLength": 1000},
        "contexts": {"type": "array", "items": {"enum": ["personal", "shared"]}, "minItems": 1, "uniqueItems": True},
        "capabilities": {"type": "array", "minItems": 1, "maxItems": 50, "items": {
            "type": "object", "required": ["id", "description"],
            "properties": {"id": {"type": "string", "pattern": TOOL_ID}, "description": {"type": "string", "maxLength": 1000},
                           "boundaries": {"type": "array", "items": {"type": "string", "maxLength": 300}},
                           "examples": {"type": "array", "items": {"type": "string", "maxLength": 300}},
                           "tools": {"type": "array", "items": {"type": "string"}},
                           "skills": {"type": "array", "items": {"type": "string"}},
                           "ui": {"type": "array", "items": {"type": "string"}}}}},
        "bindings": {"type": "array", "items": {"type": "object", "required": ["id", "profile"]}},
        "tools": _entries(["id", "descriptor", "binding"]),
        "skills": _entries(["id", "path"]),
        "events": _entries(["type", "descriptor"]),
        "ui": _entries(["id", "kind", "title", "capability", "entry", "client_api", "scopes", "fallback"]),
        "configuration": {"type": "object", "required": ["schema", "binding"]},
        "requires": {"type": "array"},
    },
}

EXECUTION = {
    "type": "object", "required": ["mode", "max_duration_ms"],
    "properties": {"mode": {"enum": ["sync", "async"]}, "max_duration_ms": {"type": "integer", "minimum": 1},
                   "admission_timeout_ms": {"type": "integer", "minimum": 1},
                   "atomicity": {"enum": ["transactional", "best_effort"]}},
    "allOf": [{"if": {"properties": {"mode": {"const": "async"}}}, "then": {"required": ["admission_timeout_ms"]}}],
}

TOOL = {
    "type": "object",
    "required": ["id", "version", "description", "when_to_use", "when_not_to_use", "input_schema", "output_schema",
                 "effect", "scopes", "execution", "errors"],
    "properties": {
        "id": {"type": "string", "pattern": TOOL_ID, "maxLength": 100},
        "version": {"type": "string", "pattern": r"^[0-9]+\.[0-9]+\.[0-9]+$"},
        "description": {"type": "string", "minLength": 1, "maxLength": 2000},
        "when_to_use": {"type": "string", "maxLength": 2000}, "when_not_to_use": {"type": "string", "maxLength": 2000},
        "input_schema": {"type": "object", "properties": {"type": {"const": "object"}}, "required": ["type"]},
        "output_schema": {"type": "object"},
        "effect": {"enum": ["read", "write"]},
        "scopes": {"type": "array", "minItems": 1, "items": {"type": "string", "pattern": SCOPE}},
        "execution": EXECUTION,
        "errors": {"type": "array", "items": {"type": "object", "required": ["code", "meaning", "recovery"]}},
        "surfaces": {"type": "array", "minItems": 1, "uniqueItems": True, "items": {"enum": ["ui", "agent"]}},
        "confirmation": {"enum": ["policy", "always"]},
        "supports_cancel": {"type": "boolean"},
        "limits": {"type": "object", "properties": {"max_response_bytes": {"type": "integer", "minimum": 1}}},
        "examples": {"type": "array"},
    },
    "allOf": [{"if": {"properties": {"effect": {"const": "write"}}},
               "then": {"properties": {"execution": {"required": ["atomicity"]}}}}],
}

EVENT = {
    "type": "object", "required": ["type", "version", "payload_schema", "scopes", "ordering", "cursor_retention_days"],
    "properties": {"type": {"type": "string", "pattern": TOOL_ID}, "version": {"type": "string"},
                   "payload_schema": {"type": "object"}, "scopes": {"type": "array", "items": {"type": "string", "pattern": SCOPE}},
                   "ordering": {"enum": ["actor"]}, "cursor_retention_days": {"type": "integer", "minimum": 1},
                   "description": {"type": "string"}},
}

CONFIGURATION = {
    "type": "object", "required": ["contract", "public_schema", "secret_schema", "scopes"],
    "properties": {"contract": {"const": "assistant.configuration-schema/v1"},
                   "public_schema": {"type": "object"}, "secret_schema": {"type": "object"},
                   "scopes": {"type": "object", "required": ["read", "write"]}},
}


def _check(schema, value, where):
    errors = field_errors(validator(schema), value)
    if errors:
        raise PackageError(f"{where}: " + ", ".join(f"{e['path'] or '/'} {e['code']}" for e in errors[:5]))


def files(root):
    """(relative POSIX path, sha256 hex) of every packaged file; symlinks are refused."""
    root = Path(root)
    info = root.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise PackageError("package root must be a directory")
    found, total = [], 0
    for directory, dirs, names in os.walk(root, followlinks=False):
        for name in list(dirs):
            if name.startswith(".") or name == "__pycache__":
                dirs.remove(name)
            elif Path(directory, name).is_symlink():
                raise PackageError("symlinks are not allowed in a package")
        for name in names:
            if name.startswith("."):
                continue
            path = Path(directory, name)
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode):
                raise PackageError("only regular files are allowed in a package")
            if info.st_size > MAX_FILE:
                raise PackageError("package file too large")
            total += info.st_size
            relative = path.relative_to(root).as_posix()
            found.append((relative, hashlib.sha256(path.read_bytes()).hexdigest()))
    if total > MAX_TOTAL or len(found) > MAX_FILES:
        raise PackageError("package too large")
    return sorted(found, key=lambda item: item[0].encode())


def digest(entries):
    lines = "".join(f"{sha}  {path}\n" for path, sha in entries)
    return "sha256:" + hashlib.sha256(lines.encode()).hexdigest()


@dataclass(frozen=True)
class Package:
    root: Path
    manifest: dict
    files: tuple
    digest: str
    tools: dict = field(default_factory=dict)          # (tool_id, major) -> descriptor
    skills: dict = field(default_factory=dict)         # skill_id -> text
    events: dict = field(default_factory=dict)         # event type -> descriptor
    ui: tuple = ()
    configuration: dict | None = None

    @property
    def id(self):
        return self.manifest["id"]

    @property
    def version(self):
        return self.manifest["version"]

    def tool(self, tool_id, major):
        return self.tools.get((tool_id, int(major)))

    def scopes(self):
        found = {scope for descriptor in self.tools.values() for scope in descriptor["scopes"]}
        found |= {scope for descriptor in self.events.values() for scope in descriptor["scopes"]}
        if self.configuration:
            found |= set(self.configuration["scopes"]["read"]) | set(self.configuration["scopes"]["write"])
        return found

    def listing(self):
        return [{"path": path, "sha256": sha} for path, sha in self.files]


def _inside(root, relative):
    path = PurePosixPath(relative)
    if not relative or path.is_absolute() or ".." in path.parts or any(part.startswith(".") for part in path.parts):
        raise PackageError(f"invalid package path {relative!r}")
    return Path(root, *path.parts)


def _json(root, relative, known):
    if relative not in known:
        raise PackageError(f"missing package file {relative}")
    try:
        return json.loads(_inside(root, relative).read_text(encoding="utf-8"))
    except (UnicodeError, ValueError):
        raise PackageError(f"invalid JSON in {relative}") from None


def load(root):
    root = Path(root)
    entries = files(root)
    known = {path for path, _ in entries}
    manifest = _json(root, "manifest.json", known)
    _check(MANIFEST, manifest, "manifest.json")
    bindings = {item["id"]: item["profile"] for item in manifest.get("bindings", [])}
    tools = {}
    for item in manifest.get("tools", []):
        if bindings.get(item["binding"]) != BINDING:
            raise PackageError(f"tool {item['id']} uses an unsupported binding")
        descriptor = _json(root, item["descriptor"], known)
        _check(TOOL, descriptor, item["descriptor"])
        if descriptor["id"] != item["id"]:
            raise PackageError(f"descriptor id mismatch for {item['id']}")
        for name in ("input_schema", "output_schema"):
            validator(descriptor[name])
        problems = closed_objects(descriptor["input_schema"])
        if problems:
            raise PackageError(f"{item['id']}: input objects must set additionalProperties false at {problems[0]}")
        key = (descriptor["id"], int(descriptor["version"].split(".")[0]))
        if key in tools:
            raise PackageError(f"duplicate tool {key}")
        tools[key] = descriptor
    skills = {}
    for item in manifest.get("skills", []):
        if item["path"] not in known:
            raise PackageError(f"missing skill {item['path']}")
        skills[item["id"]] = _inside(root, item["path"]).read_text(encoding="utf-8")
    events = {}
    for item in manifest.get("events", []):
        descriptor = _json(root, item["descriptor"], known)
        _check(EVENT, descriptor, item["descriptor"])
        validator(descriptor["payload_schema"])
        if descriptor["type"] != item["type"]:
            raise PackageError(f"event type mismatch for {item['type']}")
        events[descriptor["type"]] = descriptor
    configuration = None
    if manifest.get("configuration"):
        if bindings.get(manifest["configuration"]["binding"]) != BINDING:
            raise PackageError("configuration uses an unsupported binding")
        configuration = _json(root, manifest["configuration"]["schema"], known)
        _check(CONFIGURATION, configuration, manifest["configuration"]["schema"])
        for name in ("public_schema", "secret_schema"):
            validator(configuration[name])
            problems = closed_objects(configuration[name])
            if problems:
                raise PackageError(f"configuration {name} must be closed at {problems[0]}")
    tool_ids = {tool_id for tool_id, _ in tools}
    ui = tuple(manifest.get("ui", []))
    for contribution in ui:
        if contribution["kind"] not in ("native", "declarative", "web", "headless"):
            raise PackageError(f"unknown UI kind for {contribution['id']}")
        for tool_id in contribution.get("requires_tools", {}):
            if tool_id not in tool_ids:
                raise PackageError(f"UI {contribution['id']} requires unknown tool {tool_id}")
    ui_ids = {contribution["id"] for contribution in ui}
    for capability in manifest["capabilities"]:
        if not set(capability.get("tools", [])) <= tool_ids or not set(capability.get("skills", [])) <= set(skills) \
                or not set(capability.get("ui", [])) <= ui_ids:
            raise PackageError(f"capability {capability['id']} references an unknown contribution")
    return Package(root, manifest, tuple(entries), digest(entries), tools, skills, events, ui, configuration)
