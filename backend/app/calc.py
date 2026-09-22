"""A small, safe calculator for the assistant: arithmetic only, no names, no calls."""
from __future__ import annotations

import ast
import operator
import re

_OPS = {
    ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
    ast.Pow: operator.pow, ast.Mod: operator.mod, ast.FloorDiv: operator.floordiv,
}


class CalcError(Exception):
    pass


def _eval(node: ast.AST) -> float:
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) and not isinstance(node.value, bool):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
        left, right = _eval(node.left), _eval(node.right)
        if isinstance(node.op, ast.Pow) and (abs(right) > 10 or abs(left) > 1e9):
            raise CalcError("That power is too large.")
        return _OPS[type(node.op)](left, right)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        value = _eval(node.operand)
        return -value if isinstance(node.op, ast.USub) else value
    raise CalcError("I can only do plain arithmetic.")


def evaluate(expression: str) -> float | int:
    """'15 * 1,247.4' -> 18711.0. Commas, rupee and dollar signs, x for times and percent signs are tolerated."""
    text = expression.replace(",", "").replace("₹", "").replace("$", "").replace("×", "*").replace("÷", "/")
    text = re.sub(r"(?<=\d)\s*[xX]\s*(?=[\d(])", "*", text)
    text = re.sub(r"(\d+(?:\.\d+)?)\s*%", r"(\1/100)", text)
    if len(text) > 200:
        raise CalcError("That expression is too long.")
    try:
        value = _eval(ast.parse(text.strip(), mode="eval").body)
    except CalcError:
        raise
    except (SyntaxError, ZeroDivisionError, OverflowError, ValueError) as e:
        raise CalcError(f"I couldn't work that out ({type(e).__name__}).") from e
    return round(value, 4) if isinstance(value, float) else value
