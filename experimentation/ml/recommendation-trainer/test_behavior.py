import numpy as np
import pandas as pd
import tensorflow as tf
from trainer import behavior

from trainer.behavior import BehaviorFeatures, training_features, read_snapshot, write_snapshot, capture_snapshot
from trainer.content_similarity import ContentSimilarityIndex
from trainer.models import BoscaRanker, RankingModel, UserModel, ContentModel


def similarity():
    content = pd.DataFrame(dict(content_id=["source", "a", "b"], content_type=["text"] * 3,
                                language_tag=["en"] * 3, labels=[[]] * 3,
                                embedding=[[1., 0.], [1., 0.], [0., 1.]]))
    weights = {"similarity": {key: float(key == "semantic") for key in
               ("semantic", "categories", "labels", "language", "mime", "type", "collections")}}
    return ContentSimilarityIndex(content, pd.DataFrame(columns=["content_id", "category_id"]),
                                  ["reading\x1fen"], [tf.range(3)], 3, weights)


def snapshot():
    return dict(available_at="2026-09-01T00:00:00Z", edges=[
        dict(kind="global", source_id="source", content_id="a", cohort_key="", score=10.),
        dict(kind="cohort", source_id="source", content_id="a", cohort_key="one", score=10.),
        dict(kind="cohort", source_id="source", content_id="a", cohort_key="two", score=30.),
    ], memberships=[dict(user_id="u", cohort_key="one"), dict(user_id="u", cohort_key="two")],
       neighbors=[["u", "a", .4]], ratings=[["u", "source", 1.]])


def test_behavior_inputs_are_sparse_and_survive_saved_model_round_trip(tmp_path):
    module = BehaviorFeatures(snapshot(), similarity())
    @tf.function(input_signature=[tf.TensorSpec([None], tf.string), tf.TensorSpec([None], tf.string),
                                  tf.TensorSpec([None], tf.string)])
    def serve(users, sources, candidates):
        return {"features": module(users, sources, candidates)}
    tf.saved_model.save(module, str(tmp_path), signatures={"serving_default": serve})
    loaded = tf.saved_model.load(str(tmp_path)).signatures["serving_default"]
    result = loaded(users=tf.constant(["u", "missing", "u"]), sources=tf.constant(["source"] * 3),
                    candidates=tf.constant(["a", "a", "b"]))["features"]
    np.testing.assert_allclose(result, [[.5, .75, .4, 1.], [.5, 0., 0., 0.], [0., 0., 0., 0.]])


def test_empty_behavior_has_zero_evidence_for_known_and_unknown_profiles():
    features = BehaviorFeatures(None, similarity())
    np.testing.assert_array_equal(features(tf.constant(["u"]), tf.constant(["source"]), tf.constant(["a"])), [[0.] * 4])


def test_training_cannot_use_snapshot_for_own_or_future_observations(tmp_path):
    write_snapshot(tmp_path, snapshot())
    saved = read_snapshot(tmp_path)
    rows = pd.DataFrame(dict(user_id=["u"] * 4, content_id=["a"] * 4, source_id=["source"] * 4,
                            feature_time=["2026-08-31T23:59:59Z", "2026-09-01T00:00:00Z",
                                          "2026-09-01T00:00:01Z", "2026-09-02T00:00:00Z"]))
    values = training_features(saved, similarity(), rows)
    np.testing.assert_array_equal(values[:2], np.zeros([2, 4]))
    np.testing.assert_allclose(values[2:], [[.5, .75, .4, 1.]] * 2)


def test_behavior_gains_are_learned_and_zero_context_influence_remains_disabled():
    model = RankingModel(UserModel(["u"], 0, 0, 2), ContentModel(["a"], ["text"], ["en"], [], [], 2),
                         {"content": 0., "personalization": 0., "coEngagement": 1.,
                          "cohortCoEngagement": 0., "learnedNeighbor": 0., "rating": 0.})
    def score():
        return model.score_pairs(tf.zeros([1, 2]), tf.zeros([1, 2]), tf.constant(["a"]),
                                 behavior_features=tf.constant([[.5, 1., 1., 1.]]))
    np.testing.assert_allclose(score(), [[.5]])
    task = BoscaRanker(model).task
    optimizer = tf.keras.optimizers.SGD(.5)
    for _ in range(20):
        with tf.GradientTape() as tape:
            loss = task(labels=tf.zeros([1, 1]), predictions=score())
        optimizer.apply_gradients([(tape.gradient(loss, model.behavior_gain), model.behavior_gain)])
    assert float(score()[0, 0]) < 0.
    np.testing.assert_array_equal(model.behavior_gain.numpy()[1:], [1., 1., 1.])


def test_neighbor_features_use_only_supplied_previous_model():
    class Previous:
        signatures = {
            "similar_users": lambda **_: {"content_ids": tf.constant([["u", "neighbor"]]), "scores": tf.constant([[1., .5]])},
            "serving_default": lambda **_: {"content_ids": tf.constant([["a"]]), "scores": tf.constant([[2.]])},
        }
    empty = pd.DataFrame(columns=["kind", "user_id", "source_id", "content_id", "cohort_key", "score"])
    value = capture_snapshot(empty, None, ["u"], "reading", {"a": "en"}, Previous())
    assert value["neighbors"] == [["u", "a", .5]]


def test_neighbor_snapshot_batches_saved_model_calls_and_reuses_predictions(tmp_path, monkeypatch):
    class Previous(tf.Module):
        def __init__(self):
            super().__init__()
            self.nearest_calls = tf.Variable(0)
            self.prediction_rows = tf.Variable(0)

        @tf.function(input_signature=[tf.TensorSpec([None], tf.string)])
        def nearest(self, user_id):
            self.nearest_calls.assign_add(1)
            ids = tf.stack([user_id, tf.fill(tf.shape(user_id), "n0"), tf.fill(tf.shape(user_id), "n1")], axis=1)
            ids = tf.where(user_id[:, None] == "cold", "", ids)
            return {"content_ids": ids, "scores": tf.tile([[1., .5, -.25]], [tf.size(user_id), 1])}

        @tf.function(input_signature=[tf.TensorSpec([None], tf.string), tf.TensorSpec([None], tf.string),
                                      tf.TensorSpec([None], tf.string)])
        def recommend(self, user_id, context_type, language_tag):
            self.prediction_rows.assign_add(tf.size(user_id))
            scores = tf.where(language_tag[:, None] == "en", [[2., -1., 999.]], [[-1., 2., 999.]])
            return {"content_ids": tf.tile([["a", "b", ""]], [tf.size(user_id), 1]), "scores": scores}

    previous = Previous()
    tf.saved_model.save(previous, str(tmp_path), signatures={
        "similar_users": previous.nearest, "serving_default": previous.recommend,
    })
    previous = tf.saved_model.load(str(tmp_path))
    monkeypatch.setattr(behavior, "_PREDICTION_BATCH_SIZE", 2)
    empty = pd.DataFrame(columns=["kind", "user_id", "source_id", "content_id", "cohort_key", "score"])
    captured = capture_snapshot(empty, None, ["u0", "u1", "n0", "cold", "u2"], "reading",
                                {"a": "en", "b": "es"}, previous)
    assert captured["neighbors"] == [
        ["u0", "a", .5], ["u0", "b", .5], ["u1", "a", .5], ["u1", "b", .5],
        ["n0", "a", 0.], ["n0", "b", 0.], ["u2", "a", .5], ["u2", "b", .5],
    ]
    assert previous.nearest_calls.numpy() == 3
    assert previous.prediction_rows.numpy() == 4  # Two unique neighbors, once per language.


def test_neighbor_snapshot_preserves_twenty_neighbor_cap_and_empty_inputs():
    from unittest.mock import Mock
    ids = [f"n{i}" for i in range(21)]
    nearest = Mock(return_value={"content_ids": tf.constant([ids]), "scores": tf.ones([1, 21])})
    recommend = Mock(side_effect=lambda **args: {
        "content_ids": args["user_id"][:, None], "scores": tf.ones([tf.size(args["user_id"]), 1]),
    })
    previous = type("Previous", (), {"signatures": {"similar_users": nearest, "serving_default": recommend}})()
    empty = pd.DataFrame(columns=["kind", "user_id", "source_id", "content_id", "cohort_key", "score"])
    assert capture_snapshot(empty, None, [], "reading", {"a": "en"}, previous)["neighbors"] == []
    nearest.assert_not_called()
    recommend.assert_not_called()
    captured = capture_snapshot(empty, None, ["u"], "reading", {"a": "en"}, previous)
    assert captured["neighbors"] == [["u", neighbor, .5] for neighbor in ids[:20]]


def test_ablation_reports_each_positive_group_and_preserves_negative_effects():
    model = RankingModel(UserModel(["u"], 0, 0, 2), ContentModel(["a"], ["text"], ["en"], [], [], 2),
                         {"content": 1., "personalization": 1., "coEngagement": 1.,
                          "cohortCoEngagement": 1., "learnedNeighbor": 1., "rating": 1.},
                         behavior_model=BehaviorFeatures(snapshot(), similarity()))
    args = (tf.zeros([1, 2]), tf.zeros([1, 2]), tf.constant(["a"]),
            tf.constant(["u"]), tf.constant(["source"]))
    np.testing.assert_allclose(model.explain_pairs(*args), [[.75, 1.4, .5, .75]], atol=1e-6)
    model.behavior_gain.assign([-1., 1., 1., 1.])
    np.testing.assert_allclose(model.explain_pairs(*args), [[.75, 1.4, -.5, .75]], atol=1e-6)
