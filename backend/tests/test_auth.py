"""Google sign-in: accounts live in the database, keyed by the Google account, and survive new devices."""
from __future__ import annotations

import time
from decimal import Decimal

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.config import Settings
from app.main import create_app
from conftest import INFY, FakeMarket, fresh_db

pytestmark = pytest.mark.asyncio

CLIENT_ID = "1234-web.apps.googleusercontent.com"


def _keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return pem, key.public_key()


PRIVATE, PUBLIC = _keypair()
FORGER, _ = _keypair()


class FixedKeys:
    def signing_key(self, token):
        return PUBLIC


def id_token(sub="g-1", email="asha@gmail.com", aud=CLIENT_ID, exp_in=3600, verified=True, name="Asha Rao", key=PRIVATE, iss="https://accounts.google.com"):
    now = int(time.time())
    claims = {"iss": iss, "sub": sub, "aud": aud, "email": email, "email_verified": verified, "name": name,
              "picture": "https://example.com/a.png", "iat": now, "exp": now + exp_in}
    return jwt.encode(claims, key, algorithm="RS256")


def make(client_ids=(CLIENT_ID,)):
    settings = Settings(db_path=":memory:", jwt_secret="s" * 32, google_client_ids=list(client_ids))
    db = fresh_db()
    app = create_app(settings, FakeMarket(), None, background=False, db=db, google_keys=FixedKeys())
    return app, app.state.svc, httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")


async def sign_in(client, token):
    return await client.post("/auth/google", json={"idToken": token})


async def test_first_sign_in_creates_account_with_paper_money():
    app, svc, client = make()
    r = await sign_in(client, id_token())
    assert r.status_code == 200
    body = r.json()
    assert body["newAccount"] is True and body["profile"]["email"] == "asha@gmail.com" and body["profile"]["name"] == "Asha Rao"
    acc = (await client.get("/account", headers={"Authorization": "Bearer " + body["token"]})).json()
    assert {w["currency"]: Decimal(w["cash"]) for w in acc["wallets"]} == {"INR": Decimal("1000000"), "USD": Decimal("10000")}


async def test_signing_in_again_on_a_new_phone_keeps_the_same_account_and_balances():
    app, svc, client = make()
    first = (await sign_in(client, id_token())).json()
    user = first["profile"]["userId"]
    infy = svc.instruments.ensure(INFY)
    p = await svc.trading.preview_order(user, None, infy.conid, "BUY", quantity=10)
    await svc.trading.confirm(user, p["preview_id"])            # spends 15,000 of the paper wallet

    second = (await sign_in(client, id_token())).json()          # "new phone": a brand-new token for the same Google account
    assert second["newAccount"] is False and second["profile"]["userId"] == user
    acc = (await client.get("/account", headers={"Authorization": "Bearer " + second["token"]})).json()
    inr = next(w for w in acc["wallets"] if w["currency"] == "INR")
    assert Decimal(inr["cash"]) == Decimal("985000")             # NOT reset to 10 lakh
    pos = (await client.get("/positions", headers={"Authorization": "Bearer " + second["token"]})).json()
    assert pos[0]["quantity"] == "10"


async def test_different_google_accounts_are_different_users():
    app, svc, client = make()
    a = (await sign_in(client, id_token(sub="g-1", email="a@gmail.com"))).json()["profile"]["userId"]
    b = (await sign_in(client, id_token(sub="g-2", email="b@gmail.com"))).json()["profile"]["userId"]
    assert a != b


async def test_profile_endpoint_and_users_persist_in_the_database():
    app, svc, client = make()
    token = (await sign_in(client, id_token())).json()["token"]
    me = (await client.get("/me", headers={"Authorization": "Bearer " + token})).json()
    assert me["email"] == "asha@gmail.com"
    row = svc.db.one("SELECT google_sub, email FROM users WHERE id = ?", (me["userId"],))
    assert row["google_sub"] == "g-1" and row["email"] == "asha@gmail.com"


@pytest.mark.parametrize("bad", [
    dict(aud="someone-elses-app.apps.googleusercontent.com"),   # token minted for another app
    dict(exp_in=-10),                                           # expired
    dict(verified=False),                                       # unverified email
    dict(key=FORGER),                                           # signed by somebody who isn't Google
    dict(iss="https://evil.example.com"),                       # wrong issuer
])
async def test_bad_google_tokens_are_rejected(bad):
    app, svc, client = make()
    r = await sign_in(client, id_token(**bad))
    assert r.status_code == 401 and r.json()["code"] == "GOOGLE_AUTH_FAILED"
    assert svc.db.one("SELECT COUNT(*) AS n FROM users")["n"] == 0


async def test_garbage_token_and_missing_config_are_rejected():
    app, svc, client = make()
    assert (await sign_in(client, "not-a-jwt")).status_code == 401
    app2, _, client2 = make(client_ids=())
    r = await sign_in(client2, id_token())
    assert r.status_code == 401 and "isn't configured" in r.json()["message"]
