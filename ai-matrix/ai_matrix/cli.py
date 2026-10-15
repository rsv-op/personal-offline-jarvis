"""Command line entry point: ``python -m ai_matrix.cli <command>``."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import List, Optional

from .dataset import load_dataset
from .export import (
    DEFAULT_ASSET_PATH,
    build_default_model,
    build_holdout_model,
    describe,
    export_model,
    load_model,
)


def _cmd_train(args: argparse.Namespace) -> int:
    model, report, holdout_accuracy = build_default_model(
        epochs=args.epochs, learning_rate=args.learning_rate, verbose=args.verbose
    )
    print(f"trained on {len(load_dataset())} utterances, {model.num_classes} intents")
    print(f"  epochs            : {report.epochs}")
    print(f"  final loss        : {report.final_loss:.6f}")
    print(f"  train accuracy    : {report.accuracy:.4f}")
    print(f"  holdout accuracy  : {holdout_accuracy:.4f}")
    if args.export:
        destination = Path(args.export)
        export_model(model, destination, holdout_accuracy)
        print(f"  exported          : {destination}")
    return 0


def _cmd_export(args: argparse.Namespace) -> int:
    model, _, holdout_accuracy = build_default_model(
        epochs=args.epochs, learning_rate=args.learning_rate
    )
    destination = Path(args.out) if args.out else DEFAULT_ASSET_PATH
    export_model(model, destination, holdout_accuracy)
    size_kb = destination.stat().st_size / 1024
    print(f"exported {destination} ({size_kb:.1f} KiB), holdout accuracy {holdout_accuracy:.4f}")
    return 0


def _cmd_evaluate(args: argparse.Namespace) -> int:
    """Score the *held-out* model, i.e. the one that has never seen these phrases."""

    model, train_pairs, test_pairs = build_holdout_model()
    print(f"trained on       : {len(train_pairs)} utterances")
    print(f"train accuracy   : {model.accuracy([t for t, _ in train_pairs], [l for _, l in train_pairs]):.4f}")
    print(f"holdout accuracy : {model.accuracy([t for t, _ in test_pairs], [l for _, l in test_pairs]):.4f}")
    print("holdout predictions:")
    for line in describe(model, [text for text, _ in test_pairs]):
        print(line)
    from .export import predict  # local import keeps CLI startup light

    mismatches = [
        (text, label, predict(model, text)[0])
        for text, label in test_pairs
        if predict(model, text)[0] != label
    ]
    if mismatches:
        print(f"{len(mismatches)} mismatches on held-out examples:")
        for text, expected_label, actual in mismatches:
            print(f"  {text!r}: expected {expected_label}, got {actual}")
        return 1
    print("all held-out examples routed correctly")
    return 0


def _cmd_predict(args: argparse.Namespace) -> int:
    model = load_model(Path(args.model)) if args.model else build_default_model()[0]
    probabilities = model.score_text(args.text)
    best = sorted(probabilities.items(), key=lambda item: item[1], reverse=True)
    print(json.dumps({label: round(value, 4) for label, value in best if value > 0.005}, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ai_matrix", description="JARVIS offline intent brain")
    subparsers = parser.add_subparsers(dest="command", required=True)

    train = subparsers.add_parser("train", help="train and print a report")
    train.add_argument("--epochs", type=int, default=400)
    train.add_argument("--learning-rate", type=float, default=0.08)
    train.add_argument("--export", type=str, default=None, help="also write the JSON artifact here")
    train.add_argument("--verbose", action="store_true")
    train.set_defaults(func=_cmd_train)

    export = subparsers.add_parser("export", help="train and write the Android asset JSON")
    export.add_argument("--out", type=str, default=None)
    export.add_argument("--epochs", type=int, default=400)
    export.add_argument("--learning-rate", type=float, default=0.08)
    export.set_defaults(func=_cmd_export)

    evaluate = subparsers.add_parser("evaluate", help="report held-out accuracy")
    evaluate.set_defaults(func=_cmd_evaluate)

    predict = subparsers.add_parser("predict", help="print intent probabilities for an utterance")
    predict.add_argument("text")
    predict.add_argument("--model", type=str, default=None, help="path to an exported JSON model")
    predict.set_defaults(func=_cmd_predict)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
