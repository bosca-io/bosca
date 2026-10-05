#!/usr/bin/env python3
"""Unit tests for the two-model TF Serving loader."""

import os
import signal
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import model_loader
from bosca_storage import CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME
from model_loader import (
    ModelSpec,
    build_specs,
    get_local_versions,
    get_served_versions,
    run_poll_loop,
    set_served_versions,
    sync_model,
)


class TestGetLocalVersions(unittest.TestCase):

    def test_missing_dir_is_empty(self):
        self.assertEqual(get_local_versions("/no/such/dir"), set())

    def test_only_integer_subdirs_counted(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "1"))
            os.makedirs(os.path.join(d, "2"))
            os.makedirs(os.path.join(d, "notaversion"))
            with open(os.path.join(d, "3"), "w") as f:  # a FILE named "3" is not a version dir
                f.write("x")
            self.assertEqual(get_local_versions(d), {1, 2})


class TestSyncModel(unittest.TestCase):

    def test_downloads_when_new_version_available(self):
        with tempfile.TemporaryDirectory() as d:
            client = MagicMock()
            client.get_versions.return_value = [5]
            client.download_model.side_effect = lambda name, version, out: os.makedirs(os.path.join(out, str(version)))
            self.assertTrue(sync_model(client, ModelSpec(CONTENT_MODEL_NAME, d)))
            client.download_model.assert_called_once_with(CONTENT_MODEL_NAME, 5, d)

    def test_skips_when_latest_already_local(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "5"))
            set_served_versions(d, {5})
            client = MagicMock()
            client.get_versions.return_value = [5]
            self.assertFalse(sync_model(client, ModelSpec(CONTENT_MODEL_NAME, d)))
            client.download_model.assert_not_called()

    def test_no_model_published_yet(self):
        client = MagicMock()
        client.get_versions.return_value = []
        for name in [CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME]:
            with self.subTest(name=name), self.assertLogs("model_loader", level="DEBUG") as logs:
                self.assertFalse(sync_model(client, ModelSpec(name, "/models/x")))
            self.assertEqual(logs.output, [f"DEBUG:model_loader:No published versions for unselected model {name}"])
        client.download_model.assert_not_called()

    def test_missing_selected_artifacts_warn_and_preserve_the_served_version(self):
        with tempfile.TemporaryDirectory() as directory:
            os.makedirs(os.path.join(directory, "7"))
            set_served_versions(directory, {7})
            client = MagicMock()
            client.get_versions.return_value = []
            name = "recommender-11111111-1111-1111-1111-111111111111-content"
            for selected in [{7}, None]:
                with self.subTest(selected=selected), self.assertLogs("model_loader", level="WARNING") as logs:
                    self.assertFalse(sync_model(client, ModelSpec(name, directory), selected, include_latest=False))
                self.assertIn(f"Selected {name} versions are not available in artifacts and cannot be loaded: [7]", logs.output[0])
                self.assertEqual(get_served_versions(directory), {7})
                self.assertTrue(os.path.isdir(os.path.join(directory, "7")))
            client.download_model.assert_not_called()

    def test_download_failure_is_swallowed(self):
        with tempfile.TemporaryDirectory() as d:
            client = MagicMock()
            client.get_versions.return_value = [5]
            client.download_model.side_effect = RuntimeError("boom")
            self.assertFalse(sync_model(client, ModelSpec(CONTENT_MODEL_NAME, d)))

    def test_limits_explicit_selections_to_one_version_without_adding_latest(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "7"))
            client = MagicMock()
            client.get_versions.return_value = [9, 8, 7, 6, 5, 4]
            client.download_model.side_effect = lambda name, version, out: os.makedirs(os.path.join(out, str(version)))

            self.assertTrue(sync_model(client, ModelSpec(PERSONALIZED_MODEL_NAME, d), selected_versions={7, 5}))

            downloaded = [call.args[1] for call in client.download_model.call_args_list]
            self.assertEqual(downloaded, [])
            self.assertEqual(get_served_versions(d), {7})
            self.assertNotIn(8, get_served_versions(d))

    def test_selection_failure_retains_last_known_pin_without_adding_latest(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "7"))
            set_served_versions(d, {7})
            client = MagicMock()
            client.get_versions.return_value = [9, 8, 7]
            client.download_model.side_effect = lambda name, version, out: os.makedirs(os.path.join(out, str(version)))

            self.assertFalse(sync_model(client, ModelSpec(PERSONALIZED_MODEL_NAME, d), selected_versions=None))

            self.assertEqual(get_served_versions(d), {7})
            client.download_model.assert_not_called()


class TestServedVersions(unittest.TestCase):

    def test_selection_file_is_stable_and_ignores_invalid_lines(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertTrue(set_served_versions(d, {7, 3}))
            self.assertFalse(set_served_versions(d, {3, 7}))
            with open(os.path.join(d, model_loader.SERVED_VERSIONS_FILE), "a") as file:
                file.write("invalid\n0\n")
            self.assertEqual(get_served_versions(d), {3, 7})


class TestBuildSpecs(unittest.TestCase):

    def test_two_specs_by_name_and_dir(self):
        args = SimpleNamespace(
            content_model_dir="/models/recommender-content",
            personalized_model_dir="/models/recommender-personalized",
        )
        specs = build_specs(args)
        self.assertEqual([s.name for s in specs], [CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME])
        self.assertEqual(specs[0].model_dir, "/models/recommender-content")
        self.assertEqual(specs[1].model_dir, "/models/recommender-personalized")


class TestRunPollLoop(unittest.TestCase):

    def _run_with_one_cycle(self, mock_sync, specs, **kwargs):
        """Runs the loop, letting one periodic cycle fire before a simulated SIGTERM stops it."""
        captured = {}
        calls = {"n": 0}

        def fake_signal(signum, handler):
            captured[signum] = handler

        def fake_sleep(_):
            calls["n"] += 1
            if calls["n"] >= 2:
                captured[signal.SIGTERM](signal.SIGTERM, None)

        with patch("model_loader.signal.signal", side_effect=fake_signal), \
             patch("model_loader.time.sleep", side_effect=fake_sleep):
            run_poll_loop("http://artifacts:8084", "model", kwargs.pop("artifacts_token", None), specs, poll_interval=1, **kwargs)

    @patch("model_loader.sync_model")
    @patch("model_loader.ArtifactsModelClient")
    def test_initial_and_one_periodic_cycle_then_stops(self, mock_client_cls, mock_sync):
        with tempfile.TemporaryDirectory() as base:
            content_dir = os.path.join(base, "recommender-content")
            personalized_dir = os.path.join(base, "recommender-personalized")
            specs = [ModelSpec(CONTENT_MODEL_NAME, content_dir), ModelSpec(PERSONALIZED_MODEL_NAME, personalized_dir)]
            self._run_with_one_cycle(mock_sync, specs)
            self.assertTrue(os.path.isdir(content_dir))
            self.assertTrue(os.path.isdir(personalized_dir))
            # Initial sync of both specs + one periodic cycle of both = 4 sync_model calls.
            self.assertEqual(mock_sync.call_count, 4)

    @patch("model_loader.BoscaModelSelectionClient")
    @patch("model_loader.ArtifactsModelClient")
    def test_context_model_loads_without_missing_global_model_info_messages(self, artifacts, selection):
        with tempfile.TemporaryDirectory() as root:
            name = "recommender-11111111-1111-1111-1111-111111111111-content"
            specs = [ModelSpec(model, os.path.join(root, model))
                     for model in [CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME]]
            artifacts.return_value.get_versions.side_effect = lambda model: [3] if model == name else []
            artifacts.return_value.download_model.side_effect = lambda model, version, out: os.makedirs(os.path.join(out, str(version)))
            selection.return_value.get_selected_personalized_versions.return_value = set()
            selection.return_value.get_context_versions.return_value = {name: {3}}
            selection.return_value.get_retained_context_versions.return_value = {name: {3}}

            with self.assertLogs("model_loader", level="INFO") as logs:
                self._run_with_one_cycle(None, specs, bosca_url="http://bosca:8080")

            artifacts.return_value.download_model.assert_called_once_with(name, 3, os.path.join(root, name))
            self.assertEqual(get_served_versions(os.path.join(root, name)), {3})
            self.assertTrue(any(f"Selected {name} versions for serving: [3]" in message for message in logs.output))
            self.assertFalse(any("No published versions" in message or "Waiting for the first training run" in message
                                 for message in logs.output))

    @patch("model_loader.sync_model")
    @patch("model_loader.ArtifactsModelClient")
    def test_periodic_cycle_swallows_sync_errors(self, mock_client_cls, mock_sync):
        with tempfile.TemporaryDirectory() as base:
            specs = [ModelSpec(CONTENT_MODEL_NAME, os.path.join(base, "c")), ModelSpec(PERSONALIZED_MODEL_NAME, os.path.join(base, "p"))]
            counter = {"n": 0}

            def sync_side_effect(client, spec, versions_to_sync):
                counter["n"] += 1
                if counter["n"] > 2:  # initial 2 succeed; periodic ones raise
                    raise RuntimeError("artifacts down")

            mock_sync.side_effect = sync_side_effect
            self._run_with_one_cycle(mock_sync, specs)  # must return cleanly
            self.assertEqual(mock_sync.call_count, 4)

    @patch("model_loader.BoscaModelSelectionClient")
    @patch("model_loader.sync_model")
    @patch("model_loader.ArtifactsModelClient")
    def test_applies_bosca_selection_only_to_personalized_model(
        self,
        mock_artifacts_client,
        mock_sync,
        mock_selection_client,
    ):
        with tempfile.TemporaryDirectory() as base:
            specs = [
                ModelSpec(CONTENT_MODEL_NAME, os.path.join(base, "c")),
                ModelSpec(PERSONALIZED_MODEL_NAME, os.path.join(base, "p")),
            ]
            mock_selection_client.return_value.get_selected_personalized_versions.return_value = {4, 7}

            self._run_with_one_cycle(
                mock_sync,
                specs,
                bosca_url="http://bosca:8080",
                bosca_api_token="token",
            )

            selections = [call.args[2] for call in mock_sync.call_args_list]
            self.assertEqual(selections, [set(), {4, 7}, set(), {4, 7}])

    @patch("model_loader.BoscaModelSelectionClient")
    @patch("model_loader.sync_model")
    @patch("model_loader.ArtifactsModelClient")
    def test_selection_query_failure_preserves_personalized_manifest(
        self,
        mock_artifacts_client,
        mock_sync,
        mock_selection_client,
    ):
        with tempfile.TemporaryDirectory() as base:
            specs = [
                ModelSpec(CONTENT_MODEL_NAME, os.path.join(base, "c")),
                ModelSpec(PERSONALIZED_MODEL_NAME, os.path.join(base, "p")),
            ]
            mock_selection_client.return_value.get_selected_personalized_versions.side_effect = RuntimeError("down")

            self._run_with_one_cycle(mock_sync, specs, bosca_url="http://bosca:8080")

            selections = [call.args[2] for call in mock_sync.call_args_list]
            self.assertEqual(selections, [set(), None, set(), None])

    @patch("model_loader.BoscaModelSelectionClient")
    @patch("model_loader.sync_model")
    @patch("model_loader.ArtifactsModelClient")
    def test_selection_uses_pull_token_before_bosca_token(self, artifacts, sync, selection):
        self._run_with_one_cycle(sync, [], bosca_url="http://bosca:8080",
                                 artifacts_token="pull-token", bosca_api_token="bosca-token")
        selection.assert_called_once_with("http://bosca:8080", "pull-token")

    @patch("model_loader.ArtifactsModelClient")
    def test_unwritable_model_dir_raises_actionable_error(self, mock_client_cls):
        specs = [ModelSpec(CONTENT_MODEL_NAME, "/models/recommender-content")]
        with patch("model_loader.os.makedirs", side_effect=OSError(30, "Read-only file system")):
            with self.assertRaises(RuntimeError) as ctx:
                run_poll_loop("http://artifacts:8084", "model", None, specs, poll_interval=1)
        message = str(ctx.exception)
        self.assertIn("/models/recommender-content", message)
        self.assertIn("CONTENT_MODEL_DIR", message)


class TestMain(unittest.TestCase):

    @patch("model_loader.run_poll_loop")
    def test_main_builds_two_specs_and_runs(self, mock_run):
        argv = [
            "model_loader.py",
            "--artifacts-url", "http://artifacts:8084",
            "--artifacts-namespace", "model",
            "--content-model-dir", "/models/c",
            "--personalized-model-dir", "/models/p",
            "--poll-interval", "42",
            "--bosca-url", "http://bosca:8080",
            "--bosca-api-token", "bosca-token",
        ]
        with patch("sys.argv", argv):
            model_loader.main()
        mock_run.assert_called_once()
        _, kwargs = mock_run.call_args
        self.assertEqual(kwargs["artifacts_url"], "http://artifacts:8084")
        self.assertEqual(kwargs["namespace"], "model")
        self.assertEqual(kwargs["poll_interval"], 42)
        self.assertEqual(kwargs["bosca_url"], "http://bosca:8080")
        self.assertEqual(kwargs["bosca_api_token"], "bosca-token")
        specs = kwargs["specs"]
        self.assertEqual([s.name for s in specs], [CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME])
        self.assertEqual(specs[0].model_dir, "/models/c")


if __name__ == "__main__":
    unittest.main()
