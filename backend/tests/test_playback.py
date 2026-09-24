"""Pause, resume and 'say it again' after Mira is cut off."""
from __future__ import annotations


import pytest
from test_api import make, pair, say, start_voice_session, turn, user_of

from app.main import voice_command

pytestmark = pytest.mark.asyncio


def playback_events(svc, sid):
    return [e["data"] for e in svc.hub._buffer[sid] if e["type"] == "playback"]


class FakeAgora:
    def __init__(self):
        self.spoken: list[str] = []
        self.interrupts = 0

    async def speak(self, agent_id, text):
        self.spoken.append(text)

    async def interrupt(self, agent_id):
        self.interrupts += 1


def test_voice_commands():
    assert voice_command("Pause.") == "pause"
    assert voice_command("Mira, hold on!") == "pause"
    assert voice_command("रुको") == "pause"
    assert voice_command("Go on") == "resume"
    assert voice_command("please repeat that") == "resume"
    assert voice_command("आगे बोलो") == "resume"
    assert voice_command("what is pause") is None
    assert voice_command("how is Reliance doing") is None


async def test_pause_button_then_resume_stays_silent():
    """A plain pause/play toggle never repeats anything: only an explicit replay does."""
    app, svc, client, chat, market = make([say("Reliance is up one percent today.")])
    h = await pair(client)
    s, secret = start_voice_session(svc, user_of(svc, h))
    s.agent_id = "agent1"
    svc.agora = FakeAgora()
    await turn(client, secret, "how is Reliance doing?")
    assert s.last_reply.startswith("Reliance is up")

    assert (await client.post(f"/sessions/{s.id}/pause", headers=h)).json() == {"paused": True}
    assert s.paused and svc.agora.interrupts == 1
    assert playback_events(svc, s.id)[-1] == {"paused": True, "resumable": True}

    r = await client.post(f"/sessions/{s.id}/resume", headers=h)
    assert r.json() == {"paused": False}
    assert not s.paused and svc.agora.spoken == []  # nothing repeated
    assert playback_events(svc, s.id)[-1] == {"paused": False, "resumable": False}


async def test_replay_endpoint_says_the_last_answer_again():
    app, svc, client, chat, market = make([say("Reliance is up one percent today.")])
    h = await pair(client)
    s, secret = start_voice_session(svc, user_of(svc, h))
    s.agent_id = "agent1"
    svc.agora = FakeAgora()
    await turn(client, secret, "how is Reliance doing?")

    r = await client.post(f"/sessions/{s.id}/replay", headers=h)
    assert r.json() == {"paused": False, "replayed": True}
    assert svc.agora.spoken == ["Reliance is up one percent today."]


async def test_noise_that_cuts_mira_off_offers_resume_and_saying_continue_brings_it_back():
    app, svc, client, chat, market = make([say("Reliance is up one percent today.")])
    h = await pair(client)
    s, secret = start_voice_session(svc, user_of(svc, h))
    s.agent_id = "agent1"
    svc.agora = FakeAgora()
    await turn(client, secret, "how is Reliance doing?")
    assert s.speaking_task and not s.speaking_task.done()  # Mira is still talking

    assert await turn(client, secret, "hmm") == ""          # a cough cuts her off; nothing new to say
    assert playback_events(svc, s.id)[-1] == {"paused": False, "resumable": True}

    assert await turn(client, secret, "continue") == ""     # spoken command, no model call
    assert svc.agora.spoken == ["Reliance is up one percent today."]
    assert len(chat.calls) == 1


async def test_saying_pause_silences_mira_and_a_real_question_ends_the_pause():
    app, svc, client, chat, market = make([say("First answer."), say("Second answer.")])
    h = await pair(client)
    s, secret = start_voice_session(svc, user_of(svc, h))
    s.agent_id = "agent1"
    svc.agora = FakeAgora()
    await turn(client, secret, "first question")
    assert await turn(client, secret, "pause") == ""
    assert s.paused and svc.agora.interrupts == 1
    assert await turn(client, secret, "second question") == "Second answer."
    assert not s.paused
    assert playback_events(svc, s.id)[-1] == {"paused": False, "resumable": False}


async def test_starting_a_session_with_resume_history_seeds_context_and_greets_differently():
    app, svc, client, chat, market = make([say("You have 12 Infosys shares worth about 18000 rupees.")])
    h = await pair(client)
    r = await client.post("/sessions", headers=h, json={
        "language": "en", "voice": "female", "speechRate": 1.0,
        "resumeHistory": [
            {"role": "user", "text": "how many Infosys shares do I have?"},
            {"role": "assistant", "text": "You hold 12 shares of Infosys."},
            {"role": "system", "text": "ignored: not a real turn"},
        ],
    })
    assert r.status_code == 201
    sid = r.json()["sessionId"]
    s = svc.sessions.get(sid)
    assert s.history == [
        {"role": "user", "content": "how many Infosys shares do I have?"},
        {"role": "assistant", "content": "You hold 12 shares of Infosys."},
    ]
    assert s.last_reply == "You hold 12 shares of Infosys."
