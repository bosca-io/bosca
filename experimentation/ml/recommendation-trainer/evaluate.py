#!/usr/bin/env python3
"""Local chronological recommendation evaluation; never uploads or promotes a model.

Inputs are local point-in-time feature snapshots plus timestamped analytics. With no input file,
use the deterministic synthetic history below. Production training is not changed by this evaluator.
"""

import argparse
import hashlib
import json
from pathlib import Path
from time import perf_counter

import numpy as np
import pandas as pd

from trainer.content_similarity import ContentSimilarityIndex
from trainer.datasets import extract_language_tags, extract_recommendation_contexts, prepare_datasets
from trainer.observations import prepare_observations
from trainer.training import train_model
import tensorflow as tf


def synthetic_history(seed=173):
    """Known latent preferences, partial metadata, changing interests, and future unseen pairs.

    Two preference groups share each broad content category. Other users have consumed each user's
    held-out items, so collaborative learning can help beyond content similarity. Cold users are
    already known profiles at the cutoff; some have profile signals and others have none.
    """
    rng = np.random.default_rng(seed)
    cutoff = pd.Timestamp("2026-09-01T00:00:00Z")
    content = [dict(content_id=f"c{g * 8 + i:02}", content_type="article", language_tag="en",
                    labels=[], recommendation_contexts=["default"]) for g in range(4) for i in range(8)]
    categories = [dict(content_id=c["content_id"], category_id=f"topic-{i // 16}")
                  for i, c in enumerate(content)]
    users, signals, events = [], [], []

    def emit(user, item, days, *, negative=False):
        key = f"event-{len(events)}"
        events.append(dict(user_id=user, content_id=f"c{item:02}", interaction_type="Impression" if negative else "Completion",
                           view_percent=None, element_type="article", event_id=key, app_id="local-evaluation",
                           session_id=key, page_id="/feed", interaction_created=(cutoff + pd.Timedelta(days=days)).isoformat(),
                           visible_ms=1000 if negative else None, visibility_threshold=.5 if negative else None))

    for group in range(4):
        scenarios = ["stable"] * (10 if group == 0 else 6) + ["changed", "sparse", "cold_signal", "cold_no_signal"]
        for number, scenario in enumerate(scenarios):
            user = f"u{group}-{number:02}"
            users.append(dict(user_id=user, scenario=scenario))
            if scenario != "cold_no_signal":
                signal_group = (group + 2) % 4 if scenario == "changed" else group
                signals.append(dict(user_id=user, signal_key="interest", signal_value=json.dumps(f"interest-{signal_group}"),
                                    value_type="categorical", priority=1))
            preferred = rng.permutation(np.arange(group * 8, group * 8 + 8)).tolist()
            seen = preferred[:1] if scenario == "sparse" else preferred[:4] if scenario in ("stable", "changed") else []
            if scenario == "changed":
                old_group = (group + 2) % 4
                for repeat in range(5):
                    for item in range(old_group * 8, old_group * 8 + 4):
                        emit(user, item, -180 + repeat)
            for repeat in range(1 if scenario == "sparse" else 3):
                for item in seen:
                    emit(user, item, -7 + repeat)
                    emit(user, ((group + 1) % 4) * 8 + item % 8, -7 + repeat, negative=True)
            for item in preferred:
                if item not in seen:
                    emit(user, item, 1 + (item % 4) / 10)
    return dict(source="synthetic", fixture_seed=seed, snapshot_at=cutoff.isoformat(),
                evaluation_end=(cutoff + pd.Timedelta(days=3)).isoformat(), users=users, content=content,
                categories=categories, signals=signals, feedback=[], interactions=events)


def _timestamp(value):
    timestamp = pd.Timestamp(value)
    if pd.isna(timestamp) or timestamp.tzinfo is None:
        raise ValueError("Evaluation boundaries must include a timezone")
    return timestamp.tz_convert("UTC")


def _partition(frame, column, cutoff, end):
    """Reject unorderable history; an unknown timestamp must never drift into a training partition."""
    if frame.empty:
        return frame.copy(), frame.copy(), 0
    times = pd.to_datetime(frame[column], errors="coerce", utc=True, format="mixed")
    if times.isna().any():
        raise ValueError(f"Every {column} must be a valid timestamp for chronological evaluation")
    return frame[times <= cutoff].copy(), frame[(times > cutoff) & (times <= end)].copy(), int((times > end).sum())


def split_history(bundle):
    """Read the declared feature snapshot and isolate future outcomes from all training inputs."""
    cutoff, end = _timestamp(bundle["snapshot_at"]), _timestamp(bundle["evaluation_end"])
    if end <= cutoff:
        raise ValueError("evaluation_end must be after snapshot_at")
    users = pd.DataFrame(bundle["users"])
    content = pd.DataFrame(bundle["content"])
    for frame, key in [(users, "user_id"), (content, "content_id")]:
        if frame.empty or frame[key].isna().any() or frame[key].astype(str).str.strip().eq("").any() or frame[key].duplicated().any():
            raise ValueError(f"Snapshot {key} values must be nonempty and unique")
    categories = pd.DataFrame(bundle.get("categories", []), columns=["content_id", "category_id"])
    signals = pd.DataFrame(bundle.get("signals", []), columns=["user_id", "signal_key", "signal_value", "value_type", "priority"])
    feedback = pd.DataFrame(bundle.get("feedback", []),
                            columns=["user_id", "content_id", "feedback_label", "feedback_source", "feedback_created"])
    interactions = pd.DataFrame(bundle["interactions"])
    if interactions.empty:
        interactions = interactions.reindex(columns=["user_id", "content_id", "interaction_type", "interaction_created"])
    past, future, beyond_events = _partition(interactions, "interaction_created", cutoff, end)
    past_feedback, future_feedback, beyond_feedback = _partition(feedback, "feedback_created", cutoff, end)
    past.attrs["as_of"] = cutoff.isoformat()
    # SQL uses the eligible-profile snapshot in production; preserve that boundary for local exports.
    known = set(users.user_id)
    unknown_events = int((~past.user_id.isin(known)).sum())
    past = past[past.user_id.isin(known)].copy()
    past_feedback = past_feedback[past_feedback.user_id.isin(known)].copy()
    return dict(users=users, content=content, categories=categories, signals=signals,
                past=past, feedback=past_feedback, future=future, future_feedback=future_feedback,
                cutoff=cutoff, end=end, excluded_after_end=beyond_events + beyond_feedback,
                excluded_unknown_training_users=unknown_events)


def ranking_metrics(recommended, relevant, k):
    """Binary Recall@K, NDCG@K, and reciprocal rank; empty relevance is not an evaluable query."""
    if k < 1 or not relevant:
        raise ValueError("Metrics require positive k and at least one relevant candidate")
    if len(set(recommended)) != len(recommended):
        raise ValueError("Duplicate recommendations invalidate ranking metrics")
    hits = np.array([item in relevant for item in recommended[:k]], dtype=float)
    discounts = 1 / np.log2(np.arange(2, len(hits) + 2))
    ideal = np.sum(1 / np.log2(np.arange(2, min(k, len(relevant)) + 2)))
    positions = np.flatnonzero(hits)
    return dict(recall=float(hits.sum() / len(relevant)), ndcg=float(np.sum(hits * discounts) / ideal),
                mrr=float(1 / (positions[0] + 1)) if len(positions) else 0.0,
                hit_rate=float(bool(len(positions))))


def _ordered(ids, scores, candidates, k):
    scores = np.asarray(scores)
    if not np.isfinite(scores).all():
        raise ValueError("Evaluation received non-finite model scores")
    indexes = [i for i, item in enumerate(ids) if item in candidates]
    return sorted(indexes, key=lambda i: (-float(scores[i]), ids[i]))[:k]


def _targets(data):
    # Success is independent of recency variants: future completions or explicit ratings >=4/5.
    # Clicks, page views, and unexposed items are not asserted to be ground-truth rejection.
    completions = data["future"][data["future"].interaction_type.eq("Completion")]
    rows, _ = prepare_observations(completions, data["future_feedback"], data["content"].content_id,
                                   as_of=data["end"])
    return rows[rows.retrieval_positive & rows.label.ge(.75)]


def evaluate_once(bundle, *, seed=11, half_life_days=30.0, epochs=25, k=5, retrieval_candidates=16,
                  context="default", language="en", learning_rate=0.01):
    """Fit the existing towers/ranker on past data, then score unseen items against future outcomes.

    Scores are exact, in-process model computations. This is not a TF Serving/network load test or
    the complete Kotlin placement assembler. The content baseline pools the production item's exact
    similarity scores over weighted positive history and falls back to popularity for empty histories.
    """
    if k < 1 or retrieval_candidates < k or epochs < 1:
        raise ValueError("Require epochs >=1 and retrieval_candidates >= k >=1")
    data = split_history(bundle)
    content = data["content"].sort_values("content_id").reset_index(drop=True)
    contexts, languages = extract_recommendation_contexts(content), extract_language_tags(content)
    facet = {item for item in content.content_id if context in contexts[item] and languages[item] == language}
    if not facet:
        raise ValueError("No candidates in the requested context/language facet")
    retrieval, ranking, content_ds, user_ds, vocabs = prepare_datasets(
        data["past"], content, data["categories"], data["feedback"], data["signals"], data["users"],
        as_of=data["cutoff"], half_life_days=half_life_days,
    )
    if int(retrieval.cardinality()) == 0:
        raise ValueError("Evaluation needs positive training observations")
    positive_rows = list(retrieval.as_numpy_iterator())
    ids = content.content_id.tolist()
    item_indexes = {item: i for i, item in enumerate(ids)}
    histories, counts = {}, {}
    popularity = np.zeros(len(ids))
    for row in positive_rows:
        user, item = row["user_id"].decode(), row["content_id"].decode()
        history = histories.setdefault(user, np.zeros(len(ids)))
        history[item_indexes[item]] += float(row["sample_weight"])
        popularity[item_indexes[item]] += float(row["sample_weight"])
        counts[user] = counts.get(user, 0) + 1
    targets = _targets(data)
    known = set(data["users"].user_id)
    queries = []
    for user, rows in targets.groupby("user_id", sort=True):
        history = histories.get(user, np.zeros(len(ids)))
        seen = {ids[i] for i in np.flatnonzero(history)}
        candidates = facet - seen
        relevant = set(rows.content_id) & candidates
        if user in known and relevant:
            queries.append((user, candidates, relevant))
    if not queries:
        raise ValueError("No evaluable future positives remain for known users after facet/seen filtering")

    tf.keras.backend.clear_session()
    started = perf_counter()
    model, ranker = train_model(retrieval, ranking, content_ds, vocabs, 16, epochs, 32, learning_rate, random_seed=seed)
    training_seconds = perf_counter() - started
    item_features = next(iter(content_ds.batch(len(ids))))
    item_embeddings = model.content_model(item_features, training=False)
    user_features = next(iter(user_ds.batch(int(user_ds.cardinality()))))
    user_embeddings = model.user_model(user_features, training=False)
    user_indexes = {user.decode(): i for i, user in enumerate(user_features["user_id"].numpy())}

    @tf.function
    def score(user_index):
        embedding = user_embeddings[user_index]
        retrieval_scores = tf.linalg.matvec(item_embeddings, embedding)
        ranking_scores = ranker.score_pairs(
            tf.repeat(embedding[None, :], len(ids), axis=0), item_embeddings, tf.constant(ids),
        )
        return retrieval_scores, tf.reshape(ranking_scores, [-1])

    # Compile and warm before measuring per-request scoring; feature materialization is excluded.
    score(tf.constant(0))
    similarity = ContentSimilarityIndex(content, data["categories"], [f"{context}\x1f{language}"],
                                        [tf.constant([item_indexes[item] for item in ids if item in facet])], len(ids))
    seed_scores = {}
    for index in np.flatnonzero(popularity):
        values, item_ids = similarity(tf.constant([ids[index]]), tf.constant([context]), tf.constant([language]))
        by_id = dict(zip([item.decode() for item in item_ids.numpy()[0]], values.numpy()[0]))
        seed_scores[index] = np.array([by_id.get(item, 0) for item in ids])
    results = {name: [] for name in ["popularity", "content_history", "personalized_retrieval",
                                    "personalized_reranked", "personalized_full_catalog"]}
    latencies = []
    scenarios = data["users"].set_index("user_id").get("scenario", pd.Series(dtype=object)).dropna().to_dict()
    for user, candidates, relevant in queries:
        started = perf_counter()
        retrieval_scores, ranking_scores = score(tf.constant(user_indexes[user]))
        retrieval_scores, ranking_scores = retrieval_scores.numpy(), ranking_scores.numpy()
        latencies.append(1000 * (perf_counter() - started))
        history = histories.get(user, np.zeros(len(ids)))
        content_scores = popularity.copy()
        if history.sum() > 0:
            content_scores = sum(history[i] * seed_scores[i] for i in np.flatnonzero(history)) / history.sum()
        pool = _ordered(ids, retrieval_scores, candidates, retrieval_candidates)
        ordered = {
            "popularity": _ordered(ids, popularity, candidates, k),
            "content_history": _ordered(ids, content_scores, candidates, k),
            "personalized_retrieval": pool[:k],
            "personalized_reranked": _ordered(ids, ranking_scores, {ids[i] for i in pool}, k),
            "personalized_full_catalog": _ordered(ids, ranking_scores, candidates, k),
        }
        count = counts.get(user, 0)
        groups = ["all", "cold" if count == 0 else "sparse" if count < 5 else "warm"]
        if user in scenarios:
            groups.append(f"scenario:{scenarios[user]}")
        for name, indexes in ordered.items():
            recommendations = [ids[i] for i in indexes]
            results[name].append(dict(groups=groups, recommended=recommendations, candidates=candidates,
                                      **ranking_metrics(recommendations, relevant, k)))
    metrics = {}
    for name, rows in results.items():
        metrics[name] = {}
        for group in sorted({group for row in rows for group in row["groups"]}):
            selected = [row for row in rows if group in row["groups"]]
            metrics[name][group] = dict(
                users=len(selected),
                **{key: float(np.mean([row[key] for row in selected])) for key in ["recall", "ndcg", "mrr", "hit_rate"]},
                catalog_coverage=len(set().union(*(set(row["recommended"]) for row in selected))) /
                                 len(set().union(*(row["candidates"] for row in selected))),
            )
    return dict(seed=seed, recency_half_life_days=half_life_days, k=k, retrieval_candidates=retrieval_candidates,
                context=context, language=language, learning_rate=learning_rate, max_epochs=epochs, training_seconds=training_seconds,
                scoring_ms=dict(p50=float(np.percentile(latencies, 50)), p95=float(np.percentile(latencies, 95)),
                                queries=len(latencies)),
                snapshot_at=data["cutoff"].isoformat(), evaluation_end=data["end"].isoformat(),
                training_events=len(data["past"]), future_events=len(data["future"]),
                future_positive_users=int(targets.user_id.nunique()), evaluated_users=len(queries),
                future_positive_pairs=len(targets[["user_id", "content_id"]].drop_duplicates()),
                evaluated_positive_pairs=sum(len(relevant) for _, _, relevant in queries),
                excluded_after_end=data["excluded_after_end"],
                excluded_unknown_training_users=data["excluded_unknown_training_users"],
                training_data=vocabs["training_data"], metrics=metrics)


def summarize_runs(runs):
    """Summarize seed variation without treating repeated fits as additional evaluation users."""
    summary = {}
    for half_life in sorted({run["recency_half_life_days"] for run in runs}):
        selected = [run for run in runs if run["recency_half_life_days"] == half_life]
        summary[str(half_life)] = {}
        for model, groups in selected[0]["metrics"].items():
            summary[str(half_life)][model] = {}
            for group, values in groups.items():
                stats = {"users": values["users"], "fits": len(selected)}
                for metric in ["recall", "ndcg", "mrr", "hit_rate", "catalog_coverage"]:
                    scores = [run["metrics"][model][group][metric] for run in selected]
                    stats[metric] = dict(mean=float(np.mean(scores)), std=float(np.std(scores)))
                summary[str(half_life)][model][group] = stats
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Local JSON point-in-time feature snapshot and timestamped history")
    parser.add_argument("--output", type=Path, required=True, help="JSON metrics report")
    parser.add_argument("--seeds", nargs="+", type=int, default=[11, 29, 47])
    parser.add_argument("--half-lives", nargs="+", type=float, default=[30, 365])
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--retrieval-candidates", type=int, default=16)
    parser.add_argument("--context", default="default")
    parser.add_argument("--language", default="en")
    args = parser.parse_args(argv)
    bundle = json.loads(args.input.read_text()) if args.input else synthetic_history()
    source = bundle.get("source", "local_export") if args.input else "synthetic"
    report = dict(source=source,
                  input_sha256=hashlib.sha256(json.dumps(bundle, sort_keys=True).encode()).hexdigest(),
                  protocol="chronological discovery; known profiles and features at cutoff; future completion/rating targets",
                  limitations=["Synthetic outcomes cannot establish production uplift." if source == "synthetic" else
                               "Feature snapshots must actually reflect the cutoff; current attributes can leak future information.",
                               "Positive-only offline outcomes do not correct exposure/position bias or measure causal lift.",
                               "Latency is warm in-process exact retrieval plus full-catalog rank scoring, without network or placement assembly.",
                               "Seed variation measures training randomness, not uncertainty across real users."], runs=[])
    for half_life in args.half_lives:
        for seed in args.seeds:
            run = evaluate_once(bundle, seed=seed, half_life_days=half_life, epochs=args.epochs, k=args.k,
                                retrieval_candidates=args.retrieval_candidates, context=args.context, language=args.language,
                                learning_rate=args.learning_rate)
            report["runs"].append(run)
            print(json.dumps({"seed": seed, "half_life": half_life,
                              "ndcg": {name: groups["all"]["ndcg"] for name, groups in run["metrics"].items()}}))
    report["summary"] = summarize_runs(report["runs"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(f"Report: {args.output}")
    return report


if __name__ == "__main__":  # pragma: no cover
    main()
