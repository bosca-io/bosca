"""Immutable model loading: exact artifacts, init failure, and command-line execution."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from unittest.mock import MagicMock

import pytest

from model_loader import load_fixed_models


def test_fixed_models_load_exact_versions_and_reuse_complete_downloads(tmp_path):
    client = MagicMock()

    def download(name, version, directory):
        output = Path(directory) / str(version)
        output.mkdir(parents=True)
        (output / "saved_model.pb").write_bytes(b"model")

    client.download_model.side_effect = download
    models = {"recommender-content": 3, "recommender-personalized": 3}
    load_fixed_models(client, str(tmp_path), models)
    load_fixed_models(client, str(tmp_path), models)
    assert client.download_model.call_count == 2
    assert [call.args[1] for call in client.download_model.call_args_list] == [3, 3]
    client.get_versions.assert_not_called()


@pytest.mark.parametrize("models", [None, [], {}, {"../escape": 3}, {"a/b": 3},
                                      {"a": True}, {"a": 0}, {"a": -1}, {"a": 1.5}, {"a": "3"}])
def test_invalid_fixed_selection_fails_before_downloading(tmp_path, models):
    client = MagicMock()
    with pytest.raises(ValueError):
        load_fixed_models(client, str(tmp_path), models)
    client.download_model.assert_not_called()


def test_fixed_download_failure_propagates_and_never_falls_back(tmp_path):
    client = MagicMock()
    client.download_model.side_effect = RuntimeError("artifact missing")
    with pytest.raises(RuntimeError, match="artifact missing"):
        load_fixed_models(client, str(tmp_path), {"content": 3})
    client.get_versions.assert_not_called()


def test_incomplete_fixed_download_fails(tmp_path):
    with pytest.raises(RuntimeError, match="missing saved_model.pb"):
        load_fixed_models(MagicMock(), str(tmp_path), {"content": 3})


def test_fixed_cli_downloads_both_exports_over_http_and_exits(tmp_path):
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w:gz") as tar:
        info = tarfile.TarInfo("3/saved_model.pb")
        info.size = 5
        tar.addfile(info, io.BytesIO(b"model"))
    paths = []

    class Artifacts(BaseHTTPRequestHandler):
        def do_GET(self):
            paths.append(self.path)
            if self.path not in ["/ml/model/content/3", "/ml/model/personalized/3"]:
                self.send_error(404)
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(archive.getvalue())

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Artifacts)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("model_loader.py")),
             "--artifacts-url", f"http://127.0.0.1:{server.server_port}"],
            env={**os.environ, "MODEL_VERSIONS_JSON": json.dumps({"content": 3, "personalized": 3}),
                 "MODELS_ROOT": str(tmp_path), "BOSCA_URL": "http://unreachable.invalid"},
            capture_output=True, text=True, timeout=10,
        )
        assert result.returncode == 0, result.stderr
        assert paths == ["/ml/model/content/3", "/ml/model/personalized/3"]
        assert (tmp_path / "content/3/saved_model.pb").read_bytes() == b"model"
        assert (tmp_path / "personalized/3/saved_model.pb").read_bytes() == b"model"
    finally:
        server.shutdown()
        thread.join(timeout=5)
        server.server_close()
