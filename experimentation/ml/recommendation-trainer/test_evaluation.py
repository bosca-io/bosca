"""Chronological isolation, metric correctness, and actual local model evaluation."""

import copy
import json

import numpy as np
import pandas as pd
import pytest

import evaluate


def test_metrics_match_hand_computed_ranking_and_do_not_reward_duplicates():
    metrics = evaluate.ranking_metrics(["a", "x", "b"], {"a", "b", "c"}, 3)
    assert metrics["recall"] == pytest.approx(2 / 3)
    assert metrics["ndcg"] == pytest.approx(1.5 / (1 + 1 / np.log2(3) + .5))
    assert metrics["mrr"] == 1
    assert evaluate.ranking_metrics([], {"a"}, 5) == dict(recall=0, ndcg=0, mrr=0, hit_rate=0)
    assert evaluate.ranking_metrics(["x", "a"], {"a"}, 2)["mrr"] == .5
    with pytest.raises(ValueError, match="Duplicate"):
        evaluate.ranking_metrics(["a", "a"], {"a"}, 2)
    for k, relevant in [(0, {"a"}), (1, set())]:
        with pytest.raises(ValueError, match="Metrics require"):
            evaluate.ranking_metrics([], relevant, k)


def test_score_ordering_uses_equal_candidate_pool_and_stable_ties():
    assert evaluate._ordered(["b", "a", "c"], [1, 1, 99], {"a", "b"}, 2) == [1, 0]
    with pytest.raises(ValueError, match="non-finite"):
        evaluate._ordered(["a"], [np.nan], {"a"}, 1)


def test_future_events_feedback_and_end_boundary_cannot_change_training_history():
    bundle = evaluate.synthetic_history()
    original = evaluate.split_history(bundle)
    changed = copy.deepcopy(bundle)
    changed["interactions"].append(dict(bundle["interactions"][0], event_id="later", interaction_created="2026-10-01T00:00:00Z"))
    changed["feedback"] = [dict(user_id="u0-00", content_id="c00", feedback_label=0, feedback_source="dismissal",
                                feedback_created="2026-09-02T00:00:00Z")]
    partitions = evaluate.split_history(changed)
    pd.testing.assert_frame_equal(original["past"], partitions["past"])
    pd.testing.assert_frame_equal(original["feedback"], partitions["feedback"], check_dtype=False)
    assert len(partitions["future_feedback"]) == 1
    assert partitions["excluded_after_end"] == 1
    assert (pd.to_datetime(partitions["future"].interaction_created, format="mixed", utc=True) > partitions["cutoff"]).all()
    past_pairs = set(zip(partitions["past"].user_id, partitions["past"].content_id))
    future_pairs = set(zip(partitions["future"].user_id, partitions["future"].content_id))
    assert past_pairs.isdisjoint(future_pairs)


@pytest.mark.parametrize("change,message", [
    ({"snapshot_at": "2026-09-01"}, "timezone"),
    ({"snapshot_at": "NaT"}, "timezone"),
    ({"evaluation_end": "2026-08-01T00:00:00Z"}, "after"),
    ({"users": []}, "nonempty"),
    ({"content": [{"content_id": "same"}, {"content_id": "same"}]}, "unique"),
])
def test_invalid_snapshot_fails_loudly(change, message):
    with pytest.raises(ValueError, match=message):
        evaluate.split_history(dict(evaluate.synthetic_history(), **change))


def test_unorderable_events_are_rejected_and_unknown_profiles_do_not_enter_training():
    bundle = evaluate.synthetic_history()
    bundle["interactions"][0]["interaction_created"] = "invalid"
    with pytest.raises(ValueError, match="valid timestamp"):
        evaluate.split_history(bundle)
    bundle = evaluate.synthetic_history()
    bundle["interactions"][0]["user_id"] = "not-in-snapshot"
    data = evaluate.split_history(bundle)
    assert data["excluded_unknown_training_users"] == 1
    assert "not-in-snapshot" not in set(data["past"].user_id)


def test_future_clicks_are_not_ground_truth_and_explicit_rejection_overrides_completion():
    data = evaluate.split_history(evaluate.synthetic_history())
    completion = data["future"].iloc[0].copy()
    data["future"] = pd.DataFrame([dict(completion), dict(completion, content_id="c31", interaction_type="Interaction")])
    data["future_feedback"] = pd.DataFrame([dict(user_id=completion.user_id, content_id=completion.content_id,
                                               feedback_label=0, feedback_source="dismissal", feedback_created="2026-09-03T00:00:00Z")])
    assert evaluate._targets(data).empty


def test_real_evaluation_uses_only_past_observations_and_equal_candidate_pools(monkeypatch):
    bundle = evaluate.synthetic_history()
    bundle["users"][0].pop("scenario")
    future_event = next(row for row in bundle["interactions"] if row["interaction_created"] > bundle["snapshot_at"])
    bundle["interactions"].append(dict(future_event, user_id="profile-created-after-cutoff", event_id="future-profile"))
    partitions = evaluate.split_history(bundle)
    future_pairs = set(zip(partitions["future"].user_id, partitions["future"].content_id))
    original = evaluate.train_model
    trained_pairs = set()

    def inspect(retrieval, ranking, *args, **kwargs):
        trained_pairs.update((row["user_id"].decode(), row["content_id"].decode()) for row in ranking.as_numpy_iterator())
        return original(retrieval, ranking, *args, **kwargs)

    monkeypatch.setattr(evaluate, "train_model", inspect)
    report = evaluate.evaluate_once(bundle, epochs=1)
    assert trained_pairs and trained_pairs.isdisjoint(future_pairs)
    assert report["evaluated_users"] == len(bundle["users"])
    assert report["future_positive_users"] == len(bundle["users"]) + 1
    assert report["future_positive_pairs"] == report["evaluated_positive_pairs"] + 1
    assert report["training_data"]["ignored_exposures"] > 0
    assert report["training_data"]["missing_view_percent"] == len(partitions["past"])
    for groups in report["metrics"].values():
        assert groups["cold"]["users"] == 8
        assert groups["sparse"]["users"] == 4
        assert groups["scenario:changed"]["users"] == 4
        for metrics in groups.values():
            for key in ["recall", "ndcg", "mrr", "hit_rate", "catalog_coverage"]:
                assert 0 <= metrics[key] <= 1
    assert report["metrics"]["content_history"]["cold"] == report["metrics"]["popularity"]["cold"]


@pytest.mark.parametrize("kwargs,message", [
    ({"k": 0}, "Require"), ({"retrieval_candidates": 1}, "Require"),
    ({"epochs": 0}, "Require"), ({"language": "missing"}, "No candidates"),
])
def test_invalid_evaluation_request_is_rejected_before_training(kwargs, message):
    with pytest.raises(ValueError, match=message):
        evaluate.evaluate_once(evaluate.synthetic_history(), **kwargs)


def test_empty_training_or_future_signal_is_not_reported_as_a_success():
    bundle = evaluate.synthetic_history()
    bundle["interactions"] = [row for row in bundle["interactions"] if row["interaction_created"] > bundle["snapshot_at"]]
    with pytest.raises(ValueError, match="positive training"):
        evaluate.evaluate_once(bundle, epochs=1)
    bundle = evaluate.synthetic_history()
    bundle["interactions"] = [row for row in bundle["interactions"] if row["interaction_created"] <= bundle["snapshot_at"]]
    with pytest.raises(ValueError, match="No evaluable"):
        evaluate.evaluate_once(bundle, epochs=1)


def test_feedback_only_history_can_be_evaluated_without_analytics_rows():
    bundle = evaluate.synthetic_history()
    ids = {"u0-00", "u2-00"}
    bundle["users"] = [user for user in bundle["users"] if user["user_id"] in ids]
    bundle["signals"] = [signal for signal in bundle["signals"] if signal["user_id"] in ids]
    bundle["interactions"] = []
    bundle["feedback"] = [
        dict(user_id=user, content_id=item, feedback_label=1.0, feedback_source="rating", feedback_created=created)
        for user, past, future in [("u0-00", "c00", "c01"), ("u2-00", "c16", "c17")]
        for item, created in [(past, "2026-08-31T00:00:00Z"), (future, "2026-09-02T00:00:00Z")]
    ]
    result = evaluate.evaluate_once(bundle, epochs=1)
    assert result["training_events"] == 0
    assert result["training_data"]["explicit_feedback_rows"] == 2
    assert result["evaluated_users"] == 2
    assert result["evaluated_positive_pairs"] == 2


def test_full_catalog_matches_reranking_when_retrieval_includes_every_candidate():
    bundle = evaluate.synthetic_history()
    result = evaluate.evaluate_once(bundle, epochs=1, retrieval_candidates=len(bundle["content"]))
    assert result["metrics"]["personalized_full_catalog"] == result["metrics"]["personalized_reranked"]


@pytest.mark.parametrize("local_export", [False, True])
def test_cli_writes_source_provenance_and_each_requested_run(tmp_path, monkeypatch, local_export):
    calls = []

    def inspect(bundle, **kwargs):
        calls.append(kwargs)
        return dict(metrics={"example": {"all": dict(users=7, recall=.2, ndcg=kwargs["seed"] / 4,
                                                     mrr=.5, hit_rate=1, catalog_coverage=.5)}},
                    recency_half_life_days=kwargs["half_life_days"], **kwargs)

    monkeypatch.setattr(evaluate, "evaluate_once", inspect)
    output = tmp_path / "results" / "report.json"
    argv = ["--output", str(output), "--seeds", "1", "2", "--half-lives", "30", "365"]
    if local_export:
        path = tmp_path / "history.json"
        path.write_text(json.dumps(dict(evaluate.synthetic_history(), source="local_export")))
        argv += ["--input", str(path)]
    report = evaluate.main(argv)
    assert json.loads(output.read_text()) == report
    assert len(report["input_sha256"]) == 64
    assert report["source"] == ("local_export" if local_export else "synthetic")
    assert len(calls) == 4
    summary = report["summary"]["30.0"]["example"]["all"]
    assert summary["users"] == 7
    assert summary["fits"] == 2
    assert summary["ndcg"] == {"mean": .375, "std": .125}
