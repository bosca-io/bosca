"""Content (cold) model construction path.

Builds an item representation from independently normalized content signals and exports it as an
item->item ``similar`` index. This is the always-on base of the recommender: it answers "given this item,
what content is similar," which powers similar/related and — by seeding it at serve time with a user's
recent items — the content base of a personalized feed.

It is **item-keyed only**: no user tower, no ``serving_default(user)``, no ``rank``. Personalization comes
from *which items the serving layer seeds it with*, not from a learned user embedding here.
"""

import logging
import os

from bosca_storage import CONTENT_MODEL_NAME, ArtifactsModelClient
from trainer.context_weights import context_weights, normalized_similarity
from trainer.export import export_content_model

log = logging.getLogger(__name__)


def run_content_only_training(content, categories, feedback, signals, config: dict) -> dict:
    """Builds the content model's weighted feature vectors and item->item ``similar`` index, then uploads
    it to Bosca. Item-keyed only, so ``feedback`` and ``signals`` are accepted for call-signature parity
    with the interaction path but unused (the content model has no user concept). Never gated on interactions.
    """
    model_dir = config.get("model_dir", "/models/recommender-content")
    model_name = config.get("content_model_name", CONTENT_MODEL_NAME)
    top_k = config.get("top_k", 50)
    preserve_serving_facets = config.get("preserve_serving_facets", True)
    retired_facets = {
        (str(facet["context_type"]), str(facet["language_tag"]))
        for facet in config.get("retired_serving_facets", [])
    }
    artifacts_url = config.get("artifacts_url")
    artifacts_token = config.get("artifacts_token")
    artifacts_pull_token = config.get("artifacts_pull_token")
    artifacts_namespace = config.get("artifacts_namespace", "model")

    if len(content) == 0:
        raise ValueError("content model construction requires at least one content item")

    artifacts_client = None
    if artifacts_url:
        artifacts_client = ArtifactsModelClient(
            artifacts_url,
            artifacts_namespace,
            artifacts_token,
            pull_api_token=artifacts_pull_token,
        )
        artifacts_client.hydrate_latest_model(model_name, model_dir)

    weights = context_weights(config.get("weights"))
    log.info("Building exact sparse content similarity over %d items: %s", len(content), weights)

    export_result = export_content_model(
        content,
        categories,
        model_dir,
        top_k,
        preserve_serving_facets=preserve_serving_facets,
        retired_facets=retired_facets,
        weights=weights,
        model_version=config.get("model_version"),
    )

    if not export_result["promoted"]:
        return {
            "status": "rejected",
            "reason": "the new content model is missing facets served by the current model",
            "missing_facets": export_result["missing_facets"],
        }

    result = {
        "status": "completed_content_only",
        "model_version": export_result["version"],
        "content_items": content["content_id"].nunique(),
        "similarity_weights": normalized_similarity(weights),
        "validation": export_result.get("validation"),
    }

    if artifacts_client:
        version_dir = os.path.join(model_dir, str(export_result["version"]))
        upload_result = artifacts_client.upload_model(
            version_dir, export_result["version"], model_name=model_name,
        )
        result["artifact_version"] = upload_result["version"]
        result["artifact_size"] = upload_result["size_bytes"]
        log.info("Content model pushed to artifacts: %s@%d", CONTENT_MODEL_NAME, export_result["version"])

    return result
