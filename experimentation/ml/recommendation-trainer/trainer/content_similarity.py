"""Exact content similarity using sparse categorical postings and one dense semantic table."""

import math

import tensorflow as tf

from trainer.features import build_embedding_matrix, labels_by_content, editorial_types, collections_by_content
from trainer.context_weights import context_weights, normalized_similarity


class _MembershipIndex(tf.Module):
    """Stores each membership once per orientation; cosine scores visit only shared-feature postings.

    Storage is O(items + features + memberships), including empty rows. No item-by-vocabulary matrix is
    materialized during construction, export, or inference. Repeated memberships have set semantics.
    """

    def __init__(self, memberships):
        super().__init__()
        vocab = {key: i for i, key in enumerate(sorted({key for row in memberships for key in row}))}
        rows = [sorted({vocab[key] for key in row}) for row in memberships]
        postings = [[] for _ in vocab]
        weights = [[] for _ in vocab]
        for item, features in enumerate(rows):
            for feature in features:
                postings[feature].append(item)
                weights[feature].append(1.0 / math.sqrt(len(features)))
        self.item_features = tf.ragged.constant(rows, dtype=tf.int32, ragged_rank=1)
        self.feature_items = tf.ragged.constant(postings, dtype=tf.int32, ragged_rank=1)
        self.feature_weights = tf.ragged.constant(weights, dtype=tf.float32, ragged_rank=1)
        self.item_count = len(rows)

    def scores(self, item):
        """Returns exact multi-hot cosine similarity against every item; missing signals score zero."""
        features = self.item_features[item]
        items = tf.gather(self.feature_items, features).flat_values
        weights = tf.gather(self.feature_weights, features).flat_values
        contributions = tf.math.divide_no_nan(weights, tf.sqrt(tf.cast(tf.size(features), tf.float32)))
        return tf.math.unsorted_segment_sum(contributions, items, self.item_count)

    def pair_scores(self, left, right):
        """Aligned-pair cosine without expanding a batch into full-catalog score rows."""
        left = tf.gather(self.item_features, left)
        right = tf.gather(self.item_features, right)
        shared = tf.cast(tf.sets.size(tf.sets.intersection(left.to_sparse(), right.to_sparse())), tf.float32)
        denominator = tf.sqrt(tf.cast(left.row_lengths() * right.row_lengths(), tf.float32))
        return tf.math.divide_no_nan(shared, denominator)


class ContentSimilarityIndex(tf.Module):
    """Facet-filtered exact top-K over independently weighted content signals.

    Categorical storage grows with actual memberships, and semantic storage grows with the fixed embedding
    width. Facets store only item indexes, sharing all feature tables. Each query allocates O(items) scores;
    batches execute serially to bound scratch memory independently of batch size. ScaNN is used for learned
    personalized embeddings, not for this exact mixed sparse/dense score.
    """

    def __init__(self, content, categories, facets, candidate_indexes, k, weights=None):
        super().__init__()
        self.settings = context_weights(weights)
        self.weights = normalized_similarity(self.settings)
        self.k = k
        self.ids = tf.constant(content["content_id"].values, dtype=tf.string)
        self.lookup = tf.keras.layers.StringLookup(
            vocabulary=content["content_id"].tolist(), mask_token=None, num_oov_indices=1,
        )
        self.facet_lookup = tf.keras.layers.StringLookup(
            vocabulary=facets, mask_token=None, num_oov_indices=1,
        )
        self.facet_items = tf.ragged.constant(
            [indexes.numpy().tolist() for indexes in candidate_indexes], dtype=tf.int32, ragged_rank=1,
        )
        # Integer equality is equivalent to the former one-hot dot product, without vocabulary-width rows.
        self.content_types = tf.constant(content["content_type"].fillna("").factorize()[0], dtype=tf.int32)
        self.languages = tf.constant(content["language_tag"].fillna("").factorize()[0], dtype=tf.int32)
        category_map = categories.groupby("content_id")["category_id"].agg(lambda keys: set(keys.dropna())).to_dict()
        label_map = labels_by_content(content)
        self.categories = _MembershipIndex([category_map.get(item, ()) for item in content["content_id"]])
        self.labels = _MembershipIndex([label_map.get(item, ()) for item in content["content_id"]])
        types = editorial_types(content)
        self.editorial_types = tf.constant(types, tf.string)
        collection_map = collections_by_content(content)
        self.collections = _MembershipIndex([collection_map.get(item, ()) for item in content["content_id"]])
        preferences = {entry["type"]: entry["weight"] for entry in self.settings["typePreferences"]}
        self.type_preferences = tf.constant(
            [preferences.get(value, self.settings["defaultTypePreference"]) for value in types], tf.float32,
        )
        embeddings, dimension = build_embedding_matrix(content)
        self.semantic_dimension = dimension
        # A non-trainable variable is serialized once and shared by all facets and both serving signatures.
        self.embeddings = tf.Variable(tf.math.l2_normalize(embeddings, axis=1), trainable=False)

    def _scores(self, item):
        scores = (
            self.weights["mime"] * tf.cast(self.content_types == self.content_types[item], tf.float32)
            + self.weights["language"] * tf.cast(self.languages == self.languages[item], tf.float32)
            + self.weights["categories"] * self.categories.scores(item)
            + self.weights["labels"] * self.labels.scores(item)
            + self.weights["type"] * tf.cast(
                (self.editorial_types == self.editorial_types[item]) & (self.editorial_types != ""), tf.float32,
            )
            + self.weights["collections"] * self.collections.scores(item)
        )
        if self.semantic_dimension:
            scores += self.weights["semantic"] * tf.maximum(
                0.0, tf.linalg.matvec(self.embeddings, self.embeddings[item]),
            )
        return scores

    def default_scores(self, item):
        """Relatedness gates the type boost: unrelated items never acquire relevance from type alone.

        The (1 + preference) / 2 factor stays in [0.5, 1]. Zero preference remains eligible.
        A uniform preference changes score scale, never ordering.
        """
        return self.settings["content"] * self._scores(item) * (1.0 + self.type_preferences) / 2.0

    def pair_similarity(self, source_ids, candidate_ids):
        """The same seven-signal similarity used in content serving, for aligned training pairs."""
        sources = self.lookup(source_ids) - 1
        candidates = self.lookup(candidate_ids) - 1
        valid = (sources >= 0) & (candidates >= 0)
        left, right = tf.maximum(sources, 0), tf.maximum(candidates, 0)
        def equal(values):
            return tf.cast(tf.gather(values, left) == tf.gather(values, right), tf.float32)
        scores = (
            self.weights["mime"] * equal(self.content_types)
            + self.weights["language"] * equal(self.languages)
            + self.weights["categories"] * self.categories.pair_scores(left, right)
            + self.weights["labels"] * self.labels.pair_scores(left, right)
            + self.weights["collections"] * self.collections.pair_scores(left, right)
            + self.weights["type"] * equal(self.editorial_types)
              * tf.cast(tf.gather(self.editorial_types, left) != "", tf.float32)
        )
        if self.semantic_dimension:
            scores += self.weights["semantic"] * tf.maximum(0., tf.reduce_sum(
                tf.gather(self.embeddings, left) * tf.gather(self.embeddings, right), axis=1,
            ))
        return tf.where(valid, scores, 0.)

    def _retrieve_one(self, inputs, exclude_self=False):
        item, facet, offset = inputs

        def retrieve():
            candidates = self.facet_items[facet]
            if exclude_self:
                candidates = tf.boolean_mask(candidates, candidates != tf.cast(item, tf.int32))
            scores = tf.gather(self.default_scores(item), candidates)
            page_offset = tf.maximum(offset, 0)
            count = tf.minimum(page_offset + self.k, tf.size(candidates))
            # Candidate indexes follow sorted content ids, so top_k's index tie-break is reproducible.
            top = tf.math.top_k(scores, k=count, sorted=True)
            ids = tf.gather(self.ids, tf.gather(candidates, top.indices[page_offset:]))
            values = top.values[page_offset:]
            padding = self.k - tf.size(ids)
            return (
                tf.pad(values, [[0, padding]]),
                tf.concat([ids, tf.fill([padding], "")], axis=0),
            )

        return tf.cond(
            (item >= 0) & (facet >= 0),
            retrieve,
            lambda: (tf.zeros([self.k], tf.float32), tf.fill([self.k], "")),
        )

    def __call__(self, ids, context_types, language_tags, offsets=None, exclude_self=False):
        facets = tf.strings.join([context_types, language_tags], separator="\x1f")
        if offsets is None:
            offsets = tf.zeros(tf.shape(ids), tf.int32)
        return tf.map_fn(
            lambda inputs: self._retrieve_one(inputs, exclude_self),
            (self.lookup(ids) - 1, self.facet_lookup(facets) - 1, offsets),
            fn_output_signature=(tf.TensorSpec([self.k], tf.float32), tf.TensorSpec([self.k], tf.string)),
            parallel_iterations=1,
        )
