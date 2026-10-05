"""Pull-only artifacts client for discovering and downloading served recommendation-model versions."""

import logging
import os
import tarfile
import tempfile
from typing import Optional

import requests

log = logging.getLogger(__name__)

# The trainer pushes two independent models, each its own repository (by name) under the ML artifacts
# namespace (see recommendation-trainer/bosca_storage.py). The loader syncs the newest and explicitly
# selected versions into each TF Serving model directory.
CONTENT_MODEL_NAME = "recommender-content"
PERSONALIZED_MODEL_NAME = "recommender-personalized"


class ArtifactsModelClient:
    """Pulls trained model tarballs from the Bosca artifacts server (the custom `ml` artifact type)."""

    def __init__(self, artifacts_url: str, namespace: str, api_token: Optional[str] = None):
        """
        Args:
            artifacts_url: Base URL of the artifacts server (e.g. "http://bosca-artifacts-server:8084")
            namespace: The artifact namespace models live under (e.g. "model")
            api_token: Bearer token scoped to pull ml artifacts (``artifacts:ml:{namespace}/*:*:pull``)
        """
        self.artifacts_url = artifacts_url.rstrip("/")
        self.namespace = namespace
        self.session = requests.Session()
        if api_token:
            self.session.headers["Authorization"] = f"Bearer {api_token}"

    def get_versions(self, model_name: str) -> list[int]:
        """Returns every numeric model version newest-first, or an empty list before the first push."""
        url = f"{self.artifacts_url}/ml/{self.namespace}/api/{model_name}"
        response = self.session.get(url)
        if response.status_code == 404:
            return []
        response.raise_for_status()
        versions = response.json().get("versions", [])
        numeric = [int(v["version"]) for v in versions if str(v.get("version", "")).isdigit()]
        return sorted(set(numeric), reverse=True)

    def get_latest_version(self, model_name: str) -> Optional[int]:
        """Returns the highest numeric version of ``model_name``, or None before the first push."""
        versions = self.get_versions(model_name)
        return versions[0] if versions else None

    def download_model(self, model_name: str, version: int, output_dir: str) -> str:
        """
        Downloads the tar for ``model_name@version`` and extracts it into ``output_dir`` (whose contents
        become the TF Serving version directory).

        Returns:
            The output directory path.
        """
        url = f"{self.artifacts_url}/ml/{self.namespace}/{model_name}/{version}"
        log.info("Downloading model from %s", url)

        response = self.session.get(url, stream=True)
        response.raise_for_status()

        with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tmp:
            for chunk in response.iter_content(chunk_size=8192):
                tmp.write(chunk)
            tmp_path = tmp.name

        try:
            os.makedirs(output_dir, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix=f".download-{version}-", dir=output_dir) as staging_dir:
                with tarfile.open(tmp_path, "r:gz") as tar:
                    tar.extractall(path=staging_dir, filter="data")
                staged_version_dir = os.path.join(staging_dir, str(version))
                if not os.path.isfile(os.path.join(staged_version_dir, "saved_model.pb")):
                    raise ValueError(
                        f"Model archive {model_name}@{version} does not contain {version}/saved_model.pb"
                    )
                os.replace(staged_version_dir, os.path.join(output_dir, str(version)))
            log.info("Extracted model %s@%d to %s", model_name, version, output_dir)
            return output_dir
        finally:
            response.close()
            os.unlink(tmp_path)
