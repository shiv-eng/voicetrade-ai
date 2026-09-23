"""Price-alert push notifications via FCM's HTTP v1 API, authorized with a Firebase service account.

Talks to FCM directly over HTTP instead of pulling in the firebase-admin SDK (which drags in grpc and far
more than this needs): mint an OAuth2 access token from the service account's private key (the same
jwt.encode/RS256 the rest of this app already uses for its own tokens), then POST the message."""
from __future__ import annotations

import logging
import time

import httpx
import jwt

from .config import Settings

log = logging.getLogger(__name__)

_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_TOKEN_URL = "https://oauth2.googleapis.com/token"


class PushClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient | None = None) -> None:
        self.s = settings
        self._http = http or httpx.AsyncClient(timeout=10.0)
        self._access_token: str | None = None
        self._expires_at = 0.0

    @property
    def configured(self) -> bool:
        return bool(self.s.firebase_service_account)

    async def _access(self) -> str:
        if self._access_token and time.time() < self._expires_at:
            return self._access_token
        sa = self.s.firebase_service_account
        assert sa is not None
        now = int(time.time())
        assertion = jwt.encode(
            {"iss": sa["client_email"], "scope": _SCOPE, "aud": _TOKEN_URL, "iat": now, "exp": now + 3600},
            sa["private_key"], algorithm="RS256",
        )
        resp = await self._http.post(_TOKEN_URL, data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion,
        })
        resp.raise_for_status()
        body = resp.json()
        self._access_token = body["access_token"]
        self._expires_at = now + body["expires_in"] - 60  # refresh a minute early
        return self._access_token

    async def send(self, token: str, title: str, body: str, data: dict[str, str] | None = None) -> bool:
        """Best effort: a failed push just means the phone falls back to its periodic poll."""
        if not self.configured:
            return False
        try:
            access_token = await self._access()
            project_id = self.s.firebase_service_account["project_id"]  # type: ignore[index]
            resp = await self._http.post(
                f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send",
                headers={"Authorization": f"Bearer {access_token}"},
                json={"message": {"token": token, "notification": {"title": title, "body": body}, "data": data or {}}},
            )
            if resp.status_code != 200:
                log.info("FCM send returned %s: %s", resp.status_code, resp.text[:200])
                return False
            return True
        except Exception as e:
            log.warning("FCM send failed: %s", e)
            return False

    async def aclose(self) -> None:
        await self._http.aclose()
