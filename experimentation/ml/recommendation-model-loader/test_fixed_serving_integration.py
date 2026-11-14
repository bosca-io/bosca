"""Opt-in real TF Serving isolation test; requires Docker and a TensorFlow export interpreter."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tarfile
from threading import Thread
import time

import pytest
import requests

from bosca_storage import ArtifactsModelClient
from model_loader import load_fixed_models


@pytest.mark.skipif(not os.environ.get("TF_SERVING_EXPORT_PYTHON"),
                    reason="Set TF_SERVING_EXPORT_PYTHON to an interpreter with TensorFlow and enable Docker")
def test_candidate_loading_and_missing_artifacts_leave_active_server_available(tmp_path):
    exports = tmp_path / "exports"
    subprocess.run([os.environ["TF_SERVING_EXPORT_PYTHON"], "-c", """
import sys
import tensorflow as tf
class Model(tf.Module):
    @tf.function(input_signature=[tf.TensorSpec([None], tf.float32, name="value")])
    def predict(self, value):
        return {"result": value + 1}
model = Model()
tf.saved_model.save(model, sys.argv[1], signatures={"serving_default": model.predict})
""", str(exports)], check=True, capture_output=True, text=True, timeout=60)
    for version in (3, 4):
        archive = tmp_path / "artifacts/ml/model/content" / str(version)
        archive.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(exports, arcname=str(version))

    class Artifacts(SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass

    artifacts = ThreadingHTTPServer(("127.0.0.1", 0), partial(Artifacts, directory=str(tmp_path / "artifacts")))
    thread = Thread(target=artifacts.serve_forever, daemon=True)
    thread.start()
    containers = []

    def docker(*args):
        return subprocess.check_output(["docker", *args], text=True, timeout=30).strip()

    def start(version):
        root = tmp_path / f"pod-{version}"
        root.mkdir()
        client = ArtifactsModelClient(f"http://127.0.0.1:{artifacts.server_port}", "model")
        load_fixed_models(client, str(root), {"content": version})
        config = root / "models.config"
        config.write_text('model_config_list { config { name: "content" base_path: "/models/content" '
                          'model_platform: "tensorflow" model_version_policy { specific { versions: '
                          + str(version) + ' } } } }')
        container = docker("run", "--detach", "--rm", "--platform", "linux/amd64",
                           "--publish", "127.0.0.1::8501", "--mount", f"type=bind,source={root},target=/models,readonly",
                           "--entrypoint", "tensorflow_model_server", "tensorflow/serving:2.16.1",
                           "--rest_api_port=8501", "--model_config_file=/models/models.config",
                           "--model_config_file_poll_wait_seconds=0", "--file_system_poll_wait_seconds=0")
        containers.append(container)
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["8501/tcp"][0]["HostPort"]
        return container, f"http://127.0.0.1:{port}", version

    def predict(server):
        response = requests.post(server[1] + f"/v1/models/content/versions/{server[2]}:predict",
                                 json={"instances": [{"value": 2.0}]}, timeout=5)
        response.raise_for_status()
        assert response.json()["predictions"] == [3.0]

    def ready(server, active=None):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if active:
                predict(active)
            try:
                response = requests.get(server[1] + f"/v1/models/content/versions/{server[2]}", timeout=2)
                if response.ok and response.json()["model_version_status"][0]["state"] == "AVAILABLE":
                    return
            except requests.ConnectionError:
                pass
            time.sleep(0.25)
        pytest.fail(docker("logs", server[0]))

    try:
        active = start(3)
        ready(active)
        predict(active)
        with pytest.raises(requests.HTTPError):
            start(5)
        predict(active)
        candidate = start(4)
        ready(candidate, active=active)
        predict(candidate)
        predict(active)
        assert len(containers) == 2
        assert json.loads(docker("inspect", active[0]))[0]["RestartCount"] == 0
    finally:
        for container in containers:
            subprocess.run(["docker", "rm", "--force", container], capture_output=True, timeout=30)
        artifacts.shutdown()
        thread.join(timeout=5)
        artifacts.server_close()
