#!/usr/bin/env python3
"""
Model loader for TensorFlow Serving.

Periodically checks the Bosca artifacts server for new versions of the recommender models and downloads
them to TF Serving's local model directories. Runs as a sidecar process alongside the TF Serving container,
replacing the need for a shared Docker volume.

The trainer pushes **two** independent models, each its own repository (by name) under the ML artifacts
namespace (custom `ml` artifact type):
  - recommender-content       — the content (cold) base
  - recommender-personalized  — the two-tower personalized model

Each is synced into its own model dir; TF Serving's models.config points a served model at each dir and
loads one version per model. The loader writes an exact selection manifest; an explicit selection takes
precedence over the newest artifact. Context generations awaiting activation are loaded one at a time.

Usage:
    python model_loader.py \
        --artifacts-url http://bosca-artifacts-server:8084 \
        --artifacts-namespace model \
        --bosca-url http://bosca-server:8080 \
        --content-model-dir /models/recommender-content \
        --personalized-model-dir /models/recommender-personalized \
        --poll-interval 60
"""

import argparse
import logging
import os
import re
import signal
import shutil
import tempfile
import time
from dataclasses import dataclass
from typing import AbstractSet, Optional

from bosca_storage import (
    CONTENT_MODEL_NAME,
    PERSONALIZED_MODEL_NAME,
    ArtifactsModelClient,
)
from bosca_selection import BoscaModelSelectionClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

# In-container defaults: `/models` is the writable volume mount in the tf-serving pod / compose service.
# When running the loader FROM SOURCE (e.g. IntelliJ) these are not writable — override them via the
# CONTENT_MODEL_DIR / PERSONALIZED_MODEL_DIR env vars (or the CLI flags) to a project-local path.
DEFAULT_CONTENT_MODEL_DIR = "/models/recommender-content"
DEFAULT_PERSONALIZED_MODEL_DIR = "/models/recommender-personalized"
SERVED_VERSIONS_FILE = ".served-versions"


@dataclass(frozen=True)
class ModelSpec:
    """One synced model: its repository name in the artifacts server (also its display name) and the local
    directory TF Serving reads it from."""

    name: str
    model_dir: str


def get_local_versions(model_dir: str) -> set[int]:
    """Returns the set of model versions currently on disk."""
    if not os.path.exists(model_dir):
        return set()
    return {
        int(d) for d in os.listdir(model_dir)
        if d.isdigit() and os.path.isdir(os.path.join(model_dir, d))
    }


def get_served_versions(model_dir: str) -> set[int]:
    """Reads the versions TF Serving should load from the loader-owned selection file."""
    path = os.path.join(model_dir, SERVED_VERSIONS_FILE)
    if not os.path.isfile(path):
        return set()
    with open(path) as file:
        return {
            int(line.strip())
            for line in file
            if line.strip().isdigit() and int(line.strip()) > 0
        }


def set_served_versions(model_dir: str, versions: set[int]) -> bool:
    """Atomically publishes a changed TF Serving version selection."""
    if get_served_versions(model_dir) == versions:
        return False
    os.makedirs(model_dir, exist_ok=True)
    fd, temporary_path = tempfile.mkstemp(prefix=".served-versions-", dir=model_dir, text=True)
    try:
        with os.fdopen(fd, "w") as file:
            for version in sorted(versions):
                file.write(f"{version}\n")
        os.replace(temporary_path, os.path.join(model_dir, SERVED_VERSIONS_FILE))
    except Exception:
        if os.path.exists(temporary_path):
            os.unlink(temporary_path)
        raise
    return True


def sync_model(
    client: ArtifactsModelClient,
    spec: ModelSpec,
    selected_versions: Optional[AbstractSet[int]] = frozenset(),
    include_latest: bool = True,
) -> bool:
    """
    Makes one explicitly selected version, or the newest artifact, available to TF Serving.

    ``selected_versions=None`` means Bosca selection discovery was temporarily unavailable; in that case
    the last-known selection is retained. Returns True when a model
    was downloaded or the served selection changed. Errors are logged and isolated to this model.
    """
    try:
        available_versions = client.get_versions(spec.name)
        requested = get_served_versions(spec.model_dir) if selected_versions is None else set(selected_versions)
        unavailable = requested - set(available_versions)
        if unavailable:
            log.warning(
                "Selected %s versions are not available in artifacts and cannot be loaded: %s",
                spec.name,
                sorted(unavailable),
            )
        if not available_versions:
            if not requested:
                log.debug("No published versions for unselected model %s", spec.name)
            return False
        latest = available_versions[0]
        selected = requested & set(available_versions)
        desired = {max(selected)} if selected else ({latest} if include_latest else set())
        if len(selected) > 1:
            log.warning("Only one %s version can be served; selected %s from requested versions %s",
                        spec.name, sorted(desired), sorted(selected))
        local = get_local_versions(spec.model_dir)
        downloaded = []
        for version in sorted(desired, reverse=True):
            if version in local:
                continue
            client.download_model(spec.name, version, spec.model_dir)
            downloaded.append(version)
        local = get_local_versions(spec.model_dir)
        if not desired <= local:
            raise RuntimeError(f"Selected {spec.name} versions {sorted(desired - local)} are not available on disk after sync")
        loadable = desired & local
        selection_changed = set_served_versions(spec.model_dir, loadable)
        if downloaded:
            log.info("Downloaded %s versions %s into %s", spec.name, downloaded, spec.model_dir)
        if selection_changed:
            log.info("Selected %s versions for serving: %s", spec.name, sorted(loadable))
        return bool(downloaded) or selection_changed
    except Exception as e:
        log.error("Failed to sync %s into %s: %s", spec.name, spec.model_dir, e)
        return False


def run_poll_loop(
    artifacts_url: str,
    namespace: str,
    api_token: Optional[str],
    specs: list[ModelSpec],
    poll_interval: int,
    bosca_url: Optional[str] = None,
    bosca_api_token: Optional[str] = None,
):
    """Polls artifacts and reconciles TF Serving to one version per model."""
    client = ArtifactsModelClient(artifacts_url, namespace, api_token)
    # Discovery uses the same artifact pull permissions as model downloads.
    selection_client = BoscaModelSelectionClient(bosca_url, api_token or bosca_api_token) if bosca_url else None
    running = True

    def handle_signal(signum, frame):
        nonlocal running
        log.info("Received signal %d, shutting down", signum)
        running = False

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    for spec in specs:
        try:
            os.makedirs(spec.model_dir, exist_ok=True)
        except OSError as e:
            # A read-only / unwritable path almost always means the loader is running from source but still
            # on the in-container defaults. Fail with a clear, actionable message rather than a raw traceback.
            raise RuntimeError(
                f"Cannot create the {spec.name} model directory {spec.model_dir!r}: {e}. "
                "Point CONTENT_MODEL_DIR / PERSONALIZED_MODEL_DIR (or --content-model-dir / "
                "--personalized-model-dir) at a writable path. The defaults "
                f"({DEFAULT_CONTENT_MODEL_DIR} / {DEFAULT_PERSONALIZED_MODEL_DIR}) are the in-container "
                "volume mount and are not writable when running the loader from source."
            ) from e
    log.info("Model loader started. Polling %s every %ds", artifacts_url, poll_interval)
    for spec in specs:
        log.info("Syncing %s -> %s", spec.name, spec.model_dir)

    def sync_all() -> None:
        selected_personalized: Optional[set[int]] = None
        if selection_client is not None:
            try:
                selected_personalized = selection_client.get_selected_personalized_versions()
            except Exception as e:
                log.error("Failed to refresh Bosca model selection; retaining last-known selection: %s", e)
        for spec in specs:
            selected = selected_personalized if spec.name == PERSONALIZED_MODEL_NAME else set()
            try:
                sync_model(client, spec, selected)
            except Exception as e:
                log.error("Poll cycle failed for %s: %s", spec.name, e)
        if selection_client is not None and specs:
            sync_context_models(client, selection_client, os.path.dirname(specs[0].model_dir))

    sync_all()

    while running:
        time.sleep(poll_interval)
        if not running:
            break
        sync_all()

    log.info("Model loader stopped.")


def sync_context_models(client, selection_client, root):
    """Reconcile exact context selections, retaining disk selections on discovery failure."""
    known = {
        name for name in os.listdir(root)
        if re.fullmatch(r"recommender-[a-f0-9-]{36}-(content|personalized)", name)
        and os.path.isdir(os.path.join(root, name))
    }
    try:
        selected = selection_client.get_context_versions()
    except Exception:
        log.exception("Context selection unavailable; retaining loaded context versions")
        selected = {name: get_served_versions(os.path.join(root, name)) for name in known}
    for name in sorted(known | set(selected)):
        versions = selected.get(name, set())
        directory = os.path.join(root, name)
        if versions:
            sync_model(client, ModelSpec(name, directory), versions, include_latest=False)
        else:
            set_served_versions(directory, set())

    try:
        retained = selection_client.get_retained_context_versions()
    except Exception:
        log.exception("Context retention unavailable; keeping local model files")
        return
    for name in sorted(known | set(selected)):
        directory = os.path.join(root, name)
        # Never remove a version still selected for serving, even if selection changed during discovery.
        protected = set(retained.get(name, set())) | get_served_versions(directory)
        for version in get_local_versions(directory) - protected:
            path = os.path.join(directory, str(version))
            if os.path.islink(path):
                continue
            shutil.rmtree(path)
            log.info("Removed unretained local context model %s@%d", name, version)


def build_specs(args) -> list[ModelSpec]:
    """Builds the content + personalized model specs from CLI args / environment."""
    return [
        ModelSpec(name=CONTENT_MODEL_NAME, model_dir=args.content_model_dir),
        ModelSpec(name=PERSONALIZED_MODEL_NAME, model_dir=args.personalized_model_dir),
    ]


def main():
    parser = argparse.ArgumentParser(description="TF Serving model loader for the Bosca artifacts server")
    parser.add_argument(
        "--artifacts-url",
        default=os.environ.get("ARTIFACTS_URL", "http://bosca-artifacts-server:8084"),
    )
    parser.add_argument("--artifacts-namespace", default=os.environ.get("ARTIFACTS_NAMESPACE", "model"))
    parser.add_argument("--api-token", default=os.environ.get("ARTIFACTS_API_TOKEN"))
    parser.add_argument(
        "--content-model-dir",
        default=os.environ.get("CONTENT_MODEL_DIR", DEFAULT_CONTENT_MODEL_DIR),
    )
    parser.add_argument(
        "--personalized-model-dir",
        default=os.environ.get("PERSONALIZED_MODEL_DIR", DEFAULT_PERSONALIZED_MODEL_DIR),
    )
    parser.add_argument("--poll-interval", type=int, default=int(os.environ.get("POLL_INTERVAL", "60")))
    parser.add_argument("--bosca-url", default=os.environ.get("BOSCA_URL"))
    parser.add_argument("--bosca-api-token", default=os.environ.get("BOSCA_API_TOKEN"))
    args = parser.parse_args()

    run_poll_loop(
        artifacts_url=args.artifacts_url,
        namespace=args.artifacts_namespace,
        api_token=args.api_token,
        specs=build_specs(args),
        poll_interval=args.poll_interval,
        bosca_url=args.bosca_url,
        bosca_api_token=args.bosca_api_token,
    )


if __name__ == "__main__":  # pragma: no cover
    main()
