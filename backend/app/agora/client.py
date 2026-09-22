"""Agora Conversational AI agent control (REST). The phone never sees the customer key/secret."""
from __future__ import annotations

import base64
import logging
from typing import Any

import httpx

from ..config import Settings

log = logging.getLogger(__name__)

_BASE = "https://api.agora.io/api/conversational-ai-agent/v2/projects"


class AgoraError(Exception):
    pass


class AgoraClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient | None = None) -> None:
        self.s = settings
        self._http = http or httpx.AsyncClient(timeout=15.0)

    @property
    def _auth(self) -> dict[str, str]:
        raw = f"{self.s.agora_customer_id}:{self.s.agora_customer_secret}".encode()
        return {"Authorization": "Basic " + base64.b64encode(raw).decode(), "Content-Type": "application/json"}

    def agent_body(
        self, session_id: str, channel: str, agent_token: str, user_uid: int, llm_secret: str,
        greeting: str, voice: str, speech_rate: float, language: str = "en",
    ) -> dict[str, Any]:
        s = self.s
        if not s.sarvam_api_key and not (s.agora_asr_json and s.agora_tts_json):
            raise AgoraError("Speech isn't configured on this server (SARVAM_API_KEY).")
        sv = s.sarvam_api_key
        # The user picked a language first, so listen for and speak exactly that one.
        asr = s.agora_asr_json or {
            "credential_mode": "byok", "vendor": "sarvam", "language": "en-US",
            "params": {"api_key": sv, "language": "en-IN" if language == "en" else "hi-IN"},
        }
        tts = s.agora_tts_json or {
            "credential_mode": "byok", "vendor": "sarvam",
            "params": {"api_subscription_key": sv, "speaker": s.sarvam_speaker_female, "model": s.sarvam_tts_model,
                       "target_language_code": "en-IN" if language == "en" else "hi-IN",
                       "pace": max(0.5, min(1.6, speech_rate * s.sarvam_pace)), "sample_rate": 24000},
        }
        return {
            "name": f"vt-{session_id}",
            "properties": {
                "channel": channel,
                "token": agent_token,
                "agent_rtc_uid": str(s.agora_agent_uid),
                "remote_rtc_uids": [str(user_uid)],
                "idle_timeout": s.agora_idle_timeout,
                "asr": asr,
                "turn_detection": s.agora_turn_detection_json or {
                    "config": {
                        "start_of_speech": {"mode": "vad", "vad_config": {
                            "interrupt_duration_ms": s.interrupt_ms, "speaking_interrupt_duration_ms": s.interrupt_ms, "prefix_padding_ms": 600}},
                        "end_of_speech": {"mode": "vad", "vad_config": {"silence_duration_ms": s.end_of_speech_ms}},
                    },
                },
                "llm": {
                    "url": f"{s.public_base_url}/v1/chat/completions?s={llm_secret}",
                    "api_key": llm_secret,
                    "style": "openai",
                    "system_messages": [],  # the real prompt lives on our server, with live account context
                    "greeting_message": greeting,
                    "failure_message": "Sorry, I couldn't get that. Please try again.",
                    "max_history": 20,
                    "params": {"model": "voicetrade-orchestrator"},
                },
                "tts": tts,
            },
        }

    async def start_agent(self, body: dict[str, Any]) -> str:
        resp = await self._http.post(f"{_BASE}/{self.s.agora_app_id}/join", json=body, headers=self._auth)
        if resp.status_code != 200:
            log.error("Agora join failed: %s %s", resp.status_code, resp.text[:500])
            raise AgoraError(f"Agora refused to start the agent (HTTP {resp.status_code}): {resp.text[:200]}")
        return resp.json()["agent_id"]

    async def stop_agent(self, agent_id: str) -> None:
        try:
            resp = await self._http.post(f"{_BASE}/{self.s.agora_app_id}/agents/{agent_id}/leave", headers=self._auth)
            if resp.status_code not in (200, 404):
                log.warning("Agora leave returned %s: %s", resp.status_code, resp.text[:200])
        except httpx.HTTPError as e:
            log.warning("Agora leave failed: %s", e)

    async def speak(self, agent_id: str, text: str) -> None:
        """Best effort: make the agent say something (typed messages, tap-to-confirm results, limit fills)."""
        from ..speech import speakify
        try:
            resp = await self._http.post(
                f"{_BASE}/{self.s.agora_app_id}/agents/{agent_id}/speak",
                json={"text": speakify(text), "priority": "INTERRUPT", "interruptable": True}, headers=self._auth,
            )
            if resp.status_code != 200:
                log.info("Agora speak returned %s: %s", resp.status_code, resp.text[:200])
        except httpx.HTTPError as e:
            log.info("Agora speak failed: %s", e)

    async def interrupt(self, agent_id: str) -> None:
        """Best effort: cut off whatever the agent is saying right now."""
        try:
            resp = await self._http.post(f"{_BASE}/{self.s.agora_app_id}/agents/{agent_id}/interrupt", headers=self._auth)
            if resp.status_code != 200:
                log.info("Agora interrupt returned %s: %s", resp.status_code, resp.text[:200])
        except httpx.HTTPError as e:
            log.info("Agora interrupt failed: %s", e)

    async def aclose(self) -> None:
        await self._http.aclose()
