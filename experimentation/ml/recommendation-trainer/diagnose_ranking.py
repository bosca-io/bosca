#!/usr/bin/env python3
"""Controlled local diagnosis of reranking on synthetic chronological histories.

Retrieval embeddings and candidate pools remain fixed while heads are refitted. Future outcomes are
used only to report metrics, never to construct head inputs, weights, exposures, or stopping rules.
These probes do not change production models, training policy, or promotion.
"""

import argparse
import json
import random
from pathlib import Path

import numpy as np

from evaluate import _ordered, _targets, ranking_metrics, split_history, synthetic_history
from trainer.datasets import prepare_datasets
from trainer.models import BoscaRanker
from trainer.training import _fit_to_convergence, train_model
import tensorflow as tf


def _seed(seed):
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)


def weighted_mse(labels, predictions, weights):
    """Weight-normalized error, unlike Keras's batch-size-normalized training loss."""
    return float(np.average((np.asarray(labels) - np.asarray(predictions)) ** 2, weights=weights))


def compare_orderings(ids, retrieval, ranking, history, truth, observed, k=5, pool_size=16):
    """Attribute ranking changes inside identical discovery pools; retain a few auditable examples."""
    per_user = []
    scores = {key: [] for key in ["future_positive", "observed_without_future_positive", "unobserved_without_future_positive"]}
    introduced = unsupported = 0
    for user in range(len(retrieval)):
        candidates = {ids[i] for i in np.flatnonzero(~history[user])}
        relevant = {ids[i] for i in np.flatnonzero(truth[user] & ~history[user])}
        if not relevant:
            continue
        pool = _ordered(ids, retrieval[user], candidates, pool_size)
        reranked = _ordered(ids, ranking[user], {ids[i] for i in pool}, pool_size)
        before = ranking_metrics([ids[i] for i in pool[:k]], relevant, k)["ndcg"]
        after = ranking_metrics([ids[i] for i in reranked[:k]], relevant, k)["ndcg"]
        for item in pool:
            group = ("future_positive" if ids[item] in relevant else "observed_without_future_positive"
                     if observed[user, item] else "unobserved_without_future_positive")
            scores[group].append(float(ranking[user, item]))
        for item in set(reranked[:k]) - set(pool[:k]):
            if ids[item] not in relevant:
                introduced += 1
                unsupported += int(not observed[user, item])
        per_user.append(dict(user_index=user, retrieval_ndcg=before, reranked_ndcg=after, delta=after - before,
                             top_items=[dict(item=ids[i], future_positive=ids[i] in relevant,
                                             observed=bool(observed[user, i]), retrieval_score=float(retrieval[user, i]),
                                             ranking_score=float(ranking[user, i])) for i in reranked[:k]]))
    if not per_user:
        raise ValueError("Diagnosis requires evaluable future positives")
    summaries = {}
    for group, values in scores.items():
        if values:
            summaries[group] = dict(count=len(values), mean=float(np.mean(values)), p95=float(np.percentile(values, 95)),
                                   positive_logit_fraction=float(np.mean(np.array(values) > 0)))
    return dict(retrieval_ndcg=float(np.mean([row["retrieval_ndcg"] for row in per_user])),
                reranked_ndcg=float(np.mean([row["reranked_ndcg"] for row in per_user])),
                improved=sum(row["delta"] > 1e-9 for row in per_user),
                worsened=sum(row["delta"] < -1e-9 for row in per_user),
                unchanged=sum(abs(row["delta"]) <= 1e-9 for row in per_user),
                introduced_without_future_positive=introduced, introduced_without_training_observation=unsupported,
                candidate_scores=summaries,
                worst_changes=sorted([row for row in per_user if row["delta"] < -1e-9], key=lambda row: row["delta"])[:3])


def broaden_synthetic_exposure(users, items, row_users, row_items, labels):
    """Spread the fixture's existing negative impressions across all three nonpreferred groups.

    Uses the fixture's predefined group identities, not evaluation outcomes. Positive rows, negative
    counts, timestamps (represented by the existing weights), and confidence remain unchanged.
    This is an altered synthetic exposure policy, not inferred negative labels for real unseen items.
    """
    indexes = {item: index for index, item in enumerate(items)}
    changed = row_items.copy()
    counts = {}
    for row in np.flatnonzero(labels == 0):
        user = int(row_users[row])
        group = int(users[user].split("-")[0][1:])
        offset = counts.get(user, 0)
        counts[user] = offset + 1
        negative_group = (group + 1 + offset % 3) % 4
        item_number = int(items[row_items[row]][1:])
        changed[row] = indexes[f"c{negative_group * 8 + item_number % 8:02}"]
    return changed


class _ProbeHead(tf.keras.Model):
    """Same scoring layers as production, optionally exposing one standardized retrieval dot product."""

    def __init__(self, score, use_dot, dot_mean, dot_scale):
        super().__init__()
        self.score = score
        self.use_dot = use_dot
        self.dot_mean = dot_mean
        self.dot_scale = dot_scale

    def call(self, features, training=False):
        user, item = features["user_embedding"], features["item_embedding"]
        parts = [user, item]
        if self.use_dot:
            parts.append((tf.reduce_sum(user * item, axis=1, keepdims=True) - self.dot_mean) / self.dot_scale)
        return self.score(tf.concat(parts, axis=1), training=training)


def _fresh_score(layer_configs, width):
    score = tf.keras.Sequential([tf.keras.layers.deserialize(config) for config in layer_configs])
    score(tf.zeros([1, width]))
    return score


def diagnose_once(fixture_seed=173, seed=11, epochs=25, probe_epochs=25, long_epochs=100, learning_rate=.01):
    """Reproduce the production head and run matched-initialization controls over frozen embeddings."""
    if min(epochs, probe_epochs, long_epochs) < 1:
        raise ValueError("Epoch limits must be positive")
    bundle = synthetic_history(fixture_seed)
    data = split_history(bundle)
    retrieval_ds, ranking_ds, content_ds, user_ds, vocabs = prepare_datasets(
        data["past"], data["content"], data["categories"], data["feedback"], data["signals"], data["users"], as_of=data["cutoff"],
    )
    tf.keras.backend.clear_session()
    model, ranker = train_model(retrieval_ds, ranking_ds, content_ds, vocabs, 16, epochs, 32, learning_rate, random_seed=seed)
    user_features = next(iter(user_ds.batch(int(user_ds.cardinality()))))
    item_features = next(iter(content_ds.batch(int(content_ds.cardinality()))))
    rows = next(iter(ranking_ds.batch(int(ranking_ds.cardinality()))))
    positives = next(iter(retrieval_ds.batch(int(retrieval_ds.cardinality()))))
    users = [value.decode() for value in user_features["user_id"].numpy()]
    items = [value.decode() for value in item_features["content_id"].numpy()]
    user_indexes, item_indexes = {value: i for i, value in enumerate(users)}, {value: i for i, value in enumerate(items)}
    user_embeddings = model.user_model(user_features, training=False).numpy()
    item_embeddings = model.content_model(item_features, training=False).numpy()
    row_users = np.array([user_indexes[value.decode()] for value in rows["user_id"].numpy()])
    row_items = np.array([item_indexes[value.decode()] for value in rows["content_id"].numpy()])
    labels, weights = rows["label"].numpy(), rows["sample_weight"].numpy()
    shape = (len(users), len(items))
    history, truth, observed = [np.zeros(shape, dtype=bool) for _ in range(3)]
    for user, item in zip(positives["user_id"].numpy(), positives["content_id"].numpy()):
        history[user_indexes[user.decode()], item_indexes[item.decode()]] = True
    for row in _targets(data).itertuples():
        truth[user_indexes[row.user_id], item_indexes[row.content_id]] = True
    observed[row_users, row_items] = True
    all_user_embeddings = np.repeat(user_embeddings, len(items), axis=0)
    all_item_embeddings = np.tile(item_embeddings, (len(users), 1))
    all_features = dict(user_embedding=all_user_embeddings, item_embedding=all_item_embeddings)
    retrieval_scores = user_embeddings @ item_embeddings.T
    ranking_scores = ranker.score_pairs(
        all_user_embeddings, all_item_embeddings, tf.constant(np.tile(items, len(users))),
    ).numpy().reshape(shape)
    direct = ranker(rows, training=False).numpy().ravel()
    # Serving uses the complete captured profile features; training rows use their historical features.
    # Compare materialization against the same serving inputs, not against earlier history snapshots.
    serving_rows = {**rows, **{key: tf.gather(value, row_users) for key, value in user_features.items()}}
    serving_direct = ranker(serving_rows, training=False).numpy().ravel()
    parity = float(np.max(np.abs(serving_direct - ranking_scores[row_users, row_items])))
    if parity > 1e-5:
        raise ValueError(f"Feature and materialized-embedding ranking disagree by {parity}")
    result = dict(fixture_seed=fixture_seed, training_seed=seed, epochs=epochs, learning_rate=learning_rate,
                  feature_embedding_score_max_difference=parity,
                  ranker_parameters=int(ranker.score.count_params()),
                  training=dict(rows=len(labels), unique_pairs=int(observed.sum()),
                                positive_rows=int((labels > 0).sum()), negative_rows=int((labels == 0).sum()),
                                positive_weight=float(weights[labels > 0].sum()), negative_weight=float(weights[labels == 0].sum()),
                                constant_prediction=float(np.average(labels, weights=weights)),
                                constant_weighted_mse=weighted_mse(labels, np.average(labels, weights=weights), weights),
                                ranker_probability_weighted_mse=weighted_mse(labels, tf.sigmoid(direct).numpy(), weights)),
                  production_head=compare_orderings(items, retrieval_scores, ranking_scores, history, truth, observed), probes={})
    layer_configs = [tf.keras.layers.serialize(layer) for layer in ranker.score.layers]
    width = user_embeddings.shape[1] + item_embeddings.shape[1]
    _seed(seed + 1000)
    initial_score = _fresh_score(layer_configs, width)
    initial_weights = initial_score.get_weights()
    initial_predictions = initial_score(np.concatenate([all_user_embeddings, all_item_embeddings], axis=1)).numpy()
    historical_user_embeddings = model.user_model(rows, training=False).numpy()
    dot = np.sum(historical_user_embeddings * item_embeddings[row_items], axis=1)
    dot_mean, dot_scale = float(np.mean(dot)), max(float(np.std(dot)), 1e-6)
    broader_items = broaden_synthetic_exposure(users, items, row_users, row_items, labels)
    variants = [
        ("concat", False, False, False, probe_epochs),
        ("concat_longer", False, False, False, long_epochs),
        ("concat_dot", True, False, False, probe_epochs),
        ("concat_broader_exposure", False, True, False, probe_epochs),
        ("concat_dot_broader_exposure", True, True, False, probe_epochs),
        ("concat_balanced_weight", False, False, True, probe_epochs),
    ]
    for name, use_dot, broader, balance, max_epochs in variants:
        _seed(seed + 1000)
        score = _fresh_score(layer_configs, width + int(use_dot))
        copied = [value.copy() for value in initial_weights]
        if use_dot:
            copied[0] = np.concatenate([copied[0], np.zeros([1, copied[0].shape[1]], dtype=copied[0].dtype)], axis=0)
        score.set_weights(copied)
        head = _ProbeHead(score, use_dot, dot_mean, dot_scale)
        np.testing.assert_allclose(head(all_features).numpy(), initial_predictions, rtol=1e-6, atol=1e-6)
        fit_items = broader_items if broader else row_items
        fit_weights = weights.copy()
        if balance:
            negative = labels == 0
            fit_weights[negative] *= float(weights[~negative].sum() / weights[negative].sum())
            fit_weights *= weights.sum() / fit_weights.sum()
        features = dict(user_embedding=historical_user_embeddings, item_embedding=item_embeddings[fit_items],
                        label=labels, sample_weight=fit_weights)
        dataset = tf.data.Dataset.from_tensor_slices(features)
        probe = BoscaRanker(head)
        probe.compile(optimizer=tf.keras.optimizers.Adagrad(learning_rate))
        # Same shuffle seed and training-loss early stopping for all controls; no evaluation labels enter fit.
        _fit_to_convergence(probe, dataset, max_epochs, 32, name, seed + 1)
        predicted = head(all_features, training=False).numpy().reshape(shape)
        training_observed = np.zeros(shape, dtype=bool)
        training_observed[row_users, fit_items] = True
        metrics = compare_orderings(items, retrieval_scores, predicted, history, truth, training_observed)
        result["probes"][name] = dict(max_epochs=max_epochs, epochs_run=len(probe.history.history["mean_loss"]),
                                      fit_unique_pairs=int(training_observed.sum()),
                                      original_data_probability_weighted_mse=weighted_mse(labels, tf.sigmoid(predicted[row_users, row_items]).numpy(), weights),
                                      fit_data_probability_weighted_mse=weighted_mse(labels, tf.sigmoid(predicted[row_users, fit_items]).numpy(), fit_weights),
                                      **metrics)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fixture-seeds", type=int, nargs="+", default=[173, 307])
    parser.add_argument("--seeds", type=int, nargs="+", default=[11, 29, 47])
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--probe-epochs", type=int, default=25)
    parser.add_argument("--long-epochs", type=int, default=100)
    parser.add_argument("--learning-rate", type=float, default=.01)
    args = parser.parse_args(argv)
    report = dict(source="synthetic", protocol="Frozen retrieval, matched initial head predictions, chronological unseen-pair outcomes",
                  limitations=["Exposure broadening uses known synthetic groups; it cannot infer real unobserved negatives.",
                               "Weight balancing is a diagnostic change, not a calibrated replacement for impression confidence.",
                               "Fresh fixture seeds reshuffle histories within the same synthetic generative assumptions.",
                               "Probes isolate hypotheses; none are automatically selected or applied to production."], runs=[])
    for fixture in args.fixture_seeds:
        for seed in args.seeds:
            run = diagnose_once(fixture, seed, args.epochs, args.probe_epochs, args.long_epochs, args.learning_rate)
            report["runs"].append(run)
            print(json.dumps(dict(fixture_seed=fixture, seed=seed, production_ndcg=run["production_head"]["reranked_ndcg"],
                                  retrieval_ndcg=run["production_head"]["retrieval_ndcg"],
                                  probes={key: value["reranked_ndcg"] for key, value in run["probes"].items()})), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    return report


if __name__ == "__main__":  # pragma: no cover
    main()
