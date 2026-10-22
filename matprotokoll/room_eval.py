"""Utvärdera rumsnummer mot facit (recall/precision)."""

from __future__ import annotations

from collections.abc import Iterable


def canon_number(num: str) -> str:
    return (num or "").strip().lower()


def score_room_numbers(found: Iterable[str], expected: Iterable[str]) -> dict:
    exp = {canon_number(n) for n in expected if canon_number(n)}
    got = {canon_number(n) for n in found if canon_number(n)}
    hits = sorted(exp & got)
    misses = sorted(exp - got)
    false_pos = sorted(got - exp)
    recall = (len(hits) / len(exp)) if exp else 0.0
    precision = (len(hits) / len(got)) if got else 0.0
    return {
        "expected": len(exp),
        "found": len(got),
        "hits": hits,
        "misses": misses,
        "false_positives": false_pos,
        "n_hits": len(hits),
        "n_misses": len(misses),
        "n_false_positives": len(false_pos),
        "recall": recall,
        "precision": precision,
    }


def score_name_labels(found: Iterable[str], expected: Iterable[str]) -> dict:
    exp = {canon_number(n).split()[0] for n in expected if canon_number(n)}
    got = {canon_number(n).split()[0] for n in found if canon_number(n)}
    hits = sorted(exp & got)
    misses = sorted(exp - got)
    false_pos = sorted(got - exp)
    return {
        "hits": hits,
        "misses": misses,
        "false_positives": false_pos,
        "recall": (len(hits) / len(exp)) if exp else 0.0,
    }
