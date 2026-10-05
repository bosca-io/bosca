import json
import sys

import pytest

import train
from train import parse_configuration_json


def test_configuration_json_defaults_to_empty():
    assert parse_configuration_json(None) == {}
    assert parse_configuration_json("") == {}


def test_configuration_json_parses_override_object():
    assert parse_configuration_json('{"epochs": 3, "use_scann": true}') == {
        "epochs": 3,
        "use_scann": True,
    }


def test_configuration_json_rejects_non_object():
    with pytest.raises(ValueError, match="JSON object"):
        parse_configuration_json("[1, 2]")


def test_configuration_json_surfaces_invalid_json():
    with pytest.raises(json.JSONDecodeError):
        parse_configuration_json("{")


def test_train_command_applies_environment_configuration_after_static_arguments(monkeypatch):
    captured = {}
    monkeypatch.setenv("ML_TRAINER_CONFIGURATION_JSON", '{"epochs": 7}')
    monkeypatch.setenv("ARTIFACTS_PULL_API_TOKEN", "pull-token")
    monkeypatch.setattr(sys, "argv", ["train.py", "train", "--epochs", "3"])
    monkeypatch.setattr(
        train,
        "run_training",
        lambda configuration: captured.update(configuration) or {"status": "completed"},
    )

    train.main()

    assert captured["epochs"] == 7
    assert captured["artifacts_pull_token"] == "pull-token"


@pytest.mark.parametrize('command', ['train', 'serve'])
def test_small_catalog_learning_rate_default_reaches_both_entrypoints(monkeypatch, command):
    captured = {}
    monkeypatch.delenv('ML_TRAINER_CONFIGURATION_JSON', raising=False)
    monkeypatch.setattr(sys, 'argv', ['train.py', command])
    monkeypatch.setattr(train, 'run_training', lambda configuration: captured.update(configuration) or {'status': 'completed'})
    monkeypatch.setattr(train, 'run_server', lambda port, configuration: captured.update(configuration))
    train.main()
    assert captured['learning_rate'] == .01


# -- Background runs (serve mode) --

import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer


class _BlockingTraining:
    """A run_training stub that holds each run until the test releases it."""

    def __init__(self, result=None, error=None):
        self.started = threading.Event()
        self.release = threading.Event()
        self.configurations = []
        self.result = result if result is not None else {"status": "completed"}
        self.error = error

    def __call__(self, configuration):
        self.configurations.append(configuration)
        self.started.set()
        assert self.release.wait(10), "test never released the training run"
        if self.error is not None:
            raise self.error
        return self.result


def _wait_finished(service, run_id):
    for _ in range(200):
        run = service.get_run(run_id)
        if run and run["status"] == "finished":
            return run
        threading.Event().wait(0.01)
    raise AssertionError(f"run {run_id} did not finish")


def test_background_run_reports_running_then_its_result(monkeypatch):
    training = _BlockingTraining(result={"status": "completed", "interactions": 5})
    monkeypatch.setattr(train, "run_training", training)
    service = train.TrainerService({"epochs": 1})

    assert service.start_run("model-7", {"epochs": 3}) == (202, {"status": "started", "run_id": "model-7"})
    assert training.started.wait(5)
    assert service.get_run("model-7") == {"status": "running", "run_id": "model-7"}
    # Repeating the start is harmless; a different run waits for this one.
    assert service.start_run("model-7") == (200, {"status": "running", "run_id": "model-7"})
    code, conflict = service.start_run("model-8")
    assert code == 409 and conflict["status"] == "already_training" and conflict["run_id"] == "model-7"
    assert service.start_training() == {
        "status": "already_training",
        "message": "A training run is already in progress.",
    }
    assert service.get_status()["current_run_id"] == "model-7"

    training.release.set()
    finished = _wait_finished(service, "model-7")

    assert finished["result"] == {"status": "completed", "interactions": 5}
    assert training.configurations == [{"epochs": 3}]
    assert service.start_run("model-7")[1]["status"] == "finished"
    assert service.get_status() == {
        "is_training": False,
        "current_run_id": None,
        "last_result": {"status": "completed", "interactions": 5},
    }
    assert service.get_run("unknown") is None


def test_background_run_failure_is_recorded_and_releases_the_lock(monkeypatch):
    training = _BlockingTraining(error=RuntimeError("boom"))
    monkeypatch.setattr(train, "run_training", training)
    service = train.TrainerService({})

    service.start_run("model-9")
    training.release.set()

    assert _wait_finished(service, "model-9")["result"] == {"status": "failed", "message": "boom"}
    monkeypatch.setattr(train, "run_training", lambda configuration: {"status": "completed"})
    assert service.start_training() == {"status": "completed"}


def test_background_run_history_is_bounded(monkeypatch):
    monkeypatch.setattr(train, "run_training", lambda configuration: {"status": "completed"})
    service = train.TrainerService({})
    service.MAX_FINISHED_RUNS = 2
    for run_id in ("a", "b", "c"):
        service.start_run(run_id)
        _wait_finished(service, run_id)

    assert service.get_run("a") is None
    assert service.get_run("b")["status"] == "finished"
    assert service.get_run("c")["status"] == "finished"


def _request(base, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def test_http_service_answers_health_and_polls_while_a_run_trains(monkeypatch):
    training = _BlockingTraining(result={"status": "no_content"})
    monkeypatch.setattr(train, "run_training", training)
    service = train.TrainerService({})
    server = ThreadingHTTPServer(("127.0.0.1", 0), train.make_handler(service))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        assert _request(base, "POST", "/runs/recommendation-model-7", {"epochs": 2}) == (
            202,
            {"status": "started", "run_id": "recommendation-model-7"},
        )
        assert training.started.wait(5)
        # A single-threaded server would block these until training finished.
        assert _request(base, "GET", "/health") == (200, {"status": "ok"})
        assert _request(base, "GET", "/runs/recommendation-model-7") == (
            200,
            {"status": "running", "run_id": "recommendation-model-7"},
        )
        assert _request(base, "GET", "/runs/other")[0] == 404
        assert _request(base, "POST", "/runs/bad%20id")[0] == 400
        assert _request(base, "POST", "/elsewhere")[0] == 404
        assert _request(base, "GET", "/elsewhere")[0] == 404

        training.release.set()
        _wait_finished(service, "recommendation-model-7")
        assert _request(base, "GET", "/runs/recommendation-model-7") == (
            200,
            {"status": "finished", "run_id": "recommendation-model-7", "result": {"status": "no_content"}},
        )
        assert training.configurations == [{"epochs": 2}]
        assert _request(base, "GET", "/status")[1]["current_run_id"] is None
    finally:
        server.shutdown()
        server.server_close()


def test_http_service_keeps_the_synchronous_train_endpoint(monkeypatch):
    monkeypatch.setattr(train, "run_training", lambda configuration: {"status": "completed", "config": configuration})
    service = train.TrainerService({"epochs": 1})
    server = ThreadingHTTPServer(("127.0.0.1", 0), train.make_handler(service))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        assert _request(base, "POST", "/train", {"epochs": 4}) == (
            200,
            {"status": "completed", "config": {"epochs": 4}},
        )
        # A non-object body is ignored rather than failing the run.
        assert _request(base, "POST", "/train", [1, 2]) == (200, {"status": "completed", "config": {"epochs": 1}})
    finally:
        server.shutdown()
        server.server_close()
