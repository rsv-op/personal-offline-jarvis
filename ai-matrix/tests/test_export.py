"""End-to-end export tests: what the Android app actually loads."""

from __future__ import annotations

import json
from pathlib import Path

from ai_matrix.dataset import INTENT_ACTIONS, load_dataset, split_dataset
from ai_matrix.export import (
    DEFAULT_ASSET_PATH,
    build_default_model,
    export_model,
    load_model,
    predict,
)


def test_export_round_trip_preserves_predictions(tmp_path: Path) -> None:
    model, _, holdout_accuracy = build_default_model()
    destination = export_model(model, tmp_path / "ai_matrix_model.json", holdout_accuracy)

    restored = load_model(destination)
    for text in ["turn on the torch", "switch the flashlight off", "set an alarm for six"]:
        assert restored.predict_text(text)[0] == model.predict_text(text)[0]


def test_exported_artifact_has_the_keys_the_kotlin_reader_expects(tmp_path: Path) -> None:
    model, _, holdout_accuracy = build_default_model()
    destination = export_model(model, tmp_path / "ai_matrix_model.json", holdout_accuracy)
    payload = json.loads(destination.read_text())

    for key in (
        "format",
        "formatVersion",
        "featureVersion",
        "buckets",
        "hiddenSize",
        "labels",
        "hiddenWeights",
        "hiddenBias",
        "outputWeights",
        "outputBias",
    ):
        assert key in payload, f"missing key {key}"
    assert payload["formatVersion"] == 2
    assert payload["labels"] == list(INTENT_ACTIONS.keys())
    assert len(payload["hiddenBias"]) == payload["hiddenSize"]
    assert len(payload["outputBias"]) == len(payload["labels"])
    assert len(payload["hiddenWeights"]) == payload["buckets"]
    assert payload["metadata"]["trainingExamples"] == len(load_dataset())
    assert payload["metadata"]["parameterCount"] > 0


def test_export_is_byte_stable_for_the_same_training_run(tmp_path: Path) -> None:
    """Re-exporting the same trained weights must not churn the committed asset."""

    model, _, holdout_accuracy = build_default_model()
    first = export_model(model, tmp_path / "first.json", holdout_accuracy).read_text()
    second = export_model(model, tmp_path / "second.json", holdout_accuracy).read_text()
    assert first == second


def test_shipped_asset_matches_a_fresh_training_run() -> None:
    """Guards the committed asset: it must be regenerable with this exact command."""

    assert DEFAULT_ASSET_PATH.is_file(), (
        "app/src/main/assets/ai_matrix_model.json is missing — run "
        "`python -m ai_matrix.cli export` from ai-matrix/"
    )
    committed = load_model(DEFAULT_ASSET_PATH)
    model, _, _ = build_default_model()
    for text, _ in load_dataset():
        assert committed.predict_text(text)[0] == model.predict_text(text)[0]


def test_shipped_asset_reaches_high_holdout_accuracy() -> None:
    """The artifact that ships to the phone is still accurate after rounding."""

    _, test_pairs = split_dataset()
    committed = load_model(DEFAULT_ASSET_PATH)
    accuracy = committed.accuracy(
        [text for text, _ in test_pairs], [label for _, label in test_pairs]
    )
    assert accuracy >= 0.9, f"shipped model holdout accuracy is only {accuracy:.3f}"


def test_shipped_asset_is_small_enough_to_bundle() -> None:
    assert DEFAULT_ASSET_PATH.stat().st_size < 2_000_000, "asset too large to ship in the APK"


def test_predict_helper_returns_unknown_label() -> None:
    model, _, _ = build_default_model()
    label, confidence = predict(model, "explain how a combustion engine works")
    assert label == "UNKNOWN"
    assert 0.0 < confidence <= 1.0
