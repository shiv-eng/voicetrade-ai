"""Where to send a push: each phone that has signed in registers its FCM token here. A user can have several
(old phone, new phone, reinstalled app) — pushes go to all of them."""
from __future__ import annotations

from datetime import datetime, timezone

from .db import Database


class Devices:
    def __init__(self, db: Database) -> None:
        self.db = db

    def register(self, user_id: str, token: str) -> None:
        self.db.insert_ignore(
            "device_tokens", ["user_id", "token", "updated_at"],
            [user_id, token, datetime.now(timezone.utc).isoformat()], "user_id, token",
        )

    def tokens_for(self, user_id: str) -> list[str]:
        return [r["token"] for r in self.db.query("SELECT token FROM device_tokens WHERE user_id = ?", (user_id,))]

    def forget(self, token: str) -> None:
        """A token FCM reports as no longer valid (app uninstalled, etc.)."""
        self.db.execute("DELETE FROM device_tokens WHERE token = ?", (token,))
