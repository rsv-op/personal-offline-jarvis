"""Deterministic text featurizer shared by the Python trainer and the Kotlin runtime.

Contract (must not change without bumping FEATURE_VERSION):

1. ``normalize_text`` lowercases, replaces every non ``[a-z0-9\\s]`` character with a
   space, deletes filler words/phrases, then collapses whitespace and trims.
2. ``tokens`` are the surviving unigrams, all adjacent bigrams, and word affixes
   (``p:`` + first four characters, ``s:`` + last four characters of words with at
   least four characters). The affix features are what let the model generalise
   from "flashlight" to "torch" phrasings it has never seen.
3. Each token is mapped to a bucket with the *Java* string hash (``h = 31 * h + c``
   with 32-bit signed overflow) so Kotlin's ``String.hashCode()`` agrees exactly.
   Because ``normalize_text`` strips every non-ASCII character, every token is
   ASCII and therefore hashes identically in Python and Kotlin.
4. The feature vector is term frequency, L2-normalized, with length ``BUCKETS``.
"""

from __future__ import annotations

import re
from typing import Dict, List

import numpy as np

#: Number of hashing buckets. Small enough to be a fast, tiny model on a phone.
BUCKETS = 4096

#: Bumped whenever the featurization contract above changes.
FEATURE_VERSION = 2

#: Prefix/suffix markers used for affix features.
AFFIX_MIN_LENGTH = 4

#: Filler words removed before featurization. Kept in sync with
#: com.example.voiceassistant.nlp.TextNormalizer so both stages see the same text.
FILLERS = (
    "uh",
    "um",
    "err",
    "like",
    "please",
    "can you",
    "could you",
    "would you",
    "just",
    "actually",
    "i mean",
    "hey",
    "ok",
    "okay",
)

_NON_ALNUM = re.compile(r"[^a-z0-9\s]")
_WHITESPACE = re.compile(r"\s+")
_FILLER_PATTERNS = tuple(
    (filler, re.compile(r"\b" + re.escape(filler) + r"\b")) for filler in FILLERS
)


def normalize_text(text: str) -> str:
    """Lowercase, strip punctuation, drop fillers, collapse whitespace."""

    clean = _NON_ALNUM.sub(" ", text.lower())
    for _, pattern in _FILLER_PATTERNS:
        clean = pattern.sub(" ", clean)
    return _WHITESPACE.sub(" ", clean).strip()


def tokens(text: str) -> List[str]:
    """Normalize ``text`` then return unigrams, bigrams, and affix features."""

    words = normalize_text(text).split()
    unigrams = list(words)
    bigrams = [f"{first}_{second}" for first, second in zip(words, words[1:])]
    affixes: List[str] = []
    for word in words:
        if len(word) >= AFFIX_MIN_LENGTH:
            affixes.append(f"p:{word[:AFFIX_MIN_LENGTH]}")
            affixes.append(f"s:{word[-AFFIX_MIN_LENGTH:]}")
    return unigrams + bigrams + affixes


def java_string_hash(token: str) -> int:
    """Reproduce ``java.lang.String.hashCode()`` including 32-bit overflow."""

    result = 0
    for character in token:
        result = (31 * result + ord(character)) & 0xFFFFFFFF
    if result >= 0x80000000:  # interpret as signed 32-bit
        result -= 0x100000000
    return result


def bucket_for(token: str, buckets: int = BUCKETS) -> int:
    """Map a token to a non-negative bucket index (mirrors Kotlin's floorMod)."""

    remainder = java_string_hash(token) % buckets
    return remainder + buckets if remainder < 0 else remainder


def term_frequencies(text: str, buckets: int = BUCKETS) -> Dict[int, float]:
    """Sparse term-frequency vector for ``text`` as ``{bucket: count}``."""

    counts: Dict[int, float] = {}
    for token in tokens(text):
        index = bucket_for(token, buckets)
        counts[index] = counts.get(index, 0.0) + 1.0
    return counts


def featurize(text: str, buckets: int = BUCKETS) -> np.ndarray:
    """L2-normalized dense feature vector of shape ``(buckets,)`` and dtype float32."""

    vector = np.zeros(buckets, dtype=np.float32)
    for index, count in term_frequencies(text, buckets).items():
        vector[index] = count
    norm = float(np.linalg.norm(vector))
    if norm > 0.0:
        vector /= norm
    return vector


def featurize_batch(texts: List[str], buckets: int = BUCKETS) -> np.ndarray:
    """Stack :func:`featurize` over ``texts`` into a ``(len(texts), buckets)`` matrix."""

    matrix = np.zeros((len(texts), buckets), dtype=np.float32)
    for row, text in enumerate(texts):
        matrix[row] = featurize(text, buckets)
    return matrix
