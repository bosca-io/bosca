"""Promotion rejects broken context-serving contracts while allowing empty matches."""
from types import SimpleNamespace

import pytest
import tensorflow as tf

from trainer.export import _validate_exported_content_model, _validate_exported_recommender


def prediction(ids, scores=None):
    return {"content_ids": tf.constant([ids], tf.string),
            "scores": tf.constant([scores if scores is not None else [1.] * len(ids)], tf.float32)}


def model(personalized):
    def page(**query):
        source = query.get("source_id", query.get("content_id"))
        source = source.numpy()[0].decode() if source is not None else None
        user = query.get("user_id")
        known_user = user is None or user.numpy()[0] == b"u"
        ids = [key for key in "abcd" if key != source]
        if (source is not None and source not in "abcd") or not known_user:
            ids = []
        offset = int(query["offset"][0])
        ids = ids[offset:offset + 2]
        scores = [float(4 - "abcd".index(key)) for key in ids]
        return prediction(ids + [""] * (2 - len(ids)), scores + [0.] * (2 - len(ids)))

    def serve(**query):
        if "user_id" in query and query["user_id"].numpy()[0] != b"u":
            return prediction([""])
        return prediction(["a"])

    signatures = {"serving_default": serve, "similar": serve,
                  "explain": lambda **q: {"contributions": tf.zeros([len(q["content_id"]), 4])}}
    if personalized:
        signatures.update(related=page, feed_page=page,
                          co_engaged=lambda **q: prediction(["", ""], [0., 0.]),
                          similar_users=lambda **q: prediction(["u"]),
                          rank=lambda **q: {"scores": tf.constant([1.])})
    else:
        signatures["similar_page"] = page
    return SimpleNamespace(signatures=signatures)


def validate(value, personalized):
    if personalized:
        return _validate_exported_recommender(value, set("abcd"), {"u"}, "u", "a", "default", "en")
    return _validate_exported_content_model(value, set("abcd"), "a", "default", "en")


@pytest.mark.parametrize("personalized", [False, True])
def test_valid_contract_accepts_empty_matches_and_exhausted_pages(personalized):
    value = model(personalized)
    result = validate(value, personalized)
    assert result["status"] == "passed"
    assert set(result["signatures"]) == set(value.signatures)


@pytest.mark.parametrize("personalized,signature", [(True, name) for name in
    ("related", "feed_page", "co_engaged", "explain")] + [(False, name) for name in ("similar_page", "explain")])
def test_missing_production_signature_rejects_export(personalized, signature):
    value = model(personalized)
    del value.signatures[signature]
    with pytest.raises(ValueError, match="missing signatures"):
        validate(value, personalized)


@pytest.mark.parametrize("personalized,signature", [(True, name) for name in
    ("related", "feed_page", "co_engaged")] + [(False, "similar_page")])
@pytest.mark.parametrize("fault", ["nan", "foreign", "duplicate", "unsorted", "repeat_page"])
def test_broken_production_page_rejects_export(personalized, signature, fault):
    value = model(personalized)
    responses = {
        "nan": prediction(["b"], [float("nan")]),
        "foreign": prediction(["outside-facet"]),
        "duplicate": prediction(["b", "b"]),
        "unsorted": prediction(["b", "c"], [1., 2.]),
        "repeat_page": prediction(["b", "c"], [2., 1.]),
    }
    value.signatures[signature] = lambda **q: responses[fault]
    with pytest.raises(ValueError):
        validate(value, personalized)


@pytest.mark.parametrize("personalized", [False, True])
@pytest.mark.parametrize("fault", ["source", "unknown_source", "explanation_shape", "explanation_nan", "explanation_changes_ranking"])
def test_source_and_explanation_contracts_reject_export(personalized, fault):
    value = model(personalized)
    signature = "related" if personalized else "similar_page"
    page = value.signatures[signature]
    if fault == "source":
        value.signatures[signature] = lambda **q: prediction(["a"])
    elif fault == "unknown_source":
        def ignore_source(**q):
            q["source_id" if personalized else "content_id"] = tf.constant(["a"])
            return page(**q)
        value.signatures[signature] = ignore_source
    elif fault.startswith("explanation_"):
        def explain(**q):
            if fault == "explanation_shape":
                return {"contributions": tf.zeros([len(q["content_id"]), 3])}
            if fault == "explanation_nan":
                return {"contributions": tf.fill([len(q["content_id"]), 4], float("nan"))}
            def changed_page(**fields):
                result = page(**fields)
                result["scores"] = result["scores"] * 2
                return result
            value.signatures[signature] = changed_page
            return {"contributions": tf.zeros([len(q["content_id"]), 4])}
        value.signatures["explain"] = explain
    with pytest.raises(ValueError):
        validate(value, personalized)
