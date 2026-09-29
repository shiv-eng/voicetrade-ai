"""General web search, for anything the market-data tools don't cover by name (a hot theme, recent news, a company
not in any pinned list). Uses DuckDuckGo's public HTML results page — no API key needed, the same "unofficial but
stable" approach yahoo.py already takes for the parts of Yahoo that also have no official API."""
from __future__ import annotations

import html
import re

import httpx

_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
_TITLE = re.compile(r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', re.S)
_SNIPPET = re.compile(r'class="result__snippet"[^>]*>(.*?)</a>', re.S)
_TAG = re.compile(r"<[^>]+>")


def _clean(fragment: str) -> str:
    return html.unescape(re.sub(r"\s+", " ", _TAG.sub("", fragment))).strip()


class WebSearchError(Exception):
    """The web search could not be reached or returned nothing usable."""


class WebSearch:
    def __init__(self, client: httpx.AsyncClient | None = None) -> None:
        self._client = client or httpx.AsyncClient(timeout=8.0, headers={"User-Agent": _UA})

    async def search(self, query: str, limit: int = 4) -> list[dict[str, str]]:
        try:
            resp = await self._client.post("https://html.duckduckgo.com/html/", data={"q": query})
            resp.raise_for_status()
        except httpx.HTTPError as e:
            raise WebSearchError(str(e)) from e
        titles = [_clean(m.group(2)) for m in _TITLE.finditer(resp.text)]
        snippets = [_clean(m.group(1)) for m in _SNIPPET.finditer(resp.text)]
        out = [{"title": t, "snippet": s} for t, s in zip(titles, snippets) if t][:limit]
        if not out:
            raise WebSearchError("no results")
        return out
