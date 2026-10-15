# ai-matrix — the offline intent brain

`ai-matrix` is the NumPy half of JARVIS: a small neural network that turns a spoken
phrase into an intent, trained and tested on a laptop, then exported as JSON so the
phone can run the exact same forward pass with no runtime dependency and no internet.

```
ai-matrix/
├── ai_matrix/
│   ├── features.py   deterministic featurizer (the Python↔Kotlin contract)
│   ├── model.py      two-layer perceptron + hand-written Adam, in NumPy
│   ├── dataset.py    the teaching corpus (7 intents, 169 utterances)
│   ├── export.py     training pipeline + JSON export
│   ├── golden.py     regenerates tests/golden_features.json
│   └── cli.py        train / evaluate / export / predict
├── tests/            pytest suite (35 tests)
└── requirements.txt  numpy, pytest
```

## How it works

1. **Normalize** — lowercase, drop punctuation and filler words ("uh", "please", "hey").
2. **Featurize** — unigrams, adjacent bigrams, and word affixes (`p:flas`, `s:ight`),
   each hashed into one of 4096 buckets with the *Java* string hash, then L2-normalized.
   The affix features are what let the model generalise from "flashlight" to phrasings
   it has never heard.
3. **Classify** — `x -> dense(32) + ReLU -> dense(7) -> softmax`, 131,296 parameters,
   written by hand (no TensorFlow, no PyTorch). Training is full-batch Adam.
4. **Export** — weights are rounded to six decimals and written to
   `app/src/main/assets/ai_matrix_model.json` (≈655 KiB). The Kotlin runtime in
   `app/src/main/java/com/example/voiceassistant/aimatrix/` re-implements steps 1–3
   exactly; `tests/golden_features.json` freezes the bucket counts so the two
   implementations cannot silently drift apart.

## Measured quality

| Model | Held-out accuracy |
| --- | --- |
| Unigrams + bigrams, linear softmax | 0.786 |
| + affix features (`p:` / `s:`), linear softmax | 0.893 |
| + affix features, hidden layer of 32, **shipped** | **0.929** |
| Hidden layer of 64 / 96, or more buckets | 0.929 (no gain, 2–3× larger) |

The held-out split interleaves examples across each intent's phrasing range, so the
number reflects unseen wording rather than memorised text.

## Commands

```sh
cd ai-matrix
python -m pip install -r requirements.txt

python -m ai_matrix.cli train --verbose     # train and print the report
python -m ai_matrix.cli evaluate            # held-out accuracy + per-phrase predictions
python -m ai_matrix.cli predict "turn the torch on"
python -m ai_matrix.cli export              # rewrite the Android asset
python -m pytest tests -q                   # 35 tests, all offline
```

`python -m ai_matrix.golden` rewrites `tests/golden_features.json`; only run it when
the featurizer change is intentional, because the Kotlin port must match it.

## Why not a transformer / LLM?

Because this runs on a phone battery, offline, for a handful of intents. A 131k-parameter
perceptron answers in microseconds, ships in under a megabyte, and is small enough to
audit line by line — which matters when the same brain gates device actions. Open-ended
conversation is handled separately (see the root README) and never routes actions.
