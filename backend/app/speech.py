"""Say numbers the way people say them (rupees in lakh/crore, dollars in thousand/million), in English or in Hindi.

The language of the current turn lives in a context variable so every tool result and order summary is phrased in the
language Mira is about to answer in, without each tool having to know about it."""
from __future__ import annotations

import re
from contextvars import ContextVar
from decimal import ROUND_HALF_UP, Decimal

# "en" or "hi". Set once per turn by the orchestrator; tool tasks started in that turn inherit it.
speech_language: ContextVar[str] = ContextVar("speech_language", default="en")

_CRORE = Decimal("10000000")
_LAKH = Decimal("100000")
_THOUSAND = Decimal("1000")
_MILLION = Decimal("1000000")

_HI = (
    "शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस "
    "इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस चालीस "
    "इकतालीस बयालीस तैंतालीस चौवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास पचास "
    "इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ साठ "
    "इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर सत्तर "
    "इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उन्यासी अस्सी "
    "इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे "
    "इक्यानवे बानवे तिरानवे चौरानवे पंचानवे छियानवे सत्तानवे अट्ठानवे निन्यानवे"
).split()
assert len(_HI) == 100


def hindi_number(n: int) -> str:
    """0 to 99,99,99,999 in Hindi words: 1039 -> 'एक हज़ार उनतालीस', 1000000 -> 'दस लाख'."""
    if n < 0:
        return "माइनस " + hindi_number(-n)
    if n < 100:
        return _HI[n]
    parts: list[str] = []
    for size, word in ((10_000_000, "करोड़"), (100_000, "लाख"), (1_000, "हज़ार"), (100, "सौ")):
        if n >= size:
            q, n = divmod(n, size)
            parts.append(f"{hindi_number(q)} {word}")
    if n:
        parts.append(_HI[n])
    return " ".join(parts)


def _hi_decimal(value: Decimal, places: int) -> str:
    """12.5 -> 'बारह दशमलव पाँच' (digits read one by one after the point)."""
    q = value.quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP)
    whole, _, frac = format(q, "f").partition(".")
    frac = frac.rstrip("0")
    text = hindi_number(int(whole))
    return f"{text} दशमलव {' '.join(_HI[int(d)] for d in frac)}" if frac else text


def _trim(value: Decimal, places: int = 2) -> str:
    q = Decimal(1).scaleb(-places)
    return format(value.quantize(q, rounding=ROUND_HALF_UP).normalize(), "f")


def _speak_money_hi(amount: Decimal, currency: str) -> str:
    sign = "माइनस " if amount < 0 else ""
    a = abs(amount)
    if currency == "INR":
        if a >= _CRORE:
            body, unit = f"{_hi_decimal(a / _CRORE, 2)} करोड़", "रुपये"
        elif a >= _LAKH:
            body, unit = f"{_hi_decimal(a / _LAKH, 2)} लाख", "रुपये"
        elif a >= 1000:
            body, unit = hindi_number(int(a.quantize(Decimal(1), rounding=ROUND_HALF_UP))), "रुपये"
        else:
            rupees, paise = divmod(int((a * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP)), 100)
            body = hindi_number(rupees) + (f" रुपये {hindi_number(paise)} पैसे" if paise else " रुपये")
            return f"{sign}{body}"
        return f"{sign}{body} {unit}"
    unit = "डॉलर" if currency == "USD" else currency
    if a >= _MILLION:
        return f"{sign}{_hi_decimal(a / _MILLION, 2)} मिलियन {unit}"
    if a >= _THOUSAND:
        return f"{sign}{_hi_decimal(a / _THOUSAND, 2)} हज़ार {unit}"
    dollars, cents = divmod(int((a * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP)), 100)
    return f"{sign}{hindi_number(dollars)} {unit}" + (f" {hindi_number(cents)} सेंट" if cents else "")


def speak_money(amount: Decimal, currency: str) -> str:
    if speech_language.get() == "hi":
        return _speak_money_hi(amount, currency)
    sign = "minus " if amount < 0 else ""
    a = abs(amount)
    if currency == "INR":
        if a >= _CRORE:
            body = f"{_trim(a / _CRORE)} crore"
        elif a >= _LAKH:
            body = f"{_trim(a / _LAKH)} lakh"
        else:
            body = _trim(a, 0 if a >= 1000 else 2)
        unit = "rupees"
    else:
        if a >= _MILLION:
            body = f"{_trim(a / _MILLION)} million"
        elif a >= _THOUSAND:
            body = f"{_trim(a / _THOUSAND)} thousand"
        else:
            body = _trim(a)
        unit = "dollars" if currency == "USD" else currency
    return f"{sign}{body} {unit}"


def speak_big_money(value: float, currency: str) -> str:
    """Company sizes: '4.2 lakh crore rupees', '3.1 trillion dollars' (or the Hindi words)."""
    v = Decimal(str(value))
    hi = speech_language.get() == "hi"
    if currency == "INR":
        if v >= Decimal("1e12"):
            n, word_en, word_hi = v / Decimal("1e12"), "lakh crore", "लाख करोड़"
        elif v >= Decimal("1e7"):
            n, word_en, word_hi = v / Decimal("1e7"), "crore", "करोड़"
        else:
            return speak_money(v, "INR")
        return f"{_hi_decimal(n, 1)} {word_hi} रुपये" if hi else f"{_trim(n, 1)} {word_en} rupees"
    for size, en, hin in ((Decimal("1e12"), "trillion", "ट्रिलियन"), (Decimal("1e9"), "billion", "बिलियन"), (Decimal("1e6"), "million", "मिलियन")):
        if v >= size:
            return f"{_hi_decimal(v / size, 1)} {hin} डॉलर" if hi else f"{_trim(v / size, 1)} {en} dollars"
    return speak_money(v, currency)


def show_money(value: float, currency: str, places: int = 2) -> str:
    """Written form for cards: ₹1,04,000 with Indian digit grouping, $10,000 with western grouping."""
    sym = {"INR": "₹", "USD": "$"}.get(currency, currency + " ")
    text = f"{abs(value):,.{places}f}"
    if currency == "INR":
        whole, _, frac = f"{abs(value):.{places}f}".partition(".")
        head, tail = whole[:-3], whole[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        text = ",".join([*groups, tail]) + (f".{frac}" if frac else "")
    return ("-" if value < 0 else "") + sym + text


def show_big_money(value: float, currency: str) -> str:
    if currency == "INR":
        if value >= 1e12:
            return f"₹{value / 1e12:.2f} lakh crore"
        if value >= 1e7:
            return f"₹{value / 1e7:,.0f} crore"
    else:
        for size, unit in ((1e12, "T"), (1e9, "B"), (1e6, "M")):
            if value >= size:
                return f"${value / size:.2f}{unit}"
    return show_money(value, currency, 0)


def speak_percent(pct: Decimal) -> str:
    if speech_language.get() == "hi":
        if pct == 0:
            return "स्थिर"
        return f"{_hi_decimal(abs(pct), 1)} प्रतिशत {'ऊपर' if pct > 0 else 'नीचे'}"
    if pct == 0:
        return "flat"
    return f"{'up' if pct > 0 else 'down'} {_trim(abs(pct), 1)} percent"


# ---- speakable text: digits become words in the language of the sentence -----------------------------------

_EN_ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
            "seventeen eighteen nineteen").split()
_EN_TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def english_number(n: int) -> str:
    """1247 -> 'one thousand two hundred forty-seven'; 1000000 -> 'ten lakh'."""
    if n < 0:
        return "minus " + english_number(-n)
    if n < 20:
        return _EN_ONES[n]
    if n < 100:
        return _EN_TENS[n // 10] + (f"-{_EN_ONES[n % 10]}" if n % 10 else "")
    parts: list[str] = []
    for size, word in ((10_000_000, "crore"), (100_000, "lakh"), (1_000, "thousand"), (100, "hundred")):
        if n >= size:
            q, n = divmod(n, size)
            parts.append(f"{english_number(q)} {word}")
    if n:
        parts.append(english_number(n))
    return " ".join(parts)


_NUMBER = re.compile(r"([₹$])?(?<![\w.])(\d[\d,]*)(?:\.(\d+))?(%)?")


def speakify(text: str, hindi: bool | None = None) -> str:
    """Replace digits with words so the voice never reads them in the wrong language. The voice for a session is
    fixed (Hindi, which also reads English), and it says '1247' the Hindi way even inside an English sentence."""
    if hindi is None:
        hindi = any("ऀ" <= ch <= "ॿ" for ch in text)

    def repl(m: re.Match) -> str:
        symbol, whole, frac, pct = m.group(1), m.group(2), m.group(3), m.group(4)
        digits = whole.replace(",", "")
        if not digits:
            return m.group(0)
        n = int(digits)
        words = hindi_number(n) if hindi else english_number(n)
        if frac:
            point = "दशमलव" if hindi else "point"
            names = _HI if hindi else _EN_ONES
            words += f" {point} " + " ".join(names[int(d)] for d in frac[:4])
        if pct:
            words += " प्रतिशत" if hindi else " percent"
        if symbol == "₹":
            words += " रुपये" if hindi else " rupees"
        elif symbol == "$":
            words += " डॉलर" if hindi else " dollars"
        return words

    return _NUMBER.sub(repl, text)


class SpeechBuffer:
    """Streams text to the voice a word at a time, so a number split across chunks ('12' + '47') is converted whole."""

    def __init__(self, hindi: bool | None = None) -> None:
        self.hindi = hindi
        self._pending = ""

    def feed(self, piece: str) -> str:
        self._pending += piece
        cut = max(self._pending.rfind(" "), self._pending.rfind("\n"))
        if cut < 0:
            return ""
        out, self._pending = self._pending[: cut + 1], self._pending[cut + 1:]
        return speakify(out, self.hindi)

    def flush(self) -> str:
        out, self._pending = self._pending, ""
        return speakify(out, self.hindi) if out else ""
