"""Exporting the trained model as a SavedModel with retrieval + ranking serving signatures.

The exported model exposes four signatures, all string-in / ``content_ids`` + ``scores`` out, matching the
Kotlin ``TfServingClient`` contract:
  - ``serving_default(user_id, context_type, language_tag)`` — user -> top-K items from the requested facet
  - ``similar(content_id, context_type, language_tag)``      — item -> similar items from the requested facet
  - ``similar_users(user_id)``    — user -> nearest users by learned embedding (the dense "people like you")
  - ``rank(user_id, content_id)`` — the same complete score used before top-K

``export_model`` only ever touches ``model.content_model`` / ``model.user_model`` as duck-typed
attributes, so any object carrying those two attributes (the two-tower ``BoscaRecommender`` or the
content-only ``SimpleNamespace`` holder) can be exported through it.
"""

import json
import logging
import os
import shutil
import tempfile
from typing import Optional

import numpy as np
import tensorflow as tf
import tensorflow_recommenders as tfrs

from trainer.content_similarity import ContentSimilarityIndex
from trainer.models import build_item_query_model as _build_item_query_model
from trainer.behavior import write_snapshot
from trainer.datasets import extract_language_tags, extract_recommendation_contexts

log = logging.getLogger(__name__)


# Increment this whenever the personalized feature schema or model architecture changes incompatibly.
PERSONALIZED_MODEL_GENERATION = 4
_TRAINING_PROTOCOL = "all-eligible-data-v1"
_MODEL_MANIFEST = "model-manifest.json"


def _materialize_context_candidates(
    content_model,
    content_dataset: tf.data.Dataset,
    recommendation_contexts: dict[str, frozenset[str]],
    language_tags: dict[str, str],
):
    """Materializes candidate embeddings and their hard context/language facet membership."""
    ids, embeddings = [], []
    for batch in content_dataset.batch(128):
        ids.append(batch["content_id"])
        embeddings.append(content_model(batch))

    candidate_ids = tf.concat(ids, axis=0)
    candidate_embeddings = tf.concat(embeddings, axis=0)
    facets, candidate_indexes = _candidate_facets(candidate_ids, recommendation_contexts, language_tags)
    return candidate_ids, candidate_embeddings, facets, candidate_indexes


def _candidate_facets(candidate_ids, recommendation_contexts, language_tags):
    """Builds shared item-index memberships independently of a dense or sparse scoring representation."""
    decoded_ids = _decode_ids(candidate_ids)
    missing = [content_id for content_id in decoded_ids if content_id not in recommendation_contexts]
    if missing:
        raise ValueError(f"recommendation context membership is missing for content ids: {missing[:5]}")
    missing_languages = [content_id for content_id in decoded_ids if content_id not in language_tags]
    if missing_languages:
        raise ValueError(f"language tag is missing for content ids: {missing_languages[:5]}")
    candidate_indexes_by_facet: dict[str, list[int]] = {}
    for candidate_index, content_id in enumerate(decoded_ids):
        language_tag = language_tags[content_id]
        for context in recommendation_contexts[content_id]:
            candidate_indexes_by_facet.setdefault(_facet_key(context, language_tag), []).append(candidate_index)
    facets = sorted(candidate_indexes_by_facet)
    candidate_indexes = [
        tf.constant(candidate_indexes_by_facet[facet], dtype=tf.int32)
        for facet in facets
    ]
    return facets, candidate_indexes


def _facet_key(context_type: str, language_tag: str) -> str:
    return f"{context_type}\x1f{language_tag}"


def _split_facet_key(facet: str) -> tuple[str, str]:
    """Returns the context and normalized language encoded by ``_facet_key``."""
    context_type, language_tag = facet.split("\x1f", 1)
    return context_type, language_tag


def _read_facet_manifest(version_dir: str) -> Optional[set[str]]:
    """Loads the complete served-facet set stored with a model version, when available."""
    path = os.path.join(version_dir, "facets.json")
    if not os.path.exists(path):
        log.warning("Champion facet manifest is missing at %s; facet-removal gate is unavailable", path)
        return None
    with open(path) as file:
        return set(json.load(file)["facets"])


def _write_facet_manifest(version_dir: str, facets: list[str]) -> None:
    """Persists the complete served-facet set inside the versioned model artifact."""
    with open(os.path.join(version_dir, "facets.json"), "w") as file:
        json.dump({"facets": sorted(facets)}, file)


def _read_model_manifest(version_dir: str) -> Optional[dict]:
    """Loads generation and training-population metadata, or None for a legacy artifact."""
    path = os.path.join(version_dir, _MODEL_MANIFEST)
    if not os.path.exists(path):
        return None
    with open(path) as file:
        manifest = json.load(file)
    if not isinstance(manifest, dict):
        raise ValueError(f"Model manifest at {path} must contain a JSON object")
    return manifest


def _write_model_manifest(
    version_dir: str,
    model_generation: int,
    training_users: int,
    indexed_users: int,
    training_data_cutoff: Optional[str] = None,
) -> None:
    """Persists artifact compatibility and full-data training-population metadata."""
    with open(os.path.join(version_dir, _MODEL_MANIFEST), "w") as file:
        json.dump(
            {
                "model_generation": model_generation,
                "training_protocol": _TRAINING_PROTOCOL,
                "ranking_objective": "binary_crossentropy_logits",
                "training_users": training_users,
                "indexed_users": indexed_users,
                "training_data_cutoff": training_data_cutoff,
            },
            file,
            sort_keys=True,
        )


def _missing_champion_facets(
    champion_facets: set[str],
    challenger_facets: list[str],
    retired_facets: Optional[set[tuple[str, str]]] = None,
) -> list[str]:
    """Returns champion facets absent from the challenger unless explicitly retired."""
    allowed_missing = {_facet_key(*facet) for facet in (retired_facets or set())}
    return sorted(champion_facets - set(challenger_facets) - allowed_missing)


_SCANN_MIN_PARTITION_SIZE = 100


def _build_partition_index(use_scann, candidate_ids, candidate_embeddings, k, name):
    """Builds one exact or ANN index whose corpus is already restricted to a context."""
    candidate_count = int(candidate_ids.shape[0])
    if use_scann and candidate_count >= _SCANN_MIN_PARTITION_SIZE:
        num_leaves = min(100, max(1, int(np.sqrt(candidate_count))))
        index = tfrs.layers.factorized_top_k.ScaNN(
            k=k,
            num_leaves=num_leaves,
            num_leaves_to_search=min(10, num_leaves),
            name=name,
        )
    else:
        index = tfrs.layers.factorized_top_k.BruteForce(k=k, name=name)
    return index.index(candidate_embeddings, identifiers=candidate_ids)


class _ContextPartitionedTopK(tf.Module):
    """Routes each query to an index containing only its requested context/language facet."""

    def __init__(
        self,
        query_model,
        candidate_ids,
        candidate_embeddings,
        context_types,
        candidate_indexes,
        k,
        use_scann,
    ):
        super().__init__()
        self.query_model = query_model
        self.context_lookup = tf.keras.layers.StringLookup(
            vocabulary=context_types,
            mask_token=None,
            num_oov_indices=1,
        )
        self.k = k
        self.score_dtype = candidate_embeddings.dtype
        self.partition_sizes = []
        self.partition_indexes = []
        for position, indexes in enumerate(candidate_indexes, start=1):
            partition_ids = tf.gather(candidate_ids, indexes)
            partition_embeddings = tf.gather(candidate_embeddings, indexes)
            partition_k = min(k, int(partition_ids.shape[0]))
            index = _build_partition_index(
                use_scann,
                partition_ids,
                partition_embeddings,
                partition_k,
                name=f"context_index_{position}",
            )
            setattr(self, f"context_index_{position}", index)
            self.partition_indexes.append(index)
            self.partition_sizes.append(partition_k)

    def _query_partition(self, index, query_embedding, result_count):
        scores, result_ids = index(tf.expand_dims(query_embedding, axis=0))
        scores, result_ids = scores[0], result_ids[0]
        padding = self.k - result_count
        if padding > 0:
            scores = tf.concat([scores, tf.zeros([padding], dtype=scores.dtype)], axis=0)
            result_ids = tf.concat([result_ids, tf.fill([padding], "")], axis=0)
        return scores, result_ids

    def __call__(self, ids, context_types, language_tags):
        query_embeddings = self.query_model(ids)
        facet_keys = tf.strings.join([context_types, language_tags], separator="\x1f")

        def retrieve_one(inputs):
            query_embedding, context_type = inputs
            branch_index = tf.cast(self.context_lookup(context_type), tf.int32) - 1
            branches = [
                lambda index=index, result_count=result_count: self._query_partition(
                    index,
                    query_embedding,
                    result_count,
                )
                for index, result_count in zip(self.partition_indexes, self.partition_sizes)
            ]
            return tf.switch_case(
                branch_index,
                branch_fns=branches,
                default=lambda: (
                    tf.zeros([self.k], dtype=self.score_dtype),
                    tf.fill([self.k], ""),
                ),
            )

        return tf.map_fn(
            retrieve_one,
            (query_embeddings, facet_keys),
            fn_output_signature=(
                tf.TensorSpec(shape=[self.k], dtype=self.score_dtype),
                tf.TensorSpec(shape=[self.k], dtype=tf.string),
            ),
        )


class _ContextRankedTopK(tf.Module):
    """Apply the trained ranking head to every eligible item before choosing top-K.

    Item and user representations are captured in the export. Each request supplies IDs and a facet;
    there is no retrieval shortlist that can discard a higher-ranked eligible item.
    """

    def __init__(self, query_model, ranking_model, candidate_ids, candidate_embeddings,
                 context_types, candidate_indexes, k):
        super().__init__()
        self.query_model = query_model
        self.ranking_model = ranking_model
        self.candidate_ids = candidate_ids
        self.candidate_embeddings = candidate_embeddings
        self.context_lookup = tf.keras.layers.StringLookup(vocabulary=context_types, mask_token=None)
        self.candidate_indexes = [tf.constant(indexes, dtype=tf.int32) for indexes in candidate_indexes]
        self.k = k

    def _rank_partition(self, user_embedding, indexes, source_id=None, offset=0, user_id="", behavioral_only=False):
        if source_id is not None:
            indexes = tf.boolean_mask(indexes, tf.gather(self.candidate_ids, indexes) != source_id)
        if behavioral_only:
            behavior = self.ranking_model.behavior_model
            if behavior is None:
                indexes = indexes[:0]
            else:
                item_ids = tf.gather(self.candidate_ids, indexes)
                evidence = behavior.global_edges.lookup(tf.strings.join(
                    [tf.fill(tf.shape(item_ids), source_id), item_ids], separator="\x1f"))
                indexes = tf.boolean_mask(indexes, evidence > 0.)
        item_ids = tf.gather(self.candidate_ids, indexes)
        item_embeddings = tf.gather(self.candidate_embeddings, indexes)
        count = tf.shape(item_ids)[0]
        user_embeddings = tf.repeat(user_embedding[None, :], count, axis=0)
        kwargs = {"user_id": tf.fill([count], user_id)}
        if source_id is not None:
            kwargs["source_id"] = tf.repeat(source_id[None], count)
        scores = tf.reshape(self.ranking_model.score_pairs(user_embeddings, item_embeddings, item_ids, **kwargs), [-1])
        offset = tf.maximum(offset, 0)
        ranked = tf.math.top_k(scores, k=tf.minimum(count, offset + self.k), sorted=True)
        selected_scores, selected_indexes = ranked.values[offset:], ranked.indices[offset:]
        padding = self.k - tf.size(selected_indexes)
        return (tf.concat([selected_scores, tf.zeros([padding], dtype=scores.dtype)], axis=0),
                tf.concat([tf.gather(item_ids, selected_indexes), tf.fill([padding], "")], axis=0))

    def __call__(self, ids, context_types, language_tags, source_ids=None, offsets=None, behavioral_only=False):
        query_embeddings = self.query_model(ids)
        facets = tf.strings.join([context_types, language_tags], separator="\x1f")
        related = source_ids is not None
        if source_ids is None:
            source_ids = tf.fill(tf.shape(ids), "")
        if offsets is None:
            offsets = tf.zeros(tf.shape(ids), tf.int32)

        def rank_one(inputs):
            embedding, facet, source, offset, user = inputs
            def empty():
                return tf.zeros([self.k], self.candidate_embeddings.dtype), tf.fill([self.k], "")
            def ranked():
                return tf.switch_case(
                    tf.cast(self.context_lookup(facet), tf.int32) - 1,
                    branch_fns=[lambda indexes=indexes: self._rank_partition(
                        embedding, indexes, source if related else None, offset, user, behavioral_only,
                    ) for indexes in self.candidate_indexes], default=empty,
                )
            if related:
                return tf.cond(tf.reduce_any(self.candidate_ids == source), ranked, empty)
            return ranked()

        return tf.map_fn(rank_one, (query_embeddings, facets, source_ids, offsets, ids), fn_output_signature=(
            tf.TensorSpec([self.k], self.candidate_embeddings.dtype), tf.TensorSpec([self.k], tf.string),
        ), parallel_iterations=1)


def _build_user_query_model(user_model, user_dataset: tf.data.Dataset):
    """A query model mapping a user_id string to its feature-derived user-tower embedding.

    Materializes the user tower's embedding for every profile — including zero-interaction ones — keyed
    by user_id. Because the embedding is computed from features (profile type + category affinity), a
    cold profile resolves to a feature-derived vector rather than a single shared OOV one. `serve` and
    `rank` query this table, so no user features need to be supplied at serve time. Retrieval additionally
    receives ``context_type`` to mask candidates before top-K selection.
    """
    ids, embs = [], []
    for batch in user_dataset.batch(128):
        ids.append(batch["user_id"])
        embs.append(user_model(batch))
    all_ids = tf.concat(ids, axis=0)
    all_emb = tf.concat(embs, axis=0)

    # Dedup by user_id (StringLookup vocab must be unique), keeping the first embedding seen.
    id_to_row: dict[str, int] = {}
    decoded = [s.decode("utf-8") if isinstance(s, bytes) else str(s) for s in all_ids.numpy()]
    for row, uid in enumerate(decoded):
        id_to_row.setdefault(uid, row)
    uniq_ids = list(id_to_row.keys())
    uniq_emb = tf.gather(all_emb, [id_to_row[u] for u in uniq_ids])
    dim = int(uniq_emb.shape[1])

    lookup = tf.keras.layers.StringLookup(vocabulary=uniq_ids, mask_token=None, num_oov_indices=1)
    # Row 0 = OOV -> zero vector (a profile created after this training run retrieves nothing and the
    # caller falls back to trending); rows 1..N align to vocab indices.
    table = tf.Variable(
        tf.concat([tf.zeros([1, dim], dtype=uniq_emb.dtype), uniq_emb], axis=0),
        trainable=False,
    )

    class UserQueryModel(tf.keras.Model):
        def __init__(self):
            super().__init__()
            self.lookup = lookup
            self.table = table

        def call(self, user_id):
            return tf.gather(self.table, self.lookup(user_id))

    return UserQueryModel()


def _decode_ids(id_tensor) -> list:
    return [s.decode("utf-8") if isinstance(s, bytes) else str(s) for s in id_tensor.numpy()]


def _validate_prediction(result: dict, allowed_ids: set[str], label: str, allow_empty: bool = False) -> tuple[list[str], np.ndarray]:
    """Validates the common TF Serving recommendation envelope and returns its decoded first row."""
    if "content_ids" not in result or "scores" not in result:
        raise ValueError(f"{label} is missing content_ids or scores")
    ids_tensor = result["content_ids"]
    scores_tensor = result["scores"]
    if ids_tensor.dtype != tf.string or ids_tensor.shape != scores_tensor.shape or len(ids_tensor.shape) != 2 or ids_tensor.shape[0] != 1:
        raise ValueError(f"{label} returned an invalid content_ids/scores shape")
    scores = np.asarray(scores_tensor.numpy())
    if not np.all(np.isfinite(scores)):
        raise ValueError(f"{label} returned a non-finite score")
    ids = [content_id for content_id in _decode_ids(ids_tensor[0]) if content_id]
    if not ids and not allow_empty:
        raise ValueError(f"{label} returned no results for a known query")
    if len(ids) != len(set(ids)):
        raise ValueError(f"{label} returned duplicate ids")
    unknown = set(ids) - allowed_ids
    if unknown:
        raise ValueError(f"{label} returned ids outside its serving corpus: {sorted(unknown)}")
    return ids, scores


def _validate_page(loaded, signature, query, allowed_ids, source=None):
    """Probe production pagination without assuming every eligible query has matches."""
    call = loaded.signatures[signature]

    def page(offset, fields=query):
        result = call(**fields, offset=tf.constant([offset], tf.int32))
        ids, scores = _validate_prediction(result, allowed_ids, signature, allow_empty=True)
        if source is not None and source in ids:
            raise ValueError(f"{signature} returned its source item")
        if np.any(np.diff(scores[0, :len(ids)]) > 0):
            raise ValueError(f"{signature} returned unsorted scores")
        return ids, scores

    ids, scores = page(0)
    repeated_ids, repeated_scores = page(0)
    if ids != repeated_ids or not np.array_equal(scores, repeated_scores):
        raise ValueError(f"{signature} is not deterministic")
    next_ids, _ = page(scores.shape[1])
    if set(ids) & set(next_ids):
        raise ValueError(f"{signature} repeats items across pages")
    if page(len(allowed_ids))[0]:
        raise ValueError(f"{signature} did not exhaust its eligible corpus")
    if source is not None:
        unknown = "__bosca_contract_validation_unknown_item__"
        while unknown in allowed_ids:
            unknown += "_"
        source_key = "source_id" if "source_id" in query else "content_id"
        if page(0, {**query, source_key: tf.constant([unknown])})[0]:
            raise ValueError(f"{signature} returned results for an unknown source")
    return ids, scores


def _validate_explanation(loaded, user, source, candidates):
    result = loaded.signatures["explain"](
        user_id=tf.constant([user] * len(candidates)), source_id=tf.constant([source] * len(candidates)),
        content_id=tf.constant(candidates),
    )
    values = result.get("contributions")
    if values is None or values.shape != (len(candidates), 4) or not np.isfinite(values.numpy()).all():
        raise ValueError("explain returned invalid contributions")


def _validate_exported_recommender(
    loaded,
    known_content_ids: set[str],
    known_user_ids: set[str],
    sample_user: str,
    sample_item: str,
    context_type: str,
    language_tag: str,
) -> dict:
    """Fails closed unless a personalized SavedModel honors the production serving contract."""
    required = {"serving_default", "similar", "similar_users", "rank",
                "related", "feed_page", "co_engaged", "explain"}
    missing = required - set(loaded.signatures)
    if missing:
        raise ValueError(f"personalized model is missing signatures: {sorted(missing)}")

    serve = loaded.signatures["serving_default"]
    query = {
        "user_id": tf.constant([sample_user]),
        "context_type": tf.constant([context_type]),
        "language_tag": tf.constant([language_tag]),
    }
    first = serve(**query)
    first_ids, first_scores = _validate_prediction(first, known_content_ids, "serving_default")
    second = serve(**query)
    second_ids, second_scores = _validate_prediction(second, known_content_ids, "serving_default")
    if first_ids != second_ids or not np.array_equal(first_scores, second_scores):
        raise ValueError("serving_default is not deterministic")

    unknown_user = "__bosca_contract_validation_unknown_user__"
    if unknown_user in known_user_ids:
        raise ValueError("contract-validation user id collides with the serving population")
    unknown = serve(
        user_id=tf.constant([unknown_user]),
        context_type=tf.constant([context_type]),
        language_tag=tf.constant([language_tag]),
    )
    if any(_decode_ids(unknown["content_ids"][0])):
        raise ValueError("serving_default returned personalized results for an unknown user")

    similar = loaded.signatures["similar"](
        content_id=tf.constant([sample_item]),
        context_type=tf.constant([context_type]),
        language_tag=tf.constant([language_tag]),
    )
    _validate_prediction(similar, known_content_ids, "similar")

    neighbors = loaded.signatures["similar_users"](user_id=tf.constant([sample_user]))
    _validate_prediction(neighbors, known_user_ids, "similar_users")

    ranked = loaded.signatures["rank"](
        user_id=tf.constant([sample_user]),
        content_id=tf.constant([sample_item]),
    )
    rank_scores = np.asarray(ranked.get("scores", []).numpy()) if "scores" in ranked else np.array([])
    if rank_scores.shape != (1,) or not np.all(np.isfinite(rank_scores)):
        raise ValueError("rank returned an invalid score")

    _validate_page(loaded, "feed_page", query, known_content_ids)
    cold_ids, _ = _validate_page(loaded, "feed_page", {**query, "user_id": tf.constant([unknown_user])}, known_content_ids)
    if cold_ids:
        raise ValueError("feed_page returned personalized results for an unknown user")
    related_query = {**query, "source_id": tf.constant([sample_item])}
    related_ids, related_scores = _validate_page(loaded, "related", related_query, known_content_ids, sample_item)
    _validate_page(loaded, "co_engaged", related_query, known_content_ids, sample_item)
    _validate_explanation(loaded, sample_user, sample_item, related_ids or [sample_item])
    after_ids, after_scores = _validate_page(loaded, "related", related_query, known_content_ids, sample_item)
    if after_ids != related_ids or not np.array_equal(after_scores, related_scores):
        raise ValueError("explain changed related ranking")

    return {
        "status": "passed",
        "signatures": sorted(required),
        "probe_user": sample_user,
        "probe_context": context_type,
        "probe_language": language_tag,
    }


def _validate_exported_content_model(
    loaded,
    known_content_ids: set[str],
    sample_item: str,
    context_type: str,
    language_tag: str,
) -> dict:
    """Fails closed unless a content SavedModel honors its production serving contract."""
    required = {"serving_default", "similar", "similar_page", "explain"}
    missing = required - set(loaded.signatures)
    if missing:
        raise ValueError(f"content model is missing signatures: {sorted(missing)}")
    query = {
        "content_id": tf.constant([sample_item]),
        "context_type": tf.constant([context_type]),
        "language_tag": tf.constant([language_tag]),
    }
    first = loaded.signatures["similar"](**query)
    first_ids, first_scores = _validate_prediction(first, known_content_ids, "content similar")
    second = loaded.signatures["similar"](**query)
    second_ids, second_scores = _validate_prediction(second, known_content_ids, "content similar")
    if first_ids != second_ids or not np.array_equal(first_scores, second_scores):
        raise ValueError("content similar is not deterministic")
    page_ids, page_scores = _validate_page(loaded, "similar_page", query, known_content_ids, sample_item)
    _validate_explanation(loaded, "", sample_item, page_ids or [sample_item])
    after_ids, after_scores = _validate_page(loaded, "similar_page", query, known_content_ids, sample_item)
    if after_ids != page_ids or not np.array_equal(after_scores, page_scores):
        raise ValueError("explain changed content ranking")
    return {
        "status": "passed",
        "signatures": sorted(required),
        "probe_context": context_type,
        "probe_language": language_tag,
    }


def _retrieve_ids(index, query_model, ids, oov_empty: bool) -> dict:
    """Runs a factorized-top-k retrieval index for a batch of string ids and packs the served
    ``{"content_ids", "scores"}`` envelope (the shape the Kotlin ``TfServingClient`` parses).

    Under ``oov_empty`` (the personalized user indexes), rows whose id ``query_model`` never saw are
    blanked: an out-of-vocab id maps to the zero/OOV embedding row, so the index returns arbitrary
    nearest-to-origin items — blanking makes the serving layer read "no result" (and fall back to the
    content model / trending) instead of surfacing junk. Item indexes pass ``oov_empty=False`` (an unknown
    item legitimately retrieves nothing to blank).

    Shared by ``serve``/``serve_similar``/``serve_similar_users`` so the OOV logic lives once. Written with
    plain tensor ops (no data-dependent Python control flow) so it runs eagerly for direct unit testing and
    traces cleanly into each graph signature.
    """
    scores, result_ids = index(ids)
    if oov_empty:
        known = tf.not_equal(query_model.lookup(ids), 0)[:, tf.newaxis]
        result_ids = tf.where(known, result_ids, tf.fill(tf.shape(result_ids), ""))
        scores = tf.where(known, scores, tf.zeros_like(scores))
    return {"content_ids": result_ids, "scores": scores}


def _retrieve_context_ids(index, query_model, ids, context_types, language_tags, oov_empty: bool) -> dict:
    """Retrieves from a context/language candidate index and blanks out-of-vocabulary query ids."""
    scores, result_ids = index(ids, context_types, language_tags)
    if oov_empty:
        known = tf.not_equal(query_model.lookup(ids), 0)[:, tf.newaxis]
        result_ids = tf.where(known, result_ids, tf.fill(tf.shape(result_ids), ""))
        scores = tf.where(known, scores, tf.zeros_like(scores))
    return {"content_ids": result_ids, "scores": scores}


def _rank_scores(ranking_model, user_query_model, item_query_model, user_id, content_id) -> dict:
    """The learned second-stage ranker's score for aligned ``(user, item)`` id pairs, from the shared
    embeddings (user tower ⊕ the item lookup). Flattened to one score per pair. Eager-friendly for direct
    unit testing; the graph-traced ``rank`` signature delegates here."""
    scores = ranking_model.score_pairs(user_query_model(user_id), item_query_model(content_id), content_id, user_id=user_id)
    return {"scores": tf.reshape(scores, [-1])}


def export_model(
    model,
    ranking_model,
    content_dataset: tf.data.Dataset,
    recommendation_contexts: dict[str, frozenset[str]],
    language_tags: dict[str, str],
    user_dataset: tf.data.Dataset,
    vocabs: dict,
    model_dir: str,
    use_scann: bool,
    top_k: int,
    preserve_serving_facets: bool = True,
    retired_facets: Optional[set[tuple[str, str]]] = None,
    oov_empty: bool = False,
    model_generation: int = PERSONALIZED_MODEL_GENERATION,
    training_data_cutoff: Optional[str] = None,
    model_version: Optional[int] = None,
    behavior_snapshot=None,
):
    """Exports full-catalog personalized ranking and learned similarity as a SavedModel.

    The complete exported SavedModel is loaded and exercised before this function reports it as promoted.
    Existing context/language facets must remain available unless they were explicitly retired. No training
    interactions are withheld and no offline proxy metric compares this artifact with an older model.
    """
    version = 1
    existing: list[int] = []
    if os.path.exists(model_dir):
        existing = [int(d) for d in os.listdir(model_dir) if d.isdigit()]
        if existing:
            version = max(existing) + 1
    champion_version = max(existing) if existing else None
    champion_manifest = (
        _read_model_manifest(os.path.join(model_dir, str(champion_version)))
        if champion_version is not None
        else None
    )
    if model_version is not None:
        if isinstance(model_version, bool) or not isinstance(model_version, int) or model_version < 1:
            raise ValueError("model_version must be a positive integer")
        version = model_version
    champion_training_users = champion_manifest.get("training_users") if champion_manifest else None
    champion_indexed_users = champion_manifest.get("indexed_users") if champion_manifest else None
    champion_training_data_cutoff = champion_manifest.get("training_data_cutoff") if champion_manifest else None

    version_dir = os.path.join(model_dir, str(version))

    def build_index(query_model, k):
        if use_scann:  # pragma: no cover - ScaNN is an optional native dep, not installed in CI
            return tfrs.layers.factorized_top_k.ScaNN(query_model, k=k)
        return tfrs.layers.factorized_top_k.BruteForce(query_model, k=k)

    candidate_ids, candidate_embeddings, context_types, candidate_indexes = (
        _materialize_context_candidates(model.content_model, content_dataset, recommendation_contexts, language_tags)
    )

    if preserve_serving_facets and champion_version is not None:
        champion_facets = _read_facet_manifest(os.path.join(model_dir, str(champion_version)))
        if champion_facets is not None:
            missing_facets = _missing_champion_facets(champion_facets, context_types, retired_facets)
            if missing_facets:
                log.warning(
                    "SERVING COMPATIBILITY CHECK: rejecting new model — candidate is missing active facets: %s",
                    ", ".join(missing_facets),
                )
                return {
                    "version": None,
                    "promoted": False,
                    "missing_facets": missing_facets,
                    "champion_training_users": champion_training_users,
                    "champion_indexed_users": champion_indexed_users,
                    "champion_training_data_cutoff": champion_training_data_cutoff,
                }

    # Clamp k to the candidate-corpus size. A cold-start install can have fewer content items than the
    # configured top_k (default 50), and BruteForce/ScaNN raise if k exceeds the candidate count. The
    # `similar` index asks for one extra (the item is its own nearest neighbor; the consumer drops it).
    content_count = int(content_dataset.cardinality().numpy())
    user_top_k = max(1, min(top_k, content_count))
    item_top_k = max(1, min(top_k + 1, content_count))

    # Materialize profile representations once; rank every item in the requested eligible facet.
    log.info("Building exact context-aware user->items ranking...")
    user_query_model = _build_user_query_model(model.user_model, user_dataset)
    user_index = _ContextRankedTopK(
        user_query_model,
        ranking_model,
        candidate_ids,
        candidate_embeddings,
        context_types,
        candidate_indexes,
        user_top_k,
    )

    # Item -> similar items retrieval (query side = an item's own content-tower embedding). Shares the
    # item tower's learned representation so "similar"/"related" become ML-learned and cold-start capable.
    log.info("Building context-aware item->similar retrieval index from content-tower embeddings...")
    item_query_model = _build_item_query_model(model.content_model, content_dataset)
    item_index = _ContextPartitionedTopK(
        item_query_model,
        candidate_ids,
        candidate_embeddings,
        context_types,
        candidate_indexes,
        item_top_k,
        use_scann,
    )

    # User -> similar users retrieval (query side = a user's own user-tower embedding; corpus = every user's
    # embedding). The dense "people like you" complement to cohort co-engagement: nearest users by the learned
    # embedding (signals + affinity + id), so a viewer's neighbors can seed neighbor-conditioned candidates.
    log.info("Building user->similar-users retrieval index from user-tower embeddings...")
    user_ids_for_index = user_dataset.batch(128).map(lambda x: x["user_id"])
    user_embeddings_for_index = user_dataset.batch(128).map(model.user_model)
    user_count = int(user_dataset.cardinality().numpy())
    user_similar_top_k = max(1, min(top_k + 1, user_count))  # +1: a user is its own nearest neighbor (consumer drops it)
    user_similar_index = build_index(user_query_model, user_similar_top_k)
    user_similar_index.index_from_dataset(tf.data.Dataset.zip((user_ids_for_index, user_embeddings_for_index)))

    log.info("Exporting SavedModel to %s (version %d)...", model_dir, version)
    save_options = tf.saved_model.SaveOptions(
        namespace_whitelist=["Scann"] if use_scann else []
    )

    # One SavedModel exposing four signatures following the Kotlin TfServingClient contracts:
    #   - serving_default(user_id, context_type, language_tag): user->items personalized retrieval.
    #   - similar(content_id, context_type, language_tag): item->items shared-embedding retrieval.
    #   - similar_users(user_id): user->nearest-users retrieval.
    #   - rank(user_id, content_id): per-candidate learned ranking.
    server = tf.Module()
    server.user_index = user_index
    server.item_index = item_index

    # serving_default(user_id, context_type) -> user->items retrieval. With oov_empty (the personalized model), a user the
    # model never saw is out-of-vocab: its embedding is the zero row, so the index returns arbitrary
    # nearest-to-origin items. `_retrieve_ids` blanks those rows so the serving layer reads "no result" for
    # unknown users (and falls back to the content model / trending) instead of junk.
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None], dtype=tf.string, name="user_id"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="context_type"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="language_tag"),
    ])
    def serve(user_id, context_type, language_tag):  # pragma: no cover - graph-traced by tf.saved_model.save
        return _retrieve_context_ids(server.user_index, user_query_model, user_id, context_type, language_tag, oov_empty)

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None], dtype=tf.string, name="content_id"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="context_type"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="language_tag"),
    ])
    def serve_similar(content_id, context_type, language_tag):  # pragma: no cover - graph-traced
        return _retrieve_context_ids(server.item_index, None, content_id, context_type, language_tag, False)

    # similar_users(user_id) -> user->users retrieval: the viewer's nearest users by learned embedding. Ids
    # come back in the `content_ids` field (same envelope as the other signatures — the serving client parses
    # it generically). Blanks OOV users under oov_empty exactly like serve(), so a user the model never saw
    # returns no neighbors rather than nearest-to-origin junk.
    server.user_similar_index = user_similar_index

    @tf.function(input_signature=[tf.TensorSpec(shape=[None], dtype=tf.string, name="user_id")])
    def serve_similar_users(user_id):  # pragma: no cover - graph-traced; logic covered via _retrieve_ids
        return _retrieve_ids(server.user_similar_index, user_query_model, user_id, oov_empty)

    # rank(user_id[], content_id[]) -> score[] : the learned second-stage ranker. Scores (user, item)
    # pairs from the shared embeddings (user tower + the item lookup) so the two-stage serve path can
    # re-rank a candidate set. Pairwise (fixed-shape) inputs batch cleanly across TF Serving instances.
    server.user_query_model = user_query_model
    server.item_query_model = item_query_model
    server.ranking_model = ranking_model

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None], dtype=tf.string, name="user_id"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="content_id"),
    ])
    def rank(user_id, content_id):  # pragma: no cover - graph-traced; logic covered via _rank_scores
        return _rank_scores(server.ranking_model, server.user_query_model, server.item_query_model, user_id, content_id)

    @tf.function(input_signature=[
        tf.TensorSpec([None], tf.string, name="user_id"),
        tf.TensorSpec([None], tf.string, name="source_id"),
        tf.TensorSpec([None], tf.string, name="context_type"),
        tf.TensorSpec([None], tf.string, name="language_tag"),
        tf.TensorSpec([None], tf.int32, name="offset"),
    ])
    def related(user_id, source_id, context_type, language_tag, offset):
        scores, ids = server.user_index(user_id, context_type, language_tag, source_id, offset)
        return {"scores": scores, "content_ids": ids}

    @tf.function(input_signature=[
        tf.TensorSpec([None], tf.string, name="user_id"),
        tf.TensorSpec([None], tf.string, name="context_type"),
        tf.TensorSpec([None], tf.string, name="language_tag"),
        tf.TensorSpec([None], tf.int32, name="offset"),
    ])
    def feed_page(user_id, context_type, language_tag, offset):
        scores, ids = server.user_index(user_id, context_type, language_tag, offsets=offset)
        if oov_empty:
            known = server.user_query_model.lookup(user_id) > 0
            scores, ids = tf.where(known[:, None], scores, 0.), tf.where(known[:, None], ids, "")
        return {"scores": scores, "content_ids": ids}

    @tf.function(input_signature=related.input_signature)
    def co_engaged(user_id, source_id, context_type, language_tag, offset):
        scores, ids = server.user_index(user_id, context_type, language_tag, source_id, offset, behavioral_only=True)
        return {"scores": scores, "content_ids": ids}

    @tf.function(input_signature=[
        tf.TensorSpec([None], tf.string, name="user_id"),
        tf.TensorSpec([None], tf.string, name="source_id"),
        tf.TensorSpec([None], tf.string, name="content_id"),
    ])
    def explain(user_id, source_id, content_id):
        contributions = server.ranking_model.explain_pairs(
            server.user_query_model(user_id), server.item_query_model(content_id), content_id, user_id, source_id,
        )
        known = server.item_query_model.lookup(content_id) > 0
        return {"contributions": tf.where(known[:, None], contributions, 0.)}

    server.co_engaged = co_engaged
    server.explain = explain
    server.related = related
    server.feed_page = feed_page
    server.serve = serve
    server.serve_similar = serve_similar
    server.serve_similar_users = serve_similar_users
    server.rank = rank
    test_user = vocabs["user_ids"][0] if vocabs.get("user_ids") else None
    if test_user is None:
        raise ValueError("personalized model has no eligible users to validate")
    validation_facet = context_types[0]
    validation_context, validation_language = _split_facet_key(validation_facet)
    validation_facet_index = context_types.index(validation_facet)
    validation_item_index = int(candidate_indexes[validation_facet_index][0])
    validation_item = _decode_ids(candidate_ids[validation_item_index:validation_item_index + 1])[0]
    os.makedirs(model_dir, exist_ok=True)
    candidate_dir = tempfile.mkdtemp(prefix=f".candidate-{version}-", dir=model_dir)
    try:
        tf.saved_model.save(
            server,
            candidate_dir,
            signatures={
                "serving_default": serve,
                "similar": serve_similar,
                "similar_users": serve_similar_users,
                "rank": rank,
                "related": related,
                "feed_page": feed_page,
                "co_engaged": co_engaged,
                "explain": explain,
            },
            options=save_options,
        )
        _write_facet_manifest(candidate_dir, context_types)
        _write_model_manifest(
            candidate_dir,
            model_generation,
            len(vocabs.get("training_user_ids", vocabs.get("user_ids", []))),
            user_count,
            training_data_cutoff,
        )
        loaded = tf.saved_model.load(candidate_dir)
        if behavior_snapshot is not None:
            write_snapshot(candidate_dir, behavior_snapshot)
        validation = _validate_exported_recommender(
            loaded,
            set(_decode_ids(tf.gather(candidate_ids, candidate_indexes[validation_facet_index]))),
            set(vocabs["user_ids"]),
            test_user,
            validation_item,
            validation_context,
            validation_language,
        )
        os.replace(candidate_dir, version_dir)
    except Exception:
        shutil.rmtree(candidate_dir, ignore_errors=True)
        raise
    log.info("Personalized model contract validation passed: %s", validation)
    log.info(
        "Model exported successfully to %s (signatures: serving_default, similar, similar_users, rank)", version_dir,
    )

    return {
        "version": version,
        "promoted": True,
        "champion_training_users": champion_training_users,
        "champion_indexed_users": champion_indexed_users,
        "champion_training_data_cutoff": champion_training_data_cutoff,
        "validation": validation,
    }


def export_content_model(
    content,
    categories,
    model_dir: str,
    top_k: int,
    preserve_serving_facets: bool = True,
    retired_facets: Optional[set[tuple[str, str]]] = None,
    weights: Optional[dict] = None,
    model_version: Optional[int] = None,
):
    """Exports exact sparse content similarity, sharing feature storage across all serving facets.

    Item-keyed only — no user tower, no ``rank``. The content model answers "given this item, what content
    is similar." Source-conditioned personalization belongs to the matching personalized export.
    ``serving_default`` is aliased to ``similar``
    (both keyed by ``content_id``) so the artifact still has a default signature for TF Serving.
    """
    version = 1
    existing: list[int] = []
    if os.path.exists(model_dir):
        existing = [int(d) for d in os.listdir(model_dir) if d.isdigit()]
        if existing:
            version = max(existing) + 1
    champion_version = max(existing) if existing else None
    if model_version is not None:
        if isinstance(model_version, bool) or not isinstance(model_version, int) or model_version < 1:
            raise ValueError("model_version must be a positive integer")
        version = model_version
    version_dir = os.path.join(model_dir, str(version))

    if top_k < 1:
        raise ValueError("content top_k must be positive")
    content = content.drop_duplicates("content_id").sort_values("content_id").reset_index(drop=True)
    candidate_ids = tf.constant(content["content_id"].values, dtype=tf.string)
    context_types, candidate_indexes = _candidate_facets(
        candidate_ids, extract_recommendation_contexts(content), extract_language_tags(content),
    )
    if not context_types:
        raise ValueError("content model requires at least one serving facet")
    if preserve_serving_facets and champion_version is not None:
        champion_facets = _read_facet_manifest(os.path.join(model_dir, str(champion_version)))
        if champion_facets is not None:
            missing_facets = _missing_champion_facets(champion_facets, context_types, retired_facets)
            if missing_facets:
                log.warning(
                    "CONTENT SERVING COMPATIBILITY CHECK: rejecting new model — candidate is missing active facets: %s",
                    ", ".join(missing_facets),
                )
                return {
                    "version": None,
                    "promoted": False,
                    "missing_facets": missing_facets,
                }
    # +1: an item is its own nearest neighbor (the consumer drops it); clamp to the corpus size.
    content_count = len(content)
    item_top_k = max(1, min(top_k + 1, content_count))

    log.info("Building context-aware content item->similar index...")
    item_index = ContentSimilarityIndex(
        content,
        categories,
        context_types,
        candidate_indexes,
        item_top_k,
        weights=weights,
    )

    server = tf.Module()
    server.item_index = item_index

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None], dtype=tf.string, name="content_id"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="context_type"),
        tf.TensorSpec(shape=[None], dtype=tf.string, name="language_tag"),
    ])
    def serve_similar(content_id, context_type, language_tag):  # pragma: no cover - graph-traced
        return _retrieve_context_ids(server.item_index, None, content_id, context_type, language_tag, False)

    @tf.function(input_signature=[
        tf.TensorSpec([None], tf.string, name="content_id"),
        tf.TensorSpec([None], tf.string, name="context_type"),
        tf.TensorSpec([None], tf.string, name="language_tag"),
        tf.TensorSpec([None], tf.int32, name="offset"),
    ])
    def similar_page(content_id, context_type, language_tag, offset):
        scores, ids = server.item_index(content_id, context_type, language_tag, offset, exclude_self=True)
        return {"scores": scores, "content_ids": ids}

    @tf.function(input_signature=[
        tf.TensorSpec([None], tf.string, name="user_id"),
        tf.TensorSpec([None], tf.string, name="source_id"),
        tf.TensorSpec([None], tf.string, name="content_id"),
    ])
    def explain(user_id, source_id, content_id):
        index = server.item_index
        preference = tf.gather(index.type_preferences, tf.maximum(index.lookup(content_id) - 1, 0))
        contribution = index.settings["content"] * index.pair_similarity(source_id, content_id) * (1. + preference) / 2.
        zeros = tf.zeros_like(contribution)
        return {"contributions": tf.stack([contribution, zeros, zeros, zeros], axis=1)}

    server.similar_page = similar_page
    server.explain = explain
    server.serve_similar = serve_similar
    log.info("Exporting content SavedModel to %s (version %d)...", model_dir, version)
    validation_facet = context_types[0]
    validation_context, validation_language = _split_facet_key(validation_facet)
    validation_facet_index = context_types.index(validation_facet)
    validation_item_index = int(candidate_indexes[validation_facet_index][0])
    validation_item = _decode_ids(candidate_ids[validation_item_index:validation_item_index + 1])[0]
    os.makedirs(model_dir, exist_ok=True)
    candidate_dir = tempfile.mkdtemp(prefix=f".candidate-{version}-", dir=model_dir)
    try:
        tf.saved_model.save(
            server,
            candidate_dir,
            signatures={"serving_default": serve_similar, "similar": serve_similar, "similar_page": similar_page, "explain": explain},
        )
        _write_facet_manifest(candidate_dir, context_types)
        validation = _validate_exported_content_model(
            tf.saved_model.load(candidate_dir),
            set(_decode_ids(tf.gather(candidate_ids, candidate_indexes[validation_facet_index]))),
            validation_item,
            validation_context,
            validation_language,
        )
        os.replace(candidate_dir, version_dir)
    except Exception:
        shutil.rmtree(candidate_dir, ignore_errors=True)
        raise
    log.info("Content model contract validation passed: %s", validation)
    log.info("Content model exported successfully to %s (signature: similar)", version_dir)
    return {"version": version, "promoted": True, "missing_facets": [], "validation": validation}
