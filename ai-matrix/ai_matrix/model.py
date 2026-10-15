"""The ai-matrix classifier: a hand-written neural network in NumPy.

Architecture (a two-layer perceptron, trained from scratch — no framework):

    x  (4096 hashed features, L2-normalized)
      -> dense (hidden, e.g. 32) + ReLU        [W1, b1]
      -> dense (num_intents)                   [W2, b2]
      -> softmax

This is deliberately small: the whole model is under a megabyte of JSON, a forward
pass touches only the nonzero features, and the Kotlin port in
``app/src/main/java/com/example/voiceassistant/aimatrix`` is a few dozen lines.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .features import BUCKETS, FEATURE_VERSION, featurize, featurize_batch, term_frequencies

#: Label used when an utterance matches no taught intent.
UNKNOWN_LABEL = "UNKNOWN"

#: Default hidden width. Larger widths fit the corpus better but generalise no
#: better on held-out utterances (see ai-matrix/README.md), so keep it tiny.
DEFAULT_HIDDEN_SIZE = 32


def softmax(logits: np.ndarray) -> np.ndarray:
    """Numerically stable softmax over the last axis."""

    shifted = logits - np.max(logits, axis=-1, keepdims=True)
    exponentiated = np.exp(shifted)
    return exponentiated / np.sum(exponentiated, axis=-1, keepdims=True)


def relu(values: np.ndarray) -> np.ndarray:
    return np.maximum(values, 0.0)


@dataclass
class TrainingReport:
    """Bookkeeping returned by :meth:`AiMatrixClassifier.train`."""

    epochs: int
    final_loss: float
    accuracy: float
    loss_history: List[float] = field(default_factory=list)

    def as_dict(self) -> Dict[str, object]:
        return {
            "epochs": self.epochs,
            "final_loss": round(self.final_loss, 6),
            "accuracy": round(self.accuracy, 4),
        }


class AiMatrixClassifier:
    """Hashed bag-of-words features feeding a hand-written MLP."""

    def __init__(
        self,
        labels: Sequence[str] = (UNKNOWN_LABEL,),
        buckets: int = BUCKETS,
        hidden_size: int = DEFAULT_HIDDEN_SIZE,
        seed: int = 7,
        hidden_weights: Optional[np.ndarray] = None,
        hidden_bias: Optional[np.ndarray] = None,
        output_weights: Optional[np.ndarray] = None,
        output_bias: Optional[np.ndarray] = None,
    ) -> None:
        self.labels: List[str] = list(labels)
        self.buckets = int(buckets)
        self.hidden_size = int(hidden_size)
        self.seed = int(seed)
        rng = np.random.default_rng(self.seed)
        self.hidden_weights = _or_default(
            hidden_weights,
            (rng.standard_normal((self.buckets, self.hidden_size)) * 0.02).astype(np.float32),
        )
        self.hidden_bias = _or_default(hidden_bias, np.zeros(self.hidden_size, dtype=np.float32))
        self.output_weights = _or_default(
            output_weights,
            (rng.standard_normal((self.hidden_size, len(self.labels))) * 0.08).astype(np.float32),
        )
        self.output_bias = _or_default(output_bias, np.zeros(len(self.labels), dtype=np.float32))
        self._validate_shapes()

    # ------------------------------------------------------------------ shapes

    def _validate_shapes(self) -> None:
        expectations = {
            "hidden_weights": (self.buckets, self.hidden_size),
            "hidden_bias": (self.hidden_size,),
            "output_weights": (self.hidden_size, len(self.labels)),
            "output_bias": (len(self.labels),),
        }
        for name, expected in expectations.items():
            actual = getattr(self, name).shape
            if actual != expected:
                raise ValueError(f"{name} must have shape {expected}, got {actual}")

    @property
    def num_classes(self) -> int:
        return len(self.labels)

    @property
    def parameter_count(self) -> int:
        return (
            self.hidden_weights.size
            + self.hidden_bias.size
            + self.output_weights.size
            + self.output_bias.size
        )

    # -------------------------------------------------------------- inference

    def hidden_activations(self, features: np.ndarray) -> np.ndarray:
        matrix = np.atleast_2d(np.asarray(features, dtype=np.float32))
        return relu(matrix @ self.hidden_weights + self.hidden_bias)

    def logits(self, features: np.ndarray) -> np.ndarray:
        hidden = self.hidden_activations(features)
        return hidden @ self.output_weights + self.output_bias

    def predict_proba(self, features: np.ndarray) -> np.ndarray:
        return softmax(self.logits(features))

    def predict_text(self, text: str) -> Tuple[str, float]:
        """Return ``(label, confidence)`` for a raw utterance."""

        probabilities = self.predict_proba(featurize(text, self.buckets))[0]
        index = int(np.argmax(probabilities))
        return self.labels[index], float(probabilities[index])

    def predict_many(self, texts: Sequence[str]) -> List[Tuple[str, float]]:
        if not texts:
            return []
        probabilities = self.predict_proba(featurize_batch(list(texts), self.buckets))
        indices = np.argmax(probabilities, axis=1)
        return [
            (self.labels[int(index)], float(probabilities[row, int(index)]))
            for row, index in enumerate(indices)
        ]

    def score_text(self, text: str) -> Dict[str, float]:
        """Full probability distribution for one utterance (useful for debugging)."""

        probabilities = self.predict_proba(featurize(text, self.buckets))[0]
        return {label: float(value) for label, value in zip(self.labels, probabilities)}

    def score_sparse(self, counts: Dict[int, float]) -> Dict[str, float]:
        """Score an already-featurized ``{bucket: count}`` vector (used by parity tests)."""

        vector = np.zeros(self.buckets, dtype=np.float32)
        for index, count in counts.items():
            vector[int(index)] = float(count)
        norm = float(np.linalg.norm(vector))
        if norm > 0.0:
            vector /= norm
        probabilities = self.predict_proba(vector)[0]
        return {label: float(value) for label, value in zip(self.labels, probabilities)}

    # --------------------------------------------------------------- training

    def train(
        self,
        texts: Sequence[str],
        labels: Sequence[str],
        epochs: int = 300,
        learning_rate: float = 0.05,
        l2: float = 1e-4,
        beta1: float = 0.9,
        beta2: float = 0.999,
        epsilon: float = 1e-8,
        verbose: bool = False,
    ) -> TrainingReport:
        """Train with full-batch hand-written Adam. Deterministic for a fixed seed."""

        if len(texts) != len(labels):
            raise ValueError("texts and labels must have the same length")
        if not texts:
            raise ValueError("cannot train on an empty dataset")

        label_to_index = {label: index for index, label in enumerate(self.labels)}
        unknown = sorted({label for label in labels if label not in label_to_index})
        if unknown:
            raise ValueError(f"labels not registered on the classifier: {unknown}")

        features = featurize_batch(list(texts), self.buckets)
        targets = np.zeros((len(texts), self.num_classes), dtype=np.float32)
        for row, label in enumerate(labels):
            targets[row, label_to_index[label]] = 1.0

        parameters = [
            self.hidden_weights,
            self.hidden_bias,
            self.output_weights,
            self.output_bias,
        ]
        moments = [np.zeros_like(parameter) for parameter in parameters]
        velocities = [np.zeros_like(parameter) for parameter in parameters]

        history: List[float] = []
        for step in range(1, epochs + 1):
            hidden = relu(features @ self.hidden_weights + self.hidden_bias)
            probabilities = softmax(hidden @ self.output_weights + self.output_bias)

            loss = self._cross_entropy(probabilities, targets)
            loss += 0.5 * l2 * (
                float(np.sum(self.hidden_weights * self.hidden_weights))
                + float(np.sum(self.output_weights * self.output_weights))
            )
            history.append(loss)

            difference = (probabilities - targets) / len(texts)
            output_gradient = hidden.T @ difference + l2 * self.output_weights
            output_bias_gradient = difference.sum(axis=0)
            hidden_gradient = (difference @ self.output_weights.T) * (hidden > 0.0)
            hidden_weight_gradient = features.T @ hidden_gradient + l2 * self.hidden_weights
            hidden_bias_gradient = hidden_gradient.sum(axis=0)

            gradients = [
                hidden_weight_gradient,
                hidden_bias_gradient,
                output_gradient,
                output_bias_gradient,
            ]
            for index, (parameter, gradient) in enumerate(zip(parameters, gradients)):
                parameter -= learning_rate * _adam(
                    gradient,
                    moments[index],
                    velocities[index],
                    step,
                    beta1,
                    beta2,
                    epsilon,
                )

            if verbose and step % 50 == 0:
                print(f"step {step:5d}  loss {loss:.5f}")

        accuracy = self.accuracy(texts, labels)
        return TrainingReport(
            epochs=epochs, final_loss=history[-1], accuracy=accuracy, loss_history=history
        )

    @staticmethod
    def _cross_entropy(probabilities: np.ndarray, targets: np.ndarray) -> float:
        clipped = np.clip(probabilities, 1e-12, 1.0)
        return float(-np.sum(targets * np.log(clipped)) / len(targets))

    def accuracy(self, texts: Sequence[str], labels: Sequence[str]) -> float:
        if not texts:
            return 0.0
        predictions = self.predict_many(texts)
        correct = sum(1 for (label, _), expected in zip(predictions, labels) if label == expected)
        return correct / len(labels)

    # ------------------------------------------------- serialization helpers

    def to_dict(self) -> Dict[str, object]:
        """Round-trippable plain-Python representation (used by export.py)."""

        return {
            "labels": list(self.labels),
            "buckets": self.buckets,
            "hiddenSize": self.hidden_size,
            "seed": self.seed,
            "hiddenWeights": self.hidden_weights.tolist(),
            "hiddenBias": self.hidden_bias.tolist(),
            "outputWeights": self.output_weights.tolist(),
            "outputBias": self.output_bias.tolist(),
        }

    @classmethod
    def from_dict(cls, payload: Dict[str, object]) -> "AiMatrixClassifier":
        return cls(
            labels=list(payload["labels"]),  # type: ignore[arg-type]
            buckets=int(payload["buckets"]),  # type: ignore[arg-type]
            hidden_size=int(payload.get("hiddenSize", DEFAULT_HIDDEN_SIZE)),  # type: ignore[arg-type]
            seed=int(payload.get("seed", 7)),  # type: ignore[arg-type]
            hidden_weights=np.asarray(payload["hiddenWeights"], dtype=np.float32),
            hidden_bias=np.asarray(payload["hiddenBias"], dtype=np.float32),
            output_weights=np.asarray(payload["outputWeights"], dtype=np.float32),
            output_bias=np.asarray(payload["outputBias"], dtype=np.float32),
        )

    def export_payload(self, metadata: Optional[Dict[str, object]] = None) -> Dict[str, object]:
        """JSON-safe payload consumed by the Android runtime."""

        payload: Dict[str, object] = {
            "format": "ai-matrix",
            "formatVersion": 2,
            "featureVersion": FEATURE_VERSION,
            "buckets": self.buckets,
            "hiddenSize": self.hidden_size,
            "labels": list(self.labels),
            "hiddenBias": [_round(value) for value in self.hidden_bias.tolist()],
            "outputBias": [_round(value) for value in self.output_bias.tolist()],
            "outputWeights": [
                [_round(value) for value in row] for row in self.output_weights.tolist()
            ],
            "hiddenWeights": [
                [_round(value) for value in row] for row in self.hidden_weights.tolist()
            ],
        }
        if metadata:
            payload["metadata"] = metadata
        return payload

    def sparse_export(self, text: str) -> Dict[int, float]:
        """Featurize ``text`` for the Kotlin parity tests."""

        return term_frequencies(text, self.buckets)


def _round(value: float, digits: int = 6) -> float:
    """Compact, stable serialization: 6 decimals is far below model noise."""

    rounded = round(float(value), digits)
    return 0.0 if rounded == 0.0 else rounded


def _adam(
    gradient: np.ndarray,
    moment1: np.ndarray,
    moment2: np.ndarray,
    step: int,
    beta1: float,
    beta2: float,
    epsilon: float,
) -> np.ndarray:
    """In-place Adam update; returns the normalized step for ``gradient``."""

    moment1 *= beta1
    moment1 += (1.0 - beta1) * gradient
    moment2 *= beta2
    moment2 += (1.0 - beta2) * np.square(gradient)
    corrected1 = moment1 / (1.0 - beta1**step)
    corrected2 = moment2 / (1.0 - beta2**step)
    return corrected1 / (np.sqrt(corrected2) + epsilon)


def _or_default(value: Optional[np.ndarray], default: np.ndarray) -> np.ndarray:
    return np.asarray(value, dtype=np.float32) if value is not None else default
