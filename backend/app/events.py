"""Server-to-app event stream. Every event gets a per-session sequence number and is buffered, so a
reconnecting client can ask for everything after `lastSeq` (no gaps, no duplicates)."""
from __future__ import annotations

import asyncio
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Any


class Hub:
    BUFFER = 300

    def __init__(self) -> None:
        self._seq: dict[str, int] = defaultdict(int)
        self._buffer: dict[str, deque[dict]] = defaultdict(lambda: deque(maxlen=self.BUFFER))
        self._subs: dict[str, set[asyncio.Queue]] = defaultdict(set)
        self._user_sessions: dict[str, set[str]] = defaultdict(set)

    def register_session(self, user_id: str, session_id: str) -> None:
        self._user_sessions[user_id].add(session_id)

    def unregister_session(self, user_id: str, session_id: str) -> None:
        self._user_sessions[user_id].discard(session_id)

    def sessions_of(self, user_id: str) -> list[str]:
        return list(self._user_sessions.get(user_id, ()))

    def emit(self, session_id: str, type_: str, data: dict[str, Any]) -> dict:
        self._seq[session_id] += 1
        event = {
            "type": type_, "sessionId": session_id, "seq": self._seq[session_id],
            "ts": datetime.now(timezone.utc).isoformat(), "data": data,
        }
        self._buffer[session_id].append(event)
        for q in list(self._subs[session_id]):
            q.put_nowait(event)
        return event

    def emit_user(self, user_id: str, type_: str, data: dict[str, Any]) -> None:
        for sid in self.sessions_of(user_id):
            self.emit(sid, type_, data)

    def subscribe(self, session_id: str, last_seq: int = 0) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue()
        for event in self._buffer[session_id]:
            if event["seq"] > last_seq:
                q.put_nowait(event)
        self._subs[session_id].add(q)
        return q

    def unsubscribe(self, session_id: str, q: asyncio.Queue) -> None:
        self._subs[session_id].discard(q)

    def drop_session(self, session_id: str) -> None:
        self._subs.pop(session_id, None)
        self._buffer.pop(session_id, None)
        self._seq.pop(session_id, None)
