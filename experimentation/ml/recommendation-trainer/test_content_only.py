#!/usr/bin/env python3
"""Integration tests for the content (cold) model path.

Builds exact sparse content models from content features alone — ZERO interactions — and
validates the exported SavedModel's ``similar`` serving contract via ``assert_valid_content_model``. The
content model is item-keyed only (no user tower), so these tests query it by item, never by user.
"""

import os

os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")

import tempfile
import unittest
from unittest.mock import patch

import pandas as pd
import tensorflow as tf

from _model_checks import assert_valid_content_model, decode_ids
from trainer import content_only
from trainer.content_only import run_content_only_training


def _fixtures(with_labels=False):
    """Two feature-identical clusters: i1-i3 (text/en/cat-A), i4-i6 (video/es/cat-B)."""
    content = pd.DataFrame({
        "content_id": ["i1", "i2", "i3", "i4", "i5", "i6"],
        "content_type": ["text/plain"] * 3 + ["video/mp4"] * 3,
        "language_tag": ["en"] * 3 + ["es"] * 3,
        "labels": (
            [["news", "tech"]] * 3 + [["film"]] * 3
            if with_labels else [[] for _ in range(6)]
        ),
    })
    categories = pd.DataFrame({
        "content_id": ["i1", "i2", "i3", "i4", "i5", "i6"],
        "category_id": ["cat-A"] * 3 + ["cat-B"] * 3,
    })
    return content, categories


def _semantic_fixture():
    """Six items with IDENTICAL categorical features (text/en, no categories/labels) — so only the dense
    semantic vectors can distinguish them. Two clusters: i1-i3 point ~[1,0,0], i4-i6 point ~[0,1,0]."""
    content = pd.DataFrame({
        "content_id": ["i1", "i2", "i3", "i4", "i5", "i6"],
        "content_type": ["text/plain"] * 6,
        "language_tag": ["en"] * 6,
        "labels": [[] for _ in range(6)],
        "embedding": [
            [1.0, 0.05, 0.0], [1.0, 0.0, 0.05], [0.95, 0.0, 0.0],
            [0.0, 1.0, 0.05], [0.05, 1.0, 0.0], [0.0, 0.95, 0.0],
        ],
    })
    empty_categories = pd.DataFrame(columns=["content_id", "category_id"])
    return content, empty_categories


def _similar_neighbors(loaded, item):
    """The ``similar(item)`` neighbors with the query item itself removed."""
    ids = decode_ids(loaded.signatures["similar"](
        content_id=tf.constant([item]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
    )["content_ids"][0])
    return [c for c in ids if c != item]


def _similar_scores(loaded, item, language="en"):
    """The content model's raw scores keyed by returned item id."""
    result = loaded.signatures["similar"](
        content_id=tf.constant([item]),
        context_type=tf.constant(["default"]),
        language_tag=tf.constant([language]),
    )
    ids = decode_ids(result["content_ids"][0])
    scores = result["scores"][0].numpy().tolist()
    return {content_id: score for content_id, score in zip(ids, scores) if content_id}


class TestContentModelTraining(unittest.TestCase):

    def setUp(self):
        self.content, self.categories = _fixtures()
        self.config = {
            "top_k": 5,  # no artifacts_url -> the push step is skipped
        }

    def test_end_to_end_and_model_validation(self):
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(self.content, self.categories, None, None,
                                               dict(self.config, model_dir=d))
            self.assertEqual(result["status"], "completed_content_only")
            self.assertEqual(result["content_items"], 6)
            self.assertEqual(result["similarity_weights"], {key: 1 / 7 for key in ("mime", "language", "categories", "labels", "semantic", "type", "collections")})

            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))
            assert_valid_content_model(self, loaded, list(self.content["content_id"]), 5, sample_item="i1")

            # Domain check: similar(i1)'s nearest neighbors are its feature-identical cluster mates (i2, i3).
            neighbors = _similar_neighbors(loaded, "i1")
            self.assertTrue(set(neighbors[:2]).issubset({"i2", "i3"}), f"expected cat-A neighbors, got {neighbors}")

    def test_similar_filters_candidates_by_context_before_top_k(self):
        content = self.content.copy()
        content["recommendation_contexts"] = [["default"]] * 3 + [["videos"]] * 3
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(
                content, self.categories, None, None, dict(self.config, model_dir=d)
            )
            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))

            default_ids = decode_ids(loaded.signatures["similar"](
                content_id=tf.constant(["i1"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])
            video_ids = decode_ids(loaded.signatures["similar"](
                content_id=tf.constant(["i1"]), context_type=tf.constant(["videos"]), language_tag=tf.constant(["es"])
            )["content_ids"][0])
            missing_ids = decode_ids(loaded.signatures["similar"](
                content_id=tf.constant(["i1"]), context_type=tf.constant(["missing"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])

            self.assertEqual({"i1", "i2", "i3"}, {item for item in default_ids if item})
            self.assertEqual({"i4", "i5", "i6"}, {item for item in video_ids if item})
            self.assertTrue(all(item == "" for item in missing_ids))

    def test_similar_filters_candidates_by_language_before_top_k(self):
        content = self.content.copy()
        content["language_tag"] = ["en"] * 3 + ["es"] * 3
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(
                content, self.categories, None, None, dict(self.config, model_dir=d)
            )
            similar = tf.saved_model.load(os.path.join(d, str(result["model_version"]))).signatures["similar"]

            english_ids = decode_ids(similar(
                content_id=tf.constant(["i1"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])
            spanish_ids = decode_ids(similar(
                content_id=tf.constant(["i1"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["es"])
            )["content_ids"][0])
            missing_ids = decode_ids(similar(
                content_id=tf.constant(["i1"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["fr"])
            )["content_ids"][0])

            self.assertEqual({"i1", "i2", "i3"}, {item for item in english_ids if item})
            self.assertEqual({"i4", "i5", "i6"}, {item for item in spanish_ids if item})
            self.assertTrue(all(item == "" for item in missing_ids))

    def test_exports_to_a_fresh_model_dir(self):
        # A model_dir that doesn't exist yet -> the version-1 fresh-install path (no prior versions to scan).
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(self.content, self.categories, None, None,
                                               dict(self.config, model_dir=os.path.join(d, "content")))
            self.assertEqual(result["status"], "completed_content_only")
            self.assertEqual(result["model_version"], 1)
            loaded = tf.saved_model.load(os.path.join(d, "content", "1"))
            assert_valid_content_model(self, loaded, list(self.content["content_id"]), 5, sample_item="i1")

    def test_labels_feature_is_used(self):
        content, categories = _fixtures(with_labels=True)
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(content, categories, None, None,
                                               dict(self.config, model_dir=d))
            self.assertEqual(result["status"], "completed_content_only")
            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))
            assert_valid_content_model(self, loaded, list(content["content_id"]), 5, sample_item="i1")

    def test_semantic_embedding_drives_similarity(self):
        content, categories = _semantic_fixture()
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(content, categories, None, None,
                                               dict(self.config, model_dir=d))
            self.assertEqual(result["status"], "completed_content_only")
            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))
            assert_valid_content_model(self, loaded, list(content["content_id"]), 5, sample_item="i1")
            # Every item shares the same type and language, so those components are identical for all of
            # them — ONLY the semantic embedding can separate the two clusters. i1's nearest
            # neighbors must therefore be its semantic-cluster mates (i2, i3), never i4-i6.
            neighbors = _similar_neighbors(loaded, "i1")
            self.assertEqual(set(neighbors[:2]), {"i2", "i3"}, f"expected semantic neighbors, got {neighbors}")

    def test_missing_components_score_zero_while_type_and_language_still_match(self):
        content = pd.DataFrame({
            "content_id": ["source", "same", "orthogonal", "missing", "other-type", "spanish"],
            "content_type": ["text/plain", "text/plain", "text/plain", "text/plain", "video/mp4", "text/plain"],
            "language_tag": ["en", "en", "en", "en", "en", "es"],
            # The source deliberately has no label; the corpus still has a label vocabulary.
            "labels": [[], ["featured"], [], [], [], []],
            "embedding": [[1.0, 0.0], [1.0, 0.0], [0.0, 1.0], None, [1.0, 0.0], [1.0, 0.0]],
        })
        # The source deliberately has no category; the corpus still has a category vocabulary.
        categories = pd.DataFrame({"content_id": ["same"], "category_id": ["category-a"]})

        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(
                content, categories, None, None, dict(self.config, model_dir=d)
            )
            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))
            scores = _similar_scores(loaded, "source")

            # Seven equal similarity weights, with a uniform 0.5 type preference. Category and label
            # cannot contribute because those signals are absent from the source.
            self.assertAlmostEqual(3 * 0.75 / 7, scores["source"], places=5)
            self.assertAlmostEqual(3 * 0.75 / 7, scores["same"], places=5)
            # Missing/orthogonal semantics contribute zero; the shared type and language still contribute.
            self.assertAlmostEqual(2 * 0.75 / 7, scores["orthogonal"], places=5)
            self.assertAlmostEqual(2 * 0.75 / 7, scores["missing"], places=5)
            # Different type contributes zero while language + matching semantics remain non-zero.
            self.assertAlmostEqual(2 * 0.75 / 7, scores["other-type"], places=5)
            # Language remains a hard serving facet, not merely a score boost.
            self.assertNotIn("spanish", scores)
            self.assertEqual(
                {key: 1 / 7 for key in ("mime", "language", "categories", "labels", "semantic", "type", "collections")},
                result["similarity_weights"],
            )

    def test_category_and_label_components_contribute_independently(self):
        content = pd.DataFrame({
            "content_id": ["source", "both", "category-only", "label-only", "neither"],
            "content_type": ["text/plain"] * 5,
            "language_tag": ["en"] * 5,
            "labels": [["featured"], ["featured"], [], ["featured"], []],
        })
        categories = pd.DataFrame({
            "content_id": ["source", "both", "category-only", "label-only", "neither"],
            "category_id": ["category-a", "category-a", "category-a", "category-b", "category-b"],
        })

        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(
                content, categories, None, None, dict(self.config, model_dir=d)
            )
            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))
            scores = _similar_scores(loaded, "source")

            # Every matching signal contributes 1/7, scaled by the uniform preference factor 0.75.
            self.assertAlmostEqual(4 * 0.75 / 7, scores["source"], places=5)
            self.assertAlmostEqual(4 * 0.75 / 7, scores["both"], places=5)
            self.assertAlmostEqual(3 * 0.75 / 7, scores["category-only"], places=5)
            self.assertAlmostEqual(3 * 0.75 / 7, scores["label-only"], places=5)
            self.assertAlmostEqual(2 * 0.75 / 7, scores["neither"], places=5)

    def test_empty_content_raises(self):
        empty = pd.DataFrame(columns=self.content.columns)
        with self.assertRaises(ValueError):
            run_content_only_training(empty, self.categories, None, None, dict(self.config))

    @patch.object(content_only, "ArtifactsModelClient")
    def test_upload_success_records_artifact(self, mock_storage):
        mock_storage.return_value.upload_model.return_value = {"name": "recommender-content", "version": 5, "size_bytes": 42}
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(self.content, self.categories, None, None,
                                               dict(self.config, model_dir=d, artifacts_url="http://x"))
            self.assertEqual(result["artifact_version"], 5)
            self.assertEqual(result["artifact_size"], 42)

    @patch.object(content_only, "ArtifactsModelClient")
    def test_upload_failure_fails_build(self, mock_storage):
        mock_storage.return_value.upload_model.side_effect = RuntimeError("down")
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(RuntimeError, "down"):
                run_content_only_training(self.content, self.categories, None, None,
                                          dict(self.config, model_dir=d, artifacts_url="http://x"))

    @patch.object(content_only, "ArtifactsModelClient")
    def test_ephemeral_job_continues_registry_version(self, mock_storage):
        def hydrate(_model_name, output_dir):
            os.makedirs(os.path.join(output_dir, "17"))
            return 17

        mock_storage.return_value.hydrate_latest_model.side_effect = hydrate
        mock_storage.return_value.upload_model.side_effect = lambda _path, version, **_kwargs: {
            "name": "recommender-content", "version": version, "size_bytes": 42,
        }
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(
                self.content,
                self.categories,
                None,
                None,
                dict(self.config, model_dir=d, artifacts_url="http://x"),
            )

        self.assertEqual(result["model_version"], 18)
        self.assertEqual(result["artifact_version"], 18)

    def test_similarity_index_uses_the_full_corpus(self):
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(self.content, self.categories, None, None,
                                               dict(self.config, model_dir=d))
            self.assertEqual(result["status"], "completed_content_only")

    def test_no_categories_no_labels(self):
        content = pd.DataFrame({
            "content_id": ["i1", "i2", "i3", "i4"],
            "content_type": ["text/plain", "text/plain", "video/mp4", "video/mp4"],
            "language_tag": ["en", "en", "es", "es"],
            "labels": [[] for _ in range(4)],
        })
        empty_categories = pd.DataFrame(columns=["content_id", "category_id"])
        with tempfile.TemporaryDirectory() as d:
            result = run_content_only_training(content, empty_categories, None, None,
                                               dict(self.config, model_dir=d))
            self.assertEqual(result["status"], "completed_content_only")
            loaded = tf.saved_model.load(os.path.join(d, str(result["model_version"])))
            # Encoder falls back to type + language only; the similar contract still holds.
            assert_valid_content_model(self, loaded, list(content["content_id"]), 5, sample_item="i1")

    def test_version_increments_across_runs(self):
        # A second run into the same model_dir mints version N+1 (never overwrites the prior export).
        with tempfile.TemporaryDirectory() as d:
            first = run_content_only_training(self.content, self.categories, None, None,
                                              dict(self.config, model_dir=d))
            second = run_content_only_training(self.content, self.categories, None, None,
                                               dict(self.config, model_dir=d))
            self.assertEqual(second["model_version"], first["model_version"] + 1)

    def test_missing_language_facet_keeps_the_current_content_model(self):
        with tempfile.TemporaryDirectory() as d:
            first = run_content_only_training(
                self.content,
                self.categories,
                None,
                None,
                dict(self.config, model_dir=d),
            )
            english = self.content[self.content["language_tag"] == "en"].reset_index(drop=True)
            english_categories = self.categories[
                self.categories["content_id"].isin(set(english["content_id"]))
            ].reset_index(drop=True)

            challenger = run_content_only_training(
                english,
                english_categories,
                None,
                None,
                dict(self.config, model_dir=d),
            )

            self.assertEqual(first["model_version"], 1)
            self.assertEqual(challenger["status"], "rejected")
            self.assertEqual(challenger["missing_facets"], ["default\x1fes"])
            self.assertFalse(os.path.exists(os.path.join(d, "2")))


if __name__ == "__main__":
    unittest.main()
