"""Versioned DDL for the SDK's own tables inside a plugin's schema.

Version 1 is frozen: a migration that calls `ddl(schema, 1)` must keep producing
the same tables forever. Later versions add `upgrade(schema, from_version)`.
"""

import re

VERSION = 1


def _schema(name):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", name) or name in ("public", "core", "pg_catalog"):
        raise ValueError("invalid schema name")
    return name


def ddl(schema, version=VERSION):
    if version != 1:
        raise ValueError("unknown SDK storage version")
    s = _schema(schema)
    return [
        f"""CREATE TABLE {s}.actors (
            installation_id text NOT NULL CHECK (length(installation_id) BETWEEN 1 AND 200),
            actor_id text NOT NULL CHECK (length(actor_id) BETWEEN 1 AND 200),
            event_seq bigint NOT NULL DEFAULT 0,
            created_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (installation_id, actor_id))""",
        f"""CREATE TABLE {s}.operations (
            installation_id text NOT NULL,
            actor_id text NOT NULL,
            operation_key text NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 160),
            tool_id text NOT NULL,
            tool_major integer NOT NULL,
            tool_version text NOT NULL,
            space_id text,
            fingerprint text NOT NULL,
            state text NOT NULL CHECK (state IN ('accepted','running','completed','failed','cancelled')),
            running_reason text,
            request jsonb,
            progress jsonb,
            result jsonb,
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now(),
            terminal_at timestamptz,
            PRIMARY KEY (installation_id, actor_id, operation_key),
            FOREIGN KEY (installation_id, actor_id) REFERENCES {s}.actors (installation_id, actor_id),
            CHECK ((state IN ('completed','failed','cancelled')) = (result IS NOT NULL AND terminal_at IS NOT NULL)))""",
        f"CREATE INDEX operations_open ON {s}.operations (updated_at) WHERE state IN ('accepted','running')",
        f"""CREATE TABLE {s}.events (
            installation_id text NOT NULL,
            actor_id text NOT NULL,
            seq bigint NOT NULL,
            event_id uuid NOT NULL UNIQUE,
            type text NOT NULL CHECK (length(type) BETWEEN 1 AND 100),
            occurred_at timestamptz NOT NULL,
            operation_key text,
            resource_id text,
            revision bigint,
            payload jsonb NOT NULL,
            PRIMARY KEY (installation_id, actor_id, seq),
            FOREIGN KEY (installation_id, actor_id) REFERENCES {s}.actors (installation_id, actor_id))""",
        f"CREATE INDEX events_retention ON {s}.events (occurred_at)",
    ]
