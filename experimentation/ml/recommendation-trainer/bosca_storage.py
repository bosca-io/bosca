"""Artifacts client for recommendation model lineage reads and writes.

Ephemeral trainer Jobs hydrate the latest registered model into their scratch directory before exporting a
new candidate. That registry-backed state supplies the next version number and the previous artifact's
served-facet manifest. A validated SavedModel is archived and pushed as a single-tar ``ml`` artifact.
"""

import logging
import os
import tarfile
import tempfile
from typing import Optional

import requests

log = logging.getLogger(__name__)

# Media type for an archived TensorFlow SavedModel directory.
MODEL_CONTENT_TYPE = "application/x-tar+gzip"

# The trainer produces two independent models, each pushed to its own repository (by name) under the ML
# artifacts namespace. The loader pulls each repository's newest version into its TF Serving model dir.
CONTENT_MODEL_NAME = "recommender-content"
PERSONALIZED_MODEL_NAME = "recommender-personalized"


class ArtifactsModelClient:
    """Reads and writes trained model tarballs through Bosca's custom ``ml`` artifact routes."""

    def __init__(
        self,
        artifacts_url: str,
        namespace: str,
        api_token: Optional[str] = None,
        pull_api_token: Optional[str] = None,
    ):
        """
        Args:
            artifacts_url: Base URL of the artifacts server (e.g. "http://bosca-artifacts-server:8084")
            namespace: The artifact namespace models live under (e.g. "model")
            api_token: Bearer token scoped to push ml artifacts (``artifacts:ml:{namespace}/*:*:push``)
            pull_api_token: Bearer token scoped to pull ml artifacts. Falls back to ``api_token`` for
                development tokens carrying both actions.
        """
        self.artifacts_url = artifacts_url.rstrip("/")
        self.namespace = namespace
        self.request_timeout = (
            float(os.environ.get("ML_TRAINER_CONNECT_TIMEOUT_SECONDS", "30")),
            float(os.environ.get("ML_TRAINER_READ_TIMEOUT_SECONDS", "600")),
        )
        self.session = requests.Session()
        if api_token:
            self.session.headers["Authorization"] = f"Bearer {api_token}"
        self.pull_session = requests.Session()
        read_token = pull_api_token or api_token
        if read_token:
            self.pull_session.headers["Authorization"] = f"Bearer {read_token}"

    def get_latest_version(self, model_name: str) -> Optional[int]:
        """Returns the highest numeric registry version for ``model_name``, or ``None`` before first push."""
        url = f"{self.artifacts_url}/ml/{self.namespace}/api/{model_name}"
        response = self.pull_session.get(url, timeout=self.request_timeout)
        if response.status_code == 404:
            return None
        response.raise_for_status()
        versions = response.json().get("versions", [])
        numeric = [int(v["version"]) for v in versions if str(v.get("version", "")).isdigit()]
        return max(numeric) if numeric else None

    def download_model(self, model_name: str, version: int, output_dir: str) -> str:
        """Downloads and extracts one SavedModel archive beneath ``output_dir``."""
        url = f"{self.artifacts_url}/ml/{self.namespace}/{model_name}/{version}"
        log.info("Downloading model artifact from %s", url)
        response = self.pull_session.get(url, stream=True, timeout=self.request_timeout)
        tmp_path = None
        try:
            response.raise_for_status()
            with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tmp:
                tmp_path = tmp.name
                for chunk in response.iter_content(chunk_size=8192):
                    tmp.write(chunk)
            os.makedirs(output_dir, exist_ok=True)
            with tarfile.open(tmp_path, "r:gz") as tar:
                tar.extractall(path=output_dir, filter="data")
            version_dir = os.path.join(output_dir, str(version))
            if not os.path.isdir(version_dir):
                raise ValueError(
                    f"Model archive {model_name}@{version} did not contain the expected {version!s}/ directory"
                )
            log.info("Hydrated model %s@%d into %s", model_name, version, output_dir)
            return version_dir
        finally:
            response.close()
            if tmp_path and os.path.exists(tmp_path):
                os.unlink(tmp_path)

    def hydrate_latest_model(self, model_name: str, output_dir: str) -> Optional[int]:
        """Hydrates the latest registry version unless it is already present locally."""
        latest = self.get_latest_version(model_name)
        if latest is None:
            log.info("No existing %s model in the artifacts registry to hydrate", model_name)
            return None
        version_dir = os.path.join(output_dir, str(latest))
        if not os.path.isdir(version_dir):
            self.download_model(model_name, latest, output_dir)
        return latest

    def upload_model(self, model_dir: str, version: int, model_name: str = CONTENT_MODEL_NAME) -> dict:
        """
        Archives a SavedModel version directory and pushes it as a single tar to the artifacts server at
        ``PUT /ml/{namespace}/api/{model_name}/{version}``.

        Args:
            model_dir: Path to the SavedModel version directory (e.g. /models/recommender-content/1)
            version: The model version number (used as the artifact version)
            model_name: The artifact repository name (e.g. "recommender-content")

        Returns:
            Dict with the model name, version, and archive size.
        """
        archive_path = self._archive_model(model_dir)
        archive_size = os.path.getsize(archive_path)
        log.info("Archived model to %s (%d bytes)", archive_path, archive_size)

        url = f"{self.artifacts_url}/ml/{self.namespace}/api/{model_name}/{version}"
        try:
            with open(archive_path, "rb") as f:
                response = self.session.put(url, data=f, headers={"Content-Type": MODEL_CONTENT_TYPE},
                                            timeout=self.request_timeout)
            response.raise_for_status()
            log.info("Pushed model %s version %d to %s", model_name, version, url)
            return {"name": model_name, "version": version, "size_bytes": archive_size}
        finally:
            os.unlink(archive_path)

    def _archive_model(self, model_dir: str) -> str:
        """Creates a tar.gz archive of a SavedModel version directory (top-level dir = the version)."""
        tmp = tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False)
        tmp.close()
        with tarfile.open(tmp.name, "w:gz") as tar:
            tar.add(model_dir, arcname=os.path.basename(model_dir))
        return tmp.name
