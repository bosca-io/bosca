#!/usr/bin/env python3
"""Tests for the artifacts model client used by the ML training pipeline."""

import io
import os
import tarfile
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import requests

from bosca_storage import (
    CONTENT_MODEL_NAME,
    MODEL_CONTENT_TYPE,
    PERSONALIZED_MODEL_NAME,
    ArtifactsModelClient,
)


class TestArtifactsModelClient(unittest.TestCase):

    def setUp(self):
        self.client = ArtifactsModelClient("http://artifacts:8084", "model", api_token="test-token")

    def test_init_sets_auth_header(self):
        self.assertEqual(self.client.session.headers["Authorization"], "Bearer test-token")
        self.assertEqual(self.client.pull_session.headers["Authorization"], "Bearer test-token")

    def test_request_timeouts_are_configurable(self):
        with patch.dict(os.environ, {"ML_TRAINER_CONNECT_TIMEOUT_SECONDS": "12",
                                     "ML_TRAINER_READ_TIMEOUT_SECONDS": "1200"}):
            client = ArtifactsModelClient("http://artifacts:8084", "model")
        self.assertEqual(client.request_timeout, (12., 1200.))

    def test_init_uses_dedicated_pull_auth_header(self):
        client = ArtifactsModelClient(
            "http://artifacts:8084", "model", api_token="push-token", pull_api_token="pull-token",
        )
        self.assertEqual(client.session.headers["Authorization"], "Bearer push-token")
        self.assertEqual(client.pull_session.headers["Authorization"], "Bearer pull-token")

    def test_init_without_token(self):
        client = ArtifactsModelClient("http://artifacts:8084", "model")
        self.assertNotIn("Authorization", client.session.headers)

    def test_url_trailing_slash_stripped(self):
        client = ArtifactsModelClient("http://artifacts:8084/", "model")
        self.assertEqual(client.artifacts_url, "http://artifacts:8084")

    def test_archive_model_creates_tar_gz(self):
        with tempfile.TemporaryDirectory() as model_dir:
            os.makedirs(os.path.join(model_dir, "variables"))
            with open(os.path.join(model_dir, "saved_model.pb"), "w") as f:
                f.write("fake model data")
            with open(os.path.join(model_dir, "variables", "variables.index"), "w") as f:
                f.write("fake variables")

            archive_path = self.client._archive_model(model_dir)
            try:
                self.assertTrue(os.path.exists(archive_path))
                self.assertTrue(archive_path.endswith(".tar.gz"))
                with tarfile.open(archive_path, "r:gz") as tar:
                    names = tar.getnames()
                    self.assertTrue(any("saved_model.pb" in n for n in names))
                    self.assertTrue(any("variables" in n for n in names))
            finally:
                os.unlink(archive_path)

    def _fake_model_dir(self, tmp):
        version_dir = os.path.join(tmp, "3")
        os.makedirs(version_dir)
        with open(os.path.join(version_dir, "saved_model.pb"), "w") as f:
            f.write("fake")
        return version_dir

    def test_upload_model_puts_tar_to_the_ml_route(self):
        self.client.session = MagicMock()
        with tempfile.TemporaryDirectory() as tmp:
            version_dir = self._fake_model_dir(tmp)
            result = self.client.upload_model(version_dir, version=3, model_name=PERSONALIZED_MODEL_NAME)

        args, kwargs = self.client.session.put.call_args
        self.assertEqual(args[0], "http://artifacts:8084/ml/model/api/recommender-personalized/3")
        self.assertEqual(kwargs["headers"]["Content-Type"], MODEL_CONTENT_TYPE)
        self.assertEqual(kwargs["timeout"], self.client.request_timeout)
        self.client.session.put.return_value.raise_for_status.assert_called_once()
        self.assertEqual(result["name"], PERSONALIZED_MODEL_NAME)
        self.assertEqual(result["version"], 3)
        self.assertGreater(result["size_bytes"], 0)

    def test_upload_model_defaults_to_content_model_name(self):
        self.client.session = MagicMock()
        with tempfile.TemporaryDirectory() as tmp:
            version_dir = self._fake_model_dir(tmp)
            self.client.upload_model(version_dir, version=3)
        args, _ = self.client.session.put.call_args
        self.assertEqual(args[0], f"http://artifacts:8084/ml/model/api/{CONTENT_MODEL_NAME}/3")

    def test_upload_model_deletes_the_archive(self):
        self.client.session = MagicMock()
        with tempfile.TemporaryDirectory() as tmp:
            version_dir = self._fake_model_dir(tmp)
            self.client.upload_model(version_dir, version=3)
            # Only the version dir remains under tmp — the tar archive lives in the system temp dir and is
            # unlinked in the finally block; assert no stray .tar.gz was left in the model tree.
            self.assertEqual(os.listdir(tmp), ["3"])

    def test_get_latest_version_uses_highest_numeric_registry_version(self):
        self.client.pull_session = MagicMock()
        response = self.client.pull_session.get.return_value
        response.status_code = 200
        response.json.return_value = {
            "versions": [{"version": "2"}, {"version": "17"}, {"version": "preview"}],
        }

        self.assertEqual(self.client.get_latest_version(CONTENT_MODEL_NAME), 17)
        self.client.pull_session.get.assert_called_once_with(
            "http://artifacts:8084/ml/model/api/recommender-content",
            timeout=self.client.request_timeout,
        )
        response.raise_for_status.assert_called_once()

    def test_get_latest_version_returns_none_for_missing_repository(self):
        self.client.pull_session = MagicMock()
        self.client.pull_session.get.return_value.status_code = 404

        self.assertIsNone(self.client.get_latest_version(PERSONALIZED_MODEL_NAME))

    @staticmethod
    def _model_archive(version: int) -> bytes:
        archive = io.BytesIO()
        payload = b"saved model"
        with tarfile.open(fileobj=archive, mode="w:gz") as tar:
            info = tarfile.TarInfo(name=f"{version}/saved_model.pb")
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
        return archive.getvalue()

    def test_hydrate_latest_model_downloads_registry_version_into_scratch(self):
        self.client.pull_session = MagicMock()
        list_response = MagicMock(status_code=200)
        list_response.json.return_value = {"versions": [{"version": "12"}]}
        download_response = MagicMock(status_code=200)
        download_response.iter_content.return_value = [self._model_archive(12)]
        self.client.pull_session.get.side_effect = [list_response, download_response]

        with tempfile.TemporaryDirectory() as output_dir:
            latest = self.client.hydrate_latest_model(PERSONALIZED_MODEL_NAME, output_dir)
            with open(os.path.join(output_dir, "12", "saved_model.pb"), "rb") as model_file:
                self.assertEqual(model_file.read(), b"saved model")

        self.assertEqual(latest, 12)
        download_response.raise_for_status.assert_called_once()
        self.client.pull_session.get.assert_called_with(
            "http://artifacts:8084/ml/model/recommender-personalized/12", stream=True,
            timeout=self.client.request_timeout,
        )

    def test_upload_timeout_preserves_error_and_removes_archive(self):
        self.client.session = MagicMock()
        failure = requests.ReadTimeout("upload response stalled")
        self.client.session.put.side_effect = failure
        with tempfile.TemporaryDirectory() as tmp:
            archive_path = os.path.join(tmp, "model.tar.gz")
            with open(archive_path, "wb") as stream:
                stream.write(b"archive")
            with patch.object(self.client, "_archive_model", return_value=archive_path):
                with self.assertRaises(requests.ReadTimeout) as caught:
                    self.client.upload_model(tmp, 3)
            self.assertIs(caught.exception, failure)
            self.assertFalse(os.path.exists(archive_path))

    def test_download_timeout_preserves_error_and_closes_response(self):
        self.client.pull_session = MagicMock()
        response = self.client.pull_session.get.return_value
        failure = requests.ReadTimeout("download stalled")
        response.iter_content.side_effect = failure
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(requests.ReadTimeout) as caught:
                self.client.download_model(PERSONALIZED_MODEL_NAME, 3, tmp)
            self.assertEqual(os.listdir(tmp), [])
        self.assertIs(caught.exception, failure)
        response.close.assert_called_once()

    def test_hydrate_latest_model_does_not_redownload_local_version(self):
        self.client.get_latest_version = MagicMock(return_value=7)
        self.client.download_model = MagicMock()
        with tempfile.TemporaryDirectory() as output_dir:
            os.makedirs(os.path.join(output_dir, "7"))
            self.assertEqual(self.client.hydrate_latest_model(CONTENT_MODEL_NAME, output_dir), 7)
        self.client.download_model.assert_not_called()

    def test_constants(self):
        self.assertEqual(MODEL_CONTENT_TYPE, "application/x-tar+gzip")
        self.assertNotEqual(CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME)


if __name__ == "__main__":
    unittest.main()
