"""Pure numpy/pandas feature engineering shared by the interaction and content-only training paths.

No TensorFlow import here on purpose: this is the fast, unit-testable numeric core. Keeping the
content-tower vocab / multi-hot / affinity logic in one place means the interaction (two-tower) and
content-only paths encode the candidate corpus identically.
"""

import json

import numpy as np
import pandas as pd


def editorial_types(content: pd.DataFrame) -> list[str]:
    """Normalized attributes.type; missing and non-string values carry no matching evidence."""
    values = content.get("editorial_type", pd.Series("", index=content.index))
    return [value.strip().lower() if isinstance(value, str) else "" for value in values]


def collections_by_content(content: pd.DataFrame) -> dict[str, frozenset[str]]:
    """Distinct direct collection memberships without allocating an item-by-collection matrix."""
    values = content.get("collection_ids", pd.Series([None] * len(content), index=content.index))
    result = {}
    for content_id, raw in zip(content["content_id"], values):
        if raw is None or (isinstance(raw, float) and np.isnan(raw)):
            raw = ()
        if isinstance(raw, np.ndarray):
            raw = raw.tolist()
        if not isinstance(raw, (list, tuple, set, frozenset)) or any(not isinstance(key, str) for key in raw):
            raise ValueError(f"collection_ids for content {content_id} must be an array of strings")
        result[content_id] = frozenset(key.strip() for key in raw if key.strip())
    return result


def labels_by_content(content: pd.DataFrame) -> dict:
    """Maps ``content_id -> labels`` from the content frame's structured ``labels`` column.

    Returns an empty map when the column is absent or every cell is empty (the common case today), which
    is what keeps the label feature inert until labels are actually populated.
    """
    mapping: dict = {}
    if "labels" not in content.columns:
        return mapping
    for content_id, raw_labels in zip(content["content_id"].values, content["labels"].values):
        if raw_labels is None or (isinstance(raw_labels, float) and np.isnan(raw_labels)):
            raw_labels = ()
        elif isinstance(raw_labels, np.ndarray):
            raw_labels = raw_labels.tolist()
        if not isinstance(raw_labels, (list, tuple, set, frozenset)):
            raise ValueError(f"labels for content {content_id} must be an array, got {type(raw_labels).__name__}")
        if any(not isinstance(label, str) for label in raw_labels):
            raise ValueError(f"labels for content {content_id} must contain only strings")
        labels = [label.strip() for label in raw_labels if label.strip()]
        if labels:
            mapping[content_id] = labels
    return mapping


def extract_content_vocabs(content: pd.DataFrame, categories: pd.DataFrame) -> dict:
    """Sorted, de-duplicated content-tower vocabularies from the content + categories frames."""
    label_map = labels_by_content(content)
    label_ids = sorted({label for labels in label_map.values() for label in labels})
    collection_map = collections_by_content(content)
    return {
        "content_ids": sorted(content["content_id"].unique().tolist()),
        "content_types": sorted(content["content_type"].dropna().unique().tolist()),
        "languages": sorted(content["language_tag"].dropna().unique().tolist()),
        "category_ids": sorted(categories["category_id"].dropna().unique().tolist()) if len(categories) > 0 else [],
        "label_ids": label_ids,
        "editorial_types": sorted({value for value in editorial_types(content) if value}),
        "collection_ids": sorted({key for keys in collection_map.values() for key in keys}),
    }


def build_multi_hot_matrix(row_ids, keys_by_row: dict, vocab: list) -> np.ndarray:
    """Build a ``[len(row_ids), len(vocab)]`` float32 multi-hot matrix.

    - ``row_ids``: iterable of row identifiers in corpus order (defines the row order).
    - ``keys_by_row``: dict ``row_id -> list of vocab keys`` present for that row.
    - ``vocab``: ordered list of vocab keys (defines the column order).

    Keys absent from ``vocab`` are ignored. A zero-width matrix is returned when ``vocab`` is empty.
    """
    index = {key: i for i, key in enumerate(vocab)}
    rows = list(row_ids)
    matrix = np.zeros((len(rows), len(index)), dtype=np.float32)
    if not index:
        return matrix
    for r, row_id in enumerate(rows):
        for key in keys_by_row.get(row_id, []):
            col = index.get(key)
            if col is not None:
                matrix[r, col] = 1.0
    return matrix


def compute_user_affinity(user_arr, labels_arr, interaction_multi_hot, num_categories: int,
                          observed_at=None, feature_times=None):
    """Label-weighted per-user category affinity.

    Serving uses the complete captured history. Training uses only outcomes strictly preceding each
    request's feature time; unknown timestamps contribute no historical training feature. Returns
    ``(affinity_by_user: dict[str, np.ndarray], interaction_affinity: np.ndarray | None)`` where
    ``interaction_affinity`` aligns row-for-row with ``user_arr``. Returns ``({}, None)`` when there are
    no categories.
    """
    if num_categories <= 0:
        return {}, None
    codes, uniq_users = pd.factorize(user_arr)
    sums = np.zeros((len(uniq_users), num_categories), dtype=np.float32)
    np.add.at(sums, codes, interaction_multi_hot * labels_arr[:, None])
    weights = np.zeros(len(uniq_users), dtype=np.float32)
    np.add.at(weights, codes, labels_arr)
    user_affinity = sums / np.where(weights > 0.0, weights, 1.0)[:, None]
    user_affinity[weights <= 0.0] = 0.0
    affinity_by_user = {str(u): user_affinity[i] for i, u in enumerate(uniq_users)}
    interaction_affinity = np.zeros_like(interaction_multi_hot, dtype=np.float32)
    if observed_at is not None and feature_times is not None:
        observed = pd.to_datetime(observed_at, errors="coerce", utc=True).to_numpy()
        requested = pd.to_datetime(feature_times, errors="coerce", utc=True).to_numpy()
        for code in range(len(uniq_users)):
            rows = np.flatnonzero(codes == code)
            evidence = [row for row in rows if not pd.isna(observed[row]) and labels_arr[row] > 0]
            evidence.sort(key=lambda row: observed[row])
            requests = [row for row in rows if not pd.isna(requested[row])]
            requests.sort(key=lambda row: requested[row])
            total = np.zeros(num_categories, dtype=np.float32)
            weight = 0.0
            position = 0
            for row in requests:
                while position < len(evidence) and observed[evidence[position]] < requested[row]:
                    prior = evidence[position]
                    total += interaction_multi_hot[prior] * labels_arr[prior]
                    weight += labels_arr[prior]
                    position += 1
                if weight > 0:
                    interaction_affinity[row] = total / weight
    return affinity_by_user, interaction_affinity


def compute_sparse_user_affinity(users, content_ids, weights, memberships, observed_at, feature_times):
    """Sparse type/collection interests, with strictly historical features for each training row.

    Memberships map item IDs to distinct feature keys. No profile-by-collection or
    item-by-collection matrix is allocated. Returned training dictionaries retain input row order.
    """
    observed = pd.to_datetime(observed_at, errors="coerce", utc=True).to_numpy()
    requested = pd.to_datetime(feature_times, errors="coerce", utc=True).to_numpy()
    codes, unique_users = pd.factorize(users)
    serving = {}
    training = [{} for _ in users]

    def add(total, row):
        for token in memberships.get(content_ids[row], ()):
            total[token] = total.get(token, 0.0) + float(weights[row])

    for code, user in enumerate(unique_users):
        rows = np.flatnonzero(codes == code)
        positive = [row for row in rows if weights[row] > 0]
        total = {}
        denominator = sum(float(weights[row]) for row in positive)
        for row in positive:
            add(total, row)
        serving[str(user)] = {key: value / denominator for key, value in total.items()} if denominator else {}
        evidence = sorted((row for row in positive if not pd.isna(observed[row])), key=lambda row: observed[row])
        requests = sorted((row for row in rows if not pd.isna(requested[row])), key=lambda row: requested[row])
        total, denominator, position = {}, 0.0, 0
        for row in requests:
            while position < len(evidence) and observed[evidence[position]] < requested[row]:
                prior = evidence[position]
                add(total, prior)
                denominator += float(weights[prior])
                position += 1
            if denominator:
                training[row] = {key: value / denominator for key, value in total.items()}
    return serving, training


def parse_embedding(text):
    """Parses a pgvector text embedding (``'[f, f, ...]'``) into a list of floats; ``None`` if blank/invalid."""
    if not isinstance(text, str) or not text.strip():
        return None
    try:
        parsed = json.loads(text)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, list):
        return None
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in parsed):
        return None
    try:
        return [float(x) for x in parsed]
    except (ValueError, TypeError):
        return None


def parse_signal_value(text):
    """Parse a personalization signal's JSON-text value (from the ``signal_value`` column) into a Python
    value. The analytics view emits each value's JSON literal — ``"25-34"`` / ``42`` / ``true`` /
    ``["a","b"]`` — so ``json.loads`` recovers a str / number / bool / list. A bare (unquoted) string is
    tolerated as itself; blank / null yields ``None`` (the signal contributes nothing)."""
    if text is None:
        return None
    if not isinstance(text, str):
        return text  # already a parsed python value
    text = text.strip()
    if not text or text.lower() == "null":
        return None
    try:
        return json.loads(text)
    except (ValueError, TypeError):
        return text  # a categorical value that wasn't JSON-quoted → treat as the literal string


def _scalar_token(value) -> str:
    """A signal scalar rendered as a stable token string (``true``/``false`` for bools, no trailing ``.0``)."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _compute_numeric_bins(values: list, n_buckets: int) -> list:
    """Interior quantile edges for bucketizing a numeric signal (``np.searchsorted`` boundaries).

    Returns ``[]`` (a single bucket) when the key has one distinct value, so a constant numeric signal
    still yields one stable token rather than erroring.
    """
    uniq = sorted(set(values))
    if len(uniq) <= 1:
        return []
    n = min(n_buckets, len(uniq))
    quantiles = np.linspace(0.0, 1.0, n + 1)[1:-1]
    return sorted({float(np.quantile(values, q)) for q in quantiles})


def _bucket_index(value: float, bins: list) -> int:
    """The bucket a numeric value falls into given interior ``bins`` (0 when there are no bins)."""
    if not bins:
        return 0
    return int(np.searchsorted(bins, value, side="right"))


def _priority_weight(priority: float) -> float:
    """A bounded multi-hot weight from a signal definition's priority (0 → 1.0, rising, capped at 3.0), so
    higher-priority signals start with more magnitude in the user vector without letting any one dominate."""
    return float(min(1.0 + max(0.0, priority) * 0.1, 3.0))


def _tokens_for_signal(key: str, value_type: str, parsed, bins) -> list:
    """The ``key=value`` token(s) a single signal contributes to the user multi-hot, encoded per value type:
    categorical/boolean → one ``key=value`` token; multi_categorical → one per array element; numeric →
    ``key=q{bucket}`` from its quantile bins. Returns ``[]`` for an absent/ill-typed value."""
    if parsed is None:
        return []
    if value_type == "multi_categorical":
        elements = parsed if isinstance(parsed, list) else [parsed]
        return [f"{key}={_scalar_token(e)}" for e in elements if e is not None]
    if value_type == "numeric":
        if isinstance(parsed, bool) or not isinstance(parsed, (int, float)):
            return []
        return [f"{key}=q{_bucket_index(float(parsed), bins)}"]
    # categorical / boolean / anything else → a single keyed token.
    return [f"{key}={_scalar_token(parsed)}"]


def build_signal_features(signals: pd.DataFrame, n_numeric_buckets: int = 4) -> tuple:
    """Encodes per-(user, signal key, value) rows into weighted user-tower multi-hot tokens.

    Returns ``(tokens_by_user, vocab)`` where ``tokens_by_user[user_id] -> {token: weight}`` and ``vocab``
    is the sorted token list (the multi-hot's column order). Each signal is encoded per its ``value_type``
    (categorical / multi_categorical / numeric / boolean); numeric values are bucketized by per-key
    quantiles so magnitude becomes a keyed token. Empty-safe: ``({}, [])`` when there are no signals.
    """
    if (
        signals is None
        or len(signals) == 0
        or "signal_key" not in signals.columns
        or "signal_value" not in signals.columns
    ):
        return {}, []

    df = signals.copy()
    if "priority" in df.columns:
        df["_priority"] = pd.to_numeric(df["priority"], errors="coerce").fillna(0.0)
    else:
        df["_priority"] = 0.0
    if "value_type" in df.columns:
        df["_vtype"] = df["value_type"].astype(str).str.lower()
    else:
        df["_vtype"] = "categorical"
    df["_parsed"] = df["signal_value"].map(parse_signal_value)

    # Per-key numeric bins (computed once over all users' values for that key, so bucketing is consistent).
    numeric_bins: dict = {}
    numeric_rows = df[df["_vtype"] == "numeric"]
    for key, group in numeric_rows.groupby("signal_key"):
        values = [
            float(v) for v in group["_parsed"]
            if isinstance(v, (int, float)) and not isinstance(v, bool)
        ]
        if values:
            numeric_bins[str(key)] = _compute_numeric_bins(values, n_numeric_buckets)

    tokens_by_user: dict = {}
    for user_id, signal_key, vtype, parsed, priority in zip(
        df["user_id"], df["signal_key"], df["_vtype"], df["_parsed"], df["_priority"]
    ):
        key = str(signal_key)
        tokens = _tokens_for_signal(key, vtype, parsed, numeric_bins.get(key))
        if not tokens:
            continue
        weight = _priority_weight(float(priority))
        bucket = tokens_by_user.setdefault(str(user_id), {})
        for token in tokens:
            # A user with several attributes of a type can emit the same token more than once; keep the
            # strongest (highest-priority) weight.
            bucket[token] = max(bucket.get(token, 0.0), weight)

    vocab = sorted({token for tokens in tokens_by_user.values() for token in tokens})
    return tokens_by_user, vocab


def build_signal_multi_hot(user_ids, tokens_by_user: dict, vocab: list) -> np.ndarray:
    """A ``[len(user_ids), len(vocab)]`` float32 weighted multi-hot of each user's signal tokens.

    Row order follows ``user_ids``; column order follows ``vocab``. A user's token cell holds its
    priority-derived weight. Returns a zero-width matrix when ``vocab`` is empty (no signals).
    """
    index = {token: i for i, token in enumerate(vocab)}
    rows = list(user_ids)
    matrix = np.zeros((len(rows), len(index)), dtype=np.float32)
    if not index:
        return matrix
    for r, user_id in enumerate(rows):
        for token, weight in tokens_by_user.get(str(user_id), {}).items():
            col = index.get(token)
            if col is not None:
                matrix[r, col] = weight
    return matrix


def build_embedding_matrix(content: pd.DataFrame):
    """Stacks streamed per-content semantic vectors into a fixed-width content-tower matrix.

    Chunk pooling happens while analytics pages are consumed, before this content frame is built. Content
    without an embedding gets a zero row; ``dim`` is inferred from the first present vector.
    """
    if "embedding" not in content.columns:
        return np.zeros((len(content), 0), dtype=np.float32), 0
    vectors = content["embedding"].tolist()

    def is_missing(vector):
        return vector is None or (isinstance(vector, float) and np.isnan(vector))

    dim = 0
    for vector in vectors:
        if is_missing(vector):
            continue
        if not isinstance(vector, (list, np.ndarray)) or len(vector) == 0:
            raise ValueError("pooled content embedding must be a non-empty numeric array")
        dim = len(vector)
        break
    matrix = np.zeros((len(vectors), dim), dtype=np.float32)
    if dim == 0:
        return matrix, 0
    for i, vector in enumerate(vectors):
        if is_missing(vector):
            continue
        if not isinstance(vector, (list, np.ndarray)) or len(vector) != dim:
            actual = len(vector) if isinstance(vector, (list, np.ndarray)) else type(vector).__name__
            raise ValueError(f"pooled content embedding has dimension {actual}; expected {dim}")
        vector_array = np.asarray(vector, dtype=np.float32)
        if not np.all(np.isfinite(vector_array)):
            raise ValueError("pooled content embedding contains a non-finite value")
        matrix[i] = vector_array
    return matrix, dim
