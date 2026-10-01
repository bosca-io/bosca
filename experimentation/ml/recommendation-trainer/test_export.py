#!/usr/bin/env python3
"""Unit tests for trainer.export's pure helpers."""

import os

os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")

import unittest
import tempfile

import tensorflow as tf

from trainer.export import (
    PERSONALIZED_MODEL_GENERATION,
    _TRAINING_PROTOCOL,
    _decode_ids,
    _facet_key,
    _materialize_context_candidates,
    _missing_champion_facets,
    _rank_scores,
    _read_facet_manifest,
    _read_model_manifest,
    _retrieve_ids,
    _write_facet_manifest,
    _write_model_manifest,
)


class TestExportMetadata(unittest.TestCase):

    def test_missing_champion_facet_blocks_promotion_unless_explicitly_retired(self):
        champion = {_facet_key("default", "en"), _facet_key("default", "fr")}
        challenger = [_facet_key("default", "en")]

        self.assertEqual(
            _missing_champion_facets(champion, challenger),
            [_facet_key("default", "fr")],
        )
        self.assertEqual(
            _missing_champion_facets(champion, challenger, {("default", "fr")}),
            [],
        )

    def test_facet_manifest_round_trips_all_facets(self):
        facets = [_facet_key("images", "es"), _facet_key("default", "en")]
        with tempfile.TemporaryDirectory() as directory:
            _write_facet_manifest(directory, facets)
            self.assertEqual(_read_facet_manifest(directory), set(facets))

    def test_model_manifest_records_full_data_training_protocol(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(_read_model_manifest(directory))

            _write_model_manifest(directory, PERSONALIZED_MODEL_GENERATION, 120, 140)
            manifest = _read_model_manifest(directory)

            self.assertEqual(manifest["model_generation"], PERSONALIZED_MODEL_GENERATION)
            self.assertEqual(manifest["training_protocol"], _TRAINING_PROTOCOL)
            self.assertEqual(manifest["ranking_objective"], "binary_crossentropy_logits")
            self.assertEqual(manifest["training_users"], 120)
            self.assertEqual(manifest["indexed_users"], 140)
            self.assertIsNone(manifest["training_data_cutoff"])

    def test_candidate_materialization_stores_sparse_exact_facet_indexes(self):
        content = tf.data.Dataset.from_tensor_slices({
            "content_id": tf.constant(["a", "b", "c"]),
        })

        def content_model(batch):
            return tf.cast(tf.strings.length(batch["content_id"])[:, tf.newaxis], tf.float32)

        ids, _embeddings, facets, indexes = _materialize_context_candidates(
            content_model,
            content,
            {
                "a": frozenset({"default", "featured"}),
                "b": frozenset({"default"}),
                "c": frozenset({"featured"}),
            },
            {"a": "en", "b": "es", "c": "en"},
        )

        actual = {
            facet: tf.gather(ids, indexes[position]).numpy().tolist()
            for position, facet in enumerate(facets)
        }
        self.assertEqual(actual[_facet_key("default", "en")], [b"a"])
        self.assertEqual(actual[_facet_key("default", "es")], [b"b"])
        self.assertEqual(actual[_facet_key("featured", "en")], [b"a", b"c"])


class TestDecodeIds(unittest.TestCase):

    def test_decodes_byte_tensor_to_str(self):
        self.assertEqual(_decode_ids(tf.constant([b"a", b"b", b"c"])), ["a", "b", "c"])


class _StubIndex:
    """A factorized-top-k stand-in: returns a fixed (scores, ids) row tiled to the query batch size."""

    def __init__(self, ids_row, scores_row):
        self._ids_row, self._scores_row = ids_row, scores_row

    def __call__(self, query):
        n = tf.shape(query)[0]
        ids = tf.tile(tf.constant([self._ids_row], dtype=tf.string), [n, 1])
        scores = tf.tile(tf.constant([self._scores_row], dtype=tf.float32), [n, 1])
        return scores, ids


class _StubQueryModel:
    """A StringLookup stand-in: .lookup returns a vocab index per id, 0 == OOV (unknown)."""

    def __init__(self, known):
        self._known = set(known)

    def lookup(self, ids):
        decoded = [s.decode("utf-8") if isinstance(s, bytes) else str(s) for s in ids.numpy()]
        return tf.constant([1 if d in self._known else 0 for d in decoded], dtype=tf.int64)


class TestRetrieveIds(unittest.TestCase):
    """The shared retrieval-envelope + OOV-blanking helper the graph serving signatures delegate to."""

    def test_no_oov_passes_the_index_result_through(self):
        index = _StubIndex(["a", "b"], [0.9, 0.8])
        out = _retrieve_ids(index, None, tf.constant(["x", "y"]), oov_empty=False)
        self.assertEqual(_decode_ids(out["content_ids"][0]), ["a", "b"])
        self.assertEqual(_decode_ids(out["content_ids"][1]), ["a", "b"])
        self.assertTrue(bool(tf.reduce_all(out["scores"] > 0).numpy()))

    def test_oov_empty_keeps_known_rows(self):
        index = _StubIndex(["a", "b"], [0.9, 0.8])
        out = _retrieve_ids(index, _StubQueryModel({"known"}), tf.constant(["known"]), oov_empty=True)
        self.assertEqual(_decode_ids(out["content_ids"][0]), ["a", "b"])  # known user not blanked

    def test_oov_empty_blanks_unknown_rows_only(self):
        index = _StubIndex(["a", "b"], [0.9, 0.8])
        out = _retrieve_ids(
            index, _StubQueryModel({"known"}), tf.constant(["known", "unknown"]), oov_empty=True
        )
        self.assertEqual(_decode_ids(out["content_ids"][0]), ["a", "b"])  # known row intact
        self.assertEqual(_decode_ids(out["content_ids"][1]), ["", ""])   # OOV row blanked
        self.assertTrue(bool(tf.reduce_all(out["scores"][1] == 0).numpy()))  # ...and its scores zeroed
        self.assertTrue(bool(tf.reduce_all(out["scores"][0] > 0).numpy()))


class _StubRanker:
    def score_pairs(self, user_emb, item_emb, content_id, user_id=None, source_id=None):
        return tf.reduce_sum(user_emb * item_emb, axis=1, keepdims=True)  # a stand-in dot -> [n, 1]


class TestRankScores(unittest.TestCase):
    """The ranker helper the graph `rank` signature delegates to: flattens per-pair scores to [n]."""

    def test_scores_are_flattened_per_pair(self):
        uqm = lambda ids: tf.constant([[1.0, 2.0], [0.0, 1.0]])   # noqa: E731 - tiny test stub
        iqm = lambda ids: tf.constant([[1.0, 0.0], [1.0, 1.0]])   # noqa: E731 - tiny test stub
        out = _rank_scores(_StubRanker(), uqm, iqm, tf.constant(["u0", "u1"]), tf.constant(["c0", "c1"]))
        self.assertEqual(tuple(out["scores"].shape), (2,))          # flattened to one score per pair
        self.assertAlmostEqual(float(out["scores"][0]), 1.0)         # [1,2]·[1,0] = 1
        self.assertAlmostEqual(float(out["scores"][1]), 1.0)         # [0,1]·[1,1] = 1


if __name__ == "__main__":
    unittest.main()
