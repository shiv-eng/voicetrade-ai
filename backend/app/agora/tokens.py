"""Agora RTC token (AccessToken2, "007"), ported from Agora's reference builder (AgoraIO/Tools).

If the Agora project has no App Certificate enabled, no token is needed and an empty string is returned.
"""
from __future__ import annotations

import base64
import hmac
import secrets
import struct
import time
import zlib
from hashlib import sha256

_VERSION = "007"
_SERVICE_RTC = 1
_PRIV_JOIN, _PRIV_PUB_AUDIO, _PRIV_PUB_VIDEO, _PRIV_PUB_DATA = 1, 2, 3, 4


def _u16(x: int) -> bytes:
    return struct.pack("<H", x)


def _u32(x: int) -> bytes:
    return struct.pack("<I", x)


def _string(s: str | bytes) -> bytes:
    b = s.encode("utf-8") if isinstance(s, str) else s
    return _u16(len(b)) + b


def build_rtc_token(
    app_id: str, app_certificate: str, channel: str, uid: int, expire_s: int = 3600,
    issue_ts: int | None = None, salt: int | None = None,
) -> str:
    if not app_certificate:
        return ""
    issue_ts = issue_ts if issue_ts is not None else int(time.time())
    salt = salt if salt is not None else secrets.randbelow(99_999_999) + 1
    cert = app_certificate.encode("utf-8")

    signing = hmac.new(_u32(issue_ts), cert, sha256).digest()
    signing = hmac.new(_u32(salt), signing, sha256).digest()

    privileges = {_PRIV_JOIN: expire_s, _PRIV_PUB_AUDIO: expire_s, _PRIV_PUB_VIDEO: expire_s, _PRIV_PUB_DATA: expire_s}
    priv_bytes = _u16(len(privileges)) + b"".join(_u16(k) + _u32(v) for k, v in sorted(privileges.items()))
    service = _u16(_SERVICE_RTC) + priv_bytes + _string(channel) + _string(str(uid) if uid else "")

    signing_info = _string(app_id) + _u32(issue_ts) + _u32(expire_s) + _u32(salt) + _u16(1) + service
    signature = hmac.new(signing, signing_info, sha256).digest()
    return _VERSION + base64.b64encode(zlib.compress(_string(signature) + signing_info)).decode("ascii")
