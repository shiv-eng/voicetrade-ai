"""The token builder must produce something Agora can verify: decode it and re-check the signature."""
from __future__ import annotations

import asyncio
import base64
import hmac
import struct
import zlib
from hashlib import sha256

import pytest

from app.agora.client import AgoraClient
from app.agora.tokens import build_rtc_token
from app.config import Settings

APP_ID = "0123456789abcdef0123456789abcdef"
CERT = "fedcba9876543210fedcba9876543210"


def _read_string(buf: bytes) -> tuple[bytes, bytes]:
    (n,) = struct.unpack("<H", buf[:2])
    return buf[2:2 + n], buf[2 + n:]


def test_token_round_trips_and_signature_verifies():
    token = build_rtc_token(APP_ID, CERT, "vt-s1", 2001, expire_s=600, issue_ts=1_700_000_000, salt=12345)
    assert token.startswith("007")
    raw = zlib.decompress(base64.b64decode(token[3:]))
    signature, signing_info = _read_string(raw)

    key = hmac.new(struct.pack("<I", 1_700_000_000), CERT.encode(), sha256).digest()
    key = hmac.new(struct.pack("<I", 12345), key, sha256).digest()
    assert signature == hmac.new(key, signing_info, sha256).digest()

    app_id, rest = _read_string(signing_info)
    assert app_id == APP_ID.encode()
    issue, expire, salt, services = struct.unpack("<IIIH", rest[:14])
    assert (issue, expire, salt, services) == (1_700_000_000, 600, 12345, 1)
    rest = rest[14:]
    service_type, n_priv = struct.unpack("<HH", rest[:4])
    assert service_type == 1 and n_priv == 4
    rest = rest[4 + n_priv * 6:]
    channel, rest = _read_string(rest)
    uid, rest = _read_string(rest)
    assert (channel, uid, rest) == (b"vt-s1", b"2001", b"")


def test_no_certificate_means_no_token():
    assert build_rtc_token(APP_ID, "", "c", 1) == ""


def test_agent_body_points_at_our_llm_and_uses_sarvam_for_hearing_and_speaking():
    s = Settings(public_base_url="https://api.example.com", agora_app_id=APP_ID, jwt_secret="x" * 32, sarvam_api_key="sk-test")
    body = AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "Hi", "female", 1.0)["properties"]
    assert body["llm"]["url"] == "https://api.example.com/v1/chat/completions?s=sekret"
    assert body["llm"]["api_key"] == "sekret" and body["llm"]["style"] == "openai"
    assert body["remote_rtc_uids"] == ["2001"] and body["agent_rtc_uid"] == "1001"
    assert body["asr"]["vendor"] == "sarvam" and body["tts"]["vendor"] == "sarvam"
    assert body["llm"]["system_messages"] == []


def test_listening_auto_detects_language_but_speaking_uses_the_chosen_accent():
    """ASR hears either language turn by turn (no restart needed to switch); only the TTS base accent is pinned."""
    s = Settings(public_base_url="https://api.example.com", agora_app_id=APP_ID, jwt_secret="x" * 32, sarvam_api_key="sk-test")
    hi = AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "नमस्ते", "female", 1.0, "hinglish")["properties"]
    assert hi["asr"]["params"]["language"] == "unknown" and hi["tts"]["params"]["target_language_code"] == "hi-IN"
    en = AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "Hi", "female", 1.0, "en")["properties"]
    assert en["asr"]["params"]["language"] == "unknown" and en["tts"]["params"]["target_language_code"] == "en-IN"
    assert en["tts"]["params"]["speaker"] == "simran"


def test_no_speech_key_means_no_voice_agent():
    import pytest
    from app.agora.client import AgoraError
    s = Settings(public_base_url="https://api.example.com", agora_app_id=APP_ID, jwt_secret="x" * 32)
    with pytest.raises(AgoraError):
        AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "Hi", "female", 1.0)


def test_prompt_is_bilingual_whenever_speech_supports_it_not_just_the_users_default():
    from app.llm.prompts import build_system_prompt
    # Sarvam configured (bilingual=True): Devanagari rules apply no matter which language the user picked at onboarding.
    assert "Devanagari" in build_system_prompt(None, "w", bilingual=True, session_language="en")
    assert "Devanagari" in build_system_prompt(None, "w", bilingual=True, session_language="hinglish")
    # No Sarvam key at all (bilingual=False): English only, a real technical limit, not a preference.
    assert "Devanagari" not in build_system_prompt(None, "w", bilingual=False, session_language="hinglish")


def test_a_hindi_default_session_answering_hindi_still_switches_to_english_when_spoken():
    from app.llm.prompts import build_system_prompt
    p = build_system_prompt(None, "w", bilingual=True, session_language="hinglish", user_text="how is Reliance doing")
    assert "the user spoke English, so answer ONLY in English" in p


def test_an_english_default_session_still_switches_to_hindi_when_spoken():
    """The exact bug reported: Settings says English, user starts speaking Hindi mid-conversation."""
    from app.llm.prompts import build_system_prompt
    p = build_system_prompt(None, "w", bilingual=True, session_language="en", user_text="क्या आप हिंदी में बात कर सकते हैं?")
    assert "the user spoke Hindi, so answer in Hindi" in p


class _NamedFakeChat:
    """Records that it was the one asked to stream, without making a real call."""

    def __init__(self, name: str) -> None:
        self.name = name

    async def stream(self, messages, tools):
        from app.llm.orchestrator import ChatEvent
        yield ChatEvent(text=self.name)


@pytest.mark.asyncio
async def test_routed_chat_picks_the_model_by_the_language_just_spoken():
    from app.llm.orchestrator import RoutedChat
    from app.speech import speech_language

    routed = RoutedChat(english=_NamedFakeChat("en"), hindi=_NamedFakeChat("hi"))

    speech_language.set("en")
    pieces = [ev.text async for ev in routed.stream([], [])]
    assert pieces == ["en"]

    speech_language.set("hi")
    pieces = [ev.text async for ev in routed.stream([], [])]
    assert pieces == ["hi"]


@pytest.mark.asyncio
async def test_routed_chat_falls_back_to_english_when_no_hindi_model_is_configured():
    from app.llm.orchestrator import RoutedChat
    from app.speech import speech_language

    routed = RoutedChat(english=_NamedFakeChat("en"), hindi=None)
    speech_language.set("hi")  # even mid-Hindi-turn, there's nothing else to route to
    pieces = [ev.text async for ev in routed.stream([], [])]
    assert pieces == ["en"]


@pytest.mark.asyncio
async def test_a_slow_reply_is_filled_with_repeated_varied_fillers_not_dead_air():
    """A multi-step lookup can stay silent long enough to need more than one 'hmm' — each one different,
    and capped so a genuinely stuck call never turns into a wall of them."""
    import app.llm.orchestrator as orch_mod
    from app.sessions import Session
    from app.state import build_services
    from conftest import FakeMarket, fresh_db

    class NeverSpeaks:
        async def stream(self, messages, tools):
            await asyncio.sleep(10)
            yield orch_mod.ChatEvent(text="never gets here")

    settings = Settings(db_path=":memory:", jwt_secret="s" * 32)
    svc = build_services(settings, FakeMarket(), NeverSpeaks(), db=fresh_db())
    user = svc.ledger.create_user("test")
    session = Session(id="s1", user_id=user, channel="c1", secret_hash="h")

    orig_ack, orig_repeat, orig_max = orch_mod.ACK_AFTER_S, orch_mod.REPEAT_ACK_AFTER_S, orch_mod.MAX_ACKS
    orch_mod.ACK_AFTER_S, orch_mod.REPEAT_ACK_AFTER_S, orch_mod.MAX_ACKS = 0.01, 0.02, 3
    fillers = []
    gen = svc.orchestrator.reply(session, [], "how is reliance doing")
    try:
        async for piece in gen:
            fillers.append(piece)
            if len(fillers) == orch_mod.MAX_ACKS:
                break
    finally:
        await gen.aclose()
        orch_mod.ACK_AFTER_S, orch_mod.REPEAT_ACK_AFTER_S, orch_mod.MAX_ACKS = orig_ack, orig_repeat, orig_max

    assert len(fillers) == 3
    assert all(a != b for a, b in zip(fillers, fillers[1:]))  # never the same filler twice in a row
