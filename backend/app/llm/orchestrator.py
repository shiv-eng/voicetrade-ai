"""The 'LLM' the Agora agent talks to. It runs a real model with tools and streams back only the words
to be spoken (PRD 9.2). Tool results never go straight to speech; the model turns them into sentences."""
from __future__ import annotations

import asyncio
import json
import logging
import random
import re
from dataclasses import dataclass
from typing import Any, AsyncIterator, Callable, Protocol

from openai import APIConnectionError, APIStatusError

from ..config import Settings
from ..ledger import Ledger
from ..portfolio import Portfolio
from ..risk import RiskEngine
from ..sessions import Session
from ..speech import speak_money, speech_language
from ..trading import TradingService
from .prompts import build_system_prompt
from .tools import ToolBox, ToolContext

log = logging.getLogger(__name__)

MAX_ROUNDS = 5
ACK_AFTER_S = 1.3           # nothing said yet after this long: say a quick acknowledgement
FIRST_EVENT_TIMEOUT_S = 7.0
NEXT_EVENT_TIMEOUT_S = 4.0
_SENTENCE_END = re.compile(r"[.!?।]\s*$|[.!?।]\s")
_DONE = object()
# Signs that the model is talking about the instructions instead of to the user.
_LEAK = re.compile(
    r"the user'?s? (latest|last|message|question|spoke|is speaking)|latest message|\bI must (answer|respond|translate|speak)\b|"
    r"\bas per the (language )?instruction|\bLet'?s see\b|the tool (returned|result)|internal localization",
    re.I,
)


class _Leaked(Exception):
    pass
_ACKS_EN = ["Sure! ", "One moment. ", "Let me check. ", "Okay! "]
_ACKS_HI = ["जी, एक सेकंड। ", "ज़रूर, देखती हूँ। ", "ठीक है, अभी देखती हूँ। "]
FAILURE_TEXT = "Sorry, I had trouble with that. Please try again."


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: str
    extra: dict | None = None  # provider extras that must be echoed back (Gemini 3 thought_signature)


@dataclass
class ChatEvent:
    text: str | None = None
    tool_calls: list[ToolCall] | None = None  # delivered once, at the end of a round


class ChatClient(Protocol):
    def stream(self, messages: list[dict], tools: list[dict]) -> AsyncIterator[ChatEvent]: ...


HEDGE_AFTER_S = 3.0  # free LLM tiers stall now and then; past this, race a second identical request
_END = object()


async def _hedged(make: Callable[[int], AsyncIterator[ChatEvent]], delay: float) -> AsyncIterator[ChatEvent]:
    """Run make(); if it has not produced its first event after `delay` seconds, start a second copy and use
    whichever answers first. The loser is cancelled. Errors surface only when no request is left."""
    tasks: dict[asyncio.Future, AsyncIterator[ChatEvent]] = {}

    launched = 0

    def launch() -> None:
        nonlocal launched
        gen = make(launched)  # copy number 0 goes to the primary model, number 1 to the backup
        launched += 1
        tasks[asyncio.ensure_future(gen.__anext__())] = gen

    launch()
    hedged = False
    winner: AsyncIterator[ChatEvent] | None = None
    first: Any = _END
    last_err: BaseException | None = None
    try:
        while winner is None:
            done, _ = await asyncio.wait(list(tasks), timeout=None if hedged else delay, return_when=asyncio.FIRST_COMPLETED)
            if not done:
                hedged = True
                launch()
                continue
            for t in done:
                gen = tasks.pop(t)
                exc = t.exception()
                if exc is None:
                    winner, first = gen, t.result()
                    break
                if isinstance(exc, StopAsyncIteration):
                    winner = gen
                    break
                last_err = exc
            if winner is None and not tasks:
                if not hedged and launched < 2:
                    hedged = True
                    launch()  # the primary failed fast (for example rate limited): go straight to the backup
                    continue
                raise last_err or RuntimeError("LLM request failed")
    finally:
        losers = list(tasks.items())
        for t, _g in losers:
            t.cancel()
        await asyncio.gather(*(t for t, _g in losers), return_exceptions=True)
        for _t, g in losers:
            try:
                await g.aclose()  # type: ignore[attr-defined]
            except Exception:
                pass
    try:
        if first is not _END:
            yield first
        async for ev in winner:
            yield ev
    finally:
        try:
            await winner.aclose()  # type: ignore[attr-defined]
        except Exception:
            pass


class OpenAIChat:
    """Any OpenAI-compatible chat endpoint with streaming and tool calling.

    One model. If a call has not started answering after a few seconds, the same request is sent once more and the first
    answer wins, so a rare stall never becomes dead air."""

    def __init__(self, s: Settings) -> None:
        from openai import AsyncOpenAI

        def backend(base_url: str, key: str, model: str):
            # Gemini 3 "thinks" by default, which adds seconds of silence on a voice call.
            extra: dict = {}
            if "generativelanguage.googleapis.com" in base_url or "gpt-oss" in model:
                extra["reasoning_effort"] = "low"
            elif model.startswith(("gpt-5", "o1", "o3", "o4")):
                extra["reasoning_effort"] = "minimal" if model.startswith("gpt-5") else "low"  # thinking time is dead air on a call
            return (AsyncOpenAI(base_url=base_url, api_key=key or "missing", timeout=s.llm_timeout_s, max_retries=0), model, extra)

        self._backends = [backend(s.llm_base_url, s.llm_api_key, s.llm_model)]  # exactly one model
        self._patience = s.llm_patience

    async def stream(self, messages: list[dict], tools: list[dict]) -> AsyncIterator[ChatEvent]:
        async for ev in _hedged(lambda n: self._stream_once(messages, tools, n), HEDGE_AFTER_S * self._patience):
            yield ev

    async def _stream_once(self, messages: list[dict], tools: list[dict], n: int = 0) -> AsyncIterator[ChatEvent]:
        client, model, extra = self._backends[n % len(self._backends)]
        limits: dict = {"max_completion_tokens": 400} if model.startswith(("gpt-5", "o1", "o3", "o4")) else {"temperature": 0.3, "max_tokens": 400}
        resp = await client.chat.completions.create(
            model=model, messages=messages, tools=tools or None, stream=True, **limits, **extra,
        )
        # Keyed by each call's own id, not `.index`: Gemini's OpenAI shim omits `index` when a turn makes two
        # tool calls, and the SDK then defaults it to 0 for both, welding unrelated calls into one garbled name.
        calls: dict[str, dict[str, Any]] = {}
        order: list[str] = []
        last_key: str | None = None
        async for chunk in resp:
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if delta.content:
                yield ChatEvent(text=delta.content)
            for tc in delta.tool_calls or []:
                key = tc.id or last_key or f"idx{tc.index}"
                last_key = key
                if key not in calls:
                    calls[key] = {"id": "", "name": "", "args": "", "extra": None}
                    order.append(key)
                slot = calls[key]
                extra = getattr(tc, "extra_content", None) or (tc.model_extra or {}).get("extra_content")
                if extra and not slot["extra"]:
                    slot["extra"] = extra
                if tc.id:
                    slot["id"] = tc.id
                if tc.function and tc.function.name:
                    slot["name"] += tc.function.name
                if tc.function and tc.function.arguments:
                    slot["args"] += tc.function.arguments
        if calls:
            yield ChatEvent(tool_calls=[ToolCall(calls[k]["id"] or f"call_{i}", calls[k]["name"], calls[k]["args"], calls[k]["extra"]) for i, k in enumerate(order)])


class Orchestrator:
    def __init__(self, chat: ChatClient, tools: ToolBox, risk: RiskEngine, trading: TradingService,
                 ledger: Ledger, portfolio: Portfolio, settings: Settings) -> None:
        self.chat, self.tools, self.risk, self.trading = chat, tools, risk, trading
        self.ledger, self.portfolio, self.settings = ledger, portfolio, settings

    def _wallets_line(self, user_id: str) -> str:
        return "; ".join(
            f"{c} cash {speak_money(self.ledger.cash(user_id, c), c)}" for c in ("INR", "USD")
        )

    async def _prefetch(self, ctx: ToolContext, text: str, has_preview: bool) -> str:
        from .prefetch import plan
        calls = plan(text, has_preview)
        if not calls:
            return ""
        try:
            results = await asyncio.wait_for(
                asyncio.gather(*(self.tools.call(name, json.dumps(args), ctx) for name, args in calls)), timeout=4.0)
        except Exception as e:  # never block the answer on a prefetch
            log.warning("prefetch skipped: %s", e)
            return ""
        return "\n".join(f"{name}({json.dumps(args)}) -> {res}" for (name, args), res in zip(calls, results))

    async def reply(self, session: Session, convo: list[dict], last_user_text: str) -> AsyncIterator[str]:
        """Yield the text to speak, in order. Never raises: failures become a spoken apology.

        If real work is needed (data, tools, a slow model) and nothing has been said after ACK_AFTER_S, Mira says a
        short "Sure, one moment" first: the voice takes a second or two to start, so this makes it start sooner."""
        hindi = False
        if self.settings.sarvam_api_key:
            from .prompts import reply_language
            hindi = reply_language(last_user_text, session.language) == "hindi"
            speech_language.set("hi" if hindi else "en")  # tasks created below inherit this
        queue: asyncio.Queue = asyncio.Queue()

        async def pump() -> None:
            try:
                async for piece in self._reply(session, convo, last_user_text):
                    await queue.put(piece)
            finally:
                await queue.put(_DONE)

        task = asyncio.ensure_future(pump())
        waiting_first = True
        try:
            while True:
                try:
                    item = await asyncio.wait_for(queue.get(), timeout=ACK_AFTER_S if waiting_first else None)
                except asyncio.TimeoutError:
                    waiting_first = False
                    yield random.choice(_ACKS_HI if hindi else _ACKS_EN)
                    continue
                if item is _DONE:
                    return
                waiting_first = False
                yield item
        finally:
            if not task.done():
                task.cancel()

    async def _reply(self, session: Session, convo: list[dict], last_user_text: str) -> AsyncIterator[str]:
        user_id = session.user_id
        ctx = ToolContext(user_id, session.id, last_user_text)
        system = build_system_prompt(
            self.risk.kill_switch(user_id), self.trading.active_preview(user_id), self._wallets_line(user_id),
            language=session.language if self.settings.sarvam_api_key else "en", user_text=last_user_text,
        )
        msgs: list[dict[str, Any]] = [{"role": "system", "content": system}, *convo]
        earlier = self.tools.recent_facts(session.id)
        if earlier:
            msgs[0]["content"] += (
                "\n\nFacts already looked up earlier in this conversation. Reuse them for follow-up questions and do the simple "
                "arithmetic yourself, or with calculate. Look prices up again if they are older than a minute; lot sizes, "
                "dates and company facts do not change:\n" + earlier)
        known = await self._prefetch(ctx, last_user_text, has_preview=self.trading.active_preview(user_id) is not None)
        if known:
            msgs[0]["content"] += (
                "\n\nLive data already fetched for the user's latest message. Answer from it directly and do not call "
                "these tools again:\n" + known
            )
        tool_schemas = self.tools.schemas(user_id)
        spoke = False
        try:
            for _ in range(MAX_ROUNDS):
                round_text: list[str] = []
                calls: list[ToolCall] | None = None
                for attempt in range(2):
                    # Hold the first words until a sentence is complete: if the model stalls before that, nothing
                    # half-spoken has gone out and the round can simply be tried again.
                    held, released = "", False
                    try:
                        async for ev in self._with_timeout(self.chat.stream(msgs, tool_schemas)):
                            if ev.text:
                                round_text.append(ev.text)
                                if released:
                                    spoke = True
                                    yield ev.text
                                else:
                                    held += ev.text
                                    if _SENTENCE_END.search(held) or len(held) > 90:
                                        if _LEAK.search(held):
                                            raise _Leaked()
                                        released, spoke = True, True
                                        yield held
                                        held = ""
                            if ev.tool_calls:
                                calls = ev.tool_calls
                        if held:
                            spoke = True
                            yield held
                        break
                    except (asyncio.TimeoutError, _Leaked, APIStatusError, APIConnectionError) as problem:
                        if released or attempt == 1:
                            if isinstance(problem, _Leaked):
                                log.warning("model leaked its reasoning twice; apologising instead")
                                if not spoke:
                                    yield FAILURE_TEXT
                                return
                            raise
                        reason = (
                            "leaked its reasoning" if isinstance(problem, _Leaked)
                            else f"errored ({problem})" if isinstance(problem, (APIStatusError, APIConnectionError))
                            else "stalled before its first sentence"
                        )
                        log.warning("model %s; retrying once", reason)
                        round_text.clear()
                        calls = None
                if not calls:
                    return
                msgs.append({
                    "role": "assistant", "content": "".join(round_text) or None,
                    "tool_calls": [
                        {"id": c.id, "type": "function", "function": {"name": c.name, "arguments": c.arguments},
                         **({"extra_content": c.extra} if c.extra else {})}
                        for c in calls
                    ],
                })
                results = await asyncio.gather(*(self.tools.call(c.name, c.arguments, ctx) for c in calls))
                for c, r in zip(calls, results):
                    msgs.append({"role": "tool", "tool_call_id": c.id, "content": r})
            yield " Sorry, that took too many steps. Could you say it again?"
        except asyncio.CancelledError:
            raise  # the user interrupted (barge-in): stop quietly
        except Exception as e:
            log.exception("orchestrator failed: %s", e)
            if not spoke:
                yield FAILURE_TEXT

    async def _with_timeout(self, stream: AsyncIterator[ChatEvent]) -> AsyncIterator[ChatEvent]:
        """Bound the wait for each event: a stalled free-tier model must not hold the line for 20 s."""
        it = stream.__aiter__()
        limit = min(self.settings.llm_timeout_s, FIRST_EVENT_TIMEOUT_S * self.settings.llm_patience)
        while True:
            try:
                yield await asyncio.wait_for(it.__anext__(), timeout=limit)
            except StopAsyncIteration:
                return
            limit = min(self.settings.llm_timeout_s, NEXT_EVENT_TIMEOUT_S * self.settings.llm_patience)
