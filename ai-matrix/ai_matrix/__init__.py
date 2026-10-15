"""ai-matrix — the offline intent brain for JARVIS.

This package trains a small, hand-written NumPy neural network that classifies
spoken utterances into JARVIS intents, then exports the weights as plain JSON so
the exact same forward pass can run on the Android device with no runtime
dependencies (no TensorFlow, no PyTorch, no internet).

The package is deliberately tiny and dependency-free apart from NumPy:

    features.py  shared featurizer (hashing bag-of-words); the Kotlin port in
                 app/src/main/java/com/example/voiceassistant/aimatrix/ must
                 produce bit-identical buckets.
    model.py     softmax classifier with Adam training written by hand.
    dataset.py   the in-repo teaching corpus.
    export.py    JSON export/import of the trained weights.
    cli.py       `python -m ai_matrix.cli` — train, evaluate, export.
"""

from .features import BUCKETS, featurize, normalize_text, tokens
from .model import AiMatrixClassifier

__all__ = [
    "BUCKETS",
    "AiMatrixClassifier",
    "featurize",
    "normalize_text",
    "tokens",
]

__version__ = "1.0.0"
