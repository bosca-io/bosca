"""Frozen context filtering and local-only export registration contract tests."""
from unittest.mock import patch

import pandas as pd
import pytest

from trainer.contexts import apply_context, configure_context, report_export


def content():
    return pd.DataFrame({
        "content_id": ["devotional", "article", "video", "missing"],
        "content_type": [" Text/Plain; charset=utf-8 ", "text/plain", "video/mp4", "text/plain"],
        "editorial_type": [" Devotional ", "article", "devotional", None],
        "recommendation_contexts": [["old"]] * 4,
    })


def test_captured_filters_apply_before_training_and_replace_old_memberships():
    context = {"type": "reading", "contentFilter": {"metadata": {
        "includedContentTypePrefixes": ["text/"],
        "includedAttributeTypes": ["devotional"],
    }}}
    source = content()
    result = apply_context(source, context)
    assert result.content_id.tolist() == ["devotional"]
    assert result.recommendation_contexts.tolist() == [["reading"]]
    assert source.recommendation_contexts.tolist() == [["old"]] * 4


def test_include_lists_take_precedence_and_missing_type_survives_exclusion_only():
    context = {"type": "reading", "contentFilter": {"metadata": {
        "includedContentTypePrefixes": ["text/"], "excludedContentTypePrefixes": ["text/"],
        "excludedAttributeTypes": ["devotional"],
    }}}
    assert apply_context(content(), context).content_id.tolist() == ["article", "missing"]


def test_zero_preference_is_preserved_without_changing_eligibility():
    context = {"type": "reading", "weights": {"typePreferences": [{"type": "Devotional", "weight": 0}]}}
    config = {"context": context, "weights": {"content": 0}}
    captured = configure_context(config)
    assert captured["weights"]["typePreferences"] == [{"type": "devotional", "weight": 0}]
    assert captured["weights"]["content"] == 1
    assert captured["preserve_serving_facets"] is False
    assert apply_context(content(), context).content_id.tolist() == ["devotional", "article", "missing"]
    assert config["weights"] == {"content": 0}


def test_export_registration_uses_the_training_connection_and_exact_snapshot():
    config = {"context": {"type": "test"}, "model_version": 17,
              "bosca_url": "http://localhost:8080", "bosca_token": "local-test-token"}
    with patch("trainer.contexts._bosca_graphql", return_value={
        "recommendation": {"contexts": {"modelExported": True}},
    }) as graphql:
        report_export(config, False)
    url, token, query, variables = graphql.call_args.args
    assert url == "http://localhost:8080"
    assert token == "local-test-token"
    assert "modelExported" in query
    assert variables == {"version": 17, "personalized": False}


@pytest.mark.parametrize("response", [{}, {"recommendation": {"contexts": {"modelExported": False}}}])
def test_unsuccessful_export_registration_is_a_failure(response):
    with patch("trainer.contexts._bosca_graphql", return_value=response):
        with pytest.raises(RuntimeError, match="registration did not succeed"):
            report_export({"context": {}, "bosca_url": "http://localhost:8080", "model_version": 17}, False)


def test_non_context_training_does_not_register_a_context_export():
    with patch("trainer.contexts._bosca_graphql") as graphql:
        report_export({}, False)
    graphql.assert_not_called()


@pytest.mark.parametrize("personalized", [False, True])
def test_pipeline_registers_only_the_filtered_matching_exports(personalized):
    from trainer.pipeline import run_training
    config = {"context": {"type": "reading", "contentFilter": {"metadata": {
        "includedAttributeTypes": ["devotional"], "includedContentTypePrefixes": ["text/"],
    }}}, "model_version": 17, "bosca_url": "http://localhost:8080", "min_interactions": 0 if personalized else 100}
    empty = pd.DataFrame()
    frames = (empty, pd.DataFrame(columns=["content_id"]), content(), empty, empty, empty)
    with patch("trainer.pipeline.load_data_from_bosca", return_value=frames), \
         patch("trainer.pipeline.load_behavior_from_bosca", return_value=empty), \
         patch("trainer.pipeline.run_content_only_training", return_value={"status": "completed_content_only", "model_version": 17}), \
         patch("trainer.pipeline._train_personalized", return_value={"status": "completed", "model_version": 17}), \
         patch("trainer.pipeline.report_export") as report:
        run_training(config)
    reported_config, learned = report.call_args.args
    assert reported_config["model_version"] == 17
    assert reported_config["bosca_url"] == "http://localhost:8080"
    assert learned is personalized


@pytest.mark.parametrize("content_result,personalized_result", [
    ({"status": "rejected"}, {"status": "completed", "model_version": 17}),
    ({"status": "completed_content_only", "model_version": 17}, {"status": "rejected"}),
    ({"status": "completed_content_only", "model_version": 18}, {"status": "completed", "model_version": 17}),
    ({"status": "completed_content_only", "model_version": 17}, {"status": "completed", "model_version": 18}),
])
def test_pipeline_does_not_register_rejected_or_mismatched_exports(content_result, personalized_result):
    from trainer.pipeline import run_training
    empty = pd.DataFrame()
    with patch("trainer.pipeline.load_data_from_bosca", return_value=(empty, pd.DataFrame(columns=["content_id"]), content(), empty, empty, empty)), \
         patch("trainer.pipeline.load_behavior_from_bosca", return_value=empty), \
         patch("trainer.pipeline.run_content_only_training", return_value=content_result), \
         patch("trainer.pipeline._train_personalized", return_value=personalized_result), \
         patch("trainer.pipeline.report_export") as report:
        with pytest.raises(RuntimeError):
            run_training({"context": {"type": "reading"}, "model_version": 17,
                          "bosca_url": "http://localhost:8080", "min_interactions": 0})
    report.assert_not_called()
