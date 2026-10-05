"""Exercise real serving scripts with a small process standing in for TensorFlow Serving."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import pytest

from model_loader import set_served_versions

SCRIPTS = ["helm/tf-serving/files/serve.sh", "web/services/tf-serving/serve.sh",
           "server/services/tf-serving/serve.sh", "infra/services/tf-serving/serve.sh"]


def wait_for(check, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.02)
    raise AssertionError("Serving process did not reach expected state")


@pytest.mark.parametrize("path", SCRIPTS)
def test_replacements_stop_previous_process_and_support_rollback(tmp_path, path):
    script = Path(__file__).resolve().parents[3] / path
    if not script.exists():
        pytest.skip("Composition scripts are not in the standalone loader repository")
    root = tmp_path / "models"
    model = root / "recommender-content"
    for version in (3, 9):
        (model / str(version)).mkdir(parents=True)
    set_served_versions(str(model), {3})
    binary = tmp_path / "tensorflow_model_server"
    binary.write_text(f"#!{sys.executable}\n" + '''import json, os, pathlib, signal, sys, time
root = pathlib.Path(os.environ["TEST_ROOT"])
lock = root / "resident"
with lock.open("x") as stream:
    stream.write(str(os.getpid()))
def event(kind):
    with (root / "events").open("a") as stream:
        stream.write(json.dumps({"event": kind, "pid": os.getpid(), "config": pathlib.Path(os.environ["CONFIG_PATH"]).read_text(), "args": sys.argv[1:]}) + "\\n")
def stop(*_):
    time.sleep(.05)
    event("stop")
    lock.unlink()
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
event("start")
while True:
    time.sleep(.02)
''')
    binary.chmod(0o755)
    events_file = tmp_path / "events"
    def events():
        return [json.loads(line) for line in events_file.read_text().splitlines()] if events_file.exists() else []
    env = {**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}", "TEST_ROOT": str(tmp_path),
           "MODELS_ROOT": str(root), "CONFIG_PATH": str(tmp_path / "models.config"),
           "MODEL_NAMES": "recommender-content", "POLL_WAIT_SECONDS": ".1"}
    process = subprocess.Popen(["bash", str(script)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        wait_for(lambda: len(events()) == 1)
        assert "versions: 3" in events()[0]["config"]
        time.sleep(.25)
        assert len(events()) == 1  # Stable selections do not restart serving.
        set_served_versions(str(model), {3, 9})
        wait_for(lambda: len(events()) == 3)
        assert [event["event"] for event in events()] == ["start", "stop", "start"]
        assert "versions: 9" in events()[-1]["config"]
        assert "versions: 3" not in events()[-1]["config"]
        set_served_versions(str(model), {3})
        wait_for(lambda: len(events()) == 5)
        assert "versions: 3" in events()[-1]["config"]
        for event in events():
            assert "--model_config_file_poll_wait_seconds=0" in event["args"]
            assert "--file_system_poll_wait_seconds=0" in event["args"]
        process.terminate()
        process.communicate(timeout=5)
        assert process.returncode == 0
        assert not (tmp_path / "resident").exists()
    finally:
        if process.poll() is None:
            process.terminate()
            process.communicate(timeout=5)


@pytest.mark.parametrize("path", SCRIPTS)
def test_global_newest_version_changes_config_without_a_manifest(tmp_path, path):
    script = Path(__file__).resolve().parents[3] / path
    if not script.exists():
        pytest.skip("Composition scripts are not in the standalone loader repository")
    root = tmp_path / "models"
    model = root / "recommender-content"
    (model / "3").mkdir(parents=True)
    config = tmp_path / "models.config"
    source = script.read_text().split("\ngenerate_config\n", 1)[0] + "\ngenerate_config\n"
    env = {**os.environ, "MODELS_ROOT": str(root), "CONFIG_PATH": str(config)}
    subprocess.run(["bash", "-c", source], env=env, check=True)
    assert "versions: 3" in config.read_text()
    (model / "9").mkdir()
    (model / "10-incomplete").mkdir()
    subprocess.run(["bash", "-c", source], env=env, check=True)
    assert "versions: 9" in config.read_text()
    assert "versions: 3" not in config.read_text()


@pytest.mark.parametrize("path", SCRIPTS)
def test_unexpected_server_exit_is_reported_to_the_container_runtime(tmp_path, path):
    script = Path(__file__).resolve().parents[3] / path
    if not script.exists():
        pytest.skip("Composition scripts are not in the standalone loader repository")
    binary = tmp_path / "tensorflow_model_server"
    binary.write_text("#!/bin/sh\nprintf 'server-started\\n'\nexit 7\n")
    binary.chmod(0o755)
    result = subprocess.run(["bash", str(script)], env={**os.environ,
        "PATH": f"{tmp_path}:{os.environ['PATH']}", "MODELS_ROOT": str(tmp_path / "models"),
        "CONFIG_PATH": str(tmp_path / "models.config"), "POLL_WAIT_SECONDS": ".1"},
        capture_output=True, text=True, timeout=5)
    assert result.returncode == 7
    assert result.stdout.count("server-started") == 1
