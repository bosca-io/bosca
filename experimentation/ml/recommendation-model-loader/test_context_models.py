"""Exact context selection and TF Serving configuration, using only local fixtures."""
import os
from pathlib import Path
import subprocess
from unittest.mock import MagicMock

import pytest

from bosca_selection import BoscaModelSelectionClient
from model_loader import sync_context_models, get_served_versions, set_served_versions

NAME = "recommender-11111111-1111-1111-1111-111111111111-content"


def test_context_loader_never_adds_unselected_latest_and_preserves_selection_on_error(tmp_path):
    artifacts = MagicMock()
    artifacts.get_versions.return_value = [9, 7, 3]
    artifacts.download_model.side_effect = lambda name, version, out: (Path(out) / str(version)).mkdir(parents=True)
    selection = MagicMock()
    selection.get_context_versions.return_value = {NAME: {3}}
    selection.get_retained_context_versions.return_value = {NAME: {3, 7}}
    sync_context_models(artifacts, selection, str(tmp_path))
    assert get_served_versions(str(tmp_path / NAME)) == {3}
    assert not (tmp_path / NAME / "9").exists()
    selection.get_context_versions.side_effect = RuntimeError("offline")
    sync_context_models(artifacts, selection, str(tmp_path))
    assert get_served_versions(str(tmp_path / NAME)) == {3}
    selection.get_context_versions.side_effect = None
    selection.get_context_versions.return_value = {}
    sync_context_models(artifacts, selection, str(tmp_path))
    assert get_served_versions(str(tmp_path / NAME)) == set()
    assert (tmp_path / NAME / "3").is_dir()


def test_context_selection_parses_matching_content_and_personalized_exports():
    client = BoscaModelSelectionClient("http://bosca.invalid")
    client.session = MagicMock()
    client.session.post.return_value.json.return_value = {"data": {"recommendation": {"contexts": {
        "all": [{"id": "context", "activeModelVersion": 3, "requestedModelVersion": None}],
        "servingModels": [
            {"contextId": "context", "status": "COMPLETED", "version": 3, "contentModelName": NAME,
             "personalizedModelName": NAME.replace("-content", "-personalized"), "personalized": True},
            {"contextId": "context", "status": "RUNNING", "version": 7, "contentModelName": NAME,
             "personalizedModelName": NAME.replace("-content", "-personalized"), "personalized": False},
        ],
    }}}}
    assert client.get_context_versions() == {NAME: {7}}
    client.session.post.return_value.json.return_value = {"errors": [{"message": "unavailable"}]}
    with pytest.raises(RuntimeError, match="unavailable"):
        client.get_context_versions()


@pytest.mark.parametrize("requested,statuses,expected", [
    (3, ["COMPLETED", "COMPLETED", "RUNNING"], 3),
    (None, ["RUNNING", "COMPLETED", "RUNNING"], 3),
    (None, ["COMPLETED", "COMPLETED", "RUNNING"], 9),
    (None, ["COMPLETED", "COMPLETED", "COMPLETED"], 7),
])
def test_context_selection_prioritizes_rollback_then_pending_then_active(requested, statuses, expected, caplog):
    client = BoscaModelSelectionClient("http://bosca.invalid")
    client.session = MagicMock()
    models = [{"contextId": "context", "status": status, "version": version,
               "contentModelName": NAME, "personalizedModelName": NAME.replace("-content", "-personalized"),
               "personalized": True} for version, status in zip([3, 7, 9], statuses)]
    client.session.post.return_value.json.return_value = {"data": {"recommendation": {"contexts": {
        "all": [{"id": "context", "activeModelVersion": 7, "requestedModelVersion": requested}],
        "servingModels": models,
    }}}}
    with caplog.at_level("INFO"):
        assert client.get_context_versions() == {NAME: {expected}, NAME.replace("-content", "-personalized"): {expected}}
    assert "context models selected for serving" in caplog.text
    assert str(expected) in caplog.text


@pytest.mark.parametrize("path", [
    "helm/tf-serving/files/serve.sh", "web/services/tf-serving/serve.sh",
    "server/services/tf-serving/serve.sh", "infra/services/tf-serving/serve.sh",
])
def test_serving_scripts_discover_only_selected_context_models(tmp_path, path):
    workspace = Path(__file__).resolve().parents[3]
    script = workspace / path
    if not script.exists():
        pytest.skip("Composition workspace scripts are not part of the standalone trainer repository")
    root = tmp_path / "models"
    directory = root / NAME
    (directory / "3").mkdir(parents=True)
    (directory / "9").mkdir()
    set_served_versions(str(directory), {3})
    unselected = root / NAME.replace("11111111-", "22222222-", 1)
    (unselected / "9").mkdir(parents=True)
    config = tmp_path / "models.config"
    # Execute only the real config generation function, never launch a server or the polling loop.
    source = script.read_text().split("\ngenerate_config\n", 1)[0] + "\ngenerate_config\n"
    result = subprocess.run(
        ["bash", "-c", source],
        env={**os.environ, "MODELS_ROOT": str(root), "CONFIG_PATH": str(config)},
        capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stderr
    generated = config.read_text()
    assert f'name: "{NAME}"' in generated
    assert "specific { versions: 3 }" in generated
    assert "versions: 9" not in generated
    assert unselected.name not in generated
    set_served_versions(str(directory), set())
    subprocess.run(["bash", "-c", source], env={**os.environ, "MODELS_ROOT": str(root), "CONFIG_PATH": str(config)}, check=True)
    assert NAME not in config.read_text()


def test_retention_removes_only_unselected_unretained_local_versions(tmp_path):
    directory = tmp_path / NAME
    for version in range(1, 10):
        (directory / str(version)).mkdir(parents=True)
    set_served_versions(str(directory), {1})
    artifacts = MagicMock()
    artifacts.get_versions.return_value = list(range(9, 0, -1))
    selection = MagicMock()
    selection.get_context_versions.return_value = {NAME: {1}}
    selection.get_retained_context_versions.return_value = {NAME: {2, 5, 6, 7, 8, 9}}
    sync_context_models(artifacts, selection, str(tmp_path))
    assert {int(p.name) for p in directory.iterdir() if p.is_dir()} == {1, 2, 5, 6, 7, 8, 9}
    assert get_served_versions(str(directory)) == {1}
    (directory / "3").mkdir()
    selection.get_retained_context_versions.side_effect = RuntimeError("offline")
    sync_context_models(artifacts, selection, str(tmp_path))
    assert (directory / "3").exists()


def test_retained_discovery_uses_existing_context_history_and_fails_closed():
    client = BoscaModelSelectionClient("http://bosca.invalid")
    client.session = MagicMock()
    model = {"version": 3, "contentModelName": NAME,
             "personalizedModelName": NAME.replace("-content", "-personalized"), "personalized": True}
    def response(contexts):
        result = MagicMock()
        result.json.return_value = {"data": {"recommendation": {"contexts": contexts}}}
        return result
    client.session.post.side_effect = [response({"all": [{"id": "context"}]}), response({"models": [model]})]
    assert client.get_retained_context_versions() == {NAME: {3}, NAME.replace("-content", "-personalized"): {3}}
    assert client.session.post.call_args.kwargs["json"]["variables"] == {"id": "context"}
    invalid = {**model, "contentModelName": "../outside"}
    client.session.post.side_effect = [response({"all": [{"id": "context"}]}), response({"models": [invalid]})]
    with pytest.raises(ValueError, match="Invalid context model name"):
        client.get_retained_context_versions()
