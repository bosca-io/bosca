"""Reads the personalized-model versions currently selected by Bosca."""

import logging
import re
from typing import Optional

import requests

log = logging.getLogger(__name__)

_MODEL_SELECTION_QUERY = """
query RecommendationModelLoaderSelection {
  recommendation {
    modelSelection {
      personalizedVersions
    }
  }
}
"""


def _positive_version(value) -> Optional[int]:
    """Returns a positive integer version without accepting booleans or fractional values."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, float) and value.is_integer() and value > 0:
        return int(value)
    return None


class BoscaModelSelectionClient:
    """Reads selected model versions under the caller's artifact pull permissions."""

    def __init__(self, bosca_url: str, api_token: Optional[str] = None, timeout_seconds: float = 10.0):
        self.graphql_url = f"{bosca_url.rstrip('/')}/graphql"
        self.timeout_seconds = timeout_seconds
        self.session = requests.Session()
        if api_token:
            self.session.headers["Authorization"] = f"Bearer {api_token}"

    def get_selected_personalized_versions(self) -> set[int]:
        """Returns manually pinned and actively experimented personalized-model versions.

        The newest artifact is not included here; it is used only when no selected version is available. A disabled
        or dormant experiment does not select its variations. GraphQL/authentication failures are raised so the
        loader can preserve its last-known-good selection instead of unloading a pinned version.
        """
        response = self.session.post(
            self.graphql_url,
            json={"query": _MODEL_SELECTION_QUERY},
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        body = response.json()
        if body.get("errors"):
            raise RuntimeError(f"Bosca GraphQL error: {body['errors']}")
        data = body.get("data")
        if not isinstance(data, dict):
            raise RuntimeError("Bosca GraphQL response did not contain data")

        values = data["recommendation"]["modelSelection"]["personalizedVersions"]
        if not isinstance(values, list):
            raise RuntimeError("Bosca model selection did not contain a version list")
        selected: set[int] = set()
        for value in values:
            version = _positive_version(value)
            if version is None:
                raise RuntimeError("Bosca model selection contained an invalid version")
            selected.add(version)

        log.debug("Global personalized-model pins/experiments (context models queried separately): %s", sorted(selected))
        return selected

    def get_context_versions(self) -> dict[str, set[int]]:
        """Select one generation per context, loading pending exports before returning to the active model."""
        response = self.session.post(
            self.graphql_url,
            json={"query": """
                query ContextModelLoaderSelection {
                  recommendation { contexts {
                    all { id activeModelVersion requestedModelVersion }
                    servingModels {
                      contextId version status contentModelName personalizedModelName personalized
                    }
                  } }
                }
            """},
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        body = response.json()
        if body.get("errors"):
            raise RuntimeError(f"Bosca GraphQL error: {body['errors']}")
        contexts = body["data"]["recommendation"]["contexts"]
        models = contexts["servingModels"]
        chosen = []
        for context in contexts["all"]:
            candidates = [model for model in models if model["contextId"] == context["id"]]
            requested = [model for model in candidates if model["version"] == context["requestedModelVersion"]]
            pending = [model for model in candidates if model["status"] == "RUNNING"]
            active = [model for model in candidates if model["version"] == context["activeModelVersion"]]
            # Drain exports one at a time so every activation job can validate its exact version.
            # An explicit rollback wins even when its version number is lower than the active model.
            eligible = requested or pending or active
            if eligible:
                chosen.append(min(eligible, key=lambda model: model["version"]))
        selected = {}
        for model in chosen:
            version = _positive_version(model["version"])
            if version is None:
                raise ValueError("Context model version must be positive")
            names = [model["contentModelName"]]
            if model["personalized"]:
                names.append(model["personalizedModelName"])
            for name in names:
                if not re.fullmatch(r"recommender-[a-f0-9-]{36}-(content|personalized)", name):
                    raise ValueError("Invalid context model name")
                selected.setdefault(name, set()).add(version)
        log.info("Bosca context models selected for serving: %s", {name: sorted(versions) for name, versions in selected.items()})
        return selected

    def get_retained_context_versions(self) -> dict[str, set[int]]:
        """Read the existing history policy: active/pinned versions and five previous successes.

        In-flight exports remain protected too. Discovery errors propagate so cleanup never treats
        a partial response as permission to remove local files.
        """
        def query(document, variables=None):
            response = self.session.post(self.graphql_url,
                                         json={"query": document, "variables": variables or {}},
                                         timeout=self.timeout_seconds)
            response.raise_for_status()
            body = response.json()
            if body.get("errors"):
                raise RuntimeError(f"Bosca GraphQL error: {body['errors']}")
            return body["data"]["recommendation"]["contexts"]

        contexts = query("query { recommendation { contexts { all { id } } } }")["all"]
        retained = {}
        for context in contexts:
            models = query("""query RetainedModels($id: UUID!) {
                recommendation { contexts { models(contextId: $id) {
                    version contentModelName personalizedModelName personalized
                } } }
            }""", {"id": context["id"]})["models"]
            for model in models:
                version = _positive_version(model["version"])
                if version is None:
                    raise ValueError("Context model version must be positive")
                names = [model["contentModelName"]]
                if model["personalized"]:
                    names.append(model["personalizedModelName"])
                for name in names:
                    if not re.fullmatch(r"recommender-[a-f0-9-]{36}-(content|personalized)", name):
                        raise ValueError("Invalid context model name")
                    retained.setdefault(name, set()).add(version)
        return retained
