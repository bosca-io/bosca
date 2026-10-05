"""Paired chronological evaluation of the former squared-error loss and current logit loss.

This local diagnostic never exports or promotes models. Both fits receive identical historical
inputs and training settings; future outcomes are used only by the evaluator to measure ranking.
"""

import argparse
import hashlib
import json
from pathlib import Path
from unittest.mock import patch

import tensorflow as tf
import tensorflow_recommenders as tfrs

from evaluate import evaluate_once, synthetic_history
from trainer import training
from trainer.models import BoscaRanker


class _SquaredErrorBaseline(BoscaRanker):
    """Reproduce the former objective only for the comparison fit."""

    def __init__(self, model):
        super().__init__(model)
        self.task = tfrs.tasks.Ranking(loss=tf.keras.losses.MeanSquaredError())


def compare_losses(output, fixture_seeds=(173, 353, 761), seeds=(11, 29, 47),
                   half_lives=(30, 365), epochs=25, learning_rate=.01):
    """Compare losses without selecting a winner or changing production configuration."""
    report = {
        "protocol": "Paired historical fits; future outcomes measure chronological discovery only",
        "limitations": ["Synthetic outcomes do not establish real-user uplift.",
                        "Fresh fixture seeds vary histories under the same generative assumptions.",
                        "This feed-only evaluation has no source or previous behavioral snapshot."],
        "runs": [],
    }
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    for fixture in fixture_seeds:
        for seed in seeds:
            for half_life in half_lives:
                bundle = synthetic_history(fixture)
                row = dict(fixture_seed=fixture, seed=seed, half_life=half_life,
                           input_sha256=hashlib.sha256(json.dumps(bundle, sort_keys=True).encode()).hexdigest(),
                           variants={})
                for name, ranker in (("squared", _SquaredErrorBaseline), ("logit", BoscaRanker)):
                    with patch.object(training, "BoscaRanker", ranker):
                        result = evaluate_once(bundle, seed=seed, half_life_days=half_life,
                                               epochs=epochs, learning_rate=learning_rate)
                    row["variants"][name] = result
                    print(json.dumps(dict(fixture=fixture, seed=seed, half_life=half_life, loss=name,
                                          ndcg={k: v["all"]["ndcg"] for k, v in result["metrics"].items()})), flush=True)
                report["runs"].append(row)
                output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fixture-seeds", type=int, nargs="+", default=[173, 353, 761])
    parser.add_argument("--seeds", type=int, nargs="+", default=[11, 29, 47])
    parser.add_argument("--half-lives", type=float, nargs="+", default=[30, 365])
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--learning-rate", type=float, default=.01)
    return compare_losses(**vars(parser.parse_args(argv)))


if __name__ == "__main__":
    main()
