"""Code-level guard for `confirm_order`. It works even if the LLM is tricked or misled (PRD 9.3, TC-02/TC-09).

An utterance counts as a confirmation only if it is short, contains an explicit confirm word, and contains
neither a negation, a new order, nor anything that reads like an attempt to talk past the rules.
"""
from __future__ import annotations

import re

_CONFIRM = re.compile(
    r"\b(confirm(ed)?|yes|yeah|yep|yup|sure|go ahead|place it|place the order|do it|proceed|"
    r"haan|han|ha|kar do|kardo|karo|theek hai|thik hai|ok(ay)?)\b",
    re.I,
)
_NEGATE = re.compile(
    r"\b(no|not|don'?t|do not|nahi|nahin|na|cancel|stop|wait|hold|ruk|ruko|mat|reject|abort|never ?mind|instead|actually|change)\b",
    re.I,
)
_NEW_ORDER = re.compile(r"\b(buy|sell|kharid|kharidna|bech|becho|purchase)\b", re.I)
_TAMPER = re.compile(r"\b(ignore|rules?|instructions?|system|override|bypass|skip|without|previous|pretend|developer|admin)\b", re.I)
_MAX_WORDS = 8


def is_confirmation(utterance: str) -> bool:
    text = (utterance or "").strip()
    if not text or len(text.split()) > _MAX_WORDS:
        return False
    if _NEGATE.search(text) or _NEW_ORDER.search(text) or _TAMPER.search(text):
        return False
    return bool(_CONFIRM.search(text))
