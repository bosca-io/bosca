"""Attribution arithmetic and controlled synthetic reranking experiments."""

import json

import numpy as np
import pytest

import diagnose_ranking as diagnosis


def test_weighted_error_has_an_explicit_weight_normalized_denominator():
    assert diagnosis.weighted_mse([0, 1], [.9, .9], [1, 9]) == pytest.approx(.09)


def test_rank_change_attribution_distinguishes_observed_and_unsupported_promotions():
    report = diagnosis.compare_orderings(
        ["a", "b", "c", "d"], np.array([[4, 3, 2, 1]]), np.array([[1, 0, 3, 4]]),
        np.array([[False] * 4]), np.array([[True, True, False, False]]),
        np.array([[False, False, True, False]]), k=2, pool_size=4,
    )
    assert report["retrieval_ndcg"] == 1
    assert report["reranked_ndcg"] == 0
    assert report["worsened"] == 1
    assert report["introduced_without_future_positive"] == 2
    assert report["introduced_without_training_observation"] == 1
    assert report["candidate_scores"]["observed_without_future_positive"]["count"] == 1
    assert report["worst_changes"][0]["top_items"][0]["item"] == "d"


def test_empty_relevance_is_excluded_and_missing_score_groups_are_not_fabricated():
    report = diagnosis.compare_orderings(["a"], np.array([[1], [1]]), np.array([[1], [1]]),
                                         np.zeros((2, 1), dtype=bool), np.array([[False], [True]]),
                                         np.zeros((2, 1), dtype=bool))
    assert report["unchanged"] == 1
    assert set(report["candidate_scores"]) == {"future_positive"}
    with pytest.raises(ValueError, match="evaluable"):
        diagnosis.compare_orderings(["a"], np.ones((1, 1)), np.ones((1, 1)), np.ones((1, 1), dtype=bool),
                                    np.ones((1, 1), dtype=bool), np.ones((1, 1), dtype=bool))


def test_broader_exposure_preserves_positive_rows_and_negative_count_without_reading_future_labels():
    users, items = ["u0-00"], [f"c{i:02}" for i in range(32)]
    row_users, row_items, labels = np.zeros(7, dtype=int), np.array([0, 8, 9, 10, 11, 12, 13]), np.array([1, 0, 0, 0, 0, 0, 0])
    changed = diagnosis.broaden_synthetic_exposure(users, items, row_users, row_items, labels)
    assert changed[0] == 0
    np.testing.assert_array_equal(changed[1:] // 8, [1, 2, 3, 1, 2, 3])
    np.testing.assert_array_equal(changed[1:] % 8, row_items[1:] % 8)
    np.testing.assert_array_equal(row_items, [0, 8, 9, 10, 11, 12, 13])


def test_real_probes_keep_retrieval_fixed_and_use_matching_initial_predictions(monkeypatch):
    original = diagnosis.train_model
    captured = {}

    def capture(*args, **kwargs):
        model, ranker = original(*args, **kwargs)
        captured["model"] = model
        captured["weights"] = [value.numpy().copy() for value in model.weights]
        return model, ranker

    monkeypatch.setattr(diagnosis, "train_model", capture)
    result = diagnosis.diagnose_once(epochs=1, probe_epochs=1, long_epochs=2)
    assert result["feature_embedding_score_max_difference"] < 1e-5
    assert result["training"]["unique_pairs"] < result["training"]["rows"]
    assert result["training"]["positive_weight"] / result["training"]["negative_weight"] > 20
    assert len(result["probes"]) == 6
    for probe in result["probes"].values():
        assert probe["retrieval_ndcg"] == result["production_head"]["retrieval_ndcg"]
        assert 0 <= probe["reranked_ndcg"] <= 1
        assert 1 <= probe["epochs_run"] <= probe["max_epochs"]
    assert result["probes"]["concat_broader_exposure"]["fit_unique_pairs"] > result["training"]["unique_pairs"]
    for before, after in zip(captured["weights"], captured["model"].weights):
        np.testing.assert_array_equal(before, after.numpy())


def test_invalid_epoch_limits_fail_before_training():
    with pytest.raises(ValueError, match="positive"):
        diagnosis.diagnose_once(probe_epochs=0)


def test_cli_records_each_prespecified_fixture_and_seed_without_selecting_a_winner(tmp_path, monkeypatch):
    calls = []

    def fake(*args):
        calls.append(args)
        return dict(production_head=dict(reranked_ndcg=.5, retrieval_ndcg=.6), probes={})

    monkeypatch.setattr(diagnosis, "diagnose_once", fake)
    path = tmp_path / "report.json"
    report = diagnosis.main(["--output", str(path), "--fixture-seeds", "173", "307", "--seeds", "11", "29"])
    assert calls == [(173, 11, 25, 25, 100, .01), (173, 29, 25, 25, 100, .01), (307, 11, 25, 25, 100, .01), (307, 29, 25, 25, 100, .01)]
    assert len(report["runs"]) == 4
    assert json.loads(path.read_text()) == report
