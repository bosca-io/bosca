#!/usr/bin/env python3
"""Tests for trainer.pipeline — the run_training orchestration (content base always + personalized when
interactions suffice) plus real personalized-model training and behavioral proofs.

The branch tests mock heavy compute so they run fast; the end-to-end tests actually train the two-tower
personalized model and validate the exported SavedModel's serving contract (including OOV-empty).
"""

import os

os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")

import json
import shutil
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import numpy as np
import pandas as pd
import requests
import tensorflow as tf

from _model_checks import assert_valid_recommender, decode_ids
from trainer import export, pipeline, training
from trainer.pipeline import (
    _filter_interactions_to_content,
    _training_data_cutoff,
    run_training,
)


def _content_frame(n=8, with_labels=False):
    return pd.DataFrame({
        "content_id": [f"c{i}" for i in range(n)],
        "content_type": ["text/plain" if i % 2 == 0 else "video/mp4" for i in range(n)],
        "language_tag": ["en" if i % 2 == 0 else "es" for i in range(n)],
        "labels": ([["tag", "x"]] * n if with_labels else [[] for _ in range(n)]),
    })


def _frames(n_interactions, n_content=8, n_users=6, with_feedback=False, with_labels=False, with_categories=True):
    users = pd.DataFrame({"user_id": [f"u{i}" for i in range(n_users)]})
    content = _content_frame(n_content, with_labels=with_labels)
    if with_categories:
        categories = pd.DataFrame({
            "content_id": [f"c{i}" for i in range(n_content)],
            "category_id": ["cat-A" if i % 2 == 0 else "cat-B" for i in range(n_content)],
        })
    else:
        categories = pd.DataFrame(columns=["content_id", "category_id"])
    # A per-user categorical personalization signal — the user-tower cold-start feature.
    signals = pd.DataFrame({
        "user_id": [f"u{i}" for i in range(n_users)],
        "signal_key": ["cohort"] * n_users,
        "signal_value": [json.dumps(f"c{i % 3}") for i in range(n_users)],
        "value_type": ["categorical"] * n_users,
        "priority": [1] * n_users,
    })
    if n_interactions:
        rng = np.random.RandomState(0)
        interactions = pd.DataFrame({
            "user_id": [f"u{int(rng.randint(n_users))}" for _ in range(n_interactions)],
            "content_id": [f"c{int(rng.randint(n_content))}" for _ in range(n_interactions)],
            "interaction_type": ["Interaction"] * n_interactions,
            "view_percent": [float(rng.randint(0, 100)) for _ in range(n_interactions)],
        })
    else:
        interactions = pd.DataFrame(columns=["user_id", "content_id", "interaction_type", "view_percent"])
    if with_feedback:
        feedback = pd.DataFrame({
            "user_id": ["u0", "u1"], "content_id": ["c0", "c1"],
            "feedback_label": [1.0, 0.0], "feedback_source": ["rating", "dismissal"],
        })
    else:
        feedback = pd.DataFrame(columns=["user_id", "content_id", "feedback_label", "feedback_source"])
    return users, interactions, content, categories, signals, feedback


def _personalized_args(n, **kw):
    """_frames returns (…, signals, feedback); _train_personalized wants (…, feedback, signals) — reorder."""
    users, interactions, content, categories, signals, feedback = _frames(n, **kw)
    return users, interactions, content, categories, feedback, signals


class TestPipelineHelpers(unittest.TestCase):

    def test_training_data_cutoff_uses_newest_valid_interaction_timestamp(self):
        interactions = pd.DataFrame({
            "interaction_created": ["2026-09-01T10:00:00Z", "invalid", "2026-09-03T12:30:00-05:00"],
        })

        self.assertEqual(_training_data_cutoff(interactions), "2026-09-03T17:30:00Z")
        self.assertIsNone(_training_data_cutoff(pd.DataFrame({"user_id": ["u0"]})))

    def test_interactions_are_filtered_to_the_eligible_content_corpus(self):
        interactions = pd.DataFrame({
            "user_id": ["u0", "u0", "u1"],
            "content_id": ["eligible", "excluded", "eligible"],
        })
        content = pd.DataFrame({"content_id": ["eligible"]})

        filtered = _filter_interactions_to_content(interactions, content)

        self.assertEqual(filtered["content_id"].tolist(), ["eligible", "eligible"])


class TestPreviousModel(unittest.TestCase):

    def test_no_predecessor_does_not_download(self):
        client = MagicMock()
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(pipeline._load_previous_model("personalized", None, directory, client), (None, None))
        client.download_model.assert_not_called()

    def test_missing_local_predecessor_does_not_substitute_another_version(self):
        with tempfile.TemporaryDirectory() as directory:
            os.makedirs(os.path.join(directory, "17"))
            with self.assertLogs(pipeline.log, level="WARNING") as logs:
                result = pipeline._load_previous_model("personalized", 16, directory, None)
            self.assertEqual(result, (None, None))
            self.assertIn("personalized@16 is absent locally", logs.output[0])

    def test_download_errors_other_than_not_found_propagate(self):
        errors = [requests.ReadTimeout("stalled"), requests.ConnectionError("unreachable"),
                  requests.HTTPError("no response"), ValueError("invalid model archive")]
        for status in (401, 403, 500):
            response = requests.Response()
            response.status_code = status
            errors.append(requests.HTTPError(str(status), response=response))
        for error in errors:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
                client = MagicMock()
                client.download_model.side_effect = error
                with self.assertRaises(type(error)) as caught:
                    pipeline._load_previous_model("personalized", 16, directory, client)
                self.assertIs(caught.exception, error)

    def test_loads_exact_local_or_downloaded_predecessor_and_snapshot(self):
        snapshot = {"available_at": "2026-09-01T00:00:00Z", "neighbors": [["u0", "c2", 0.8]]}
        for local in (True, False):
            with self.subTest(local=local), tempfile.TemporaryDirectory() as directory:
                previous_directory = os.path.join(directory, "16")

                def download(*_args):
                    os.makedirs(previous_directory)
                    with open(os.path.join(previous_directory, "behavior.json"), "w") as stream:
                        json.dump(snapshot, stream)

                client = MagicMock()
                client.download_model.side_effect = download
                if local:
                    download()
                with patch.object(pipeline.tf.saved_model, "load") as load:
                    result = pipeline._load_previous_model("personalized", 16, directory, client)
                    self.assertEqual(result, (load.return_value, snapshot))
                    load.assert_called_once_with(previous_directory)
                if local:
                    client.download_model.assert_not_called()
                else:
                    client.download_model.assert_called_once_with("personalized", 16, directory)

    def test_invalid_local_model_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            os.makedirs(os.path.join(directory, "16"))
            with self.assertRaises(OSError):
                pipeline._load_previous_model("personalized", 16, directory, None)

    def test_invalid_snapshot_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            previous_directory = os.path.join(directory, "16")
            os.makedirs(previous_directory)
            with open(os.path.join(previous_directory, "behavior.json"), "w") as stream:
                stream.write("invalid json")
            with patch.object(pipeline.tf.saved_model, "load"), self.assertRaises(json.JSONDecodeError):
                pipeline._load_previous_model("personalized", 16, directory, None)


class TestContextRetrieval(unittest.TestCase):

    def test_language_facets_reject_null_values(self):
        content = pd.DataFrame({"content_id": ["c0"], "language_tag": [np.nan]})

        with self.assertRaisesRegex(ValueError, "language_tag is missing for content c0"):
            pipeline.extract_language_tags(content)

    def test_large_context_partition_uses_scann_when_enabled(self):
        candidate_ids = tf.constant([f"c{i}" for i in range(export._SCANN_MIN_PARTITION_SIZE)])
        candidate_embeddings = tf.zeros([export._SCANN_MIN_PARTITION_SIZE, 4])
        indexed = MagicMock()
        with patch.object(export.tfrs.layers.factorized_top_k, "ScaNN") as scann:
            scann.return_value.index.return_value = indexed
            result = export._build_partition_index(
                True,
                candidate_ids,
                candidate_embeddings,
                5,
                "default_index",
            )

        self.assertIs(result, indexed)
        scann.assert_called_once_with(
            k=5,
            num_leaves=10,
            num_leaves_to_search=10,
            name="default_index",
        )
        scann.return_value.index.assert_called_once_with(candidate_embeddings, identifiers=candidate_ids)

    def test_small_context_partition_stays_exact(self):
        candidate_ids = tf.constant(["c0", "c1"])
        candidate_embeddings = tf.zeros([2, 4])
        indexed = MagicMock()
        with (
            patch.object(export.tfrs.layers.factorized_top_k, "ScaNN") as scann,
            patch.object(export.tfrs.layers.factorized_top_k, "BruteForce") as brute_force,
        ):
            brute_force.return_value.index.return_value = indexed
            result = export._build_partition_index(
                True,
                candidate_ids,
                candidate_embeddings,
                2,
                "images_index",
            )

        self.assertIs(result, indexed)
        scann.assert_not_called()
        brute_force.assert_called_once_with(k=2, name="images_index")


class TestRunTrainingBranches(unittest.TestCase):
    """run_training orchestration: content base always, personalized only when interactions suffice."""

    def setUp(self):
        self.behavior_rows = pd.DataFrame(columns=["kind", "user_id", "source_id", "content_id", "cohort_key", "score"])
        loader = patch.object(pipeline, "load_behavior_from_bosca", return_value=self.behavior_rows)
        self.behavior_loader = loader.start()
        self.addCleanup(loader.stop)


    def test_missing_bosca_url_raises(self):
        with self.assertRaises(ValueError):
            run_training({"bosca_url": ""})

    @patch.object(pipeline, "load_data_from_bosca")
    def test_no_content_returns_no_content(self, mock_load):
        users, interactions, _c, categories, signals, feedback = _frames(0)
        empty = pd.DataFrame(columns=["content_id", "content_type", "language_tag", "labels"])
        mock_load.return_value = (users, interactions, empty, categories, signals, feedback)
        self.assertEqual(run_training({"bosca_url": "http://x"})["status"], "no_content")

    @patch.object(pipeline, "_train_personalized")
    @patch.object(pipeline, "run_content_only_training")
    @patch.object(pipeline, "load_data_from_bosca")
    def test_low_interactions_trains_content_skips_personalized(self, mock_load, mock_content, mock_personalized):
        mock_load.return_value = _frames(0)
        mock_content.return_value = {"status": "completed_content_only", "model_version": 1}
        result = run_training({"bosca_url": "http://x", "model_dir": "/m/recommender", "min_interactions": 100})
        mock_content.assert_called_once()
        mock_personalized.assert_not_called()
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["models"]["content"]["status"], "completed_content_only")
        self.assertEqual(result["models"]["personalized"]["status"], "skipped")

    @patch.object(pipeline, "_train_personalized")
    @patch.object(pipeline, "run_content_only_training")
    @patch.object(pipeline, "load_data_from_bosca")
    def test_sufficient_interactions_trains_both(self, mock_load, mock_content, mock_personalized):
        mock_load.return_value = _frames(150)
        mock_content.return_value = {"status": "completed_content_only", "model_version": 1}
        mock_personalized.return_value = {"status": "completed", "model_version": 1}
        result = run_training({"bosca_url": "http://x", "model_dir": "/m/recommender", "min_interactions": 100})
        mock_content.assert_called_once()
        mock_personalized.assert_called_once()
        self.assertEqual(result["eligible_interactions"], 150)
        self.assertEqual(result["models"]["personalized"]["status"], "completed")

    @patch.object(pipeline, "_train_personalized")
    @patch.object(pipeline, "run_content_only_training")
    @patch.object(pipeline, "load_data_from_bosca")
    def test_excluded_interactions_do_not_trigger_personalized_training(
        self,
        mock_load,
        mock_content,
        mock_personalized,
    ):
        users, interactions, content, categories, signals, feedback = _frames(150)
        interactions = interactions.assign(content_id="excluded")
        mock_load.return_value = (users, interactions, content, categories, signals, feedback)
        mock_content.return_value = {"status": "completed_content_only", "model_version": 1}

        result = run_training({"bosca_url": "http://x", "model_dir": "/m/recommender", "min_interactions": 100})

        mock_content.assert_called_once()
        mock_personalized.assert_not_called()
        self.assertEqual(result["eligible_interactions"], 0)
        self.assertEqual(result["models"]["personalized"]["status"], "skipped")

    @patch.object(pipeline, "_train_personalized")
    @patch.object(pipeline, "run_content_only_training")
    @patch.object(pipeline, "load_data_from_bosca")
    def test_two_models_use_separate_sibling_dirs(self, mock_load, mock_content, mock_personalized):
        mock_load.return_value = _frames(150)
        mock_content.return_value = {"status": "completed_content_only"}
        mock_personalized.return_value = {"status": "completed"}
        run_training({"bosca_url": "http://x", "model_dir": "/models/recommender", "min_interactions": 100})
        # content dir is passed to run_content_only_training via its config (5th positional arg).
        self.assertEqual(mock_content.call_args.args[4]["model_dir"], "/models/recommender-content")
        # personalized dir is passed to _train_personalized after its seven data/config arguments.
        self.assertEqual(mock_personalized.call_args.args[7], "/models/recommender-personalized")


class TestTrainPersonalized(unittest.TestCase):
    def setUp(self):
        loader = patch.object(pipeline, "load_behavior_from_bosca", return_value=pd.DataFrame(
            columns=["kind", "user_id", "source_id", "content_id", "cohort_key", "score"]))
        loader.start()
        self.addCleanup(loader.stop)

    """The personalized path in isolation — export validation + upload handling (compute mocked)."""

    @patch.object(pipeline, "train_model")
    def test_only_unqualified_impressions_skip_fitting_after_preparation(self, mock_train):
        users, interactions, content, categories, signals, feedback = _frames(100)
        interactions = interactions.assign(interaction_type="Impression", element_type="article")
        with tempfile.TemporaryDirectory() as directory:
            result = pipeline._train_personalized(
                users, interactions, content, categories, feedback, signals,
                {"min_interactions": 10}, directory,
            )
        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["training_data"]["retrieval_positives"], 0)
        mock_train.assert_not_called()

    @patch.object(pipeline, "train_model")
    def test_duplicate_delivery_does_not_satisfy_minimum_positive_observations(self, mock_train):
        users, interactions, content, categories, signals, feedback = _frames(1)
        interactions = pd.concat([interactions.assign(event_id="same-event")] * 100, ignore_index=True)
        with tempfile.TemporaryDirectory() as directory:
            result = pipeline._train_personalized(
                users, interactions, content, categories, feedback, signals,
                {"min_interactions": 10}, directory,
            )
        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["training_data"]["duplicate_event_rows"], 99)
        mock_train.assert_not_called()

    @patch.object(pipeline, "export_model")
    @patch.object(pipeline, "train_model")
    @patch.object(pipeline, "run_content_only_training")
    @patch.object(pipeline, "load_data_from_bosca")
    def test_explicit_positive_feedback_can_train_without_implicit_history(self, mock_load, mock_content, mock_train, mock_export):
        users, interactions, content, categories, signals, _ = _frames(0)
        feedback = pd.DataFrame(dict(user_id=["u0", "u1"], content_id=["c0", "c1"],
                                     feedback_label=[1.0, .75], feedback_source=["rating", "rating"]))
        mock_load.return_value = (users, interactions, content, categories, signals, feedback)
        mock_train.return_value = (MagicMock(), MagicMock())
        mock_export.return_value = {"version": 1, "promoted": True, "validation": {"status": "passed"}}
        with tempfile.TemporaryDirectory() as directory:
            result = run_training({"bosca_url": "http://x", "model_dir": directory, "min_interactions": 2})
        self.assertEqual(result["models"]["personalized"]["status"], "completed")
        training_data = result["models"]["personalized"]["training_data"]
        self.assertEqual(training_data["explicit_feedback_rows"], 2)
        self.assertEqual(training_data["retrieval_positives"], 2)
        mock_train.assert_called_once()

    @patch.object(pipeline, "ArtifactsModelClient")
    @patch.object(pipeline, "export_model")
    @patch.object(pipeline, "train_model")
    @patch.object(pipeline, "prepare_datasets")
    def test_missing_serving_facet_rejection(self, mock_prepare, mock_train, mock_export, _mock_storage):
        user_dataset = MagicMock()
        user_dataset.cardinality.return_value.numpy.return_value = 0
        training_data = {"observations": 1, "retrieval_positives": 1}
        mock_prepare.return_value = (
            tf.data.Dataset.range(1), None, None, user_dataset,
            {"user_ids": [], "training_user_ids": [], "content_ids": [], "training_data": training_data},
        )
        mock_train.return_value = (MagicMock(), MagicMock())
        mock_export.return_value = {"version": None, "promoted": False, "missing_facets": ["default\x1fen"]}
        with tempfile.TemporaryDirectory() as d:
            result = pipeline._train_personalized(*_personalized_args(60), {"artifacts_url": "http://x"}, d)
        self.assertEqual(result["status"], "rejected")
        self.assertIn("missing facets", result["reason"])
        self.assertEqual(result["training_data"], training_data)

    @patch.object(pipeline, "ArtifactsModelClient")
    @patch.object(pipeline, "export_model")
    @patch.object(pipeline, "train_model")
    @patch.object(pipeline, "prepare_datasets")
    def test_upload_failure_fails_training(self, mock_prepare, mock_train, mock_export, mock_storage):
        user_dataset = MagicMock()
        user_dataset.cardinality.return_value.numpy.return_value = 1
        mock_prepare.return_value = (
            tf.data.Dataset.range(1), None, None, user_dataset,
            {"user_ids": ["u0"], "training_user_ids": ["u0"], "content_ids": ["c0"]},
        )
        mock_train.return_value = (MagicMock(), MagicMock())
        mock_export.return_value = {"version": 1, "promoted": True, "validation": {"status": "passed"}}
        mock_storage.return_value.upload_model.side_effect = RuntimeError("upload down")
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(RuntimeError, "upload down"):
                pipeline._train_personalized(*_personalized_args(60), {"artifacts_url": "http://x"}, d)


class TestPersonalizedEndToEnd(unittest.TestCase):
    """Real personalized training — full-data prepare + train + validated export + OOV-empty."""

    def setUp(self):
        tf.random.set_seed(7)
        np.random.seed(7)
        self.cfg = {
            "artifacts_url": "http://x", "embedding_dim": 8, "epochs": 2,
            "batch_size": 16, "top_k": 5,
        }

    def _train(self, d, n=60, cfg=None, **kw):
        return pipeline._train_personalized(*_personalized_args(n, **kw), cfg or self.cfg, d)

    def test_deleted_predecessor_trains_and_exports_allocated_version_with_current_behavior(self):
        client = pipeline.ArtifactsModelClient("http://artifacts", "model")
        client.pull_session = MagicMock()
        client.session = MagicMock()
        registry_response = requests.Response()
        registry_response.status_code = 200
        registry_response._content = b'{"versions": []}'
        missing_response = requests.Response()
        missing_response.status_code = 404
        missing_response.url = "http://artifacts/ml/model/personalized/16"
        missing_response._content = b""
        missing_response._content_consumed = True
        client.pull_session.get.side_effect = [registry_response, missing_response]
        config = {**self.cfg, "model_version": 17, "previous_personalized_model_version": 16,
                  "personalized_model_name": "personalized", "preserve_serving_facets": False}
        behavior = pd.DataFrame([
            {"kind": "global", "user_id": "", "source_id": "c0", "content_id": "c2",
             "cohort_key": "", "score": 5.},
            {"kind": "cohort", "user_id": "", "source_id": "c0", "content_id": "c2",
             "cohort_key": "group", "score": 3.},
            {"kind": "membership", "user_id": "u0", "source_id": "", "content_id": "",
             "cohort_key": "group", "score": 0.},
        ])
        with tempfile.TemporaryDirectory() as directory, patch.object(pipeline, "ArtifactsModelClient", return_value=client):
            with self.assertLogs(pipeline.log, level="WARNING") as logs:
                result = pipeline._train_personalized(
                    *_personalized_args(60, with_feedback=True), config, directory, behavior_rows=behavior,
                )
            self.assertIn("personalized@16 was not found (HTTP 404)", logs.output[0])
            self.assertEqual(result["status"], "completed")
            self.assertEqual(result["model_version"], 17)
            self.assertEqual(result["artifact_version"], 17)
            self.assertEqual(result["validation"]["status"], "passed")
            self.assertEqual(result["training_data"]["behavior_feature_rows"], 0)
            model_directory = os.path.join(directory, "17")
            snapshot = pipeline.read_snapshot(model_directory)
            self.assertEqual(snapshot["neighbors"], [])
            self.assertEqual(snapshot["edges"], behavior.iloc[:2].to_dict("records"))
            self.assertEqual(snapshot["memberships"], [{"user_id": "u0", "cohort_key": "group"}])
            self.assertEqual(snapshot["ratings"], [["u0", "c0", 1.]])
            loaded = tf.saved_model.load(model_directory)
            assert_valid_recommender(self, loaded, list(_content_frame()["content_id"]), self.cfg["top_k"], sample_user="u0")
            co_engaged = loaded.signatures["co_engaged"](
                user_id=tf.constant(["u0"]), source_id=tf.constant(["c0"]),
                context_type=tf.constant(["default"]), language_tag=tf.constant(["en"]),
                offset=tf.constant([0], tf.int32),
            )
            self.assertIn("c2", decode_ids(co_engaged["content_ids"][0]))
        self.assertEqual(client.pull_session.get.call_count, 2)
        client.pull_session.get.assert_called_with(
            "http://artifacts/ml/model/personalized/16", stream=True, timeout=client.request_timeout,
        )
        self.assertEqual(client.session.put.call_args.args[0], "http://artifacts/ml/model/api/personalized/17")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_train_export_validation_and_automatic_promotion(self, mock_storage):
        mock_storage.return_value.upload_model.side_effect = lambda _path, version, **_kwargs: {
            "name": "recommender-personalized", "version": version, "size_bytes": 123,
        }
        content_ids = list(_content_frame(8)["content_id"])
        with tempfile.TemporaryDirectory() as first_dir, tempfile.TemporaryDirectory() as ephemeral_dir:
            first = self._train(first_dir, with_feedback=True)
            self.assertEqual(first["status"], "completed")
            self.assertEqual(first["model_version"], 1)
            self.assertEqual(first["validation"]["status"], "passed")
            self.assertEqual(first["artifact_version"], 1)
            self.assertEqual(first["model_generation"], export.PERSONALIZED_MODEL_GENERATION)
            self.assertEqual(first["training_users"], 6)
            self.assertEqual(first["indexed_users"], 6)
            self.assertIsNone(first["champion_training_users"])
            self.assertIsNone(first["training_data_cutoff"])
            loaded = tf.saved_model.load(os.path.join(first_dir, "1"))
            assert_valid_recommender(self, loaded, content_ids, self.cfg["top_k"], sample_user="u0")

            # Simulate the next Kubernetes Job's fresh emptyDir: registry hydration places the current
            # champion (v12) in a different scratch directory before export.
            def hydrate(_model_name, output_dir):
                shutil.copytree(os.path.join(first_dir, "1"), os.path.join(output_dir, "12"))
                return 12

            mock_storage.return_value.hydrate_latest_model.side_effect = hydrate
            second = self._train(ephemeral_dir, with_feedback=True)
            self.assertEqual(second["status"], "completed")
            self.assertEqual(second["model_version"], 13)
            self.assertEqual(second["artifact_version"], 13)
            self.assertEqual(second["validation"]["status"], "passed")
            self.assertEqual(second["champion_training_users"], first["training_users"])
            self.assertEqual(second["champion_indexed_users"], first["indexed_users"])

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_serving_default_is_empty_for_unknown_user(self, mock_storage):
        # oov_empty: a user the model saw gets real recommendations; a user it never saw gets blanked out,
        # so the serving layer reads "no personalized signal" and falls back to the content model.
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(self._train(d, with_feedback=True)["status"], "completed")
            loaded = tf.saved_model.load(os.path.join(d, "1"))
            known = decode_ids(loaded.signatures["serving_default"](
                user_id=tf.constant(["u0"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])
            unknown = decode_ids(loaded.signatures["serving_default"](
                user_id=tf.constant(["never-seen"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])
            self.assertTrue(any(cid for cid in known), "a known user gets real recommendations")
            self.assertTrue(all(cid == "" for cid in unknown), f"an unknown user gets blanks, got {unknown}")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_training_uses_all_rows_without_a_holdout(self, mock_storage):
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        with tempfile.TemporaryDirectory() as d:
            result = self._train(d, n=30)
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["training_users"], result["indexed_users"])

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_fuses_semantic_embeddings(self, mock_storage):
        # A content frame carrying a dense semantic embedding trains + exports through both towers + rank.
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        users, interactions, content, categories, feedback, signals = _personalized_args(60, with_feedback=True)
        rng = np.random.RandomState(3)
        content = content.copy()
        content["embedding"] = [list(rng.rand(4).astype(float)) for _ in range(len(content))]
        with tempfile.TemporaryDirectory() as d:
            result = pipeline._train_personalized(users, interactions, content, categories, feedback, signals, self.cfg, d)
            self.assertEqual(result["status"], "completed")
            loaded = tf.saved_model.load(os.path.join(d, "1"))
            assert_valid_recommender(self, loaded, list(content["content_id"]), self.cfg["top_k"], sample_user="u0")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_labels_and_no_categories(self, mock_storage):
        # Exercises the label feature (num_labels > 0) and the no-category path (num_categories == 0).
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        with tempfile.TemporaryDirectory() as d:
            result = self._train(d, n=40, with_feedback=True, with_labels=True, with_categories=False)
            self.assertEqual(result["status"], "completed")
            loaded = tf.saved_model.load(os.path.join(d, "1"))
            assert_valid_recommender(self, loaded, list(_content_frame(8)["content_id"]),
                                     self.cfg["top_k"], sample_user="u0")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_each_valid_model_is_automatically_promoted(self, mock_storage):
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        with tempfile.TemporaryDirectory() as d:
            first = self._train(d, with_feedback=True)
            second = self._train(d, with_feedback=True)
            self.assertEqual(first["model_version"], 1)
            self.assertEqual(second["status"], "completed")
            self.assertEqual(second["model_version"], 2)
            self.assertEqual(second["validation"]["status"], "passed")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_an_older_unreadable_model_does_not_block_a_valid_export(self, mock_storage):
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 2, "size_bytes": 1}
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "1"))
            result = self._train(d, with_feedback=True)

        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["model_version"], 2)

    def _export_inputs(self, n=40):
        """The model, datasets, vocabularies, and context memberships consumed by export_model, built via the same
        prepare_datasets + train_model steps the pipeline uses (one epoch — these tests exercise export
        branches, not model quality). Lets the export-branch tests drive export_model directly."""
        from trainer.datasets import prepare_datasets
        from trainer.training import train_model

        users, interactions, content, categories, feedback, signals = _personalized_args(n, with_feedback=True)
        retrieval, ranking_ds, content_ds, user_ds, vocabs = prepare_datasets(
            interactions, content, categories, feedback, signals, users
        )
        model, ranking_model = train_model(
            retrieval, ranking_ds, content_ds, vocabs,
            embedding_dim=8, epochs=1, batch_size=16, learning_rate=0.1,
        )
        return (
            model,
            ranking_model,
            content_ds,
            user_ds,
            vocabs,
            pipeline.extract_recommendation_contexts(content),
            pipeline.extract_language_tags(content),
        )

    def test_export_rejects_an_artifact_with_no_eligible_users(self):
        model, ranking_model, content_ds, user_ds, vocabs, recommendation_contexts, language_tags = self._export_inputs()
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, "no eligible users"):
                export.export_model(
                    model=model, ranking_model=ranking_model, content_dataset=content_ds, user_dataset=user_ds,
                    recommendation_contexts=recommendation_contexts,
                    language_tags=language_tags,
                    vocabs=dict(vocabs, user_ids=[]), model_dir=os.path.join(d, "recommender"),
                    use_scann=False, top_k=5,
                )

    @patch.object(export, "_validate_exported_recommender", side_effect=ValueError("invalid SavedModel"))
    def test_contract_validation_failure_prevents_promotion(self, _mock_validation):
        model, ranking_model, content_ds, user_ds, vocabs, recommendation_contexts, language_tags = self._export_inputs()
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, "invalid SavedModel"):
                export.export_model(
                    model=model, ranking_model=ranking_model, content_dataset=content_ds, user_dataset=user_ds,
                    recommendation_contexts=recommendation_contexts,
                    language_tags=language_tags,
                    vocabs=vocabs, model_dir=d, use_scann=False, top_k=5,
                )
            self.assertFalse(os.path.exists(os.path.join(d, "1")))
            self.assertFalse(any(name.startswith(".candidate-") for name in os.listdir(d)))

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_train_personalized_without_artifacts_url_skips_upload(self, mock_storage):
        # No artifacts_url configured -> the model trains + exports but the push step is skipped entirely.
        with tempfile.TemporaryDirectory() as d:
            result = pipeline._train_personalized(
                *_personalized_args(40, with_feedback=True), dict(self.cfg, artifacts_url=""), d
            )
            self.assertEqual(result["status"], "completed")
            self.assertNotIn("artifact_version", result)
            mock_storage.assert_not_called()


def _preference_frames():
    """Opposing preferences within one language/context: serving filters cannot separate the categories."""
    content = pd.DataFrame({
        "content_id": [f"c{i}" for i in range(8)],
        "content_type": ["article"] * 8,
        "language_tag": ["en"] * 8,
        "labels": [[] for _ in range(8)],
    })
    categories = pd.DataFrame({
        "content_id": [f"c{i}" for i in range(8)],
        "category_id": ["cat-A"] * 4 + ["cat-B"] * 4,
    })
    # Distinct per-user signals aligned with each user's exclusive category preference.
    signals = pd.DataFrame({
        "user_id": ["u0", "u1"],
        "signal_key": ["pref", "pref"],
        "signal_value": [json.dumps("a"), json.dumps("b")],
        "value_type": ["categorical", "categorical"],
        "priority": [1, 1],
    })
    rows = []
    for _ in range(8):  # repeat the exclusive engagement so the signal is strong
        rows += [("u0", f"c{i}") for i in range(4)]
        rows += [("u1", f"c{i}") for i in range(4, 8)]
    interactions = pd.DataFrame({
        "user_id": [u for u, _ in rows],
        "content_id": [c for _, c in rows],
        "interaction_type": ["Interaction"] * len(rows),
        "view_percent": [90.0] * len(rows),
    })
    feedback = pd.DataFrame(columns=["user_id", "content_id", "feedback_label", "feedback_source"])
    users = pd.DataFrame({"user_id": ["u0", "u1"]})
    return users, interactions, content, categories, signals, feedback


class TestTwoTowerBehavior(unittest.TestCase):
    """Behavioral correctness: the personalized model recommends what each user actually preferred."""

    def setUp(self):
        tf.random.set_seed(1234)
        np.random.seed(1234)

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_analytics_completions_and_ignored_exposures_train_personalized_results(self, mock_storage):
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        users, interactions, content, categories, signals, feedback = _preference_frames()
        rows = []
        for index, row in interactions.iterrows():
            common = dict(user_id=row.user_id, app_id="app", session_id=f"visit-{index}",
                          page_id="/feed", interaction_created="2026-09-05T10:00:00Z", view_percent=None)
            rows.append(dict(common, event_id=f"complete-{index}", content_id=row.content_id,
                             interaction_type="Completion", element_type="article"))
            ignored = f"c{(int(row.content_id[1:]) + 4) % 8}"
            rows.append(dict(common, event_id=f"seen-{index}", content_id=ignored,
                             interaction_type="Impression", element_type="article",
                             visible_ms=1000, visibility_threshold=.5))
        analytics = pd.DataFrame(rows)
        analytics.attrs["as_of"] = "2026-09-05T12:00:00Z"
        cfg = dict(artifacts_url="http://x", embedding_dim=16, epochs=40,
                   batch_size=16, learning_rate=.1, top_k=4)
        with tempfile.TemporaryDirectory() as directory:
            result = pipeline._train_personalized(users, analytics, content, categories, feedback, signals, cfg, directory)
            self.assertEqual(result["status"], "completed")
            self.assertEqual(result["training_data"]["ignored_exposures"], 64)
            self.assertEqual(result["training_data"]["retrieval_positives"], 64)
            loaded = tf.saved_model.load(os.path.join(directory, "1"))
            for user, preferred in [("u0", {"c0", "c1", "c2", "c3"}), ("u1", {"c4", "c5", "c6", "c7"})]:
                recs = decode_ids(loaded.signatures["serving_default"](
                    user_id=tf.constant([user]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"]),
                )["content_ids"][0])
                self.assertGreaterEqual(len(set(recs) & preferred), 3, f"{user}: {recs}")
                scores = loaded.signatures["rank"](
                    user_id=tf.constant([user] * 8), content_id=tf.constant([f"c{i}" for i in range(8)]),
                )["scores"].numpy().reshape(-1)
                positive = [score for index, score in enumerate(scores) if f"c{index}" in preferred]
                ignored = [score for index, score in enumerate(scores) if f"c{index}" not in preferred]
                self.assertGreater(np.mean(positive), np.mean(ignored), f"{user} scores={scores}")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_recommends_the_users_preferred_category(self, mock_storage):
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        users, interactions, content, categories, signals, feedback = _preference_frames()
        cfg = {
            "artifacts_url": "http://x", "embedding_dim": 16, "epochs": 40,
            "batch_size": 16, "learning_rate": 0.1, "top_k": 4,
        }
        cat_a = {"c0", "c1", "c2", "c3"}
        with tempfile.TemporaryDirectory() as d:
            result = pipeline._train_personalized(users, interactions, content, categories, feedback, signals, cfg, d)
            self.assertEqual(result["status"], "completed")
            loaded = tf.saved_model.load(os.path.join(d, "1"))

            u0_top = decode_ids(loaded.signatures["serving_default"](
                user_id=tf.constant(["u0"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])
            u1_top = decode_ids(loaded.signatures["serving_default"](
                user_id=tf.constant(["u1"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
            )["content_ids"][0])

            # u0 engaged only cat-A, so its top-4 should be dominated by cat-A (and u1 by cat-B).
            self.assertGreaterEqual(len(set(u0_top) & cat_a), 3, f"u0 recommendations={u0_top}")
            self.assertLessEqual(len(set(u1_top) & cat_a), 1, f"u1 recommendations={u1_top}")

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_similar_users_returns_nearest_users_by_embedding(self, mock_storage):
        """The `similar_users` signature returns a viewer's nearest users (self + the rest of the corpus)."""
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        users, interactions, content, categories, signals, feedback = _preference_frames()  # users u0, u1
        cfg = {
            "artifacts_url": "http://x", "embedding_dim": 16, "epochs": 20,
            "batch_size": 16, "learning_rate": 0.1, "top_k": 4,
        }
        with tempfile.TemporaryDirectory() as d:
            pipeline._train_personalized(users, interactions, content, categories, feedback, signals, cfg, d)
            loaded = tf.saved_model.load(os.path.join(d, "1"))
            neighbors = decode_ids(loaded.signatures["similar_users"](user_id=tf.constant(["u0"]))["content_ids"][0])
            # u0 is its own nearest neighbor and u1 (the only other user) is reached.
            self.assertIn("u0", neighbors)
            self.assertIn("u1", [n for n in neighbors if n])

    @patch.object(pipeline, "ArtifactsModelClient")
    def test_signal_only_user_gets_cold_start_recommendations(self, mock_storage):
        """A user with signals but ZERO interactions is placed by its signals and still served (not blanked
        as OOV) — the cold-start guarantee."""
        mock_storage.return_value.upload_model.return_value = {"name": "m", "version": 1, "size_bytes": 1}
        users, interactions, content, categories, signals, feedback = _preference_frames()
        # Two zero-history users with opposing signals must receive different preferred categories.
        users = pd.concat([users, pd.DataFrame({"user_id": ["u_cold_a", "u_cold_b"]})], ignore_index=True)
        signals = pd.concat([signals, pd.DataFrame({
            "user_id": ["u_cold_a", "u_cold_b"], "signal_key": ["pref"] * 2,
            "signal_value": [json.dumps("a"), json.dumps("b")],
            "value_type": ["categorical"] * 2, "priority": [1] * 2,
        })], ignore_index=True)
        cfg = {
            "artifacts_url": "http://x", "embedding_dim": 16, "epochs": 40,
            "batch_size": 16, "learning_rate": 0.1, "top_k": 4,
        }
        with tempfile.TemporaryDirectory() as d:
            result = pipeline._train_personalized(users, interactions, content, categories, feedback, signals, cfg, d)
            self.assertEqual(result["status"], "completed")
            loaded = tf.saved_model.load(os.path.join(d, "1"))
            for user_id, preferred in [
                ("u_cold_a", {"c0", "c1", "c2", "c3"}),
                ("u_cold_b", {"c4", "c5", "c6", "c7"}),
            ]:
                with self.subTest(user_id=user_id):
                    recs = decode_ids(loaded.signatures["serving_default"](
                        user_id=tf.constant([user_id]), context_type=tf.constant(["default"]),
                        language_tag=tf.constant(["en"]),
                    )["content_ids"][0])
                    self.assertGreaterEqual(len(set(recs) & preferred), 3, f"{user_id} recs={recs}")


class TestPrepareDatasets(unittest.TestCase):
    """Directly exercises prepare_datasets' data-shape branches (no training)."""

    def test_percent_points_and_missing_consumption_have_distinct_labels(self):
        from trainer.datasets import prepare_datasets

        content = _content_frame(4)
        no_categories = pd.DataFrame(columns=["content_id", "category_id"])

        # Percentages below one still use percentage points; their scale never depends on other rows.
        prenormalized = pd.DataFrame({
            "user_id": ["u0", "u1"], "content_id": ["c0", "c1"],
            "interaction_type": ["Interaction"] * 2, "view_percent": [0.5, 0.8],
        })
        _, ranking, *_ = prepare_datasets(prenormalized, content, no_categories, None, None)
        np.testing.assert_allclose([row["label"] for row in ranking.as_numpy_iterator()], [.005, .008])

        # A click with unknown consumption retains a positive engagement target.
        without_view_percent = prenormalized.drop(columns=["view_percent"])
        _, ranking, *_ = prepare_datasets(without_view_percent, content, no_categories, None, None)
        np.testing.assert_allclose([row["label"] for row in ranking.as_numpy_iterator()], [.75, .75])

    def test_every_eligible_user_is_materialized_without_withholding_interactions(self):
        from trainer.datasets import prepare_datasets

        content = _content_frame(2)
        interactions = pd.DataFrame({
            "user_id": ["u0", "u0"],
            "content_id": ["c0", "c1"],
            "interaction_type": ["Interaction", "Interaction"],
            "view_percent": [0.5, 0.8],
        })
        users = pd.DataFrame({"user_id": ["u0", "u1", "u_cold"]})
        signals = pd.DataFrame({
            "user_id": ["u_cold", "not_eligible"],
            "signal_key": ["interest", "interest"],
            "signal_value": [json.dumps("science"), json.dumps("sports")],
            "value_type": ["categorical", "categorical"],
            "priority": [1, 1],
        })
        no_categories = pd.DataFrame(columns=["content_id", "category_id"])

        retrieval, ranking, _, user_dataset, vocabs = prepare_datasets(
            interactions, content, no_categories, None, signals, users
        )

        self.assertEqual(sum(1 for _ in retrieval), len(interactions))
        self.assertEqual(sum(1 for _ in ranking), len(interactions))
        self.assertEqual(vocabs["training_user_ids"], ["u0"])
        self.assertEqual(vocabs["user_ids"], ["u0", "u1", "u_cold"])
        indexed_users = sorted(value["user_id"].numpy().decode() for value in user_dataset)
        self.assertEqual(indexed_users, ["u0", "u1", "u_cold"])

    def test_recommendation_contexts_are_structured_index_memberships_not_tensor_features(self):
        from trainer.datasets import build_content_dataset, extract_recommendation_contexts

        content = _content_frame(3)
        content["recommendation_contexts"] = [
            ["default", "featured"],
            ["videos"],
            [],
        ]
        memberships = extract_recommendation_contexts(content)
        content_dataset, *_ = build_content_dataset(
            content,
            pd.DataFrame(columns=["content_id", "category_id"]),
        )

        self.assertEqual(memberships["c0"], frozenset({"default", "featured"}))
        self.assertEqual(memberships["c1"], frozenset({"videos"}))
        self.assertEqual(memberships["c2"], frozenset())
        self.assertNotIn("recommendation_contexts", content_dataset.element_spec)

    def test_delimiter_packed_recommendation_contexts_are_rejected(self):
        from trainer.datasets import extract_recommendation_contexts

        content = _content_frame(1)
        content["recommendation_contexts"] = ["default\x1ffeatured"]
        with self.assertRaisesRegex(ValueError, "must be an array"):
            extract_recommendation_contexts(content)

    def test_pooled_embeddings_are_fused(self):
        from trainer.datasets import prepare_datasets

        content = _content_frame(4)
        content["embedding"] = [
            [0.9993629, 0.03569153],
            [0.0, 1.0],
            [0.7071068, 0.7071068],
            [0.7071068, 0.7071068],
        ]
        no_categories = pd.DataFrame(columns=["content_id", "category_id"])
        interactions = pd.DataFrame({
            "user_id": ["u0", "u1"], "content_id": ["c0", "c1"],
            "interaction_type": ["Interaction"] * 2, "view_percent": [0.5, 0.8],
        })
        retrieval, ranking, content_ds, user_ds, vocabs = prepare_datasets(
            interactions, content, no_categories, None, None
        )
        self.assertEqual(vocabs["embedding_dim"], 2)
        self.assertIn("embedding", ranking.element_spec)
        self.assertEqual(ranking.element_spec["embedding"].shape[-1], 2)
        self.assertIn("embedding", content_ds.element_spec)

    def test_no_embedding_column_omits_feature(self):
        from trainer.datasets import prepare_datasets

        content = _content_frame(4)  # no embedding column
        no_categories = pd.DataFrame(columns=["content_id", "category_id"])
        interactions = pd.DataFrame({
            "user_id": ["u0"], "content_id": ["c0"],
            "interaction_type": ["Interaction"], "view_percent": [0.5],
        })
        _, ranking, content_ds, _, vocabs = prepare_datasets(interactions, content, no_categories, None, None)
        self.assertEqual(vocabs["embedding_dim"], 0)
        self.assertNotIn("embedding", ranking.element_spec)
        self.assertNotIn("embedding", content_ds.element_spec)

    def test_feedback_with_no_matching_content_is_dropped(self):
        from trainer.datasets import prepare_datasets

        content = _content_frame(4)  # c0..c3
        no_categories = pd.DataFrame(columns=["content_id", "category_id"])
        interactions = pd.DataFrame({
            "user_id": ["u0"], "content_id": ["c0"],
            "interaction_type": ["Interaction"], "view_percent": [0.5],
        })
        # Feedback that references content the corpus doesn't contain -> the inner merge drops every row, so
        # the feedback branch adds nothing (exercises the "no surviving feedback rows" path) without erroring.
        feedback = pd.DataFrame({
            "user_id": ["u0"], "content_id": ["does-not-exist"],
            "feedback_label": [1.0], "feedback_source": ["rating"],
        })
        retrieval, ranking, _, _, _ = prepare_datasets(interactions, content, no_categories, feedback, None)
        # Only the single implicit view survives as a labeled row; no feedback rows were merged in.
        self.assertEqual(sum(1 for _ in ranking), 1)


class TestRankingModel(unittest.TestCase):
    """The ranking head's loss path, including the optional sample_weight."""

    def _ranker(self):
        from trainer.models import BoscaRanker, ContentModel, RankingModel, UserModel

        user_model = UserModel(["u0"], num_signal_tokens=0, num_categories=0, embedding_dim=8)
        content_model = ContentModel(["c0"], ["text/plain"], ["en"], [], [], embedding_dim=8)
        return BoscaRanker(RankingModel(user_model, content_model))

    def _features(self, with_weight):
        feats = {
            "user_id": tf.constant(["u0"]),
            "content_id": tf.constant(["c0"]),
            "content_type": tf.constant(["text/plain"]),
            "language_tag": tf.constant(["en"]),
            "label": tf.constant([1.0]),
        }
        if with_weight:
            feats["sample_weight"] = tf.constant([2.0])
        return feats

    def test_compute_loss_with_and_without_sample_weight(self):
        ranker = self._ranker()
        with_weight = ranker.compute_loss(self._features(with_weight=True))
        # Missing "sample_weight" key -> the None branch (loss computed unweighted), must still be finite.
        without_weight = ranker.compute_loss(self._features(with_weight=False))
        self.assertTrue(bool(tf.math.is_finite(with_weight).numpy()))
        self.assertTrue(bool(tf.math.is_finite(without_weight).numpy()))


class TestUserModel(unittest.TestCase):
    """The user tower's feature-absent branches (no signals / no categories), via a direct forward pass."""

    def test_forward_pass_without_signals_or_categories(self):
        from trainer.models import UserModel, _user_features

        user_model = UserModel(["u0", "u1"], num_signal_tokens=0, num_categories=0, embedding_dim=8)
        out = user_model({"user_id": tf.constant(["u0"])})
        self.assertEqual(tuple(out.shape), (1, 8))  # id-only path (the single-part concat branch)
        # _user_features drops the optional signal / affinity inputs when they're absent.
        self.assertEqual(set(_user_features({"user_id": tf.constant(["u0"])})), {"user_id"})

    def test_forward_pass_with_signals(self):
        from trainer.models import UserModel

        user_model = UserModel(["u0"], num_signal_tokens=3, num_categories=0, embedding_dim=8)
        out = user_model({
            "user_id": tf.constant(["u0"]),
            "user_signal_multi_hot": tf.constant([[1.0, 0.0, 2.0]]),
        })
        self.assertEqual(tuple(out.shape), (1, 8))


if __name__ == "__main__":
    unittest.main()
