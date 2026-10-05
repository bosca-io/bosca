#!/usr/bin/env python3
"""Fast unit tests for the pure numpy/pandas feature core (no TensorFlow import)."""

import unittest

import numpy as np
import pandas as pd

from trainer.features import (
    _scalar_token,
    build_embedding_matrix,
    build_multi_hot_matrix,
    build_signal_features,
    build_signal_multi_hot,
    compute_user_affinity,
    extract_content_vocabs,
    labels_by_content,
    parse_embedding,
    parse_signal_value,
)


class TestContentVocabs(unittest.TestCase):

    def test_dedup_and_sort(self):
        content = pd.DataFrame({
            "content_id": ["c2", "c1", "c1"],
            "content_type": ["text/plain", "text/html", None],
            "language_tag": ["en", "en", "es"],
            "labels": [["z"], ["a", "z"], []],
        })
        categories = pd.DataFrame({"content_id": ["c1", "c2", "c1"], "category_id": ["b", "a", "b"]})
        v = extract_content_vocabs(content, categories)
        self.assertEqual(v["content_ids"], ["c1", "c2"])
        self.assertEqual(v["content_types"], ["text/html", "text/plain"])  # None dropped, sorted
        self.assertEqual(v["languages"], ["en", "es"])
        self.assertEqual(v["category_ids"], ["a", "b"])
        self.assertEqual(v["label_ids"], ["a", "z"])

    def test_empty_categories(self):
        content = pd.DataFrame({"content_id": ["c1"], "content_type": ["t"], "language_tag": ["en"], "labels": [[]]})
        v = extract_content_vocabs(content, pd.DataFrame(columns=["content_id", "category_id"]))
        self.assertEqual(v["category_ids"], [])
        self.assertEqual(v["label_ids"], [])


class TestMultiHotMatrix(unittest.TestCase):

    def test_assigned_unknown_and_absent(self):
        vocab = ["a", "b", "c"]
        keys_by_row = {"r1": ["a", "c"], "r2": ["b", "zzz"]}  # "zzz" not in vocab; "r3" absent
        m = build_multi_hot_matrix(["r1", "r2", "r3"], keys_by_row, vocab)
        self.assertEqual(m.shape, (3, 3))
        np.testing.assert_array_equal(m[0], [1.0, 0.0, 1.0])
        np.testing.assert_array_equal(m[1], [0.0, 1.0, 0.0])  # unknown key ignored
        np.testing.assert_array_equal(m[2], [0.0, 0.0, 0.0])  # absent row -> all zeros

    def test_empty_vocab_is_zero_width(self):
        m = build_multi_hot_matrix(["r1", "r2"], {"r1": ["a"]}, [])
        self.assertEqual(m.shape, (2, 0))


class TestUserAffinity(unittest.TestCase):

    def test_training_affinity_excludes_own_outcome_same_time_and_future_evidence(self):
        users = np.array(["u"] * 4)
        weights = np.ones(4, dtype=np.float32)
        features = np.array([[1, 0], [0, 1], [1, 0], [1, 1]], dtype=np.float32)
        completed = pd.to_datetime(["2026-01-03", "2026-01-01", "2026-01-02", None], utc=True)
        requested = pd.to_datetime(["2026-01-02", "2026-01-01", "2026-01-02", "2026-01-04"], utc=True)
        serving, training = compute_user_affinity(users, weights, features, 2, completed, requested)
        np.testing.assert_allclose(training, [[0, 1], [0, 0], [0, 1], [2 / 3, 1 / 3]])
        np.testing.assert_allclose(serving["u"], [.75, .5])
        changed = weights.copy()
        changed[0] = 20
        _, after = compute_user_affinity(users, changed, features, 2, completed, requested)
        np.testing.assert_array_equal(after[:3], training[:3])

    def test_unknown_request_time_has_no_historical_affinity(self):
        serving, training = compute_user_affinity(
            np.array(["u"]), np.ones(1), np.ones((1, 1)), 1,
            pd.to_datetime(["2026-01-01"], utc=True), pd.to_datetime([None], utc=True),
        )
        np.testing.assert_array_equal(training, [[0]])
        np.testing.assert_array_equal(serving["u"], [1])

    def test_label_weighted_mean(self):
        user_arr = np.array(["u1", "u1", "u2"])
        labels_arr = np.array([1.0, 0.0, 0.5], dtype=np.float32)
        interaction_multi_hot = np.array([[1.0], [1.0], [1.0]], dtype=np.float32)  # one category, present each row
        by_user, interaction_affinity = compute_user_affinity(user_arr, labels_arr, interaction_multi_hot, 1)
        # u1: (1*1 + 1*0) / (1 + 0) = 1.0 ; u2: (1*0.5) / 0.5 = 1.0
        self.assertAlmostEqual(float(by_user["u1"][0]), 1.0, places=5)
        self.assertAlmostEqual(float(by_user["u2"][0]), 1.0, places=5)
        self.assertEqual(interaction_affinity.shape, (3, 1))

    def test_zero_weight_user_gets_zero_affinity(self):
        # A user whose only interaction has label 0 has total weight 0 -> affinity forced to 0.
        by_user, _ = compute_user_affinity(
            np.array(["u1"]), np.array([0.0], dtype=np.float32), np.array([[1.0]], dtype=np.float32), 1
        )
        self.assertAlmostEqual(float(by_user["u1"][0]), 0.0, places=5)

    def test_no_categories_returns_empty(self):
        by_user, interaction_affinity = compute_user_affinity(
            np.array(["u1"]), np.array([1.0], dtype=np.float32), np.zeros((1, 0), dtype=np.float32), 0
        )
        self.assertEqual(by_user, {})
        self.assertIsNone(interaction_affinity)


class TestLabels(unittest.TestCase):

    def test_labels_by_content(self):
        content = pd.DataFrame({
            "content_id": ["c1", "c2"],
            "labels": [["x", "y", "  "], []],
        })
        self.assertEqual(labels_by_content(content), {"c1": ["x", "y"]})

    def test_labels_by_content_missing_column(self):
        self.assertEqual(labels_by_content(pd.DataFrame({"content_id": ["c1"]})), {})

    def test_delimiter_packed_labels_are_rejected(self):
        content = pd.DataFrame({"content_id": ["c1"], "labels": ["x\x1fy"]})
        with self.assertRaisesRegex(ValueError, "must be an array"):
            labels_by_content(content)

    def test_labels_must_contain_strings(self):
        content = pd.DataFrame({"content_id": ["c1"], "labels": [["x", 1]]})
        with self.assertRaisesRegex(ValueError, "only strings"):
            labels_by_content(content)


class TestParseEmbedding(unittest.TestCase):

    def test_valid_vector(self):
        self.assertEqual(parse_embedding("[1.0, 2.5, -3.0]"), [1.0, 2.5, -3.0])

    def test_integers_coerced_to_float(self):
        parsed = parse_embedding("[1, 2, 3]")
        self.assertEqual(parsed, [1.0, 2.0, 3.0])
        self.assertTrue(all(isinstance(x, float) for x in parsed))

    def test_blank_and_none_and_non_string(self):
        self.assertIsNone(parse_embedding(""))
        self.assertIsNone(parse_embedding("   "))
        self.assertIsNone(parse_embedding(None))
        self.assertIsNone(parse_embedding(123))

    def test_invalid_json(self):
        self.assertIsNone(parse_embedding("[1, 2, "))

    def test_non_list_json(self):
        self.assertIsNone(parse_embedding("42"))
        self.assertIsNone(parse_embedding('{"a": 1}'))

    def test_non_numeric_elements(self):
        self.assertIsNone(parse_embedding('["a", "b"]'))
        self.assertIsNone(parse_embedding("[true, false]"))


class TestEmbeddingMatrix(unittest.TestCase):

    def test_present_vectors(self):
        content = pd.DataFrame({
            "content_id": ["c0", "c1"],
            "embedding": [[0.9486833, 0.31622776], [0.0, 1.0]],
        })
        matrix, dim = build_embedding_matrix(content)
        self.assertEqual(dim, 2)
        np.testing.assert_allclose(matrix[0], [0.9486833, 0.31622776], rtol=1e-6)
        np.testing.assert_allclose(matrix[1], [0.0, 1.0], rtol=1e-6)

    def test_missing_column_is_zero_width(self):
        content = pd.DataFrame({"content_id": ["c0", "c1"]})
        matrix, dim = build_embedding_matrix(content)
        self.assertEqual(dim, 0)
        self.assertEqual(matrix.shape, (2, 0))

    def test_all_absent_vectors_is_zero_width(self):
        # Column present but every cell empty (e.g. a left-join that matched nothing) -> inert.
        content = pd.DataFrame({"content_id": ["c0", "c1"], "embedding": [None, None]})
        matrix, dim = build_embedding_matrix(content)
        self.assertEqual(dim, 0)
        self.assertEqual(matrix.shape, (2, 0))

    def test_partial_vectors_get_zero_rows(self):
        # dim inferred from the first present vector; the content without an embedding gets a zero row.
        content = pd.DataFrame({"content_id": ["c0", "c1"], "embedding": [None, [0.6, 0.8]]})
        matrix, dim = build_embedding_matrix(content)
        self.assertEqual(dim, 2)
        np.testing.assert_array_equal(matrix[0], [0.0, 0.0])
        np.testing.assert_allclose(matrix[1], [0.6, 0.8], rtol=1e-6)

    def test_mismatched_dimension_is_rejected(self):
        content = pd.DataFrame({
            "content_id": ["c0", "c1"],
            "embedding": [[0.4472136, 0.8944272], [1.0]],
        })
        with self.assertRaisesRegex(ValueError, "dimension 1; expected 2"):
            build_embedding_matrix(content)

    def test_non_finite_vector_is_rejected(self):
        content = pd.DataFrame({"content_id": ["c0"], "embedding": [[1.0, np.nan]]})
        with self.assertRaisesRegex(ValueError, "non-finite"):
            build_embedding_matrix(content)


class TestSignalFeatures(unittest.TestCase):
    """Personalization-signal encoding: per-value_type tokenization + weighted multi-hot."""

    def _signals(self, rows):
        return pd.DataFrame(rows, columns=["user_id", "signal_key", "signal_value", "value_type", "priority"])

    def test_parse_signal_value_variants(self):
        self.assertEqual(parse_signal_value('"25-34"'), "25-34")
        self.assertEqual(parse_signal_value("42"), 42)
        self.assertEqual(parse_signal_value("true"), True)
        self.assertEqual(parse_signal_value('["a","b"]'), ["a", "b"])
        self.assertEqual(parse_signal_value("bare"), "bare")  # unquoted → the literal string
        self.assertIsNone(parse_signal_value(""))
        self.assertIsNone(parse_signal_value(None))
        self.assertIsNone(parse_signal_value("null"))
        # An already-parsed (non-string) value passes through untouched — the analytics layer may hand us a
        # decoded number / bool / list directly rather than its JSON text.
        self.assertEqual(parse_signal_value(42), 42)
        self.assertIs(parse_signal_value(True), True)
        self.assertEqual(parse_signal_value(["a", "b"]), ["a", "b"])

    def test_scalar_token_rendering(self):
        # Whole-number floats render without a trailing ".0" so a numeric signal's token is stable ("k=25"
        # not "k=25.0"); bools render as words; other values fall through to str().
        self.assertEqual(_scalar_token(25.0), "25")
        self.assertEqual(_scalar_token(True), "true")
        self.assertEqual(_scalar_token(False), "false")
        self.assertEqual(_scalar_token(3.5), "3.5")
        self.assertEqual(_scalar_token("x"), "x")

    def test_categorical_and_boolean_tokens(self):
        signals = self._signals([
            ("u0", "age_band", '"25-34"', "categorical", 0),
            ("u0", "is_premium", "true", "boolean", 0),
        ])
        tokens_by_user, vocab = build_signal_features(signals)
        self.assertEqual(set(tokens_by_user["u0"]), {"age_band=25-34", "is_premium=true"})
        self.assertEqual(vocab, ["age_band=25-34", "is_premium=true"])

    def test_repeated_categorical_key_preserves_every_distinct_value(self):
        signals = self._signals([
            ("u0", "interest_category", '"hiking"', "categorical", 0),
            ("u0", "interest_category", '"photography"', "categorical", 0),
        ])
        tokens_by_user, vocab = build_signal_features(signals)
        expected = {"interest_category=hiking", "interest_category=photography"}
        self.assertEqual(set(tokens_by_user["u0"]), expected)
        self.assertEqual(set(vocab), expected)

    def test_multi_categorical_expands_to_one_token_per_element(self):
        signals = self._signals([("u0", "interest", '["hiking","coffee"]', "multi_categorical", 0)])
        tokens_by_user, _ = build_signal_features(signals)
        self.assertEqual(set(tokens_by_user["u0"]), {"interest=hiking", "interest=coffee"})

    def test_numeric_is_bucketized_by_quantiles(self):
        signals = self._signals([
            ("u0", "age", "20", "numeric", 0), ("u1", "age", "35", "numeric", 0),
            ("u2", "age", "50", "numeric", 0), ("u3", "age", "65", "numeric", 0),
        ])
        tokens_by_user, _ = build_signal_features(signals)
        low, high = next(iter(tokens_by_user["u0"])), next(iter(tokens_by_user["u3"]))
        self.assertTrue(low.startswith("age=q"))
        self.assertNotEqual(low, high)  # the smallest and largest values land in different buckets

    def test_numeric_constant_key_yields_one_bucket(self):
        signals = self._signals([("u0", "age", "40", "numeric", 0), ("u1", "age", "40", "numeric", 0)])
        tokens_by_user, vocab = build_signal_features(signals)
        self.assertEqual(vocab, ["age=q0"])
        self.assertEqual(set(tokens_by_user["u0"]), {"age=q0"})

    def test_priority_weights_the_multi_hot(self):
        signals = self._signals([
            ("u0", "a", '"x"', "categorical", 0),
            ("u0", "b", '"y"', "categorical", 20),
        ])
        tokens_by_user, vocab = build_signal_features(signals)
        matrix = build_signal_multi_hot(["u0"], tokens_by_user, vocab)
        weights = {tok: matrix[0, i] for i, tok in enumerate(vocab)}
        self.assertEqual(weights["a=x"], 1.0)          # priority 0 → weight 1.0
        self.assertGreater(weights["b=y"], 1.0)        # higher priority → larger weight (capped at 3.0)
        self.assertLessEqual(weights["b=y"], 3.0)

    def test_build_signal_multi_hot_shape_and_missing_user(self):
        signals = self._signals([("u0", "a", '"x"', "categorical", 0)])
        tokens_by_user, vocab = build_signal_features(signals)
        matrix = build_signal_multi_hot(["u0", "u1"], tokens_by_user, vocab)
        self.assertEqual(matrix.shape, (2, 1))
        self.assertEqual(matrix[0, 0], 1.0)
        self.assertEqual(matrix[1, 0], 0.0)            # u1 has no signals → zero row

    def test_null_value_contributes_no_token(self):
        tokens_by_user, vocab = build_signal_features(self._signals([("u0", "a", "null", "categorical", 0)]))
        self.assertEqual((tokens_by_user, vocab), ({}, []))

    def test_missing_value_type_defaults_to_categorical(self):
        signals = pd.DataFrame(
            [("u0", "a", '"x"', 0)], columns=["user_id", "signal_key", "signal_value", "priority"]
        )
        _, vocab = build_signal_features(signals)
        self.assertEqual(vocab, ["a=x"])

    def test_empty_and_malformed_signals_are_safe(self):
        self.assertEqual(build_signal_features(None), ({}, []))
        self.assertEqual(build_signal_features(pd.DataFrame()), ({}, []))
        # A frame missing the signal columns (e.g. a stale users frame) → no signals, no crash.
        self.assertEqual(build_signal_features(pd.DataFrame({"user_id": ["u0"]})), ({}, []))
        # Zero-width multi-hot when the vocab is empty.
        self.assertEqual(build_signal_multi_hot(["u0"], {}, []).shape, (1, 0))

    def test_numeric_with_non_numeric_value_is_skipped(self):
        # A numeric-typed signal whose value isn't a number contributes no token (and forms no bins).
        signals = self._signals([("u0", "age", '"oops"', "numeric", 0)])
        self.assertEqual(build_signal_features(signals), ({}, []))

    def test_frame_without_priority_column_uses_default_weight(self):
        signals = pd.DataFrame(
            [("u0", "a", '"x"', "categorical")],
            columns=["user_id", "signal_key", "signal_value", "value_type"],
        )
        tokens_by_user, vocab = build_signal_features(signals)
        matrix = build_signal_multi_hot(["u0"], tokens_by_user, vocab)
        self.assertEqual(matrix[0, 0], 1.0)

    def test_multi_hot_ignores_tokens_absent_from_vocab(self):
        matrix = build_signal_multi_hot(["u0"], {"u0": {"absent=z": 2.0}}, ["present=x"])
        self.assertEqual(matrix.shape, (1, 1))
        self.assertEqual(matrix[0, 0], 0.0)  # an out-of-vocab token is ignored

    def test_strongest_weight_wins_for_a_repeated_token(self):
        # A user with two attributes of a type emitting the same token keeps the higher-priority weight.
        signals = self._signals([
            ("u0", "interest", '"coffee"', "categorical", 0),
            ("u0", "interest", '"coffee"', "categorical", 20),
        ])
        tokens_by_user, vocab = build_signal_features(signals)
        matrix = build_signal_multi_hot(["u0"], tokens_by_user, vocab)
        self.assertGreater(matrix[0, 0], 1.0)


if __name__ == "__main__":
    unittest.main()
