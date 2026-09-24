"""The prefetch shortcut: for plain read-only questions, run the likely tool before the model even starts,
so the answer needs one round trip instead of two. Wrong here means either a wasted API call (guessed a tool
that wasn't needed) or a slower reply (missed one that was) — worth testing directly."""
from __future__ import annotations

from app.llm.prefetch import plan


def test_a_plain_portfolio_question_prefetches_positions():
    assert plan("what's in my portfolio", False) == [("get_positions", {})]


def test_a_plain_balance_question_prefetches_account_summary():
    assert plan("what's my cash balance", False) == [("get_account_summary", {})]


def test_a_market_briefing_request_prefetches_the_briefing():
    assert plan("give me my morning briefing", False) == [("get_market_briefing", {})]
    assert plan("how's the market today", False) == [("get_market_briefing", {})]


def test_a_hindi_briefing_request_also_prefetches_the_briefing():
    assert plan("मार्केट का अपडेट दो", False) == [("get_market_briefing", {})]


def test_a_stock_price_question_prefetches_a_quote_not_an_overview():
    """Also covers a real bug found while writing this test: the apostrophe in 'what's' used to leave a
    stray lone 's' stuck to the extracted company name ('s Reliance' instead of 'Reliance')."""
    assert plan("what's the price of Reliance", False) == [("get_quote", {"company": "Reliance"})]


def test_a_general_stock_question_prefetches_an_overview():
    assert plan("how is Reliance doing", False) == [("get_company_overview", {"company": "Reliance"})]


def test_a_number_in_the_question_is_never_prefetched():
    """Could be a sum or a trade ('buy 500 of it') — ambiguous enough that only the model should decide."""
    assert plan("what's 10 percent of my portfolio", False) == []


def test_buying_or_selling_is_never_prefetched():
    assert plan("buy 10 shares of Reliance", False) == []
    assert plan("sell my Infosys", False) == []


def test_a_yes_or_no_reply_to_an_active_preview_is_never_prefetched():
    """A bare 'yes' during an open order preview must reach the model, never get short-circuited by prefetch."""
    assert plan("yes", True) == []
    assert plan("no", True) == []


def test_tools_with_their_own_disambiguation_are_left_to_the_model():
    for text in ["show me a chart", "any IPOs open", "what's the news on Reliance", "set an alert", "calculate 5 times 200"]:
        assert plan(text, False) == []
