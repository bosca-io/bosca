"""Turning the loaded DataFrames into ``tf.data.Dataset``s for training."""

import logging
from typing import Optional

import numpy as np
import pandas as pd
import tensorflow as tf
from trainer.observations import prepare_observations
from trainer.behavior import training_features

from trainer.features import (
    build_embedding_matrix,
    build_multi_hot_matrix,
    build_signal_features,
    build_signal_multi_hot,
    compute_user_affinity,
    compute_sparse_user_affinity,
    extract_content_vocabs,
    labels_by_content,
    editorial_types,
    collections_by_content,
)

log = logging.getLogger(__name__)


def extract_recommendation_contexts(content: pd.DataFrame) -> dict[str, frozenset[str]]:
    """Returns normalized candidate-index memberships keyed by content id.

    Recommendation contexts are eligibility metadata, not content-tower features. The analytics boundary
    therefore keeps them as arrays, and this mapping travels directly to SavedModel index construction.
    Test and caller-owned frames that predate the column default every item to the standard experience.
    """
    if "recommendation_contexts" not in content.columns:
        return {
            str(content_id): frozenset({"default"})
            for content_id in content["content_id"].values
        }

    memberships = {}
    for content_id, raw_contexts in zip(content["content_id"].values, content["recommendation_contexts"].values):
        if raw_contexts is None or (isinstance(raw_contexts, float) and np.isnan(raw_contexts)):
            raw_contexts = ()
        elif isinstance(raw_contexts, np.ndarray):
            raw_contexts = raw_contexts.tolist()
        if not isinstance(raw_contexts, (list, tuple, set, frozenset)):
            raise ValueError(
                f"recommendation_contexts for content {content_id} must be an array, "
                f"got {type(raw_contexts).__name__}"
            )
        invalid = [context for context in raw_contexts if not isinstance(context, str)]
        if invalid:
            raise ValueError(f"recommendation_contexts for content {content_id} must contain only strings")
        memberships[str(content_id)] = frozenset(
            context.strip() for context in raw_contexts if context.strip()
        )
    return memberships


def extract_language_tags(content: pd.DataFrame) -> dict[str, str]:
    """Returns the resolved, non-blank language facet for each recommendable content item."""
    languages = {}
    for content_id, raw_language_tag in zip(content["content_id"].values, content["language_tag"].values):
        language_tag = "" if pd.isna(raw_language_tag) else str(raw_language_tag).strip()
        if not language_tag:
            raise ValueError(f"language_tag is missing for content {content_id}")
        languages[str(content_id)] = language_tag
    return languages


def build_content_dataset(content: pd.DataFrame, categories: pd.DataFrame):
    """Encode the content corpus into a ``tf.data.Dataset`` of content-tower features.

    Used by ``prepare_datasets`` for learned personalized towers. The exact content index stores its
    categorical memberships sparsely instead of materializing these dense training inputs. Returns
    ``(content_dataset, content_multi_hot, label_multi_hot, embedding_matrix, vocabs)`` where ``vocabs``
    carries ``content_ids`` / ``content_types`` / ``languages`` / ``category_ids`` / ``label_ids`` /
    ``embedding_dim``. The category and label multi-hots are empty-safe: when the corpus has none, the
    matrix is zero-width and the corresponding feature key is omitted from the dataset. The dense semantic
    ``embedding`` feature is consume-if-present the same way — omitted when ``embedding_dim == 0``.
    """
    vocabs = extract_content_vocabs(content, categories)
    num_categories = len(vocabs["category_ids"])
    num_labels = len(vocabs["label_ids"])

    # Pre-index categories by content_id for O(1) lookup, then build the fixed-width multi-hot matrix.
    category_map: dict = {}
    if num_categories > 0 and len(categories) > 0:
        for content_id, group in categories.groupby("content_id"):
            category_map[content_id] = group["category_id"].tolist()
    content_multi_hot = build_multi_hot_matrix(
        content["content_id"].values, category_map, vocabs["category_ids"]
    )

    label_map = labels_by_content(content)
    label_multi_hot = build_multi_hot_matrix(
        content["content_id"].values, label_map, vocabs["label_ids"]
    )

    # Per-content semantic embedding (dense; consume-if-present). embedding_dim == 0 when absent.
    embedding_matrix, embedding_dim = build_embedding_matrix(content)
    vocabs["embedding_dim"] = embedding_dim

    content_features = {
        "content_id": content["content_id"].values,
        "content_type": content["content_type"].fillna("").values,
        "language_tag": content["language_tag"].fillna("").values,
    }
    if vocabs["editorial_types"]:
        content_features["editorial_type"] = np.array(editorial_types(content), dtype=object)
    if vocabs["collection_ids"]:
        memberships = collections_by_content(content)
        content_features["collection_ids"] = tf.ragged.constant(
            [sorted(memberships.get(key, ())) for key in content["content_id"]],
            dtype=tf.string, ragged_rank=1,
        )
    if num_categories > 0:
        content_features["category_multi_hot"] = content_multi_hot
    if num_labels > 0:
        content_features["label_multi_hot"] = label_multi_hot
    if embedding_dim > 0:
        content_features["embedding"] = embedding_matrix

    content_dataset = tf.data.Dataset.from_tensor_slices(content_features)
    return content_dataset, content_multi_hot, label_multi_hot, embedding_matrix, vocabs


def prepare_datasets(
    interactions: pd.DataFrame,
    content: pd.DataFrame,
    categories: pd.DataFrame,
    feedback: Optional[pd.DataFrame] = None,
    signals: Optional[pd.DataFrame] = None,
    users: Optional[pd.DataFrame] = None,
    *,
    as_of=None,
    half_life_days: float = 30.0,
    attribution_minutes: float = 30.0,
    context_type: str | None = None,
    previous_behavior_snapshot=None,
    content_similarity=None,
) -> tuple[tf.data.Dataset, tf.data.Dataset, tf.data.Dataset, tf.data.Dataset, dict]:
    """Prepares TF datasets for retrieve-then-rank training.

    Returns a *retrieval* dataset (positive (user, content) pairs only), a *ranking* dataset (every
    labeled row, with a `sample_weight`), the *content* dataset (the candidate corpus), and a *user*
    dataset (one feature row per profile — including zero-interaction profiles — for the cold-start user
    tower's serve-time lookup). The label fuses implicit views (watch percentage) with explicit feedback
    (ratings mapped to [0, 1]; dismissals as a hard 0); explicit feedback supersedes the implicit label
    for the same pair and is weighted higher. Dismissals and sub-neutral ratings are excluded from the
    retrieval positives so rejected observations do not reinforce retrieval or category affinity.
    """
    observations, quality_report = prepare_observations(
        interactions, feedback, content["content_id"], as_of=as_of,
        half_life_days=half_life_days, attribution_minutes=attribution_minutes,
    )
    merged = observations.merge(content, on="content_id", how="inner")
    # Candidate membership is not evidence that a request occurred in this context. Preserve every
    # observation for general preferences, but expose a source only for explicitly attributed requests.
    selected_context = (context_type or "").strip().lower()
    source_attributed = (
        merged["recommendation_context"].eq(selected_context)
        & bool(selected_context)
        & merged["recommendation_source_id"].isin(content["content_id"])
        & merged["recommendation_source_id"].ne(merged["content_id"])
    )
    quality_report["source_attributed_rows"] = int(source_attributed.sum())
    merged["source_id"] = merged["recommendation_source_id"].where(source_attributed, "")
    behavior_features = training_features(previous_behavior_snapshot, content_similarity, merged)
    quality_report["behavior_feature_rows"] = int(np.any(behavior_features != 0, axis=1).sum())

    trained_user_ids = sorted(str(user_id) for user_id in merged["user_id"].unique().tolist())
    eligible_user_ids = set(trained_user_ids)
    if users is not None and not users.empty:
        eligible_user_ids.update(str(user_id) for user_id in users["user_id"].dropna().tolist())
    unique_user_ids = sorted(eligible_user_ids)

    # Shared content encoding: content vocabs, per-item category/label multi-hots, the dense embedding
    # matrix, and the candidate dataset.
    content_dataset, content_multi_hot, label_multi_hot, embedding_matrix, content_vocabs = build_content_dataset(
        content, categories
    )
    unique_content_ids = content_vocabs["content_ids"]
    unique_content_types = content_vocabs["content_types"]
    unique_languages = content_vocabs["languages"]
    unique_category_ids = content_vocabs["category_ids"]
    unique_label_ids = content_vocabs["label_ids"]
    num_categories = len(unique_category_ids)
    num_labels = len(unique_label_ids)
    embedding_dim = content_vocabs["embedding_dim"]

    log.info(
        "Vocabularies: %d eligible users (%d with training labels), %d content, %d types, %d languages, %d categories",
        len(unique_user_ids),
        len(trained_user_ids),
        len(unique_content_ids),
        len(unique_content_types),
        len(unique_languages),
        len(unique_category_ids),
    )

    # Build multi-hots for the interaction dataset (look up each row's content by content_id).
    content_id_to_idx = {cid: i for i, cid in enumerate(content["content_id"].values)}
    interaction_multi_hot = np.array(
        [content_multi_hot[content_id_to_idx.get(cid, 0)] for cid in merged["content_id"].values],
        dtype=np.float32,
    ).reshape(len(merged), num_categories)
    interaction_label_multi_hot = np.array(
        [label_multi_hot[content_id_to_idx.get(cid, 0)] for cid in merged["content_id"].values],
        dtype=np.float32,
    ).reshape(len(merged), num_labels)
    interaction_embedding = np.array(
        [embedding_matrix[content_id_to_idx.get(cid, 0)] for cid in merged["content_id"].values],
        dtype=np.float32,
    ).reshape(len(merged), embedding_dim)

    # ── Cold-start user features ───────────────────────────────────────────────────────────────
    # Personalization signals per user — the configurable, per-value_type-encoded profile
    # attribute / segment features a zero-history user has — and a label-weighted category affinity per user
    # (the categories they engage with — emphasizing liked content, ignoring dismissals via the label
    # weighting). Both let the user tower generalize to cold/low-activity users.
    eligible_signals = signals
    if signals is not None and not signals.empty:
        eligible_signals = signals[signals["user_id"].astype(str).isin(eligible_user_ids)].reset_index(drop=True)
    signal_tokens_by_user, signal_vocab = build_signal_features(eligible_signals)
    num_signal_tokens = len(signal_vocab)
    user_arr = merged["user_id"].to_numpy()
    interaction_signal_multi_hot = build_signal_multi_hot(user_arr, signal_tokens_by_user, signal_vocab)

    # Only accepted positive evidence creates interests; rejection never becomes a positive affinity.
    labels_arr = (merged["label"] * merged["sample_weight"] * merged["retrieval_positive"]).to_numpy(dtype=np.float32)
    affinity_by_user, interaction_affinity = compute_user_affinity(
        user_arr, labels_arr, interaction_multi_hot, num_categories,
        observed_at=merged["interaction_created"], feature_times=merged["feature_time"],
    )
    collections = collections_by_content(content)
    affinity_memberships = {
        key: frozenset(([f"type:{editorial}"] if editorial else []) +
                       [f"collection:{collection}" for collection in collections.get(key, ())])
        for key, editorial in zip(content["content_id"], editorial_types(content))
    }
    affinity_tokens = sorted({token for tokens in affinity_memberships.values() for token in tokens})
    sparse_by_user, sparse_interactions = compute_sparse_user_affinity(
        user_arr, merged["content_id"].to_numpy(), labels_arr, affinity_memberships,
        merged["interaction_created"], merged["feature_time"],
    )

    def sparse_features(rows):
        keys = [sorted(row) for row in rows]
        return {
            "user_affinity_tokens": tf.ragged.constant(keys, dtype=tf.string, ragged_rank=1),
            "user_affinity_weights": tf.ragged.constant(
                [[row[key] for key in tokens] for row, tokens in zip(rows, keys)], dtype=tf.float32, ragged_rank=1,
            ),
        }

    # Both objectives consume confidence and recency. Retrieval additionally scales positive evidence
    # by its label; ranking retains every labeled observation, including explicit and weak negatives.
    interaction_features = {
        "user_id": merged["user_id"].to_numpy(),
        "content_id": merged["content_id"].to_numpy(),
        "content_type": merged["content_type"].to_numpy(),
        "language_tag": merged["language_tag"].to_numpy(),
        "label": merged["label"].to_numpy(dtype=np.float32),
        "sample_weight": merged["sample_weight"].to_numpy(dtype=np.float32),
        "source_id": merged["source_id"].to_numpy(dtype=object),
        "behavior_features": behavior_features,
    }
    if content_vocabs["editorial_types"]:
        interaction_features["editorial_type"] = np.array(editorial_types(merged), dtype=object)
    if content_vocabs["collection_ids"]:
        memberships = collections_by_content(content)
        interaction_features["collection_ids"] = tf.ragged.constant(
            [sorted(memberships.get(key, ())) for key in merged["content_id"]], dtype=tf.string, ragged_rank=1,
        )
    if num_signal_tokens > 0:
        interaction_features["user_signal_multi_hot"] = interaction_signal_multi_hot
    if affinity_tokens:
        interaction_features.update(sparse_features(sparse_interactions))
    if num_categories > 0:
        interaction_features["category_multi_hot"] = interaction_multi_hot
        interaction_features["user_category_affinity"] = interaction_affinity
    if num_labels > 0:
        interaction_features["label_multi_hot"] = interaction_label_multi_hot
    if embedding_dim > 0:
        interaction_features["embedding"] = interaction_embedding

    retrieval_mask = merged["retrieval_positive"].to_numpy(dtype=bool)
    log.info(
        "Training rows: %d ranking (%d explicit feedback), %d retrieval positives",
        len(merged),
        quality_report["explicit_feedback_rows"],
        int(retrieval_mask.sum()),
    )
    retrieval_features = {
        key: tf.ragged.boolean_mask(value, retrieval_mask) if isinstance(value, tf.RaggedTensor) else value[retrieval_mask]
        for key, value in interaction_features.items()
    }
    retrieval_features["sample_weight"] = retrieval_features["sample_weight"] * retrieval_features["label"]
    retrieval_dataset = tf.data.Dataset.from_tensor_slices(retrieval_features)
    ranking_dataset = tf.data.Dataset.from_tensor_slices(interaction_features)

    # One feature row per eligible profile, not just interacting ones, so the serve-time user query model
    # resolves a feature-derived embedding for cold (zero-interaction) profiles too. Signals belonging to a
    # profile outside the authoritative eligible-user query do not add an out-of-vocabulary index entry.
    all_user_ids = unique_user_ids
    user_features = {
        "user_id": np.array(all_user_ids, dtype=object),
    }
    if affinity_tokens:
        user_features.update(sparse_features([sparse_by_user.get(user, {}) for user in all_user_ids]))
    if num_signal_tokens > 0:
        user_features["user_signal_multi_hot"] = build_signal_multi_hot(
            all_user_ids, signal_tokens_by_user, signal_vocab
        )
    if num_categories > 0:
        zero_affinity = np.zeros(num_categories, dtype=np.float32)
        user_features["user_category_affinity"] = np.array(
            [affinity_by_user.get(u, zero_affinity) for u in all_user_ids], dtype=np.float32
        )
    user_dataset = tf.data.Dataset.from_tensor_slices(user_features)

    vocabs = {
        "user_ids": unique_user_ids,
        "training_user_ids": trained_user_ids,
        "signal_tokens": signal_vocab,
        "affinity_tokens": affinity_tokens,
        "content_ids": unique_content_ids,
        "content_types": unique_content_types,
        "languages": unique_languages,
        "category_ids": unique_category_ids,
        "label_ids": unique_label_ids,
        "editorial_types": content_vocabs["editorial_types"],
        "editorial_types_by_id": dict(zip(content["content_id"], editorial_types(content))),
        "collection_ids": content_vocabs["collection_ids"],
        "embedding_dim": embedding_dim,
        "training_data": quality_report,
    }

    return retrieval_dataset, ranking_dataset, content_dataset, user_dataset, vocabs
