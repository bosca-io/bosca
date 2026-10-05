"""Versioned behavioral inputs shared by training, ranking, and source ablation.

Training uses a completed snapshot only for observations after that snapshot became available.
Older observations still teach general preference, without borrowing newer population outcomes.
"""
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from time import perf_counter

import numpy as np
import pandas as pd
import tensorflow as tf

SNAPSHOT_FILE = "behavior.json"
GROUPS = ("coEngagement", "cohortCoEngagement", "learnedNeighbor", "rating")
_PREDICTION_BATCH_SIZE = 128
log = logging.getLogger(__name__)


def read_snapshot(directory):
    path = Path(directory) / SNAPSHOT_FILE
    if not path.exists():
        return None
    with path.open() as stream:
        snapshot = json.load(stream)
    if not snapshot.get("available_at"):
        raise ValueError("Behavior snapshot has no availability timestamp")
    return snapshot


def write_snapshot(directory, snapshot):
    with (Path(directory) / SNAPSHOT_FILE).open("w") as stream:
        json.dump(snapshot, stream, allow_nan=False)


def capture_snapshot(rows, feedback, users, context, languages, previous_model=None):
    """Capture analytics outputs and prior completed-model neighbor predictions as features, never labels."""
    edges = rows[rows["kind"].isin(["global", "cohort"])].to_dict("records")
    memberships = rows[rows["kind"].eq("membership")][["user_id", "cohort_key"]].to_dict("records")
    ratings = []
    if feedback is not None and not feedback.empty:
        latest = feedback[feedback["feedback_source"].eq("rating")]
        if "feedback_created" in latest:
            latest = latest.sort_values("feedback_created")
        latest = latest.drop_duplicates(["user_id", "content_id"], keep="last")
        ratings = latest[["user_id", "content_id", "feedback_label"]].values.tolist()
    neighbors = []
    if previous_model is not None:
        started = perf_counter()
        nearest = previous_model.signatures["similar_users"]
        recommend = previous_model.signatures["serving_default"]
        user_neighbors = []
        unique_neighbors = {}
        log.info("Capturing previous-model neighbors for %d users", len(users))
        for offset in range(0, len(users), _PREDICTION_BATCH_SIZE):
            batch = users[offset:offset + _PREDICTION_BATCH_SIZE]
            found = nearest(user_id=tf.constant(batch))
            for user, ids, scores in zip(batch, found["content_ids"].numpy(), found["scores"].numpy()):
                weights = {key.decode(): max(0., float(value)) for key, value in zip(ids, scores)
                           if key and key.decode() != user}
                weights = dict(list(weights.items())[:20])
                user_neighbors.append(weights)
                unique_neighbors.update(dict.fromkeys(weights))
            log.info("Captured neighbors for %d/%d users in %.1fs", offset + len(batch), len(users),
                     perf_counter() - started)

        # A neighbor's predictions depend only on this completed model and serving facet. Score each
        # distinct neighbor once per language, then reuse those predictions for every referring user.
        predictions = {}
        neighbor_ids = list(unique_neighbors)
        for language in sorted(set(languages.values())):
            predictions[language] = {}
            for offset in range(0, len(neighbor_ids), _PREDICTION_BATCH_SIZE):
                batch = neighbor_ids[offset:offset + _PREDICTION_BATCH_SIZE]
                results = recommend(user_id=tf.constant(batch), context_type=tf.constant([context] * len(batch)),
                                    language_tag=tf.constant([language] * len(batch)))
                for neighbor, ids, scores in zip(batch, results["content_ids"].numpy(), results["scores"].numpy()):
                    predictions[language][neighbor] = [(item.decode(), max(0., float(score)))
                                                       for item, score in zip(ids, scores) if item]
                log.info("Scored %d/%d distinct neighbors for language %s in %.1fs", offset + len(batch),
                         len(neighbor_ids), language, perf_counter() - started)

        for user, weights in zip(users, user_neighbors):
            aggregate = {}
            for language in predictions:
                for neighbor, weight in weights.items():
                    for item, score in predictions[language][neighbor]:
                        aggregate[item] = aggregate.get(item, 0.) + weight * score
            neighbors.extend([user, item, score / (1. + score)] for item, score in aggregate.items())
        log.info("Captured %d learned-neighbor features for %d users in %.1fs", len(neighbors), len(users),
                 perf_counter() - started)
    return {"available_at": datetime.now(timezone.utc).isoformat(), "edges": edges,
            "memberships": memberships, "ratings": ratings, "neighbors": neighbors}


def _table(values):
    keys = list(values) or ["\x00"]
    scores = list(values.values()) or [0.]
    return tf.lookup.StaticHashTable(tf.lookup.KeyValueTensorInitializer(
        tf.constant(keys, tf.string), tf.constant(scores, tf.float32),
    ), 0.)


def _key(*parts):
    return "\x1f".join(parts)


class BehaviorFeatures(tf.Module):
    """Sparse population edges, cohort memberships, neighbor affinity, and signed rating affinity."""

    def __init__(self, snapshot, content_similarity):
        super().__init__()
        snapshot = snapshot or {}
        self.content_similarity = content_similarity
        global_edges, cohort_edges, memberships = {}, {}, {}
        for row in snapshot.get("edges", []):
            source, candidate = row["source_id"], row["content_id"]
            count = max(0., float(row["score"]))
            # A fixed saturating scale preserves comparisons across requests and model versions.
            score = count / (count + 10.)
            if row["kind"] == "cohort":
                cohort_edges[_key(row["cohort_key"], source, candidate)] = score
            else:
                global_edges[_key(source, candidate)] = score
        for row in snapshot.get("memberships", []):
            memberships.setdefault(row["user_id"], set()).add(row["cohort_key"])
        self.global_edges = _table(global_edges)
        self.cohort_edges = _table(cohort_edges)
        self.users = tf.keras.layers.StringLookup(vocabulary=sorted(memberships) or ["\x00"], mask_token=None)
        self.memberships = tf.ragged.constant([[]] + [sorted(memberships[user]) for user in self.users.get_vocabulary()[1:]
                                                     if user in memberships], dtype=tf.string, ragged_rank=1)
        if not memberships:
            self.memberships = tf.ragged.constant([[], []], dtype=tf.string, ragged_rank=1)
        self.neighbors = _table({_key(row[0], row[1]): float(row[2]) for row in snapshot.get("neighbors", [])})
        ratings = {}
        for user, item, value in snapshot.get("ratings", []):
            ratings.setdefault(user, []).append((item, 2. * float(value) - 1.))
        self.rating_users = tf.keras.layers.StringLookup(vocabulary=sorted(ratings) or ["\x00"], mask_token=None)
        rows = [ratings.get(user, []) for user in self.rating_users.get_vocabulary()]
        self.rating_items = tf.ragged.constant([[item for item, _ in row] for row in rows], tf.string, ragged_rank=1)
        self.rating_values = tf.ragged.constant([[value for _, value in row] for row in rows], tf.float32, ragged_rank=1)

    def __call__(self, users, sources, candidates):
        global_scores = self.global_edges.lookup(tf.strings.join([sources, candidates], separator="\x1f"))
        def cohort(inputs):
            user, source, candidate = inputs
            keys = self.memberships[self.users(user)]
            edges = tf.strings.join([keys, tf.fill(tf.shape(keys), source), tf.fill(tf.shape(keys), candidate)], separator="\x1f")
            return tf.reduce_max(tf.concat([self.cohort_edges.lookup(edges), [0.]], axis=0))
        cohort_scores = tf.map_fn(cohort, (users, sources, candidates), fn_output_signature=tf.float32)
        neighbor_scores = self.neighbors.lookup(tf.strings.join([users, candidates], separator="\x1f"))
        def rating(inputs):
            user, candidate = inputs
            index = self.rating_users(user)
            seeds, values = self.rating_items[index], self.rating_values[index]
            similarities = self.content_similarity.pair_similarity(seeds, tf.fill(tf.shape(seeds), candidate))
            return tf.math.divide_no_nan(tf.reduce_sum(similarities * values), tf.reduce_sum(tf.abs(values)))
        rating_scores = tf.map_fn(rating, (users, candidates), fn_output_signature=tf.float32)
        return tf.stack([global_scores, cohort_scores, neighbor_scores, rating_scores], axis=1)


def training_features(snapshot, similarity, observations):
    """No snapshot, own-outcome, or future-outcome leakage into historical training examples."""
    values = np.zeros((len(observations), len(GROUPS)), np.float32)
    if snapshot is None or observations.empty:
        return values
    available = pd.Timestamp(snapshot["available_at"])
    if available.tzinfo is None:
        raise ValueError("Behavior snapshot availability must include a timezone")
    eligible = pd.to_datetime(observations["feature_time"], utc=True) > available
    rows = observations.loc[eligible]
    if not rows.empty:
        values[eligible.to_numpy()] = BehaviorFeatures(snapshot, similarity)(
            tf.constant(rows["user_id"].tolist()), tf.constant(rows["source_id"].tolist()),
            tf.constant(rows["content_id"].tolist()),
        ).numpy()
    return values
