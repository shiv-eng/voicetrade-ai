"""Storage. PostgreSQL in real use (DATABASE_URL), SQLite for fast local tests.

Money is stored as TEXT and handled as Decimal, never float. All SQL is written once with `?` placeholders
and portable syntax; this module adapts it per engine.
"""
from __future__ import annotations

import logging
import re
import sqlite3
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any, Iterable, Iterator

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY, google_sub TEXT UNIQUE, email TEXT, name TEXT, picture TEXT,
    created_at TEXT NOT NULL, last_login_at TEXT, revoked INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users (email);
CREATE TABLE IF NOT EXISTS wallets (
    user_id TEXT NOT NULL, currency TEXT NOT NULL, cash TEXT NOT NULL,
    realized_pnl TEXT NOT NULL DEFAULT '0', PRIMARY KEY (user_id, currency)
);
CREATE TABLE IF NOT EXISTS instruments (
    id @SERIAL@, symbol TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
    exchange TEXT NOT NULL, currency TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS positions (
    user_id TEXT NOT NULL, instrument_id INTEGER NOT NULL, qty INTEGER NOT NULL, avg_cost TEXT NOT NULL,
    PRIMARY KEY (user_id, instrument_id)
);
CREATE TABLE IF NOT EXISTS orders (
    id @SERIAL@, user_id TEXT NOT NULL, instrument_id INTEGER NOT NULL,
    side TEXT NOT NULL, qty INTEGER NOT NULL, type TEXT NOT NULL, limit_price TEXT,
    status TEXT NOT NULL, avg_price TEXT, note TEXT, preview_id TEXT UNIQUE,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_orders_user ON orders (user_id, status);
CREATE TABLE IF NOT EXISTS previews (
    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, session_id TEXT, kind TEXT NOT NULL,
    payload_json TEXT NOT NULL, price_at_preview TEXT, expires_at TEXT NOT NULL,
    status TEXT NOT NULL, confirmed_at TEXT, order_id INTEGER
);
CREATE INDEX IF NOT EXISTS idx_previews_user ON previews (user_id, status);
CREATE TABLE IF NOT EXISTS alerts (
    id @SERIAL@, user_id TEXT NOT NULL, instrument_id INTEGER NOT NULL, direction TEXT NOT NULL, target TEXT NOT NULL,
    created_at TEXT NOT NULL, triggered_at TEXT, trigger_price TEXT, notified INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_alerts_user ON alerts (user_id, active);
CREATE TABLE IF NOT EXISTS watchlist (
    user_id TEXT NOT NULL, instrument_id INTEGER NOT NULL, position INTEGER NOT NULL, added_at TEXT NOT NULL,
    PRIMARY KEY (user_id, instrument_id)
);
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, agent_id TEXT, channel TEXT NOT NULL,
    secret_hash TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT
);
CREATE TABLE IF NOT EXISTS device_tokens (
    user_id TEXT NOT NULL, token TEXT NOT NULL, updated_at TEXT NOT NULL,
    PRIMARY KEY (user_id, token)
);
CREATE TABLE IF NOT EXISTS audit_log (
    id @SERIAL@, user_id TEXT, session_id TEXT, actor TEXT NOT NULL,
    tool TEXT NOT NULL, args_json TEXT, result_json TEXT, latency_ms INTEGER, ts TEXT NOT NULL
);
"""


@dataclass(frozen=True)
class Result:
    rowcount: int
    lastrowid: int | None = None


class Database:
    """One shared connection guarded by a re-entrant lock: every statement and every transaction is serialised
    inside this process. Cross-process safety for balances comes from per-user advisory locks (Postgres)."""

    def __init__(self, url: str = ":memory:") -> None:
        self.url = url
        self.is_pg = url.startswith(("postgres://", "postgresql://"))
        self._lock = threading.RLock()
        self._in_tx = False
        self._conn: Any = None
        self._connect()
        self._create_schema()

    # ---- connection ------------------------------------------------------------------------------

    def _connect(self) -> None:
        if self.is_pg:
            import psycopg
            from psycopg.rows import dict_row

            # prepare_threshold=None: no server-side prepared statements, so it also works behind a transaction pooler
            # (Supabase). Keepalives stop idle-connection drops between calls.
            self._conn = psycopg.connect(
                self.url, autocommit=True, row_factory=dict_row, connect_timeout=10, prepare_threshold=None,
                keepalives=1, keepalives_idle=30, keepalives_interval=10, keepalives_count=3,
            )
        else:
            self._conn = sqlite3.connect(self.url, check_same_thread=False, isolation_level=None)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA foreign_keys = ON")

    def _create_schema(self) -> None:
        serial = "BIGSERIAL PRIMARY KEY" if self.is_pg else "INTEGER PRIMARY KEY AUTOINCREMENT"
        script = SCHEMA.replace("@SERIAL@", serial)
        with self._lock:
            if self.is_pg:
                for stmt in filter(None, (s.strip() for s in script.split(";"))):
                    self._conn.execute(stmt)
                self._lock_down_tables(script)
            else:
                self._conn.executescript(script)

    def _lock_down_tables(self, script: str) -> None:
        """Supabase publishes every table in `public` through its REST API for the `anon` and `authenticated` roles.
        This app talks to the database only from the server (as an owner role that bypasses row-level security), so turn
        RLS on with no policies: the public API then sees nothing, whatever keys exist."""
        for table in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", script):
            try:
                self._conn.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
            except Exception as e:  # never stop the server over a hardening step
                log.warning("could not enable row level security on %s: %s", table, e)

    def _sql(self, sql: str) -> str:
        return sql.replace("?", "%s") if self.is_pg else sql

    def _run(self, sql: str, params: Iterable[Any]):
        sql = self._sql(sql)
        try:
            return self._conn.execute(sql, tuple(params))
        except Exception as e:
            if self.is_pg and not self._in_tx and type(e).__name__ in ("OperationalError", "InterfaceError"):
                log.warning("Postgres connection lost (%s); reconnecting", e)
                self._connect()
                return self._conn.execute(sql, tuple(params))
            raise

    # ---- statements ------------------------------------------------------------------------------

    def execute(self, sql: str, params: Iterable[Any] = ()) -> Result:
        with self._lock:
            cur = self._run(sql, params)
            return Result(cur.rowcount, None if self.is_pg else cur.lastrowid)

    def insert_id(self, sql: str, params: Iterable[Any] = ()) -> int:
        """INSERT and return the new row's `id` on either engine."""
        with self._lock:
            if self.is_pg:
                return self._run(sql + " RETURNING id", params).fetchone()["id"]
            return self._run(sql, params).lastrowid

    def insert_ignore(self, table: str, columns: list[str], values: list[Any], conflict: str) -> None:
        cols = ", ".join(columns)
        marks = ", ".join("?" for _ in columns)
        with self._lock:
            self._run(f"INSERT INTO {table} ({cols}) VALUES ({marks}) ON CONFLICT ({conflict}) DO NOTHING", values)

    def query(self, sql: str, params: Iterable[Any] = ()) -> list[Any]:
        with self._lock:
            return self._run(sql, params).fetchall()

    def one(self, sql: str, params: Iterable[Any] = ()) -> Any | None:
        with self._lock:
            return self._run(sql, params).fetchone()

    @contextmanager
    def transaction(self, lock_key: str | None = None) -> Iterator[None]:
        """All-or-nothing block: balances and positions must never be half-updated.
        `lock_key` (a user id) takes a Postgres advisory lock so two workers can't change one wallet at once."""
        with self._lock:
            self._conn.execute("BEGIN" if self.is_pg else "BEGIN IMMEDIATE")
            self._in_tx = True
            try:
                if self.is_pg and lock_key:
                    self._conn.execute("SELECT pg_advisory_xact_lock(hashtext(%s))", (lock_key,))
                yield
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
            else:
                self._conn.execute("COMMIT")
            finally:
                self._in_tx = False


_UNSAFE = re.compile(r"[^a-zA-Z0-9_]")


def safe_ident(name: str) -> str:
    return _UNSAFE.sub("", name)
