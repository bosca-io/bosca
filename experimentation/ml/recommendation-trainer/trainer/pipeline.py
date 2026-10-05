"""``run_training`` orchestration.

Produces **two** separate model artifacts each run:
  - ``recommender-content`` — the content (cold) base. Built ALWAYS (needs no interactions), so newly
    added content is always recommendable. Owns item->item ``similar`` and content-based user retrieval.
  - ``recommender-personalized`` — the two-tower personalized model. Trained only when there are enough
    interactions to learn one. Its ``serving_default`` returns EMPTY for users it never saw, so the serving
    layer can tell a real personalized result from a cold user (and fall back to the content model).

They are separate artifacts so the serving layer can query and cache them independently. Content similarity
uses exact sparse categorical scoring and dense semantic embeddings. ``use_scann`` accelerates the learned
personalized indexes; it does not change the content model's exact scoring.
"""

import logging
import os
from datetime import datetime, timezone
from time import perf_counter

import pandas as pd
import requests
import tensorflow as tf

from bosca_storage import PERSONALIZED_MODEL_NAME, ArtifactsModelClient
from trainer.content_only import run_content_only_training
from trainer.content_similarity import ContentSimilarityIndex
from trainer.data import load_data_from_bosca, load_behavior_from_bosca
from trainer.behavior import BehaviorFeatures, capture_snapshot, read_snapshot
from trainer.datasets import extract_language_tags, extract_recommendation_contexts, prepare_datasets
from trainer.export import PERSONALIZED_MODEL_GENERATION, export_model
from trainer.training import train_model
from trainer.contexts import apply_context, configure_context, report_export

log = logging.getLogger(__name__)


def _training_data_cutoff(interactions: pd.DataFrame) -> str | None:
    """Returns the newest interaction timestamp included in the training snapshot, when available."""
    if interactions.empty or "interaction_created" not in interactions.columns:
        return None
    timestamps = pd.to_datetime(interactions["interaction_created"], errors="coerce", utc=True).dropna()
    if timestamps.empty:
        return None
    return timestamps.max().isoformat().replace("+00:00", "Z")


def _filter_interactions_to_content(interactions: pd.DataFrame, content: pd.DataFrame) -> pd.DataFrame:
    """Keep eligible events, all page boundaries, and contentless depth for visit attribution."""
    eligible_content_ids = set(content["content_id"].astype(str))
    element_type = interactions.get("element_type", pd.Series(index=interactions.index, dtype=object))
    missing_content = interactions["content_id"].fillna("").astype(str).str.strip().eq("")
    contextual = element_type.eq("page") | (missing_content & element_type.isin(["scroll_depth", "scroll_max_depth"]))
    return interactions[interactions["content_id"].astype(str).isin(eligible_content_ids) | contextual].reset_index(drop=True)


def _load_previous_model(model_name, previous_version, model_dir, artifacts_client):
    """Load optional prior behavioral inputs; only an absent artifact permits starting without them."""
    if previous_version is None:
        return None, None
    previous_directory = os.path.join(model_dir, str(previous_version))
    if not os.path.exists(previous_directory):
        if artifacts_client is None:
            log.warning(
                "Previous personalized model %s@%s is absent locally; continuing without prior "
                "behavioral training features or model-derived neighbor recommendations",
                model_name, previous_version,
            )
            return None, None
        try:
            artifacts_client.download_model(model_name, previous_version, model_dir)
        except requests.HTTPError as error:
            if error.response is None or error.response.status_code != 404:
                raise
            log.warning(
                "Previous personalized model %s@%s was not found (HTTP 404); continuing without prior "
                "behavioral training features or model-derived neighbor recommendations. "
                "Current interactions, feedback, and analytics will still be used",
                model_name, previous_version,
            )
            return None, None
    log.info("Loading previous personalized model %s@%s for behavioral inputs", model_name, previous_version)
    return tf.saved_model.load(previous_directory), read_snapshot(previous_directory)


def _train_personalized(users, interactions, content, categories, feedback, signals, config, model_dir, behavior_rows=None) -> dict:
    """Trains + exports the personalized two-tower model (retrieval + ranking) and pushes it to the artifacts server.

    Its ``serving_default`` is exported with ``oov_empty`` so a user the model never saw returns empty
    rather than junk. The exported artifact is contract-validated before upload and trains on the complete
    eligible user/interaction snapshot. Called only when interactions are sufficient.
    """
    embedding_dim = config.get("embedding_dim", 64)
    epochs = config.get("epochs", 50)
    batch_size = config.get("batch_size", 8192)
    learning_rate = config.get("learning_rate", 0.01)
    top_k = config.get("top_k", 50)
    use_scann = config.get("use_scann", False)
    preserve_serving_facets = config.get("preserve_serving_facets", True)
    training_seed = int(config.get("training_seed", 42))
    retired_facets = {
        (str(facet["context_type"]), str(facet["language_tag"]))
        for facet in config.get("retired_serving_facets", [])
    }
    artifacts_url = config.get("artifacts_url")
    artifacts_token = config.get("artifacts_token")
    artifacts_pull_token = config.get("artifacts_pull_token")
    artifacts_namespace = config.get("artifacts_namespace", "model")
    model_name = config.get("personalized_model_name", PERSONALIZED_MODEL_NAME)

    artifacts_client = None
    if artifacts_url:
        artifacts_client = ArtifactsModelClient(
            artifacts_url,
            artifacts_namespace,
            artifacts_token,
            pull_api_token=artifacts_pull_token,
        )
        artifacts_client.hydrate_latest_model(model_name, model_dir)

    training_data_cutoff = _training_data_cutoff(interactions)
    previous_version = config.get("previous_personalized_model_version")
    previous_model, previous_snapshot = _load_previous_model(
        model_name, previous_version, model_dir, artifacts_client,
    )
    similarity = ContentSimilarityIndex(content, categories, ["training"], [tf.range(len(content))], 1, config.get("weights"))
    preparation_started = perf_counter()
    log.info("Preparing training observations and features from %d interactions", len(interactions))
    retrieval_dataset, ranking_dataset, content_dataset, user_dataset, vocabs = prepare_datasets(
        interactions, content, categories, feedback, signals, users,
        as_of=interactions.attrs.get("as_of"),
        half_life_days=float(config.get("recency_half_life_days", 30.0)),
        attribution_minutes=float(config.get("attribution_minutes", 30.0)),
        context_type=(config.get("context") or {}).get("type"),
        previous_behavior_snapshot=previous_snapshot,
        content_similarity=similarity,
    )
    log.info("Prepared training datasets in %.1fs", perf_counter() - preparation_started)
    positive_count = int(retrieval_dataset.cardinality().numpy())
    minimum = int(config.get("min_interactions", 1))
    if positive_count < max(1, minimum):
        return {"status": "skipped", "reason": f"only {positive_count} positive observations (minimum {max(1, minimum)})",
                "training_data": vocabs.get("training_data", {})}
    if behavior_rows is None:
        behavior_rows = pd.DataFrame(columns=["kind", "user_id", "source_id", "content_id", "cohort_key", "score"])
    behavior_snapshot = capture_snapshot(
        behavior_rows, feedback, vocabs["user_ids"], (config.get("context") or {}).get("type", "default"),
        extract_language_tags(content), previous_model,
    )
    log.info("Starting retrieval and ranking fits")
    model, ranking_model = train_model(
        retrieval_dataset=retrieval_dataset,
        ranking_dataset=ranking_dataset,
        content_dataset=content_dataset,
        vocabs=vocabs,
        embedding_dim=embedding_dim,
        epochs=epochs,
        batch_size=batch_size,
        learning_rate=learning_rate,
        random_seed=training_seed,
        weights=config.get("weights"),
        content_similarity=similarity,
        behavior_model=BehaviorFeatures(behavior_snapshot, similarity),
    )
    recommendation_contexts = extract_recommendation_contexts(content)
    language_tags = extract_language_tags(content)
    export_result = export_model(
        model=model,
        ranking_model=ranking_model,
        content_dataset=content_dataset,
        recommendation_contexts=recommendation_contexts,
        language_tags=language_tags,
        user_dataset=user_dataset,
        vocabs=vocabs,
        model_dir=model_dir,
        use_scann=use_scann,
        top_k=top_k,
        preserve_serving_facets=preserve_serving_facets,
        retired_facets=retired_facets,
        oov_empty=True,
        model_generation=PERSONALIZED_MODEL_GENERATION,
        training_data_cutoff=training_data_cutoff,
        model_version=config.get("model_version"),
        behavior_snapshot=behavior_snapshot,
    )
    training_users = len(vocabs["training_user_ids"])
    indexed_users = int(user_dataset.cardinality().numpy())
    if not export_result["promoted"]:
        missing_facets = export_result.get("missing_facets", [])
        return {
            "status": "rejected",
            "reason": (
                "the new personalized model is missing facets served by the current model"
                if missing_facets
                else "the exported personalized model failed validation"
            ),
            "missing_facets": missing_facets,
            "training_users": training_users,
            "indexed_users": indexed_users,
            "model_generation": PERSONALIZED_MODEL_GENERATION,
            "champion_training_users": export_result.get("champion_training_users"),
            "champion_indexed_users": export_result.get("champion_indexed_users"),
            "training_data_cutoff": training_data_cutoff,
            "champion_training_data_cutoff": export_result.get("champion_training_data_cutoff"),
            "training_data": vocabs.get("training_data", {}),
        }

    version = export_result["version"]
    result = {
        "status": "completed",
        "model_version": version,
        "users": indexed_users,
        "training_users": training_users,
        "indexed_users": indexed_users,
        "model_generation": PERSONALIZED_MODEL_GENERATION,
        "champion_training_users": export_result.get("champion_training_users"),
        "champion_indexed_users": export_result.get("champion_indexed_users"),
        "training_data_cutoff": training_data_cutoff,
        "champion_training_data_cutoff": export_result.get("champion_training_data_cutoff"),
        "content_items": len(vocabs["content_ids"]),
        "validation": export_result.get("validation"),
        "training_data": vocabs.get("training_data", {}),
    }
    if artifacts_client:
        upload_result = artifacts_client.upload_model(
            os.path.join(model_dir, str(version)), version, model_name=model_name,
        )
        result["artifact_version"] = upload_result["version"]
        result["artifact_size"] = upload_result["size_bytes"]
        log.info("Personalized model pushed to artifacts: %s@%d", PERSONALIZED_MODEL_NAME, version)
    return result


def run_training(config: dict) -> dict:
    """Trains the content (cold) base always, and the personalized two-tower when interactions suffice.

    Returns ``{"status", "interactions", "content_items", "models": {"content": ..., "personalized": ...}}``.
    Never returns a bare "skipped": with no/low interactions the content base is still (re)built, so newly
    added content is always recommendable; ``models.personalized`` carries its own "skipped" in that case.
    """
    config = configure_context(config)
    bosca_url = config.get("bosca_url", "http://bosca-server:8080")
    bosca_token = config.get("bosca_token")
    min_interactions = config.get("min_interactions", 100)

    # Two model dirs (recommender-content / recommender-personalized), defaulting alongside the parent of
    # the configured model_dir so `--model-dir /models/recommender` yields `/models/recommender-{content,
    # personalized}`.
    base = os.path.dirname(config.get("model_dir", "/models/recommender").rstrip("/")) or "/models"
    content_model_dir = config.get("content_model_dir") or os.path.join(base, config.get("content_model_name", "recommender-content"))
    personalized_model_dir = config.get("personalized_model_dir") or os.path.join(base, config.get("personalized_model_name", "recommender-personalized"))

    if not bosca_url:
        raise ValueError("bosca_url is required: the trainer loads data via Bosca's GraphQL, not Trino directly")
    users, interactions, content, categories, signals, feedback = load_data_from_bosca(bosca_url, bosca_token)
    if "context" in config:
        content = apply_context(content, config["context"])

    if len(content) == 0:
        msg = "No content found; nothing to train."
        log.warning(msg)
        return {"status": "no_content", "message": msg, "interactions": len(interactions)}

    result = {
        "status": "completed",
        "interactions": len(interactions),
        "content_items": len(content),
        "models": {},
    }
    eligible_interactions = _filter_interactions_to_content(interactions, content)
    result["eligible_interactions"] = len(eligible_interactions)

    # 1. Content (cold) base — always. Never skips, so newly added content is always recommendable.
    log.info("Building content similarity model over %d items -> %s", len(content), content_model_dir)
    result["models"]["content"] = run_content_only_training(
        content, categories, feedback, signals,
        dict(config, model_dir=content_model_dir),
    )

    # 2. Personalized two-tower — only when there are enough interactions to learn one.
    if len(eligible_interactions) + len(feedback) >= min_interactions:
        log.info(
            "Training personalized model from %d eligible interactions -> %s",
            len(eligible_interactions),
            personalized_model_dir,
        )
        result["models"]["personalized"] = _train_personalized(
            users, eligible_interactions, content, categories, feedback, signals,
            dict(config, min_interactions=min_interactions), personalized_model_dir,
            behavior_rows=load_behavior_from_bosca(
                bosca_url, bosca_token, interactions.attrs.get("as_of", datetime.now(timezone.utc).isoformat()),
            ),
        )
    else:
        log.info(
            "Only %d eligible interactions (< %d); skipping the personalized model (content base still refreshed).",
            len(eligible_interactions), min_interactions,
        )
        result["models"]["personalized"] = {
            "status": "skipped",
            "reason": f"only {len(eligible_interactions)} eligible interactions (minimum {min_interactions})",
            "interactions": len(eligible_interactions),
        }

    if "context" in config:
        content_result = result["models"]["content"]
        personalized_result = result["models"]["personalized"]
        if content_result["status"] != "completed_content_only" or personalized_result["status"] not in ("completed", "skipped"):
            raise RuntimeError("Context model export failed validation")
        completed_exports = [content_result] + ([personalized_result] if personalized_result["status"] == "completed" else [])
        if any(export.get("model_version") != config["model_version"] for export in completed_exports):
            raise RuntimeError("Context exports do not match the captured model version")
        report_export(config, personalized_result["status"] == "completed")
    return result
