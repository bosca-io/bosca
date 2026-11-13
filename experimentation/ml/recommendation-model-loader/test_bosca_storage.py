#!/usr/bin/env python3
"""Unit tests for the loader's artifacts client (version discovery + download)."""

import io
import os
import tarfile
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

from bosca_storage import (
    CONTENT_MODEL_NAME,
    PERSONALIZED_MODEL_NAME,
    ArtifactsModelClient,
)


class TestArtifactsModelClient(unittest.TestCase):

    def setUp(self):
        self.client = ArtifactsModelClient("http://artifacts:8084/", "model", api_token="tok")

    def test_init_sets_auth_and_strips_slash(self):
        self.assertEqual(self.client.artifacts_url, "http://artifacts:8084")
        self.assertEqual(self.client.session.headers["Authorization"], "Bearer tok")

    def test_init_without_token(self):
        self.assertNotIn("Authorization", ArtifactsModelClient("http://artifacts:8084", "model").session.headers)

    def test_get_latest_version_returns_max_and_hits_list_route(self):
        self.client.session = MagicMock()
        resp = MagicMock()
        resp.status_code = 200
        resp.json.return_value = {"name": PERSONALIZED_MODEL_NAME, "versions": [
            {"version": "1", "created": "t"}, {"version": "3", "created": "t"}, {"version": "2", "created": "t"},
        ]}
        self.client.session.get.return_value = resp

        result = self.client.get_latest_version(PERSONALIZED_MODEL_NAME)

        self.assertEqual(result, 3)
        args, _ = self.client.session.get.call_args
        self.assertEqual(args[0], "http://artifacts:8084/ml/model/api/recommender-personalized")

    def test_get_versions_returns_numeric_versions_newest_first(self):
        self.client.session = MagicMock()
        resp = MagicMock()
        resp.status_code = 200
        resp.json.return_value = {
            "versions": [{"version": "2"}, {"version": "latest"}, {"version": "7"}, {"version": "2"}],
        }
        self.client.session.get.return_value = resp

        self.assertEqual(self.client.get_versions(PERSONALIZED_MODEL_NAME), [7, 2])

    def test_get_latest_version_none_on_404(self):
        self.client.session = MagicMock()
        resp = MagicMock()
        resp.status_code = 404
        self.client.session.get.return_value = resp
        self.assertIsNone(self.client.get_latest_version(CONTENT_MODEL_NAME))
        resp.raise_for_status.assert_not_called()

    def test_get_latest_version_none_when_no_versions(self):
        self.client.session = MagicMock()
        resp = MagicMock()
        resp.status_code = 200
        resp.json.return_value = {"name": CONTENT_MODEL_NAME, "versions": []}
        self.client.session.get.return_value = resp
        self.assertIsNone(self.client.get_latest_version(CONTENT_MODEL_NAME))

    def test_get_latest_version_ignores_non_numeric(self):
        self.client.session = MagicMock()
        resp = MagicMock()
        resp.status_code = 200
        resp.json.return_value = {"versions": [{"version": "latest"}, {"version": "7"}]}
        self.client.session.get.return_value = resp
        self.assertEqual(self.client.get_latest_version(CONTENT_MODEL_NAME), 7)

    def test_download_model_extracts_archive(self):
        # Build a small tar.gz holding a fake SavedModel version dir.
        buf = io.BytesIO()
        with tempfile.TemporaryDirectory() as src:
            version_dir = os.path.join(src, "3")
            os.makedirs(version_dir)
            with open(os.path.join(version_dir, "saved_model.pb"), "w") as f:
                f.write("fake")
            with tarfile.open(fileobj=buf, mode="w:gz") as tar:
                tar.add(version_dir, arcname="3")
        archive_bytes = buf.getvalue()

        self.client.session = MagicMock()
        resp = MagicMock()
        resp.iter_content.return_value = [archive_bytes]
        self.client.session.get.return_value = resp

        with tempfile.TemporaryDirectory() as out:
            self.client.download_model(PERSONALIZED_MODEL_NAME, 3, out)
            self.assertTrue(os.path.exists(os.path.join(out, "3", "saved_model.pb")))
        args, _ = self.client.session.get.call_args
        self.assertEqual(args[0], "http://artifacts:8084/ml/model/recommender-personalized/3")

    def test_distinct_model_names(self):
        self.assertNotEqual(CONTENT_MODEL_NAME, PERSONALIZED_MODEL_NAME)

    def test_configurable_timeouts_are_applied_to_discovery_and_downloads(self):
        with patch.dict(os.environ, {"ML_LOADER_CONNECT_TIMEOUT_SECONDS": "5", "ML_LOADER_READ_TIMEOUT_SECONDS": "90"}):
            client = ArtifactsModelClient("http://artifacts", "model")
        client.session = MagicMock()
        client.session.get.return_value.status_code = 404
        self.assertEqual(client.get_versions("content"), [])
        self.assertEqual(client.session.get.call_args.kwargs["timeout"], (5., 90.))
        client.session.get.return_value.close.assert_called_once()
        client.session.get.side_effect = requests.ConnectTimeout("unreachable")
        with self.assertRaises(requests.ConnectTimeout):
            client.download_model("content", 3, "/unused")
        self.assertEqual(client.session.get.call_args.kwargs["timeout"], (5., 90.))

    def test_stalled_download_times_out_closes_response_and_removes_partial_archive(self):
        release = threading.Event()

        class StalledDownload(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "1000")
                self.end_headers()
                self.wfile.flush()
                release.wait(10)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), StalledDownload)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        client = ArtifactsModelClient(f"http://127.0.0.1:{server.server_port}", "model")
        client.request_timeout = (1., .1)
        original_tempfile = tempfile.NamedTemporaryFile
        try:
            with tempfile.TemporaryDirectory() as scratch, tempfile.TemporaryDirectory() as output:
                with patch("bosca_storage.tempfile.NamedTemporaryFile", side_effect=lambda **kwargs: original_tempfile(dir=scratch, **kwargs)):
                    with patch.object(requests.Response, "close", autospec=True, side_effect=requests.Response.close) as close:
                        with self.assertRaises(requests.RequestException):
                            client.download_model("content", 3, output)
                        close.assert_called_once()
                self.assertEqual([], os.listdir(scratch))
                self.assertEqual([], os.listdir(output))
        finally:
            release.set()
            client.session.close()
            server.shutdown()
            server.server_close()
            thread.join(5)


if __name__ == "__main__":
    unittest.main()
