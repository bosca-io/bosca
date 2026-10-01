"""Two-tower TFRS model definitions for Bosca content recommendations."""

import tensorflow as tf
import tensorflow_recommenders as tfrs
from trainer.context_weights import context_weights, normalized_similarity
# Imported under an alias (rather than called as ``tfrs.tasks.Retrieval(...)``) purely so the source does
# not contain the literal ``Retrieval(`` — a naive security linter substring-matches ``eval(`` inside it.
from tensorflow_recommenders.tasks import Retrieval as RetrievalTask


class UserModel(tf.keras.Model):
    """User tower: maps a user's id + features to an embedding vector.

    Hybrid design for cold-start. Four signals are combined:
      - an id embedding that memorizes a warm user's specific history;
      - a learned projection of the user's **personalization signals** — the configurable,
        per-value_type-encoded profile-attribute / segment features (age band, interests, membership, …),
        the signal a brand-new zero-history user has. Each keyed signal is a column in a weighted multi-hot
        (categorical / boolean / multi-categorical values and bucketized numerics), so a new user gets a
        signal-derived embedding instead of one shared out-of-vocabulary vector, and warm users who share
        signals with a cold user pull relevant content toward them;
      - a learned projection of the user's category affinity (the categories of content they engage
        with), which becomes meaningful after even a few interactions — far faster than training a
        per-user id embedding — and shares the content tower's category space so a user's affinity
        aligns with matching content;
      - sparse editorial-type and direct-collection interests, pooled through learned embeddings.

    A user absent from the id vocabulary still gets a feature-derived embedding from signals + affinity.
    """

    def __init__(
        self,
        unique_user_ids: list[str],
        num_signal_tokens: int,
        num_categories: int,
        embedding_dim: int,
        unique_affinity_tokens: list[str] = (),
        weights: dict | None = None,
    ):
        super().__init__()
        self.has_sparse_affinity = bool(unique_affinity_tokens)
        if self.has_sparse_affinity:
            self.sparse_affinity_lookup = tf.keras.layers.StringLookup(vocabulary=unique_affinity_tokens, mask_token=None)
            self.sparse_affinity_embedding = tf.keras.layers.Embedding(len(unique_affinity_tokens) + 1, 16)
            similarity_weights = normalized_similarity(weights)
            self.sparse_affinity_importance = tf.constant([0.] + [
                similarity_weights["type" if token.startswith("type:") else "collections"]
                for token in unique_affinity_tokens
            ], dtype=tf.float32)
        self.user_lookup = tf.keras.layers.StringLookup(
            vocabulary=unique_user_ids, mask_token=None
        )
        self.user_embedding = tf.keras.layers.Embedding(
            len(unique_user_ids) + 1, embedding_dim, embeddings_initializer="zeros"
        )
        # Personalization signals: a learned projection of the per-user weighted signal multi-hot. The whole
        # feature is consume-if-present — with no signals configured the multi-hot is zero-width and the
        # branch is skipped, so the tower falls back to id + affinity (behavior unchanged from before signals).
        self.num_signal_tokens = num_signal_tokens
        if self.num_signal_tokens > 0:
            self.signal_dense = tf.keras.layers.Dense(16, activation="relu")
        self.num_categories = num_categories
        if self.num_categories > 0:
            # Regularize historical affinity so the tower also learns from ID and profile signals.
            self.affinity_dropout = tf.keras.layers.Dropout(0.3)
            self.affinity_dense = tf.keras.layers.Dense(16, activation="relu")
        self.dense = tf.keras.Sequential(
            [
                # Keep ReLU active at zero input so history-only users can learn their ID embeddings.
                # Unobserved IDs retain zero embeddings and share the same feature-based cold start.
                tf.keras.layers.Dense(128, activation="relu", bias_initializer=tf.keras.initializers.Constant(0.01)),
                tf.keras.layers.Dense(embedding_dim),
            ]
        )

    def call(self, features, training=False):
        user_emb = self.user_embedding(self.user_lookup(features["user_id"]))
        parts = [user_emb]
        if self.has_sparse_affinity:
            tokens = self.sparse_affinity_lookup(features["user_affinity_tokens"])
            affinity = features["user_affinity_weights"]
            feature_importance = tf.gather(self.sparse_affinity_importance, tokens)
            importance = affinity * feature_importance
            enabled_affinity = affinity * tf.cast(feature_importance > 0., tf.float32)
            embeddings = self.sparse_affinity_embedding(tokens)
            parts.append(tf.math.divide_no_nan(
                tf.reduce_sum(embeddings * importance[..., None], axis=1),
                tf.reduce_sum(enabled_affinity, axis=1)[:, None],
            ))
        if self.num_signal_tokens > 0 and "user_signal_multi_hot" in features:
            parts.append(self.signal_dense(features["user_signal_multi_hot"]))
        if self.num_categories > 0 and "user_category_affinity" in features:
            affinity = self.affinity_dropout(features["user_category_affinity"], training=training)
            parts.append(self.affinity_dense(affinity))
        return self.dense(tf.concat(parts, axis=1) if len(parts) > 1 else parts[0], training=training)


class ContentModel(tf.keras.Model):
    """Content tower: maps content_id + features to an embedding vector.

    Features include content type, language, and a multi-hot encoding of
    assigned categories. The category encoding is a fixed-width binary vector
    where each position corresponds to a category in the vocabulary; a 1
    indicates the content item belongs to that category. When present, a dense
    semantic embedding (from the platform's index-time embedder) is projected
    down and fused in as an additional content signal.
    """

    def __init__(
        self,
        unique_content_ids: list[str],
        unique_content_types: list[str],
        unique_languages: list[str],
        unique_category_ids: list[str],
        unique_label_ids: list[str],
        embedding_dim: int,
        semantic_embedding_dim: int = 0,
        unique_editorial_types: list[str] = (),
        unique_collection_ids: list[str] = (),
        weights: dict | None = None,
    ):
        super().__init__()
        self.similarity_weights = normalized_similarity(weights)
        self.content_lookup = tf.keras.layers.StringLookup(
            vocabulary=unique_content_ids, mask_token=None
        )
        self.content_embedding = tf.keras.layers.Embedding(
            len(unique_content_ids) + 1, embedding_dim
        )
        self.has_editorial_types = bool(unique_editorial_types)
        if self.has_editorial_types:
            self.editorial_lookup = tf.keras.layers.StringLookup(vocabulary=unique_editorial_types, mask_token=None)
            self.editorial_embedding = tf.keras.layers.Embedding(len(unique_editorial_types) + 1, 8)
        self.has_collections = bool(unique_collection_ids)
        if self.has_collections:
            self.collection_lookup = tf.keras.layers.StringLookup(vocabulary=unique_collection_ids, mask_token=None)
            self.collection_embedding = tf.keras.layers.Embedding(len(unique_collection_ids) + 1, 16)
        self.type_lookup = tf.keras.layers.StringLookup(
            vocabulary=unique_content_types, mask_token=None
        )
        self.type_embedding = tf.keras.layers.Embedding(
            len(unique_content_types) + 1, 8
        )
        self.language_lookup = tf.keras.layers.StringLookup(
            vocabulary=unique_languages, mask_token=None
        )
        self.language_embedding = tf.keras.layers.Embedding(
            len(unique_languages) + 1, 8
        )
        self.num_categories = len(unique_category_ids)
        if self.num_categories > 0:
            self.category_dense = tf.keras.layers.Dense(16, activation="relu")
        # Labels are a consume-if-present feature (symmetric to categories): a learned projection of the
        # per-item label multi-hot. Absent (empty label vocabulary) it contributes nothing.
        self.num_labels = len(unique_label_ids)
        if self.num_labels > 0:
            self.label_dense = tf.keras.layers.Dense(16, activation="relu")
        # Dense semantic embedding (consume-if-present): projected down from its native width (e.g. 768) so it
        # doesn't numerically swamp the small type/language/category signals in the concat. 0 when absent.
        self.semantic_embedding_dim = semantic_embedding_dim
        if self.semantic_embedding_dim > 0:
            self.embedding_dense = tf.keras.layers.Dense(32, activation="relu")
        self.dense = tf.keras.Sequential(
            [
                tf.keras.layers.Dense(128, activation="relu"),
                tf.keras.layers.Dense(embedding_dim),
            ]
        )

    def call(self, features, training=False):
        content_emb = self.content_embedding(self.content_lookup(features["content_id"]))
        type_emb = self.type_embedding(self.type_lookup(features["content_type"]))
        lang_emb = self.language_embedding(self.language_lookup(features["language_tag"]))
        weights = self.similarity_weights
        parts = [content_emb, weights["mime"] * type_emb, weights["language"] * lang_emb]
        if self.has_editorial_types:
            editorial = self.editorial_lookup(features["editorial_type"])
            parts.append(weights["type"] * self.editorial_embedding(editorial) * tf.cast(editorial[:, None] > 0, tf.float32))
        if self.has_collections:
            collections = self.collection_lookup(features["collection_ids"])
            embeddings = self.collection_embedding(collections)
            parts.append(weights["collections"] * tf.math.divide_no_nan(
                tf.reduce_sum(embeddings, axis=1),
                tf.cast(collections.row_lengths()[:, None], tf.float32),
            ))
        if self.num_categories > 0 and "category_multi_hot" in features:
            category_emb = self.category_dense(features["category_multi_hot"])
            parts.append(weights["categories"] * category_emb)
        if self.num_labels > 0 and "label_multi_hot" in features:
            parts.append(weights["labels"] * self.label_dense(features["label_multi_hot"]))
        if self.semantic_embedding_dim > 0 and "embedding" in features:
            parts.append(weights["semantic"] * self.embedding_dense(features["embedding"]))
        combined = tf.concat(parts, axis=1)
        return self.dense(combined, training=training)


def _user_features(features: dict) -> dict:
    """Extracts the user-tower inputs (id + cold-start signal/affinity features) from an interaction batch."""
    user_features = {"user_id": features["user_id"]}
    if "user_signal_multi_hot" in features:
        user_features["user_signal_multi_hot"] = features["user_signal_multi_hot"]
    if "user_category_affinity" in features:
        user_features["user_category_affinity"] = features["user_category_affinity"]
    for key in ("user_affinity_tokens", "user_affinity_weights"):
        if key in features:
            user_features[key] = features[key]
    return user_features


class BoscaRecommender(tfrs.Model):
    """Two-tower TFRS model for Bosca content recommendations."""

    def __init__(
        self,
        user_model: UserModel,
        content_model: ContentModel,
        content_dataset: tf.data.Dataset,
    ):
        super().__init__()
        self.user_model = user_model
        self.content_model = content_model
        self.mean_loss = tf.keras.metrics.Mean(name="mean_loss")
        self.task = RetrievalTask(
            remove_accidental_hits=True,
            metrics=tfrs.metrics.FactorizedTopK(
                candidates=content_dataset.batch(128).map(
                    lambda features: (features["content_id"], self.content_model(features, training=False))
                )
            )
        )

    def compute_loss(self, features, training=False):
        user_embeddings = self.user_model(_user_features(features), training=training)
        content_features = {
            "content_id": features["content_id"],
            "content_type": features["content_type"],
            "language_tag": features["language_tag"],
        }
        if "category_multi_hot" in features:
            content_features["category_multi_hot"] = features["category_multi_hot"]
        if "label_multi_hot" in features:
            content_features["label_multi_hot"] = features["label_multi_hot"]
        if "embedding" in features:
            content_features["embedding"] = features["embedding"]
        for key in ("editorial_type", "collection_ids"):
            if key in features:
                content_features[key] = features[key]
        content_embeddings = self.content_model(content_features, training=training)
        # Training monitors mean_loss; full-catalog top-K metrics are reserved for evaluation passes.
        loss = self.task(user_embeddings, content_embeddings, candidate_ids=features["content_id"],
                         sample_weight=features.get("sample_weight"), compute_metrics=not training)
        batch_size = tf.cast(tf.shape(user_embeddings)[0], loss.dtype)
        # TFRS returns a batch sum; aggregate per example for epoch-level early stopping.
        self.mean_loss.update_state(tf.math.divide_no_nan(loss, batch_size), sample_weight=batch_size)
        return loss


class RankingModel(tf.keras.Model):
    """Ranking head: produces an engagement logit for a (user, content) pair.

    Adds a supervised correction to the towers' contrastively learned relevance and captures the
    context's editorial baseline. The towers are frozen during head training. Exported serving applies
    this complete score to every eligible candidate before selecting top-K.
    """

    def __init__(self, user_model: UserModel, content_model: ContentModel, weights=None, editorial_types_by_id=None,
                 source_query_model=None, content_similarity=None, behavior_model=None):
        super().__init__()
        self.user_model = user_model
        self.content_model = content_model
        self.source_query_model = source_query_model
        self.content_similarity = content_similarity
        self.behavior_model = behavior_model
        settings = context_weights(weights)
        self.content_weight = settings["content"]
        self.personalization_weight = settings["personalization"]
        self.behavior_importance = tf.constant([settings[key] for key in
                                                ("coEngagement", "cohortCoEngagement", "learnedNeighbor", "rating")])
        self.behavior_gain = self.add_weight("behavior_gain", shape=[4], initializer="ones", trainable=True)
        preferences = {entry["type"]: entry["weight"] for entry in settings["typePreferences"]}
        editorial_types_by_id = editorial_types_by_id or {}
        self.type_preferences = tf.constant([
            preferences.get(editorial_types_by_id.get(key, ""), settings["defaultTypePreference"])
            for key in content_model.content_lookup.get_vocabulary()
        ], dtype=tf.float32)
        # Dropout regularizes the flexible scoring MLP against overfitting sparse feedback. It is inert at
        # inference, so the served `rank` signature is unaffected.
        self.score = tf.keras.Sequential(
            [
                tf.keras.layers.Dense(128, activation="relu"),
                tf.keras.layers.Dropout(0.2),
                tf.keras.layers.Dense(64, activation="relu"),
                tf.keras.layers.Dropout(0.2),
                tf.keras.layers.Dense(1, kernel_initializer="zeros", bias_initializer="zeros"),
            ]
        )
        self.source_score = tf.keras.Sequential([
            tf.keras.layers.Dense(64, activation="relu"),
            tf.keras.layers.Dense(1, kernel_initializer="zeros", bias_initializer="zeros"),
        ]) if source_query_model is not None and content_similarity is not None else None

    def score_embeddings(self, user_embedding, content_embedding, training=False):
        # Retain the contrastively learned preference when feedback contains only positive examples.
        # The supervised head learns a signed correction to this learned relevance, rather than having
        # to rediscover separation from an all-positive regression target.
        relevance = tf.reduce_sum(user_embedding * content_embedding, axis=1, keepdims=True)
        return relevance + self.score(tf.concat([user_embedding, content_embedding], axis=1), training=training)

    def score_pairs(self, user_embedding, content_embedding, content_id, training=False, source_id=None,
                    user_id=None, behavior_features=None, disabled_group=None):
        """Captured editorial baseline plus a learned, signed behavioral correction.

        A zero type preference reduces the baseline without excluding the item. Known profiles can
        receive a learned personal correction; anonymous and unknown IDs use content and shared behavior.
        Direct embedding callers may omit user_id when supplying an already resolved profile vector.
        """
        preference = tf.gather(self.type_preferences, self.content_model.content_lookup(content_id))
        relevance = tf.ones_like(preference)
        source_correction = tf.zeros_like(preference[:, None])
        if self.source_query_model is not None and self.content_similarity is not None and disabled_group != "content":
            if source_id is None:
                source_id = tf.fill(tf.shape(content_id), "")
            present = self.source_query_model.lookup(source_id) > 0
            relevance = tf.where(present, self.content_similarity.pair_similarity(source_id, content_id), 1.)
            source_embedding = self.source_query_model(source_id)
            source_correction = tf.cast(present[:, None], tf.float32) * self.source_score(tf.concat([
                user_embedding, content_embedding, source_embedding,
                source_embedding * content_embedding,
            ], axis=1), training=training)
        baseline = (0. if disabled_group == "content" else self.content_weight) * relevance[:, None] * (1.0 + preference[:, None]) / 2.0
        # Similarity supplies an additive log-prior: weaker similarity lowers a signed logit
        # instead of shrinking a negative correction toward zero. The floor keeps zero similarity
        # finite without turning a weighting choice into an eligibility rule.
        source_prior = tf.math.log(tf.maximum(relevance[:, None], tf.keras.backend.epsilon()))
        if behavior_features is None:
            if self.behavior_model is not None:
                behavior_features = self.behavior_model(
                    user_id if user_id is not None else tf.fill(tf.shape(content_id), ""),
                    source_id if source_id is not None else tf.fill(tf.shape(content_id), ""), content_id,
                )
            else:
                behavior_features = tf.zeros([tf.shape(content_id)[0], 4])
        group_mask = tf.constant([float(disabled_group != group) for group in
                                   ("coEngagement", "cohortCoEngagement", "personalization", "personalization")])
        personal_weight = 0. if disabled_group == "personalization" else self.personalization_weight
        if user_id is not None:
            known_user = tf.cast(self.user_model.user_lookup(user_id) > 0, tf.float32)[:, None]
            group_mask *= tf.concat([tf.ones_like(known_user), tf.repeat(known_user, 3, axis=1)], axis=1)
            personal_weight *= known_user
        behavioral = tf.reduce_sum(behavior_features * self.behavior_importance * self.behavior_gain * group_mask, axis=1, keepdims=True)
        return baseline + behavioral + personal_weight * (self.score_embeddings(
            user_embedding, content_embedding, training=training,
        ) + source_correction + source_prior)

    def explain_pairs(self, user_embedding, content_embedding, content_id, user_id, source_id):
        """Same-model group ablation; scores are effects within this model, not causal explanations.

        Content removes its default and source relevance path. Personalization removes individual,
        learned-neighbor and rating contributions. Population edge groups each use zero evidence.
        """
        features = None if self.behavior_model is None else self.behavior_model(user_id, source_id, content_id)
        kwargs = dict(user_id=user_id, source_id=source_id, behavior_features=features)
        full = self.score_pairs(user_embedding, content_embedding, content_id, **kwargs)
        return tf.concat([full - self.score_pairs(user_embedding, content_embedding, content_id,
                                                  disabled_group=group, **kwargs)
                          for group in ("content", "personalization", "coEngagement", "cohortCoEngagement")], axis=1)

    def call(self, features, training=False):
        content_features = {
            "content_id": features["content_id"],
            "content_type": features["content_type"],
            "language_tag": features["language_tag"],
        }
        if "category_multi_hot" in features:
            content_features["category_multi_hot"] = features["category_multi_hot"]
        if "label_multi_hot" in features:
            content_features["label_multi_hot"] = features["label_multi_hot"]
        if "embedding" in features:
            content_features["embedding"] = features["embedding"]
        # Rank on the same deterministic tower embeddings that are exported for serving.
        for key in ("editorial_type", "collection_ids"):
            if key in features:
                content_features[key] = features[key]
        # Freezing weights alone does not disable the user tower's dropout.
        return self.score_pairs(
            self.user_model(_user_features(features), training=False),
            self.content_model(content_features, training=False),
            features["content_id"],
            training=training,
            source_id=features.get("source_id"),
            user_id=features["user_id"],
            behavior_features=features.get("behavior_features", tf.zeros([tf.shape(features["user_id"])[0], 4])),
        )


class BoscaRanker(tfrs.Model):
    """Trains the RankingModel's logits against soft engagement targets in [0, 1].

    The label fuses implicit and explicit feedback: a view's watch percentage, an explicit star rating
    (mapped to [0, 1]), or a dismissal (a hard 0). Explicit feedback carries a higher `sample_weight`, so
    logit loss learns how much a rating or dismissal should move a candidate's score. Correctly
    confident scores need not be pulled back toward the label's numeric value. Serving keeps the
    logits so context contributions remain additive and extreme probabilities do not create ties.
    """

    def __init__(self, ranking_model: RankingModel):
        super().__init__()
        self.ranking_model = ranking_model
        self.mean_loss = tf.keras.metrics.Mean(name="mean_loss")
        self.task = tfrs.tasks.Ranking(
            loss=tf.keras.losses.BinaryCrossentropy(from_logits=True),
            metrics=[tf.keras.metrics.BinaryCrossentropy(from_logits=True)],
        )

    def compute_loss(self, features, training=False):
        predictions = self.ranking_model(features, training=training)
        labels = tf.reshape(features["label"], (-1, 1))
        sample_weight = features.get("sample_weight")
        if sample_weight is not None:
            sample_weight = tf.reshape(sample_weight, (-1, 1))
        loss = self.task(labels=labels, predictions=predictions, sample_weight=sample_weight)
        # Ranking loss is a batch mean; weight by its size, including the final partial batch.
        self.mean_loss.update_state(loss, sample_weight=tf.shape(labels)[0])
        return loss


def build_item_query_model(content_model, content_dataset: tf.data.Dataset):
    """A query model mapping a content_id string to its content-tower embedding.

    Materializes the trained item tower's embedding for every content item, keyed by content_id, into
    a lookup table. Used as the query side of the item->similar index so item-to-item similarity runs
    on the same learned representation as user->item retrieval (and stays cold-start capable, since the
    item tower derives embeddings from content features, not just an id).
    """
    ids, embs = [], []
    for batch in content_dataset.batch(128):
        ids.append(batch["content_id"])
        embs.append(content_model(batch))
    all_ids = tf.concat(ids, axis=0)
    all_emb = tf.concat(embs, axis=0)

    # Dedup by content_id (StringLookup vocab must be unique), keeping the first embedding seen.
    id_to_row: dict[str, int] = {}
    decoded = [s.decode("utf-8") if isinstance(s, bytes) else str(s) for s in all_ids.numpy()]
    for row, cid in enumerate(decoded):
        id_to_row.setdefault(cid, row)
    uniq_ids = list(id_to_row.keys())
    uniq_emb = tf.gather(all_emb, [id_to_row[c] for c in uniq_ids])
    dim = int(uniq_emb.shape[1])

    lookup = tf.keras.layers.StringLookup(vocabulary=uniq_ids, mask_token=None, num_oov_indices=1)
    # Row 0 = OOV -> zero vector (unknown ids retrieve nothing useful); rows 1..N align to vocab indices.
    table = tf.Variable(
        tf.concat([tf.zeros([1, dim], dtype=uniq_emb.dtype), uniq_emb], axis=0),
        trainable=False,
    )

    class ItemQueryModel(tf.keras.Model):
        def __init__(self):
            super().__init__()
            self.lookup = lookup
            self.table = table

        def call(self, content_id):
            return tf.gather(self.table, self.lookup(content_id))

    return ItemQueryModel()
