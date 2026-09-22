"""Live voice sessions. The per-session secret protects the LLM endpoint Agora calls."""
from __future__ import annotations

import asyncio
import secrets
import time
from dataclasses import dataclass, field
from hashlib import sha256

from .db import Database
from .ledger import utcnow


def _hash(secret: str) -> str:
    return sha256(secret.encode()).hexdigest()


@dataclass
class Session:
    id: str
    user_id: str
    channel: str
    secret_hash: str
    language: str = "en"
    voice: str = "female"
    speech_rate: float = 1.0
    agent_id: str | None = None
    user_uid: int = 0
    history: list[dict] = field(default_factory=list)       # text turns, shared by voice and typed input
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)  # one turn at a time per session
    last_user_text: str = ""
    speaking_task: asyncio.Task | None = None
    last_reply: str = ""      # the last full answer, so Mira can say it again after a pause or an interruption
    paused: bool = False       # while paused Mira stays silent and only listens for "continue"
    started: float = field(default_factory=time.monotonic)


class SessionRegistry:
    def __init__(self, db: Database) -> None:
        self.db = db
        self._by_id: dict[str, Session] = {}
        self._by_secret: dict[str, str] = {}

    def create(self, user_id: str, language: str, voice: str, speech_rate: float) -> tuple[Session, str]:
        sid = "s_" + secrets.token_hex(4)
        secret = secrets.token_urlsafe(24)
        session = Session(sid, user_id, f"vt-{sid}", _hash(secret), language, voice, speech_rate)
        self._by_id[sid] = session
        self._by_secret[session.secret_hash] = sid
        self.db.execute(
            "INSERT INTO sessions (id, user_id, channel, secret_hash, started_at) VALUES (?, ?, ?, ?, ?)",
            (sid, user_id, session.channel, session.secret_hash, utcnow().isoformat()),
        )
        return session, secret

    def get(self, session_id: str) -> Session | None:
        return self._by_id.get(session_id)

    def by_secret(self, secret: str) -> Session | None:
        sid = self._by_secret.get(_hash(secret))
        return self._by_id.get(sid) if sid else None

    def end(self, session_id: str) -> Session | None:
        session = self._by_id.pop(session_id, None)
        if session:
            self._by_secret.pop(session.secret_hash, None)  # the LLM endpoint rejects the secret from now on
            self.db.execute("UPDATE sessions SET ended_at = ? WHERE id = ?", (utcnow().isoformat(), session_id))
        return session

    def all(self) -> list[Session]:
        return list(self._by_id.values())
