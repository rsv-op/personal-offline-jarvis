"""Training pipeline and JSON export for the ai-matrix intent brain."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Tuple

from .dataset import INTENT_ACTIONS, load_dataset, split_dataset
from .model import UNKNOWN_LABEL, AiMatrixClassifier, TrainingReport

#: Default location of the artifact consumed by the Android app.
DEFAULT_ASSET_PATH = (
    Path(__file__).resolve().parents[2]
    / "app"
    / "src"
    / "main"
    / "assets"
    / "ai_matrix_model.json"
)

DEFAULT_EPOCHS = 300
DEFAULT_LEARNING_RATE = 0.05


def build_holdout_model(
    epochs: int = DEFAULT_EPOCHS,
    learning_rate: float = DEFAULT_LEARNING_RATE,
    verbose: bool = False,
) -> Tuple[AiMatrixClassifier, List[Tuple[str, str]], List[Tuple[str, str]]]:
    """Train only on the training split and return it with both splits.

    This is the honest way to measure how the brain behaves on phrasing it has
    never seen, which is exactly what matters for speech input.
    """

    train_pairs, test_pairs = split_dataset()
    model = AiMatrixClassifier(labels=INTENT_ACTIONS.keys())
    model.train(
        [text for text, _ in train_pairs],
        [label for _, label in train_pairs],
        epochs=epochs,
        learning_rate=learning_rate,
        verbose=verbose,
    )
    return model, train_pairs, test_pairs


def build_default_model(
    epochs: int = DEFAULT_EPOCHS,
    learning_rate: float = DEFAULT_LEARNING_RATE,
    verbose: bool = False,
) -> Tuple[AiMatrixClassifier, TrainingReport, float]:
    """Train on the full corpus; also report held-out accuracy."""

    pairs = load_dataset()
    texts = [text for text, _ in pairs]
    labels = [label for _, label in pairs]

    holdout_model, _, test_pairs = build_holdout_model(epochs, learning_rate, verbose)
    holdout_accuracy = holdout_model.accuracy(
        [text for text, _ in test_pairs], [label for _, label in test_pairs]
    )

    model = AiMatrixClassifier(labels=INTENT_ACTIONS.keys())
    report = model.train(texts, labels, epochs=epochs, learning_rate=learning_rate, verbose=verbose)
    return model, report, holdout_accuracy


def _metadata(model: AiMatrixClassifier, holdout_accuracy: float) -> Dict[str, object]:
    pairs = load_dataset()
    return {
        "intentActions": INTENT_ACTIONS,
        "trainingExamples": len(pairs),
        "hiddenSize": model.hidden_size,
        "parameterCount": model.parameter_count,
        "trainAccuracy": round(
            model.accuracy([text for text, _ in pairs], [label for _, label in pairs]), 4
        ),
        "holdoutAccuracy": round(holdout_accuracy, 4),
        "generator": "ai-matrix/ai_matrix/export.py",
    }


def export_model(model: AiMatrixClassifier, destination: Path, holdout_accuracy: float) -> Path:
    """Write the trained weights as JSON for the Android asset folder."""

    payload = model.export_payload(_metadata(model, holdout_accuracy))
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, separators=(",", ":"), sort_keys=True) + "\n")
    return destination


def load_model(path: Path) -> AiMatrixClassifier:
    """Load weights exported by :func:`export_model` (used by tests and tooling)."""

    raw = json.loads(Path(path).read_text())
    if raw.get("format") != "ai-matrix":
        raise ValueError(f"{path} is not an ai-matrix model file")
    return AiMatrixClassifier(
        labels=raw["labels"],
        buckets=int(raw["buckets"]),
        hidden_size=int(raw["hiddenSize"]),
        hidden_weights=raw["hiddenWeights"],
        hidden_bias=raw["hiddenBias"],
        output_weights=raw["outputWeights"],
        output_bias=raw["outputBias"],
    )


def predict(model: AiMatrixClassifier, text: str) -> Tuple[str, float]:
    """Convenience wrapper that keeps UNKNOWN handling in one place."""

    label, confidence = model.predict_text(text)
    return (UNKNOWN_LABEL if label == UNKNOWN_LABEL else label), confidence


def describe(model: AiMatrixClassifier, samples: List[str]) -> List[str]:
    """Human-readable per-sample explanation lines for the CLI."""

    lines = []
    for sample in samples:
        label, confidence = model.predict_text(sample)
        lines.append(f"  {sample!r:<58} -> {label:<14} ({confidence:.3f})")
    return lines
