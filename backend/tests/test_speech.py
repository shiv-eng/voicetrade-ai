
from app.speech import SpeechBuffer, english_number, hindi_number, speakify


def test_english_numbers_are_spelled_out():
    assert english_number(1247) == "one thousand two hundred forty-seven"
    assert english_number(1000000) == "ten lakh"
    assert english_number(0) == "zero"


def test_digits_never_reach_the_voice():
    assert speakify("Reliance is at 1,247.40 rupees, up 1.7%.", False) == \
        "Reliance is at one thousand two hundred forty-seven point four zero rupees, up one point seven percent."
    assert "1" not in speakify("₹1,247 और 12.5%", True) and "प्रतिशत" in speakify("12.5%", True)
    assert speakify("आज 1039 रुपये") == "आज " + hindi_number(1039) + " रुपये"  # language chosen by script


def test_a_number_split_across_chunks_is_still_converted_whole():
    buf = SpeechBuffer(False)
    out = "".join([buf.feed("It is at 12"), buf.feed("47 rupees"), buf.feed(" today."), buf.flush()])
    assert out == "It is at one thousand two hundred forty-seven rupees today."


def test_calculator_is_exact_and_safe():
    import pytest
    from app.calc import CalcError, evaluate
    assert evaluate("15 * 1,247.4") == 18711.0
    assert evaluate("(1400 - 1247.4) / 1247.4 * 100") == 12.2334
    assert evaluate("150 x 99") == 14850
    assert evaluate("200000 // 14850") == 13
    for bad in ("__import__('os')", "1/0", "abs(3)", "2**999"):
        with pytest.raises(CalcError):
            evaluate(bad)


def test_noise_only_turns_get_no_answer():
    from app.main import is_noise
    assert is_noise("") and is_noise("hmm") and is_noise("Uh, um.") and is_noise("a") and is_noise("...")
    assert not is_noise("yes") and not is_noise("ok") and not is_noise("buy ten shares")
