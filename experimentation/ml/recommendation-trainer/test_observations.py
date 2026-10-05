"""Analytics payload semantics, attribution, and training-label regressions."""

import numpy as np
import pandas as pd
import pytest

from trainer.datasets import prepare_datasets
from trainer.observations import prepare_observations

AS_OF = pd.Timestamp("2026-09-05T12:00:00Z")


@pytest.mark.parametrize("context_type,expected", [(" Reading ", 1), (None, 0)])
def test_dataset_sources_require_actual_matching_context_and_a_known_distinct_item(context_type, expected):
    content = pd.DataFrame(dict(content_id=["a", "b"], content_type=["text/plain"] * 2,
                                language_tag=["en"] * 2, labels=[[], []]))
    cases = [("reading", "b"), ("other", "b"), ("", "b"), ("reading", "unknown"),
             ("reading", "a"), ("reading", "")]
    events = [event(str(i), recommendation_context=context, recommendation_source_id=source,
                    recommendation_request_id=str(i)) for i, (context, source) in enumerate(cases)]
    retrieval, ranking, _, _, vocabs = prepare_datasets(
        pd.DataFrame(events), content, pd.DataFrame(columns=["content_id", "category_id"]),
        as_of=AS_OF, context_type=context_type,
    )
    rows = list(ranking.as_numpy_iterator())
    assert len(rows) == len(cases)  # Unattributed observations still teach general preferences.
    assert sum(row["source_id"] == b"b" for row in rows) == expected
    assert sum(bool(row["source_id"]) for row in rows) == expected
    assert len(list(retrieval.as_numpy_iterator())) == len(cases)
    assert vocabs["training_data"]["source_attributed_rows"] == expected


def event(key="e", content="a", minutes=90, **fields):
    return dict(user_id="u", content_id=content, interaction_type="Interaction", element_type="click",
                view_percent=None, event_id=key, app_id="app", session_id="session", page_id="/article",
                interaction_created=(AS_OF - pd.Timedelta(minutes=minutes)).isoformat(), **fields)


def prepare(events, feedback=None, **kwargs):
    return prepare_observations(pd.DataFrame(events), pd.DataFrame(feedback) if feedback else None,
                                ["a", "b", "c"], as_of=AS_OF, **kwargs)


def changed(row, **fields):
    return dict(row, **fields)


def test_missing_consumption_uses_event_meaning_and_actual_zero_stays_zero():
    rows, report = prepare([
        changed(event(content="a"), interaction_type="Impression", element_type="page"),
        event("click", "b"),
        changed(event("complete", "c"), interaction_type="Completion"),
    ])
    assert dict(zip(rows.content_id, rows.label)) == {"a": .5, "b": .75, "c": 1.0}
    assert rows.retrieval_positive.all()
    assert report["missing_view_percent"] == 3
    zero, _ = prepare([changed(event(), view_percent=0)])
    assert zero.label.tolist() == [0]
    assert not zero.retrieval_positive.any()


def test_percent_units_do_not_depend_on_other_events():
    one, _ = prepare([changed(event(), view_percent=1)])
    mixed, _ = prepare([changed(event(), view_percent=1), changed(event("other", "b"), view_percent=90)])
    assert one.label.iloc[0] == pytest.approx(.01)
    assert mixed.set_index("content_id").loc["a", "label"] == pytest.approx(.01)


@pytest.mark.parametrize("percent", [-1, 101, "invalid", float("inf")])
def test_invalid_consumption_is_reported_and_uses_observed_event_meaning(percent):
    rows, report = prepare([changed(event(), view_percent=percent)])
    assert rows.label.tolist() == [.75]
    assert report["invalid_view_percent"] == 1


def test_page_and_scroll_are_one_observation_and_duplicate_delivery_does_not_add_weight():
    page = changed(event("page"), interaction_type="Impression", element_type="page")
    scroll = changed(event("scroll", None, 89), element_type="scroll_depth", depth_percent=25)
    maximum = changed(event("max", None, 88), element_type="scroll_max_depth", depth_percent=90)
    rows, report = prepare([page, page, scroll, maximum])
    assert len(rows) == 1
    assert rows.label.iloc[0] == pytest.approx(.9)
    assert report["duplicate_event_rows"] == 1
    assert report["joined_depth_events"] == 2
    assert rows.sample_weight.iloc[0] < .25


def test_conflicting_replays_are_reported_instead_of_choosing_an_arbitrary_label():
    rows, report = prepare([changed(event(), view_percent=0), changed(event(), view_percent=100)])
    assert rows.empty
    assert report["conflicting_event_rows"] == 2


def test_real_repeat_visit_survives_and_depth_does_not_cross_visits():
    first = changed(event("first", minutes=90), interaction_type="Impression", element_type="page")
    second = changed(event("second", minutes=85), interaction_type="Impression", element_type="page")
    rows, _ = prepare([first, changed(event("depth", None, 89), element_type="scroll_depth", depth_percent=90), second])
    assert rows.label.tolist() == pytest.approx([.9, .5])


def test_long_page_visit_keeps_its_content_binding_for_reading_quality():
    page = changed(event("page", minutes=120), interaction_type="Impression", element_type="page")
    depth = changed(event("depth", None, 30), element_type="scroll_max_depth", depth_percent=95)
    rows, report = prepare([page, depth])
    assert len(rows) == 1
    assert rows.label.iloc[0] == pytest.approx(.95)
    assert report["joined_depth_events"] == 1


@pytest.mark.parametrize("other_content,expected", [("b", [.5, .5]), ("outside-catalog", [.5])])
def test_ambiguous_page_bindings_do_not_assign_scroll_to_a_card(other_content, expected):
    from trainer.pipeline import _filter_interactions_to_content

    page = changed(event("page"), interaction_type="Impression", element_type="page")
    events = pd.DataFrame([page, changed(page, content_id=other_content),
                          changed(event("depth", None, 89), element_type="scroll_depth", depth_percent=100)])
    filtered = _filter_interactions_to_content(events, pd.DataFrame({"content_id": ["a", "b", "c"]}))
    rows, report = prepare_observations(filtered, None, ["a", "b", "c"], as_of=AS_OF)
    assert rows.label.tolist() == expected
    assert report["unattributed_depth_events"] == 1


def test_ineligible_page_still_separates_visits_before_scroll_attribution():
    from trainer.pipeline import _filter_interactions_to_content

    first = changed(event("first", minutes=90), interaction_type="Impression", element_type="page")
    second = changed(event("second", "outside-catalog", 89), interaction_type="Impression", element_type="page")
    depth = changed(event("depth", None, 88), element_type="scroll_depth", depth_percent=90)
    filtered = _filter_interactions_to_content(pd.DataFrame([first, second, depth]), pd.DataFrame({"content_id": ["a"]}))
    rows, report = prepare_observations(filtered, None, ["a"], as_of=AS_OF)
    assert rows.content_id.tolist() == ["a"]
    assert rows.label.tolist() == [.5]
    assert report["unattributed_depth_events"] == 1


def exposure(key="seen", content="a", minutes=90, **fields):
    return changed(event(key, content, minutes), interaction_type="Impression", element_type="article",
                   visible_ms=1000, visibility_threshold=.5, **fields)


def test_qualified_ignored_impression_is_a_weak_negative():
    rows, report = prepare([exposure()])
    assert rows.label.tolist() == [0]
    assert not rows.retrieval_positive.any()
    assert 0 < rows.sample_weight.iloc[0] < .1
    assert report["ignored_exposures"] == 1


@pytest.mark.parametrize("fields,counter", [
    ({"visible_ms": 999}, "qualified_impressions"),
    ({"visibility_threshold": .49}, "qualified_impressions"),
    ({"visibility_threshold": None}, "qualified_impressions"),
    ({"session_id": None}, "unattributable_impressions"),
    ({"page_id": None}, "unattributable_impressions"),
    ({"interaction_created": (AS_OF - pd.Timedelta(minutes=10)).isoformat()}, "pending_impressions"),
])
def test_unqualified_uncorrelated_or_recent_impressions_are_not_negative_labels(fields, counter):
    rows, report = prepare([changed(exposure(), **fields)])
    assert rows.empty
    assert report[counter] == (0 if counter == "qualified_impressions" else 1)


def test_click_after_navigating_from_feed_explains_impression():
    rows, report = prepare([changed(exposure(), page_id="/feed"), event("click", minutes=89)])
    assert len(rows) == 1
    assert rows.retrieval_positive.all()
    assert report["engaged_impressions"] == 1


def test_engagement_during_the_recorded_dwell_period_explains_impression():
    clicked = changed(event("click"), interaction_created="2026-09-05T10:29:59.500Z")
    rows, report = prepare([changed(exposure(), page_id="/feed"), clicked])
    assert len(rows) == 1
    assert rows.label.iloc[0] == .75
    assert report["engaged_impressions"] == 1


@pytest.mark.parametrize("field", ["session_id", "app_id", "user_id"])
def test_engagement_in_another_context_does_not_explain_an_impression(field):
    rows, _ = prepare([exposure(), changed(event("click", minutes=89), **{field: "different"})])
    assert sorted(rows.label) == [0, .75]


def test_missing_correlation_preserves_individual_engagements():
    rows, _ = prepare([changed(event("first"), session_id=None), changed(event("second"), session_id=None)])
    assert len(rows) == 2


def test_recent_observation_has_twice_the_weight_after_one_half_life():
    rows, _ = prepare([event("new", "a", 0), event("old", "b", 30 * 24 * 60)])
    weights = rows.set_index("content_id").sample_weight
    assert weights["a"] == pytest.approx(2 * weights["b"])


def guide_state(key, content, kind, minutes=0):
    return changed(event(key, content, minutes), interaction_type="Completion", element_type=kind,
                   view_percent=None, app_id="bosca-guide-state", session_id=None, page_id=None)


@pytest.mark.parametrize("app_id", ["bosca-guide-state", "bosca-guide-completions"])
def test_guide_and_step_state_are_positive_with_stronger_full_guide_weight(app_id):
    rows, report = prepare([changed(row, app_id=app_id) for row in [
        guide_state("step", "a", "guide_step"),
        guide_state("progress", "b", "guide_progress"),
        guide_state("complete", "c", "guide"),
    ]])
    assert rows.label.tolist() == [1, 1, 1]
    assert rows.retrieval_positive.all()
    assert rows.set_index("content_id").sample_weight.to_dict() == {"a": 1, "b": 1, "c": 2}
    assert report["guide_step_completions"] == 1
    assert report["guide_progress_observations"] == 1
    assert report["guide_completions"] == 1
    assert report["explicit_feedback_rows"] == 0


def test_guide_state_filters_ineligible_content_and_deduplicates_repeated_rows():
    step = guide_state("step", "a", "guide_step")
    rows, report = prepare([step, step, guide_state("outside", "outside-catalog", "guide")])
    assert len(rows) == 1
    assert report["duplicate_event_rows"] == 1
    assert report["guide_completions"] == 0
    assert report["guide_step_completions"] == 1


def test_guide_weights_reach_both_training_stages_and_feedback_keeps_precedence(caplog):
    content = pd.DataFrame(dict(content_id=["a", "b", "c"], content_type=["guide"] * 3,
                                language_tag=["en"] * 3, labels=[[], [], []]))
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    feedback = pd.DataFrame([dict(user_id="u", content_id="c", feedback_label=0,
                                 feedback_source="dismissal", feedback_created=AS_OF.isoformat())])
    with caplog.at_level("INFO", logger="trainer.datasets"):
        retrieval, ranking, _, _, vocabs = prepare_datasets(pd.DataFrame([
            guide_state("step", "a", "guide_step", 30 * 24 * 60),
            guide_state("guide", "b", "guide", 30 * 24 * 60),
            guide_state("rejected-guide", "c", "guide"),
        ]), content, categories, feedback, as_of=AS_OF)
    positives = {row["content_id"]: row for row in retrieval.as_numpy_iterator()}
    ranks = {row["content_id"]: row for row in ranking.as_numpy_iterator()}
    assert set(positives) == {b"a", b"b"}
    assert positives[b"a"]["sample_weight"] == ranks[b"a"]["sample_weight"] == .5
    assert positives[b"b"]["sample_weight"] == ranks[b"b"]["sample_weight"] == 1
    assert ranks[b"c"]["label"] == 0
    assert ranks[b"c"]["sample_weight"] == 5
    assert vocabs["training_data"]["explicit_feedback_rows"] == 1
    assert "3 ranking (1 explicit feedback), 2 retrieval positives" in caplog.text


def test_completion_weight_is_not_logged_as_explicit_feedback(caplog):
    content = pd.DataFrame(dict(content_id=["a"], content_type=["guide"], language_tag=["en"], labels=[[]]))
    with caplog.at_level("INFO", logger="trainer.datasets"):
        prepare_datasets(pd.DataFrame([guide_state("complete", "a", "guide")]), content,
                         pd.DataFrame(columns=["content_id", "category_id"]), as_of=AS_OF)
    assert "1 ranking (0 explicit feedback), 1 retrieval positives" in caplog.text


def test_latest_explicit_rating_replaces_history_and_dismissal_overrides_it():
    feedback = [dict(user_id="u", content_id="a", feedback_label=.25, feedback_source="rating",
                     feedback_created="2026-09-01T12:00:00Z"),
                dict(user_id="u", content_id="a", feedback_label=1, feedback_source="rating",
                     feedback_created="2026-09-04T12:00:00Z")]
    rows, report = prepare([event()], feedback)
    assert rows.label.tolist() == [1]
    assert rows.sample_weight.iloc[0] > 4
    assert report["explicit_feedback_rows"] == 1
    feedback.append(dict(user_id="u", content_id="a", feedback_label=0, feedback_source="dismissal"))
    rows, _ = prepare([event()], feedback)
    assert rows.label.tolist() == [0]
    assert not rows.retrieval_positive.any()


def test_rejected_content_never_builds_positive_affinity_and_weights_reach_retrieval():
    content = pd.DataFrame(dict(content_id=["a", "b"], content_type=["article"] * 2,
                                language_tag=["en"] * 2, labels=[[], []]))
    categories = pd.DataFrame(dict(content_id=["a", "b"], category_id=["liked", "disliked"]))
    feedback = pd.DataFrame([dict(user_id="u", content_id="b", feedback_label=.25, feedback_source="rating")])
    retrieval, ranking, _, users, vocabs = prepare_datasets(
        pd.DataFrame([changed(event(), view_percent=50), event("bad", "b")]), content, categories,
        feedback, as_of=AS_OF)
    positive = list(retrieval.as_numpy_iterator())
    assert [row["content_id"] for row in positive] == [b"a"]
    ranks = {row["content_id"]: row for row in ranking.as_numpy_iterator()}
    assert positive[0]["sample_weight"] == pytest.approx(ranks[b"a"]["sample_weight"] * .5)
    affinity = next(users.as_numpy_iterator())["user_category_affinity"]
    assert affinity[vocabs["category_ids"].index("disliked")] == 0
    assert affinity[vocabs["category_ids"].index("liked")] == 1


def test_empty_unsupported_unidentified_and_future_events_are_reported():
    rows, report = prepare([changed(event(), interaction_type="Heartbeat"),
                            changed(event("anon"), user_id=None), event("future", minutes=-1)])
    assert rows.empty
    assert report["unsupported_events"] == 1
    assert report["unidentified_events"] == 1
    assert report["future_events"] == 1
    rows, report = prepare([])
    assert rows.empty
    assert report["raw_events"] == 0


def test_empty_prepared_observations_keep_valid_tensor_shapes_and_eligible_users():
    content = pd.DataFrame(dict(content_id=["a"], content_type=["article"], language_tag=["en"], labels=[[]]))
    categories = pd.DataFrame(dict(content_id=["a"], category_id=["category"]))
    retrieval, ranking, _, users, _ = prepare_datasets(
        pd.DataFrame([changed(exposure(), visibility_threshold=None)]), content, categories,
        users=pd.DataFrame({"user_id": ["u"]}), as_of=AS_OF)
    assert int(retrieval.cardinality()) == 0
    assert int(ranking.cardinality()) == 0
    assert ranking.element_spec["category_multi_hot"].shape == (1,)
    np.testing.assert_array_equal(next(users.as_numpy_iterator())["user_category_affinity"], [0])


@pytest.mark.parametrize("kwargs", [{"half_life_days": 0}, {"half_life_days": float("nan")},
                                   {"attribution_minutes": -1}])
def test_invalid_policy_configuration_fails(kwargs):
    with pytest.raises(ValueError):
        prepare([event()], **kwargs)


@pytest.mark.parametrize("timestamp", ["2026-09-05T12:00:00", pd.NaT])
def test_snapshot_requires_a_real_time_with_timezone(timestamp):
    with pytest.raises(ValueError, match="timezone"):
        prepare_observations(pd.DataFrame([event()]), None, ["a"], as_of=timestamp)


def test_recommendation_requests_keep_actual_source_context_and_version_separate():
    rows, _ = prepare([
        event("first", recommendation_source_id="b", recommendation_context=" Reading ",
              recommendation_model_version="17", recommendation_request_id="r1"),
        event("second", recommendation_source_id="c", recommendation_context="explore",
              recommendation_model_version="18", recommendation_request_id="r2"),
        event("historical"),
    ])
    assert len(rows) == 3
    by_request = rows.set_index("recommendation_request_id")
    assert by_request.loc["r1", "recommendation_source_id"] == "b"
    assert by_request.loc["r1", "recommendation_context"] == "reading"
    assert by_request.loc["r1", "recommendation_model_version"] == "17"
    assert by_request.loc["r2", "recommendation_source_id"] == "c"
    assert by_request.loc["r2", "recommendation_context"] == "explore"
    assert by_request.loc["", "recommendation_source_id"] == ""
    assert by_request.loc["", "recommendation_context"] == ""
    assert rows.label.tolist() == pytest.approx([.75] * 3)


def test_conflicting_replay_cannot_change_recommendation_attribution():
    original = event(recommendation_context="reading", recommendation_source_id="b")
    rows, report = prepare([original, changed(original, recommendation_context="other")])
    assert rows.empty
    assert report["conflicting_event_rows"] == 2


def test_explicit_feedback_does_not_inherit_an_unrecorded_recommendation_context():
    rows, _ = prepare([
        event(recommendation_context="reading", recommendation_source_id="b"),
    ], feedback=[dict(user_id="u", content_id="a", feedback_source="rating", feedback_label=.8,
                      feedback_created=(AS_OF - pd.Timedelta(minutes=5)).isoformat())])
    assert len(rows) == 1
    assert rows.recommendation_context.tolist() == [""]
    assert rows.recommendation_source_id.tolist() == [""]
    assert rows.label.tolist() == pytest.approx([.8])
