"""Verify a Google ID token (the thing the Android app gets from 'Sign in with Google').

We never trust the app's claim about who the user is: the token's signature is checked against Google's
published keys, and its audience must be our own OAuth client id.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Protocol

import jwt

log = logging.getLogger(__name__)

_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
_ISSUERS = ("https://accounts.google.com", "accounts.google.com")


class AuthError(Exception):
    pass


@dataclass(frozen=True)
class GoogleProfile:
    sub: str            # stable Google account id, the key we store users under
    email: str
    name: str
    picture: str | None


class KeyResolver(Protocol):
    def signing_key(self, token: str) -> Any: ...


class GoogleKeys:
    """Fetches and caches Google's signing keys."""

    def __init__(self) -> None:
        self._client = jwt.PyJWKClient(_JWKS_URL, cache_keys=True, lifespan=3600)

    def signing_key(self, token: str) -> Any:
        return self._client.get_signing_key_from_jwt(token).key


def verify_google_id_token(token: str, client_ids: list[str], keys: KeyResolver) -> GoogleProfile:
    if not client_ids:
        raise AuthError("Google sign-in isn't configured on this server.")
    try:
        claims = jwt.decode(
            token, keys.signing_key(token), algorithms=["RS256"], audience=client_ids,
            options={"require": ["exp", "iat", "sub", "aud", "iss"]},
        )
    except jwt.PyJWTError as e:
        raise AuthError(f"Invalid Google token: {e}") from e
    except Exception as e:  # network trouble fetching the keys
        log.warning("Could not verify Google token: %s", e)
        raise AuthError("Couldn't verify the Google sign-in. Please try again.") from e
    if claims.get("iss") not in _ISSUERS:
        raise AuthError("Token was not issued by Google.")
    if not claims.get("email") or claims.get("email_verified") is not True:
        raise AuthError("Your Google email address isn't verified.")
    return GoogleProfile(
        sub=str(claims["sub"]), email=str(claims["email"]).lower(),
        name=str(claims.get("name") or claims["email"].split("@")[0]), picture=claims.get("picture"),
    )
