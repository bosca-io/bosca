"""Sparse editorial-type and collection interests through training and export."""
import pytest
import numpy as np
import pandas as pd
import tensorflow as tf

from trainer.models import UserModel
from trainer.datasets import prepare_datasets
from trainer.pipeline import _train_personalized


def frames():
    content = pd.DataFrame({
        "content_id": ["a", "b"], "content_type": ["text/plain"] * 2,
        "language_tag": ["en"] * 2, "editorial_type": ["article", "devotional"],
        "collection_ids": [["C", "C"], ["D"]],
    })
    interactions = pd.DataFrame({
        "user_id": ["u", "u", "v"], "content_id": ["a", "b", "a"],
        "interaction_type": ["Interaction"] * 3,
        "interaction_created": ["2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z"],
    })
    return interactions, content, pd.DataFrame(columns=["content_id", "category_id"])


def test_sparse_histories_are_historical_for_training_and_complete_for_serving():
    interactions, content, categories = frames()
    _, ranking, _, users, vocabs = prepare_datasets(interactions, content, categories,
                                                   as_of="2026-01-03T00:00:00Z")
    rows = {(row["user_id"], row["content_id"]): row for row in ranking.as_numpy_iterator()}
    assert rows[(b"u", b"a")]["user_affinity_tokens"].size == 0
    assert rows[(b"v", b"a")]["user_affinity_tokens"].size == 0
    prior = rows[(b"u", b"b")]
    np.testing.assert_array_equal(prior["user_affinity_tokens"], [b"collection:C", b"type:article"])
    np.testing.assert_allclose(prior["user_affinity_weights"], [1, 1])
    serving = {row["user_id"]: row for row in users.as_numpy_iterator()}
    assert set(serving[b"u"]["user_affinity_tokens"]) == {
        b"collection:C", b"collection:D", b"type:article", b"type:devotional",
    }
    assert vocabs["affinity_tokens"] == ["collection:C", "collection:D", "type:article", "type:devotional"]


def test_sparse_profile_features_train_and_export_behind_id_inputs(tmp_path):
    interactions, content, categories = frames()
    interactions.attrs["as_of"] = "2026-01-03T00:00:00Z"
    result = _train_personalized(
        pd.DataFrame({"user_id": ["u", "v", "cold"]}), interactions, content, categories,
        pd.DataFrame(), pd.DataFrame(),
        {"epochs": 2, "batch_size": 3, "embedding_dim": 4, "top_k": 2}, str(tmp_path),
    )
    assert result["status"] == "completed"
    loaded = tf.saved_model.load(str(tmp_path / str(result["model_version"])))
    predictions = loaded.signatures["serving_default"](
        user_id=tf.constant(["u", "cold"]), context_type=tf.constant(["default"] * 2),
        language_tag=tf.constant(["en"] * 2),
    )
    assert predictions["content_ids"].shape == (2, 2)
    assert np.isfinite(predictions["scores"].numpy()).all()
    assert set(predictions["content_ids"].numpy()[0]) == {b"a", b"b"}


@pytest.mark.parametrize("enabled", ["type", "collections", None])
def test_disabled_sparse_affinities_cannot_change_user_embedding(tmp_path, enabled):
    weights = {"similarity": {key: float(key == (enabled or "semantic")) for key in
               ("semantic", "categories", "labels", "language", "mime", "type", "collections")}}
    active = "type:article" if enabled == "type" else "collection:A"
    disabled = "collection:A" if enabled == "type" else "type:article"
    model = UserModel(["u"], 0, 0, 4,
                      unique_affinity_tokens=["type:article", "collection:A"], weights=weights)
    features = {
        "user_id": tf.constant(["u"] * 7),
        "user_affinity_tokens": tf.ragged.constant([
            [active], [active, disabled], [active, disabled, "unknown"],
            [], [disabled], [disabled, "unknown"], [active, disabled],
        ]),
        "user_affinity_weights": tf.ragged.constant([
            [1.], [1., 1.], [1., .5, 1.], [], [1.], [.2, 1.], [1., 0.],
        ]),
    }
    eager = model(features).numpy()
    assert np.isfinite(eager).all()
    for row in [1, 2, 6]:
        np.testing.assert_allclose(eager[0], eager[row], atol=1e-7)
    for row in [4, 5]:
        np.testing.assert_allclose(eager[3], eager[row], atol=1e-7)
    if enabled is None:
        np.testing.assert_allclose(eager, np.repeat(eager[:1], 7, axis=0), atol=1e-7)
    module = tf.Module()
    module.model = model

    @tf.function(input_signature=[tf.TensorSpec([None], tf.string),
                                  tf.RaggedTensorSpec([None, None], tf.string),
                                  tf.RaggedTensorSpec([None, None], tf.float32)])
    def embed(user_id, tokens, affinities):
        return module.model({"user_id": user_id, "user_affinity_tokens": tokens,
                             "user_affinity_weights": affinities})

    module.embed = embed
    np.testing.assert_allclose(embed(*features.values()), eager, atol=1e-7)
    tf.saved_model.save(module, str(tmp_path))
    loaded = tf.saved_model.load(str(tmp_path))
    np.testing.assert_allclose(loaded.embed(*features.values()), eager, atol=1e-7)
