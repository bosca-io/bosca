import json

import pytest

import compare_ranking_losses as comparison
from trainer import training
from trainer.models import BoscaRanker


def test_comparison_pairs_identical_inputs_and_restores_production_ranker(tmp_path, monkeypatch):
    calls = []

    def evaluate(bundle, **settings):
        calls.append((json.dumps(bundle, sort_keys=True), settings, training.BoscaRanker))
        return {"metrics": {"personalized_full_catalog": {"all": {"ndcg": .5}}}}

    monkeypatch.setattr(comparison, "evaluate_once", evaluate)
    output = tmp_path / "nested" / "report.json"
    report = comparison.main(["--output", str(output), "--fixture-seeds", "353", "--seeds", "11",
                              "--half-lives", "30", "--epochs", "1", "--learning-rate", ".02"])
    assert len(calls) == 2
    assert calls[0][:2] == calls[1][:2]
    assert calls[0][1]["learning_rate"] == .02
    assert calls[0][2] is comparison._SquaredErrorBaseline
    assert calls[1][2] is BoscaRanker
    assert training.BoscaRanker is BoscaRanker
    assert len(report["runs"][0]["input_sha256"]) == 64
    assert json.loads(output.read_text()) == report


def test_failed_comparison_cannot_leave_squared_error_enabled(tmp_path, monkeypatch):
    def fail(*args, **kwargs):
        assert training.BoscaRanker is comparison._SquaredErrorBaseline
        raise RuntimeError("fit failed")

    monkeypatch.setattr(comparison, "evaluate_once", fail)
    with pytest.raises(RuntimeError, match="fit failed"):
        comparison.compare_losses(tmp_path / "report.json", fixture_seeds=[353], seeds=[11], half_lives=[30])
    assert training.BoscaRanker is BoscaRanker
