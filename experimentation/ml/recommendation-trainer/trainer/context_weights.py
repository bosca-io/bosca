"""Context importance validation shared by training and content export.

Settings are captured during training, never supplied as live serving overrides.
"""
import math

SIMILARITY_DEFAULTS = dict.fromkeys(
    ("semantic", "categories", "labels", "language", "mime", "type", "collections"), 0.2,
)
INFLUENCES = ("content", "coEngagement", "cohortCoEngagement", "learnedNeighbor", "personalization", "rating")


def _importance(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
        raise ValueError(f"{name} must be finite and between 0 and 1")
    return float(value)


def context_weights(settings=None):
    """Validate and canonicalize a saved context snapshot without changing its relative scale."""
    settings = settings or {}
    similarity = {**SIMILARITY_DEFAULTS, **settings.get("similarity", {})}
    if set(similarity) != set(SIMILARITY_DEFAULTS):
        raise ValueError("Unknown similarity weight")
    similarity = {key: _importance(value, key) for key, value in similarity.items()}
    if not sum(similarity.values()):
        raise ValueError("At least one similarity weight must be greater than zero")
    preferences = {}
    for preference in settings.get("typePreferences", []):
        key = preference["type"]
        if not isinstance(key, str) or not key.strip():
            raise ValueError("Editorial type must not be blank")
        key = key.strip().lower()
        if key in preferences:
            raise ValueError("Editorial type preferences must have distinct normalized types")
        preferences[key] = _importance(preference["weight"], key)
    return {
        "similarity": similarity,
        "typePreferences": [{"type": key, "weight": value} for key, value in preferences.items()],
        "defaultTypePreference": _importance(settings.get("defaultTypePreference", 0.5), "defaultTypePreference"),
        **{key: _importance(settings.get(key, 1.0), key) for key in INFLUENCES},
    }


def normalized_similarity(settings):
    """Normalize only the competing content similarity group."""
    values = context_weights(settings)["similarity"]
    total = sum(values.values())
    return {key: value / total for key, value in values.items()}
