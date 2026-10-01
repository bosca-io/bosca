"""Loading training data from Bosca's analytics GraphQL (no direct Trino).

The trainer never connects to Trino. Each data set is an installer-seeded Bosca analytics query
(see the recommendations RecommendationsInstaller) addressed by a well-known key; Bosca runs the SQL
server-side (federated over the warehouse + operational DB) and the trainer pages the JSON rows out
via GraphQL using the query's ``:offset`` / ``:limit`` parameters.
"""

import logging
import os
from datetime import datetime, timezone
from time import perf_counter
from typing import Optional

import numpy as np
import pandas as pd
import requests

from trainer.features import parse_embedding

log = logging.getLogger(__name__)


# (analytics query key, expected columns).
_QUERY_DEFINITIONS = [
    ("recommender-users", ["user_id"]),
    (
        "recommender-interactions",
        ["user_id", "content_id", "interaction_type", "view_percent", "interaction_created", "event_id",
         "app_id", "session_id", "page_id", "element_type", "depth_percent", "visible_ms", "visibility_threshold",
         "guide_id", "guide_version", "guide_started", "recommendation_source_id", "recommendation_context",
         "recommendation_model_version", "recommendation_request_id"],
    ),
    (
        "recommender-content-features",
        ["content_id", "content_type", "language_tag", "labels", "recommendation_contexts",
         "editorial_type", "collection_ids"],
    ),
    ("recommender-content-categories", ["content_id", "category_id"]),
    # Per-(user, signal key, value) personalization signals — the configurable, self-identifying user-tower
    # features that replaced the profile_type-only `recommender-user-features`. Repeated keys
    # intentionally preserve distinct values; each row's `value_type` drives the trainer's encoding.
    ("recommender-user-signals", ["user_id", "signal_key", "signal_value", "value_type", "priority"]),
    ("recommender-feedback", ["user_id", "content_id", "feedback_label", "feedback_source", "feedback_created"]),
]

_EMBEDDING_QUERY_KEY = "recommender-content-embeddings"

_PAGE_SIZE = int(os.environ.get("ML_TRAINER_ANALYTICS_PAGE_SIZE", "20000"))
if not 1 <= _PAGE_SIZE <= 20000:
    raise ValueError("ML_TRAINER_ANALYTICS_PAGE_SIZE must be between 1 and 20000")
_EMBEDDING_PAGE_SIZE = 500
_REQUEST_TIMEOUT = (
    float(os.environ.get("ML_TRAINER_CONNECT_TIMEOUT_SECONDS", "30")),
    float(os.environ.get("ML_TRAINER_READ_TIMEOUT_SECONDS", "600")),
)

_EXECUTE_QUERY = (
    "query($k:String!,$p:[AnalyticsQueryExecutionParameterInput!]!)"
    "{analytics{queries{executeByKey(key:$k, parameters:$p){records}}}}"
)


def _bosca_graphql(bosca_url: str, token: Optional[str], query: str, variables: Optional[dict] = None) -> dict:
    """Posts a GraphQL operation to Bosca and returns its `data`, raising on GraphQL errors."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    context = ""
    if query == _EXECUTE_QUERY and variables:
        paging = {
            item["parameter"]: item["value"]
            for item in variables.get("p", [])
            if item["parameter"] in ("offset", "limit", "asOf")
        }
        context = f" query_key={variables.get('k')!r} parameters={paging!r}"
    started = perf_counter()
    try:
        resp = requests.post(
            f"{bosca_url.rstrip('/')}/graphql",
            json={"query": query, "variables": variables or {}},
            headers=headers,
            timeout=_REQUEST_TIMEOUT,
        )
    except requests.RequestException as error:
        error.add_note(
            f"Bosca GraphQL request:{context} elapsed_seconds={perf_counter() - started:.3f} "
            f"connect_timeout_seconds={_REQUEST_TIMEOUT[0]} read_timeout_seconds={_REQUEST_TIMEOUT[1]}"
        )
        raise
    try:
        resp.raise_for_status()
        body = resp.json()
    except (requests.HTTPError, requests.exceptions.JSONDecodeError) as error:
        # Preserve the failure type while making unexpected HTTP responses diagnosable.
        preview = resp.text
        if token:
            preview = preview.replace(token, "[REDACTED]")
        error.add_note(
            f"Bosca GraphQL response:{context} status={resp.status_code} "
            f"content_type={resp.headers.get('Content-Type')!r} bytes={len(resp.content)} "
            f"elapsed_seconds={resp.elapsed.total_seconds():.3f} "
            f"redirect_statuses={[response.status_code for response in resp.history]} "
            f"server={resp.headers.get('Server')!r} cf_ray={resp.headers.get('CF-Ray')!r} "
            f"request_id={resp.headers.get('X-Request-ID')!r} "
            f"body_prefix={preview[:512]!r}"
        )
        raise
    if body.get("errors"):
        raise RuntimeError(f"Bosca GraphQL error: {body['errors']}")
    return body["data"]


def _execute_paged(bosca_url: str, token: Optional[str], key: str, columns: list[str], as_of=None) -> pd.DataFrame:
    """Executes the installer-seeded analytics query by key, paging all rows out via :offset / :limit."""
    rows: list = []
    offset = 0
    while True:
        params = [
            {"parameter": "offset", "value": offset},
            {"parameter": "limit", "value": _PAGE_SIZE},
        ]
        if as_of is not None:
            params.insert(0, {"parameter": "asOf", "value": as_of})
        page_started = perf_counter()
        log.info("Loading %s page: offset=%d limit=%d", key, offset, _PAGE_SIZE)
        data = _bosca_graphql(bosca_url, token, _EXECUTE_QUERY, {"k": key, "p": params})
        records = data["analytics"]["queries"]["executeByKey"]["records"]
        rows.extend(records)
        log.info(
            "Loaded %s page: offset=%d rows=%d total_rows=%d elapsed_seconds=%.3f",
            key, offset, len(records), len(rows), perf_counter() - page_started,
        )
        if len(records) < _PAGE_SIZE:
            break
        offset += _PAGE_SIZE
    frame = pd.DataFrame(rows, columns=columns)
    # Bosca serializes JDBC timestamps as epoch milliseconds. Normalize at the API boundary so
    # observation decay and guide-run matching operate on UTC timestamps throughout training.
    for column in ("interaction_created", "feedback_created", "guide_started"):
        if column not in frame:
            continue
        raw = frame[column]
        numeric = raw.map(lambda value: isinstance(value, (int, float)) and not isinstance(value, bool))
        parsed = pd.to_datetime(raw.where(~numeric), errors="coerce", utc=True, format="mixed").astype("datetime64[ns, UTC]")
        parsed.loc[numeric] = pd.to_datetime(raw[numeric], unit="ms", errors="coerce", utc=True).astype("datetime64[ns, UTC]")
        frame[column] = parsed
    return frame


def _execute_pooled_embeddings(bosca_url: str, token: Optional[str]) -> pd.DataFrame:
    """Streams ordered embedding chunks and retains one overlap-neutral vector per content item.

    Analytics pages contain content ids, not raw rows, so replacement of one item's chunk set cannot shift
    later documents across offset boundaries. Raw chunk strings and parsed Python-float lists are released
    with each page; exactly one normalized vector is retained per content item.
    """
    pooled_rows: list[dict] = []
    offset = 0
    current_content_id = None
    current_chunk_index = None
    current_token_start = None
    current_token_end = None
    weighted_sum = None
    total_weight = 0.0
    embedding_dim = 0

    def finish_current():
        nonlocal weighted_sum, total_weight, current_chunk_index, current_token_start, current_token_end
        if current_content_id is not None and weighted_sum is not None and total_weight > 0:
            if not np.isclose(total_weight, current_token_end, rtol=1e-9, atol=1e-6):
                raise ValueError(
                    f"embedding chunk weights for content {current_content_id} total {total_weight}; "
                    f"expected {current_token_end} unique source tokens"
                )
            pooled = weighted_sum / total_weight
            norm = np.linalg.norm(pooled)
            if norm > 0:
                pooled = pooled / norm
            pooled_rows.append({
                "content_id": current_content_id,
                "embedding": pooled.astype(np.float32),
            })
        weighted_sum = None
        total_weight = 0.0
        current_chunk_index = None
        current_token_start = None
        current_token_end = None

    while True:
        params = [
            {"parameter": "offset", "value": offset},
            {"parameter": "limit", "value": _EMBEDDING_PAGE_SIZE},
        ]
        page_started = perf_counter()
        log.info(
            "Loading %s page: content_offset=%d content_limit=%d",
            _EMBEDDING_QUERY_KEY, offset, _EMBEDDING_PAGE_SIZE,
        )
        data = _bosca_graphql(bosca_url, token, _EXECUTE_QUERY, {"k": _EMBEDDING_QUERY_KEY, "p": params})
        records = data["analytics"]["queries"]["executeByKey"]["records"]
        page_content_ids = {str(record.get("content_id")) for record in records if record.get("content_id") is not None}
        for record in records:
            content_id = record.get("content_id")
            if content_id is None:
                raise ValueError("embedding chunk is missing content_id")
            if current_content_id is not None and str(content_id) < str(current_content_id):
                raise ValueError("embedding chunks must be ordered by content_id and chunk_index")
            if content_id != current_content_id:
                finish_current()
                current_content_id = content_id

            try:
                raw_chunk_index = float(record.get("chunk_index"))
            except (TypeError, ValueError) as error:
                raise ValueError(f"embedding chunk for content {content_id} has an invalid chunk_index") from error
            if not np.isfinite(raw_chunk_index) or raw_chunk_index < 0 or not raw_chunk_index.is_integer():
                raise ValueError(f"embedding chunk for content {content_id} has an invalid chunk_index")
            chunk_index = int(raw_chunk_index)
            expected_chunk_index = 0 if current_chunk_index is None else current_chunk_index + 1
            if chunk_index != expected_chunk_index:
                raise ValueError(
                    f"embedding chunks for content {content_id} must be contiguous from zero; "
                    f"expected {expected_chunk_index}, got {chunk_index}"
                )
            current_chunk_index = chunk_index

            try:
                raw_token_start = float(record.get("token_start"))
                raw_token_end = float(record.get("token_end"))
                raw_token_count = float(record.get("token_count"))
            except (TypeError, ValueError) as error:
                raise ValueError(f"embedding chunk {content_id}/{chunk_index} has an invalid token span") from error
            if (
                not np.isfinite(raw_token_start)
                or not np.isfinite(raw_token_end)
                or not np.isfinite(raw_token_count)
                or not raw_token_start.is_integer()
                or not raw_token_end.is_integer()
                or not raw_token_count.is_integer()
            ):
                raise ValueError(f"embedding chunk {content_id}/{chunk_index} has an invalid token span")
            token_start = int(raw_token_start)
            token_end = int(raw_token_end)
            token_count = int(raw_token_count)
            if token_start < 0 or token_end <= token_start or token_count != token_end - token_start:
                raise ValueError(f"embedding chunk {content_id}/{chunk_index} has an invalid token span")
            if current_token_start is None:
                if token_start != 0:
                    raise ValueError(f"embedding chunks for content {content_id} must start at token zero")
            elif token_start <= current_token_start or token_start > current_token_end or token_end <= current_token_end:
                raise ValueError(
                    f"embedding chunks for content {content_id} must have ordered, gap-free token spans"
                )
            current_token_start = token_start
            current_token_end = token_end

            vector = parse_embedding(record.get("embedding"))
            if not vector:
                raise ValueError(f"embedding chunk {content_id}/{chunk_index} has an invalid embedding")
            if embedding_dim == 0:
                embedding_dim = len(vector)
            if len(vector) != embedding_dim:
                raise ValueError(
                    f"embedding chunk {content_id}/{chunk_index} has dimension {len(vector)}; "
                    f"expected {embedding_dim}"
                )
            vector_array = np.asarray(vector, dtype=np.float64)
            if not np.all(np.isfinite(vector_array)):
                raise ValueError(f"embedding chunk {content_id}/{chunk_index} contains a non-finite value")
            try:
                raw_weight = float(record.get("aggregation_weight"))
            except (TypeError, ValueError) as error:
                raise ValueError(
                    f"embedding chunk {content_id}/{chunk_index} has an invalid aggregation_weight"
                ) from error
            if not np.isfinite(raw_weight) or raw_weight <= 0:
                raise ValueError(f"embedding chunk {content_id}/{chunk_index} has an invalid aggregation_weight")
            weight = raw_weight
            if weighted_sum is None:
                weighted_sum = np.zeros(embedding_dim, dtype=np.float64)
            weighted_sum += vector_array * weight
            total_weight += weight

        log.info(
            "Loaded %s page: content_offset=%d chunks=%d content_items=%d "
            "total_content_items=%d elapsed_seconds=%.3f",
            _EMBEDDING_QUERY_KEY, offset, len(records), len(page_content_ids),
            offset + len(page_content_ids), perf_counter() - page_started,
        )
        if len(page_content_ids) < _EMBEDDING_PAGE_SIZE:
            break
        offset += len(page_content_ids)

    finish_current()
    return pd.DataFrame(pooled_rows, columns=["content_id", "embedding"])


def _merge_embeddings(content: pd.DataFrame, embeddings: pd.DataFrame) -> pd.DataFrame:
    """Attaches one streamed, token-weighted semantic vector per content item."""
    if len(embeddings) == 0:
        return content
    return content.merge(embeddings[["content_id", "embedding"]], on="content_id", how="left")


def _merge_guide_completions(interactions: pd.DataFrame, saved: pd.DataFrame) -> pd.DataFrame:
    """Prefer timestamped completion events over state-derived evidence for the same guide run.

    Active state records the run's start. History has no start, so an exact full-guide event at the
    history completion time links that history to its run. Older steps with no exact event retain
    their state fallback, even when the final step has timestamped analytics.
    """
    exact = interactions[
        interactions["element_type"].isin(["guide_step", "guide"]) & interactions["interaction_type"].eq("Completion")
    ]
    completed_runs = {}
    observed = set()

    def timestamp(value):
        return pd.to_datetime(value, utc=True, errors="coerce")

    def guide_key(row):
        return str(row.user_id), str(row.guide_id), str(row.guide_version)

    for row in exact.itertuples():
        started = timestamp(row.guide_started)
        created = timestamp(row.interaction_created)
        if pd.isna(started) or pd.isna(created):
            continue
        key = guide_key(row)
        observed.add((key, started, str(row.content_id), row.element_type))
        if row.element_type == "guide":
            # Warehouse `created` has millisecond precision; the remainder is a separate column.
            completed_runs[(key, created.floor("ms"))] = started

    keep = []
    for row in saved.itertuples():
        key = guide_key(row)
        started = timestamp(row.guide_started)
        if pd.isna(started):
            completed = timestamp(row.interaction_created)
            started = completed_runs.get((key, completed.floor("ms"))) if pd.notna(completed) else None
        kind = "guide_step" if row.element_type == "guide_progress" else row.element_type
        keep.append((key, started, str(row.content_id), kind) not in observed)
    retained = saved.loc[keep] if len(saved) else saved
    log.info("Guide completions: %d state fallback rows retained, %d replaced by timestamped events",
             len(retained), len(saved) - len(retained))
    return pd.concat([interactions, retained], ignore_index=True)


def load_behavior_from_bosca(bosca_url, token, as_of):
    """Read the analytics-produced behavioral inputs captured by a context model version."""
    return _execute_paged(bosca_url, token, "recommender-behavior",
                          ["kind", "user_id", "source_id", "content_id", "cohort_key", "score"], as_of=as_of)


def load_data_from_bosca(
    bosca_url: str,
    token: Optional[str],
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Loads users, interactions, content, categories, signals, and feedback via Bosca's analytics GraphQL.

    The fifth frame is the per-(user, signal key, value) personalization signals (``recommender-user-signals``) —
    the configurable user-tower features that replaced the profile_type ``recommender-user-features``.
    """
    log.info("Loading training data via Bosca GraphQL at %s", bosca_url)
    frames: dict[str, pd.DataFrame] = {}
    as_of = datetime.now(timezone.utc).isoformat()
    for key, columns in _QUERY_DEFINITIONS:
        if key in ("recommender-interactions", "recommender-feedback"):
            frames[key] = _execute_paged(bosca_url, token, key, columns, as_of=as_of)
        else:
            frames[key] = _execute_paged(bosca_url, token, key, columns)
        frames[key].attrs["as_of"] = as_of
        log.info("Loaded %d rows for %s", len(frames[key]), key)
    guide_completions = _execute_paged(
        bosca_url, token, "recommender-guide-completions",
        frames["recommender-interactions"].columns.tolist(), as_of=as_of,
    )
    log.info("Loaded %d guide-state completion observations", len(guide_completions))
    frames["recommender-interactions"] = _merge_guide_completions(
        frames["recommender-interactions"], guide_completions,
    )
    frames["recommender-interactions"].attrs["as_of"] = as_of
    embeddings = _execute_pooled_embeddings(bosca_url, token)
    log.info("Loaded and pooled semantic embeddings for %d content items", len(embeddings))
    content = _merge_embeddings(frames["recommender-content-features"], embeddings)
    return (
        frames["recommender-users"],
        frames["recommender-interactions"],
        content,
        frames["recommender-content-categories"],
        frames["recommender-user-signals"],
        frames["recommender-feedback"],
    )
