"""Captured recommendation-context eligibility and training completion."""
from trainer.data import _bosca_graphql
from trainer.features import editorial_types
from trainer.context_weights import context_weights


def apply_context(content, context):
    """Evaluate the captured filter, with Bosca's include-list precedence."""
    settings = context.get("contentFilter", {}).get("metadata", {})
    def matches(value, included, excluded, prefix=False):
        value = value.strip().lower() if isinstance(value, str) else ""
        included = [entry.strip().lower() for entry in included if entry.strip()]
        excluded = [entry.strip().lower() for entry in excluded if entry.strip()]
        def match(entry):
            return value.startswith(entry) if prefix else value == entry
        if included:
            return bool(value) and any(match(entry) for entry in included)
        return not value or not any(match(entry) for entry in excluded)

    mask = [
        matches(mime.split(";")[0] if isinstance(mime, str) else "",
                settings.get("includedContentTypePrefixes", []),
                settings.get("excludedContentTypePrefixes", ["image/", "video/", "audio/", "font/", "model/", "application/octet-stream"]), True)
        and matches(editorial, settings.get("includedAttributeTypes", []), settings.get("excludedAttributeTypes", []))
        for mime, editorial in zip(content["content_type"], editorial_types(content))
    ]
    result = content.loc[mask].copy().reset_index(drop=True)
    result["recommendation_contexts"] = [[context["type"]]] * len(result)
    return result


def configure_context(config):
    """Carry immutable context importance into both exports."""
    context = config.get("context")
    if context is None:
        return config
    config = dict(config)
    config["weights"] = context_weights(context.get("weights"))
    config["preserve_serving_facets"] = False
    return config


def report_export(config, personalized):
    """Report the export to the same Bosca service that supplied its training data."""
    if "context" not in config:
        return
    data = _bosca_graphql(
        config["bosca_url"], config.get("bosca_token"),
        """
        mutation ContextModelExported($version: Long!, $personalized: Boolean!) {
          recommendation { contexts {
            modelExported(version: $version, personalized: $personalized)
          } }
        }
        """,
        {"version": config["model_version"], "personalized": personalized},
    )
    if not data.get("recommendation", {}).get("contexts", {}).get("modelExported"):
        raise RuntimeError("Context export registration did not succeed")
