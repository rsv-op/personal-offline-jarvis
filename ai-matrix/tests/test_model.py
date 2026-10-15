"""Training and inference tests for the hand-written NumPy network."""

from __future__ import annotations

import json

import numpy as np
import pytest

from ai_matrix.dataset import INTENT_ACTIONS, load_dataset, split_dataset
from ai_matrix.features import BUCKETS
from ai_matrix.model import (
    DEFAULT_HIDDEN_SIZE,
    UNKNOWN_LABEL,
    AiMatrixClassifier,
    relu,
    softmax,
)


def _trained_model() -> tuple[AiMatrixClassifier, float]:
    train_pairs, test_pairs = split_dataset()
    model = AiMatrixClassifier(labels=INTENT_ACTIONS.keys())
    model.train(
        [text for text, _ in train_pairs],
        [label for _, label in train_pairs],
        epochs=300,
    )
    accuracy = model.accuracy([text for text, _ in test_pairs], [label for _, label in test_pairs])
    return model, accuracy


def test_softmax_is_a_probability_distribution() -> None:
    probabilities = softmax(np.array([[1.0, 2.0, 3.0]]))
    assert np.isclose(float(probabilities.sum()), 1.0)
    assert np.all(probabilities > 0.0)


def test_softmax_is_numerically_stable_for_large_logits() -> None:
    probabilities = softmax(np.array([[1000.0, 1001.0]]))
    assert np.all(np.isfinite(probabilities))


def test_relu_zeroes_negative_activations() -> None:
    assert np.array_equal(relu(np.array([-1.0, 0.0, 2.0])), np.array([0.0, 0.0, 2.0]))


def test_training_reaches_high_holdout_accuracy() -> None:
    """Held-out utterances must route correctly — that is the whole point."""

    _, accuracy = _trained_model()
    assert accuracy >= 0.9, f"holdout accuracy regressed to {accuracy:.3f}"


def test_training_is_deterministic_for_a_fixed_seed() -> None:
    pairs = load_dataset()
    texts = [text for text, _ in pairs]
    labels = [label for _, label in pairs]

    first = AiMatrixClassifier(labels=INTENT_ACTIONS.keys(), seed=7)
    first.train(texts, labels, epochs=50)
    second = AiMatrixClassifier(labels=INTENT_ACTIONS.keys(), seed=7)
    second.train(texts, labels, epochs=50)

    assert np.array_equal(first.hidden_weights, second.hidden_weights)
    assert np.array_equal(first.output_weights, second.output_weights)
    assert np.array_equal(first.output_bias, second.output_bias)


def test_unseen_paraphrase_routes_to_the_right_intent() -> None:
    model, _ = _trained_model()
    assert model.predict_text("turn the torch on please")[0] == "FLASHLIGHT_ON"
    assert model.predict_text("switch off my flashlight")[0] == "FLASHLIGHT_OFF"
    assert model.predict_text("set an alarm for six am")[0] == "ALARM_SET"
    assert model.predict_text("what is the tallest mountain")[0] == UNKNOWN_LABEL


def test_confidence_is_a_probability() -> None:
    model, _ = _trained_model()
    label, confidence = model.predict_text("turn on the flashlight")
    assert label == "FLASHLIGHT_ON"
    assert 0.0 < confidence <= 1.0


def test_parameter_count_is_small_enough_to_ship() -> None:
    """The exported artifact must stay phone-sized (a few hundred KiB)."""

    model, _ = _trained_model()
    assert model.parameter_count < 200_000
    assert model.hidden_size == DEFAULT_HIDDEN_SIZE


def test_sparse_scoring_matches_dense_scoring() -> None:
    """The Kotlin runtime scores sparse `{bucket: count}` vectors; keep them equivalent."""

    model, _ = _trained_model()
    text = "jarvis could you turn the torch on"
    sparse = model.score_sparse(model.sparse_export(text))
    dense = model.score_text(text)
    for label in dense:
        assert abs(sparse[label] - dense[label]) < 1e-6


def test_score_text_covers_every_label() -> None:
    model, _ = _trained_model()
    scores = model.score_text("set a timer for five minutes")
    assert set(scores) == set(INTENT_ACTIONS)
    assert np.isclose(sum(scores.values()), 1.0, atol=1e-4)


def test_shape_validation_rejects_broken_payloads() -> None:
    with pytest.raises(ValueError):
        AiMatrixClassifier(
            labels=["A", "B"],
            hidden_weights=np.zeros((3, 8)),
            hidden_bias=np.zeros(8),
            output_weights=np.zeros((8, 2)),
            output_bias=np.zeros(2),
        )
    with pytest.raises(ValueError):
        AiMatrixClassifier(
            labels=["A", "B"],
            hidden_weights=np.zeros((BUCKETS, 8)),
            hidden_bias=np.zeros(8),
            output_weights=np.zeros((8, 3)),
            output_bias=np.zeros(2),
        )


def test_training_rejects_unknown_labels() -> None:
    model = AiMatrixClassifier(labels=["KNOWN"])
    with pytest.raises(ValueError):
        model.train(["hello"], ["NOT_A_LABEL"])


def test_to_dict_round_trip_preserves_predictions() -> None:
    model, _ = _trained_model()
    restored = AiMatrixClassifier.from_dict(model.to_dict())
    assert restored.predict_text("turn the torch off") == model.predict_text("turn the torch off")


def test_export_payload_is_json_serializable_and_compact() -> None:
    model, _ = _trained_model()
    payload = model.export_payload({"generator": "test"})
    serialized = json.dumps(payload, separators=(",", ":"))
    assert len(serialized) < 2_000_000
    assert payload["format"] == "ai-matrix"
    assert payload["buckets"] == BUCKETS
    assert len(payload["outputWeights"]) == DEFAULT_HIDDEN_SIZE
    assert payload["hiddenSize"] == DEFAULT_HIDDEN_SIZE
    assert payload["metadata"] == {"generator": "test"}
    for row in payload["hiddenWeights"]:
        assert len(row) == DEFAULT_HIDDEN_SIZE
    assert len(payload["hiddenWeights"]) == BUCKETS
    assert payload["formatVersion"] == 2
