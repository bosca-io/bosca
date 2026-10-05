"""Regression tests for training dynamics, gradients, and reproducibility."""

import random

import numpy as np
import pandas as pd
import pytest
import tensorflow as tf

from trainer.datasets import prepare_datasets
from trainer.models import BoscaRanker, BoscaRecommender, ContentModel, RankingModel, UserModel
from trainer.training import _fit_to_convergence, train_model


@pytest.fixture(params=[False, True], ids=["eager", "graph"])
def graph_mode(request):
    previous = tf.config.functions_run_eagerly()
    tf.config.run_functions_eagerly(not request.param)
    random.seed(42)
    np.random.seed(42)
    tf.random.set_seed(42)
    yield request.param
    tf.config.run_functions_eagerly(previous)


def _data():
    # Deliberately non-numeric IDs and reverse catalog order exercise ID-based retrieval metrics.
    content = {
        "content_id": tf.constant(["z", "a"]),
        "content_type": tf.constant(["article"] * 2),
        "language_tag": tf.constant(["en"] * 2),
        "category_multi_hot": tf.constant([[1.0, 0.0], [0.0, 1.0]]),
    }
    features = dict(content, user_id=tf.constant(["u0", "u1"]),
                    user_signal_multi_hot=tf.eye(2), user_category_affinity=tf.eye(2),
                    label=tf.constant([0.9, 0.2]), sample_weight=tf.ones(2))
    vocabs = {
        "user_ids": ["u0", "u1"], "content_ids": ["a", "z"],
        "content_types": ["article"], "languages": ["en"],
        "category_ids": ["A", "B"], "signal_tokens": ["a", "b"],
    }
    return features, tf.data.Dataset.from_tensor_slices(content), vocabs


def test_editorial_types_and_sparse_collections_train_with_variable_memberships(graph_mode):
    content = {
        "content_id": tf.constant(["a", "b", "c"]),
        "content_type": tf.constant(["text/plain"] * 3),
        "language_tag": tf.constant(["en"] * 3),
        "editorial_type": tf.constant(["guide", "article", ""]),
        "collection_ids": tf.ragged.constant([["A", "B"], ["A"], []], dtype=tf.string),
    }
    features = dict(content, user_id=tf.constant(["u0", "u1", "u0"]),
                    label=tf.constant([1., 0.8, 0.1]), sample_weight=tf.ones(3))
    dataset = tf.data.Dataset.from_tensor_slices(content)
    user = UserModel(["u0", "u1"], 0, 0, 8)
    item = ContentModel(["a", "b", "c"], ["text/plain"], ["en"], [], [], 8,
                        unique_editorial_types=["article", "guide"], unique_collection_ids=["A", "B"])
    recommender = BoscaRecommender(user, item, dataset)
    recommender.compute_loss(features)
    before_type = item.editorial_embedding.get_weights()[0].copy()
    before_collection = item.collection_embedding.get_weights()[0].copy()
    recommender.compile(optimizer=tf.keras.optimizers.Adagrad(0.1))
    recommender.fit(tf.data.Dataset.from_tensor_slices(features).batch(3), epochs=2, verbose=0)
    assert not np.array_equal(before_type, item.editorial_embedding.get_weights()[0])
    assert not np.array_equal(before_collection, item.collection_embedding.get_weights()[0])
    assert np.isfinite(item(content).numpy()).all()
    ranker = RankingModel(user, item)
    assert np.isfinite(ranker(features).numpy()).all()


def _models(content_dataset):
    user = UserModel(["u0", "u1"], 2, 2, 8)
    content = ContentModel(["a", "z"], ["article"], ["en"], ["A", "B"], [], 8)
    return BoscaRecommender(user, content, content_dataset)


def test_training_skips_catalog_metrics_but_evaluation_still_scans_catalog(graph_mode):
    features, content_dataset, _ = _data()
    visited = tf.Variable(0, dtype=tf.int32)

    def count_item(item):
        visited.assign_add(1)
        return item

    model = BoscaRecommender(
        UserModel(["u0", "u1"], 0, 0, 8),
        ContentModel(["a", "z"], ["article"], ["en"], [], [], 8),
        content_dataset.map(count_item),
    )

    @tf.function
    def loss(training):
        with tf.GradientTape() as tape:
            value = model.compute_loss(features, training=training)
        return value, [tf.convert_to_tensor(gradient)
                       for gradient in tape.gradient(value, model.trainable_variables)]

    training_loss, training_gradients = loss(True)
    assert visited.numpy() == 0
    evaluation_loss, evaluation_gradients = loss(False)
    assert visited.numpy() == 2
    np.testing.assert_allclose(training_loss, evaluation_loss)
    for actual, expected in zip(training_gradients, evaluation_gradients):
        np.testing.assert_allclose(actual, expected)


def _observe_dropout(monkeypatch, layer):
    """Count actual input/output changes during fit, including inside a traced train_step."""
    changed = tf.Variable(0, dtype=tf.int32, trainable=False)
    original = layer.call

    def observe(inputs, *args, **kwargs):
        outputs = original(inputs, *args, **kwargs)
        changed.assign_add(tf.reduce_sum(tf.cast(tf.not_equal(inputs, outputs), tf.int32)))
        return outputs

    monkeypatch.setattr(layer, "call", observe)
    return changed


def test_fit_activates_dropout_but_ranker_preserves_serving_embeddings(graph_mode, monkeypatch):
    features, content_dataset, _ = _data()
    model = _models(content_dataset)
    affinity_changes = _observe_dropout(monkeypatch, model.user_model.affinity_dropout)
    batches = tf.data.Dataset.from_tensor_slices(features).repeat(16).batch(16)
    model.compute_loss(features)  # Build weights before measuring updates.
    before = [weight.numpy().copy() for weight in model.trainable_variables]
    model.compile(optimizer=tf.keras.optimizers.Adagrad(0.1))
    model.fit(batches, epochs=2, verbose=0)
    assert affinity_changes.numpy() > 0
    assert any(not np.array_equal(old, weight.numpy()) for old, weight in zip(before, model.trainable_variables))

    model.user_model.trainable = False
    model.content_model.trainable = False
    frozen = [weight.numpy().copy() for weight in model.weights]
    ranking = RankingModel(model.user_model, model.content_model)
    ranking(features)
    head_before = [weight.numpy().copy() for weight in ranking.score.weights]
    head_changes = [
        _observe_dropout(monkeypatch, layer)
        for layer in ranking.score.layers if isinstance(layer, tf.keras.layers.Dropout)
    ]
    affinity_changes.assign(0)
    ranker = BoscaRanker(ranking)
    ranker.compile(optimizer=tf.keras.optimizers.Adagrad(0.1))
    ranker.fit(batches, epochs=2, verbose=0)
    assert affinity_changes.numpy() == 0
    assert all(count.numpy() > 0 for count in head_changes)
    for old, weight in zip(frozen, model.weights):
        np.testing.assert_array_equal(old, weight.numpy())
    assert any(not np.array_equal(old, weight.numpy()) for old, weight in zip(head_before, ranking.score.weights))

    for count in head_changes:
        count.assign(0)
    first = ranking(features, training=False).numpy()
    np.testing.assert_array_equal(first, ranking(features, training=False).numpy())
    np.testing.assert_array_equal(first, ranking.score_pairs(
        model.user_model(features, training=False), model.content_model(features, training=False), features["content_id"]
    ).numpy())
    assert affinity_changes.numpy() == 0
    assert all(count.numpy() == 0 for count in head_changes)


@pytest.mark.parametrize("indices", [[0, 0], [0, 1], [0, 0, 1]], ids=["same", "distinct", "mixed"])
@pytest.mark.parametrize("weighted", [False, True], ids=["uniform", "confidence-recency"])
def test_retrieval_loss_and_gradients_exclude_only_duplicate_item_negatives(graph_mode, indices, weighted):
    features, content_dataset, _ = _data()
    features = {key: tf.gather(value, indices) for key, value in features.items()}
    if weighted:
        features["sample_weight"] = tf.constant([0.1, 2.0, 0.5][:len(indices)])
    model = _models(content_dataset)

    @tf.function
    def losses_and_gradients():
        with tf.GradientTape(persistent=True) as tape:
            actual = model.compute_loss(features, training=False)
            scores = tf.matmul(model.user_model(features), model.content_model(features), transpose_b=True)
            ids = features["content_id"]
            duplicate = tf.equal(ids[:, None], ids[None, :]) & ~tf.eye(len(indices), dtype=tf.bool)
            masked = tf.where(duplicate, tf.constant(-1e9), scores)
            expected = tf.reduce_sum(tf.nn.sparse_softmax_cross_entropy_with_logits(
                labels=tf.range(len(indices)), logits=masked,
            ) * features["sample_weight"])
        weights = model.trainable_variables
        return actual, expected, tape.gradient(actual, weights), tape.gradient(expected, weights)

    actual, expected, actual_gradients, expected_gradients = losses_and_gradients()
    np.testing.assert_allclose(actual, expected, rtol=1e-6, atol=1e-7)
    for actual_gradient, expected_gradient in zip(actual_gradients, expected_gradients):
        np.testing.assert_allclose(tf.convert_to_tensor(actual_gradient), tf.convert_to_tensor(expected_gradient),
                                   rtol=1e-5, atol=1e-7)
    if len(set(indices)) == 1:
        assert actual.numpy() == 0
        assert all(np.count_nonzero(tf.convert_to_tensor(gradient).numpy()) == 0 for gradient in actual_gradients)
    else:
        assert actual.numpy() > 0
        assert any(np.count_nonzero(tf.convert_to_tensor(gradient).numpy()) > 0 for gradient in actual_gradients)


def test_fitting_reshuffles_batch_membership_each_epoch_without_dropping_examples(caplog):
    class RecordingModel(tf.keras.Model):
        def __init__(self):
            super().__init__()
            self.seen = []

        def train_step(self, rows):
            self.seen.append(rows.numpy().tolist())
            return {"mean_loss": tf.constant(1.0)}

    model = RecordingModel()
    model.compile(run_eagerly=True)
    with caplog.at_level("INFO", logger="trainer.training"):
        _fit_to_convergence(model, tf.data.Dataset.range(37), 3, 8, "retrieval", 42)
    epochs = [model.seen[start:start + 5] for start in range(0, len(model.seen), 5)]
    assert len(epochs) == 3
    for batches in epochs:
        assert sorted(row for batch in batches for row in batch) == list(range(37))
    memberships = [sorted(tuple(sorted(batch)) for batch in batches) for batches in epochs]
    assert memberships[0] != memberships[1]
    assert memberships[1] != memberships[2]
    assert "retrieval epoch 1/3 started" in caplog.text
    assert "retrieval epoch 3/3 batch 1: mean_loss=1.000000" in caplog.text


@pytest.mark.parametrize("stage", ["retrieval", "ranking"])
def test_epoch_loss_weights_partial_batches_and_resets_between_epochs(graph_mode, stage):
    features, content_dataset, _ = _data()
    features = {key: tf.gather(value, [0, 1, 0]) for key, value in features.items()}
    features["sample_weight"] = tf.constant([1.0, 2.0, 5.0])
    retrieval = _models(content_dataset)
    if stage == "retrieval":
        model = retrieval
    else:
        model = BoscaRanker(RankingModel(retrieval.user_model, retrieval.content_model))

    class CheckEpochLoss(tf.keras.callbacks.Callback):
        def on_epoch_begin(self, epoch, logs=None):
            self.total = 0.0

        def on_train_batch_end(self, batch, logs=None):
            size = [2, 1][batch]
            self.total += logs["loss"] * (1 if stage == "retrieval" else size)

        def on_epoch_end(self, epoch, logs=None):
            np.testing.assert_allclose(logs["mean_loss"], self.total / 3, rtol=1e-6)
            assert logs["mean_loss"] != logs["total_loss"]

    model.compile(optimizer=tf.keras.optimizers.Adagrad(0.1))
    model.fit(tf.data.Dataset.from_tensor_slices(features).batch(2), epochs=2,
              callbacks=[CheckEpochLoss()], verbose=0)


def test_early_stopping_restores_best_epoch_not_best_final_batch():
    class ScriptedLossModel(tf.keras.Model):
        def __init__(self):
            super().__init__()
            self.step = self.add_weight(initializer="zeros")
            self.mean_loss = tf.keras.metrics.Mean(name="mean_loss")
            self.seen = 0

        def train_step(self, rows):
            # Epoch 1 is best overall; epoch 2 onward has the smallest final batch loss.
            losses = [1.0, 9.0, 4.0, 4.0, 20.0, 1.0]
            loss = losses[self.seen] if self.seen < 6 else losses[4 + self.seen % 2]
            self.seen += 1
            self.step.assign_add(1)
            self.mean_loss.update_state(loss, sample_weight=tf.shape(rows)[0])
            return {"mean_loss": self.mean_loss.result(), "total_loss": tf.constant(loss)}

    model = ScriptedLossModel()
    model.compile(run_eagerly=True)
    _fit_to_convergence(model, tf.data.Dataset.range(4), 20, 2, "retrieval", 42)
    assert model.seen == 14  # Two epochs to the minimum, followed by five without improvement.
    assert model.step.numpy() == 4  # Weights from the end of the best complete epoch.


def test_seed_reproduces_trained_weights_and_predictions(graph_mode):
    features, content_dataset, vocabs = _data()
    dataset = tf.data.Dataset.from_tensor_slices(features)

    def train(seed):
        retrieval, ranking = train_model(dataset, dataset, content_dataset, vocabs, 8, 2, 2, 0.1, seed)
        return [weight.numpy().copy() for weight in retrieval.weights + ranking.score.weights], ranking(features).numpy()

    first_weights, first_predictions = train(42)
    second_weights, second_predictions = train(42)
    for first, second in zip(first_weights, second_weights):
        np.testing.assert_allclose(first, second, rtol=1e-6, atol=1e-7)
    np.testing.assert_allclose(first_predictions, second_predictions, rtol=1e-6, atol=1e-7)
    other_weights, _ = train(43)
    assert any(not np.array_equal(first, other) for first, other in zip(first_weights, other_weights))


@pytest.mark.parametrize("empty_side_features", [False, True], ids=["absent", "all-zero"])
def test_history_only_users_receive_id_gradients_from_retrieval(graph_mode, empty_side_features):
    features, _, _ = _data()
    content = {key: features[key] for key in ["content_id", "content_type", "language_tag"]}
    features = dict(content, user_id=features["user_id"], sample_weight=tf.ones(2))
    side_width = 2 if empty_side_features else 0
    if empty_side_features:
        features.update(user_signal_multi_hot=tf.zeros([2, 2]), user_category_affinity=tf.zeros([2, 2]))
    user_model = UserModel(["u0", "u1", "cold"], side_width, side_width, 8)
    model = BoscaRecommender(
        user_model, ContentModel(["a", "z"], ["article"], ["en"], [], [], 8),
        tf.data.Dataset.from_tensor_slices(content),
    )

    @tf.function
    def id_gradients():
        with tf.GradientTape() as tape:
            loss = model.compute_loss(features, training=True)
        return tf.convert_to_tensor(tape.gradient(loss, user_model.user_embedding.embeddings))

    gradients = id_gradients().numpy()
    for user in ["u0", "u1"]:
        assert np.linalg.norm(gradients[int(user_model.user_lookup(tf.constant(user)))]) > 0
    np.testing.assert_array_equal(gradients[0], np.zeros(8))
    np.testing.assert_array_equal(gradients[int(user_model.user_lookup(tf.constant("cold")))], np.zeros(8))


@pytest.mark.parametrize("seed", [11, 29, 47])
def test_history_only_training_learns_opposite_preferences_and_keeps_cold_ids_neutral(graph_mode, seed):
    content = pd.DataFrame(dict(content_id=["a", "b"], content_type=["article"] * 2, language_tag=["en"] * 2))
    interactions = pd.DataFrame([
        dict(user_id=user, content_id=item, interaction_type="Completion",
             interaction_created="2026-09-01T00:00:00Z")
        for _ in range(5) for user, item in [("u0", "a"), ("u1", "b")]
    ])
    retrieval, ranking, items, _, vocabs = prepare_datasets(
        interactions, content, pd.DataFrame(columns=["content_id", "category_id"]),
        users=pd.DataFrame({"user_id": ["u0", "u1", "cold-a", "cold-b"]}),
        as_of=pd.Timestamp("2026-09-02T00:00:00Z"),
    )
    model, _ = train_model(retrieval, ranking, items, vocabs, 8, 20, 4, .1, random_seed=seed)
    query = {"user_id": tf.constant(["u0", "u1", "cold-a", "cold-b", "unknown"])}
    user_vectors = model.user_model(query).numpy()
    item_vectors = model.content_model(next(iter(items.batch(2)))).numpy()
    scores = user_vectors @ item_vectors.T
    assert scores[0, 0] > scores[0, 1]
    assert scores[1, 1] > scores[1, 0]
    id_vectors = model.user_model.user_embedding(model.user_model.user_lookup(query["user_id"])).numpy()
    assert np.all(np.linalg.norm(id_vectors[:2], axis=1) > 0)
    np.testing.assert_array_equal(id_vectors[2:], np.zeros((3, 8)))
    np.testing.assert_array_equal(user_vectors[2:], np.repeat(user_vectors[2:3], 3, axis=0))


@pytest.mark.parametrize('compiled', [False, True], ids=['eager', 'graph'])
def test_ranker_rewards_correct_confidence_and_keeps_wrong_confidence_gradients_finite(compiled):
    class Scores(tf.keras.Model):
        def call(self, features, training=False):
            return features['logits']

    model = BoscaRanker(Scores())

    def loss_and_gradient(values, labels, weights):
        with tf.GradientTape() as tape:
            tape.watch(values)
            loss = model.compute_loss({'logits': values, 'label': labels, 'sample_weight': weights})
        return loss, tape.gradient(loss, values)

    compute = tf.function(loss_and_gradient) if compiled else loss_and_gradient
    labels, weights = tf.constant([1., 0.]), tf.ones(2)
    neutral, _ = compute(tf.constant([[0.], [0.]]), labels, weights)
    confident, gradient = compute(tf.constant([[100.], [-100.]]), labels, weights)
    wrong, wrong_gradient = compute(tf.constant([[-100.], [100.]]), labels, weights)
    assert confident < neutral < wrong
    assert np.isfinite(wrong.numpy()) and np.isfinite(wrong_gradient.numpy()).all()
    assert wrong_gradient[0, 0] < 0 < wrong_gradient[1, 0]
    assert np.abs(gradient.numpy()).max() < 1e-6


@pytest.mark.parametrize('label', [.25, .5, .75])
def test_soft_feedback_target_has_stationary_loss_at_its_log_odds(label):
    class Scores(tf.keras.Model):
        def call(self, features, training=False):
            return features['logits']

    model = BoscaRanker(Scores())
    logits = tf.Variable([[np.log(label / (1 - label))]], dtype=tf.float32)
    with tf.GradientTape() as tape:
        loss = model.compute_loss({'logits': logits, 'label': tf.constant([label]), 'sample_weight': tf.constant([5.])})
    np.testing.assert_allclose(tape.gradient(loss, logits), 0., atol=1e-6)
