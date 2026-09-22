"""The token builder must produce something Agora can verify: decode it and re-check the signature."""
from __future__ import annotations

import base64
import hmac
import struct
import zlib
from hashlib import sha256

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


def test_the_chosen_language_pins_both_hearing_and_speaking():
    s = Settings(public_base_url="https://api.example.com", agora_app_id=APP_ID, jwt_secret="x" * 32, sarvam_api_key="sk-test")
    hi = AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "नमस्ते", "female", 1.0, "hinglish")["properties"]
    assert hi["asr"]["params"]["language"] == "hi-IN" and hi["tts"]["params"]["target_language_code"] == "hi-IN"
    en = AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "Hi", "female", 1.0, "en")["properties"]
    assert en["asr"]["params"]["language"] == "en-IN" and en["tts"]["params"]["target_language_code"] == "en-IN"
    assert en["tts"]["params"]["speaker"] == "simran"


def test_no_speech_key_means_no_voice_agent():
    import pytest
    from app.agora.client import AgoraError
    s = Settings(public_base_url="https://api.example.com", agora_app_id=APP_ID, jwt_secret="x" * 32)
    with pytest.raises(AgoraError):
        AgoraClient(s).agent_body("s1", "vt-s1", "tok", 2001, "sekret", "Hi", "female", 1.0)


def test_prompt_asks_for_devanagari_hindi_only_in_hindi_mode():
    from app.llm.prompts import build_system_prompt
    assert "Devanagari" in build_system_prompt(False, None, "w", language="hinglish")
    assert "Devanagari" not in build_system_prompt(False, None, "w", language="en")
