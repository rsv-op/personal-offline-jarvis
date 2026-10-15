"""Featurization tests: the Python side of the Python/Kotlin contract."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from ai_matrix.features import (
    BUCKETS,
    bucket_for,
    featurize,
    java_string_hash,
    normalize_text,
    term_frequencies,
    tokens,
)

GOLDEN_PATH = Path(__file__).parent / "golden_features.json"


@pytest.mark.parametrize(
    ("token", "expected"),
    [
        ("", 0),
        ("a", 97),
        ("ab", 3105),
        ("hello", 99162322),
        ("Hello", 69609650),
        ("flashlight", -1183073498),
    ],
)
def test_java_string_hash_matches_java(token: str, expected: int) -> None:
    """Kotlin's String.hashCode() must agree exactly, including 32-bit overflow."""

    assert java_string_hash(token) == expected


def test_golden_hash_survives_overflow() -> None:
    """A long token overflows a 32-bit int; the result must stay signed 32-bit."""

    value = java_string_hash("turnontheflashlightrightnow")
    assert -(2**31) <= value < 2**31


def test_normalize_strips_punctuation_case_and_fillers() -> None:
    assert normalize_text("Uh, please turn ON the flashlight!") == "turn on the flashlight"
    assert normalize_text("Hey JARVIS... could you set an alarm?") == "jarvis set an alarm"
    assert normalize_text("   ") == ""


def test_tokens_include_unigrams_bigrams_and_affixes() -> None:
    assert tokens("turn on flashlight") == [
        "turn",
        "on",
        "flashlight",
        "turn_on",
        "on_flashlight",
        "p:turn",
        "s:turn",
        "p:flas",
        "s:ight",
    ]
    # Words shorter than the affix threshold contribute no affix features.
    assert "p:on" not in tokens("turn on flashlight")


def test_buckets_are_always_in_range() -> None:
    for token in ["a", "zzzz", "jarvis", "unlikely-word", "x" * 40]:
        assert 0 <= bucket_for(token) < BUCKETS


def test_feature_vectors_are_normalized_and_sparse() -> None:
    vector = featurize("turn on the flashlight")
    assert vector.shape == (BUCKETS,)
    assert vector.dtype == np.float32
    assert np.isclose(float(np.linalg.norm(vector)), 1.0, atol=1e-6)
    assert int(np.count_nonzero(vector)) == len(term_frequencies("turn on the flashlight"))


def test_empty_text_yields_zero_vector() -> None:
    assert float(np.linalg.norm(featurize("  ...  "))) == 0.0


def test_featurization_is_deterministic() -> None:
    text = "Jarvis, could you please turn the torch on?"
    assert np.array_equal(featurize(text), featurize(text))


def test_golden_features_match_committed_snapshot() -> None:
    """Locks the contract the Kotlin runtime re-implements.

    Regenerate with: python -m ai_matrix.golden (see tests/golden_features.json).
    """

    golden = json.loads(GOLDEN_PATH.read_text())
    for text, expected in golden["cases"].items():
        assert {str(bucket): float(count) for bucket, count in term_frequencies(text).items()} == {
            str(bucket): float(count) for bucket, count in expected.items()
        }, f"featurization drift for {text!r}"
