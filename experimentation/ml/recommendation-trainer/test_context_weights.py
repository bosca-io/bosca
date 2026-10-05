"""Context cold-start defaults are captured by exported SavedModels, before top-K."""
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import tensorflow as tf

from trainer.context_weights import context_weights, normalized_similarity
from trainer.content_only import run_content_only_training
from trainer.features import editorial_types, collections_by_content
from trainer.content_similarity import ContentSimilarityIndex


def only(signal):
    return {"similarity": {key: float(key == signal) for key in
            ("semantic", "categories", "labels", "language", "mime", "type", "collections")}}


def query(directory, content, weights):
    result = run_content_only_training(
        content, pd.DataFrame(columns=["content_id", "category_id"]), None, None,
        {"model_dir": directory, "weights": weights, "top_k": 1},
    )
    model = tf.saved_model.load(str(Path(directory) / str(result["model_version"])))
    return model.signatures["similar"](
        content_id=tf.constant(["source"]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"]),
    )


def test_two_exported_contexts_reverse_default_type_preference_before_top_k():
    content = pd.DataFrame({
        "content_id": ["source", "article", "devotional", "unrelated"],
        "content_type": ["text/plain"] * 4, "language_tag": ["en"] * 4,
        "editorial_type": ["", "article", " Devotional ", "devotional"],
        "embedding": [[1., 0.], [1., 0.], [1., 0.], [0., 1.]],
    })
    settings = only("semantic")
    with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
        settings["typePreferences"] = [{"type": "devotional", "weight": 0.9}, {"type": "article", "weight": 0.0}]
        a = query(first, content, settings)
        settings["typePreferences"] = [{"type": "devotional", "weight": 0.0}, {"type": "article", "weight": 0.9}]
        b = query(second, content, settings)
        assert a["content_ids"].numpy()[0, 0] == b"devotional"
        assert b["content_ids"].numpy()[0, 0] == b"article"
        assert b"unrelated" not in a["content_ids"].numpy()[0]
        assert float(a["scores"].numpy()[0, 0]) == pytest.approx(0.95)


@pytest.mark.parametrize("same_type,expected", [(0., "study"), (.2, "study"), (.5, "article")])
def test_source_type_matching_competes_with_study_preference(same_type, expected):
    """A stronger source-type match can outweigh a candidate preference before top-K."""
    content = pd.DataFrame({
        "content_id": ["source", "article", "study"],
        "content_type": ["text/plain"] * 3, "language_tag": ["en"] * 3,
        "editorial_type": ["article", "article", " Study "],
        "collection_ids": [["collection"]] * 3,
        "embedding": [[1., 0.]] * 3,
    })
    settings = {"similarity": {"semantic": .3, "categories": .2, "labels": .2,
                              "language": .2, "mime": .2, "type": same_type, "collections": .5},
                "defaultTypePreference": .5, "typePreferences": [{"type": "Study", "weight": .8}]}
    index = ContentSimilarityIndex(content, pd.DataFrame(columns=["content_id", "category_id"]),
                                   ["default\x1fen"], [tf.range(3)], 2, settings)
    scores, ids = tf.function(index.__call__)(tf.constant(["source"]), tf.constant(["default"]),
                                             tf.constant(["en"]), exclude_self=True)
    assert ids.numpy()[0, 0] == expected.encode()
    # Normalized relatedness includes equal semantic, MIME, language, and collection evidence.
    total = sum(settings["similarity"].values())
    expected_scores = {b"article": (1.2 + same_type) / total * .75, b"study": 1.2 / total * .9}
    for candidate, score in zip(ids.numpy()[0], scores.numpy()[0]):
        assert score == pytest.approx(expected_scores[candidate])


@pytest.mark.parametrize("signal", ["type", "collections"])
def test_type_and_collection_matches_ignore_missing_and_duplicate_values(signal):
    content = pd.DataFrame({
        "content_id": ["source", "same", "missing", "other"],
        "content_type": ["text/plain"] * 4, "language_tag": ["en"] * 4,
        "editorial_type": [" Guide ", "guide", None, "article"],
        "collection_ids": [["A", "A"], ["A"], [], ["B"]],
    })
    with tempfile.TemporaryDirectory() as directory:
        result = query(directory, content, only(signal))
        assert set(result["content_ids"].numpy()[0]) == {b"source", b"same"}
        np.testing.assert_allclose(result["scores"].numpy(), [[0.75, 0.75]])


def test_zero_type_preference_does_not_exclude_and_missing_type_does_not_match():
    content = pd.DataFrame({
        "content_id": ["source", "same"], "content_type": ["text"] * 2,
        "language_tag": ["en"] * 2, "editorial_type": [None, None],
    })
    with tempfile.TemporaryDirectory() as directory:
        settings = {**only("mime"), "defaultTypePreference": 0.0}
        result = query(directory, content, settings)
        assert set(result["content_ids"].numpy()[0]) == {b"source", b"same"}
        np.testing.assert_allclose(result["scores"].numpy(), [[0.5, 0.5]])
        missing = query(directory, content, only("type"))
        np.testing.assert_array_equal(missing["scores"].numpy(), [[0., 0.]])


@pytest.mark.parametrize("value", [-0.01, 1.01, float("nan"), float("inf"), True, "0.5"])
def test_invalid_weights_fail_before_export(value):
    for key in ("defaultTypePreference", "content", "coEngagement", "cohortCoEngagement",
                "learnedNeighbor", "personalization", "rating"):
        with pytest.raises(ValueError):
            context_weights({key: value})
    with pytest.raises(ValueError):
        context_weights({"similarity": {"type": value}})


def test_normalization_and_invalid_type_keys():
    assert context_weights({"typePreferences": [{"type": " Guide ", "weight": 0}]})["typePreferences"] == [
        {"type": "guide", "weight": 0.0},
    ]
    assert normalized_similarity(only("collections"))["collections"] == 1
    for preferences in (
        [{"type": " ", "weight": 1}],
        [{"type": "Guide", "weight": 1}, {"type": " guide ", "weight": 0}],
    ):
        with pytest.raises(ValueError):
            context_weights({"typePreferences": preferences})
    with pytest.raises(ValueError):
        context_weights({"similarity": dict.fromkeys(only("type")["similarity"], 0)})
    with pytest.raises(ValueError):
        context_weights({"similarity": {"unknown": 1}})
    frame = pd.DataFrame({"content_id": ["a", "b"], "editorial_type": [3, " GUIDE "],
                          "collection_ids": [None, ["A", "A", " "]]})
    assert editorial_types(frame) == ["", "guide"]
    assert collections_by_content(frame) == {"a": frozenset(), "b": frozenset({"A"})}
    with pytest.raises(ValueError):
        collections_by_content(pd.DataFrame({"content_id": ["a"], "collection_ids": ["A"]}))
