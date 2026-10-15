"""Regenerates tests/golden_features.json, the frozen featurization snapshot.

Run from the repository root:

    python -m ai_matrix.golden          # from inside ai-matrix/
    python ai-matrix/ai_matrix/golden.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from .features import BUCKETS, FEATURE_VERSION, term_frequencies

GOLDEN_CASES = [
    "turn on the flashlight",
    "turn off the torch",
    "set an alarm for seven thirty am",
    "what is the capital of france",
    "jarvis could you please set a timer for five minutes",
    "delete my notes file",
    "",
]


def build_payload() -> dict:
    return {
        "featureVersion": FEATURE_VERSION,
        "buckets": BUCKETS,
        "note": (
            "Frozen featurization snapshot. The Kotlin runtime in "
            "app/src/main/java/com/example/voiceassistant/aimatrix/AiMatrixFeaturizer.kt "
            "must produce these exact bucket counts."
        ),
        "cases": {text: term_frequencies(text) for text in GOLDEN_CASES},
    }


def main() -> int:
    destination = Path(__file__).resolve().parents[1] / "tests" / "golden_features.json"
    destination.write_text(json.dumps(build_payload(), indent=2, sort_keys=True) + "\n")
    print(f"wrote {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
