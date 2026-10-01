"""Learned context correction and full-catalog ranking behavior."""
import numpy as np
import random
import pytest
import tensorflow as tf
import pandas as pd

from trainer.export import _ContextRankedTopK, _build_user_query_model
from trainer.models import BoscaRanker, ContentModel, RankingModel, UserModel, build_item_query_model
from trainer.datasets import build_content_dataset
from trainer.content_similarity import ContentSimilarityIndex


def ranker(personalization=1):
    return RankingModel(
        UserModel(["profile"], 0, 0, 2),
        ContentModel(["article", "devotional"], ["text/plain"], ["en"], [], [], 2),
        {"personalization": personalization, "typePreferences": [
            {"type": "article", "weight": 0}, {"type": "devotional", "weight": 0.9},
        ]},
        {"article": "article", "devotional": "devotional"},
    )


def test_type_baseline_is_inside_ranker_and_learned_evidence_can_reverse_it():
    random.seed(42)
    np.random.seed(42)
    tf.random.set_seed(42)
    model = ranker()
    ids = tf.constant(["article", "devotional"])
    users = tf.zeros([2, 2])
    items = tf.eye(2)
    np.testing.assert_allclose(model.score_pairs(users, items, ids).numpy()[:, 0], [.5, .95])
    task = BoscaRanker(model).task
    optimizer = tf.keras.optimizers.Adam(.02)
    for _ in range(80):
        with tf.GradientTape() as tape:
            predictions = model.score_pairs(users, items, ids)
            loss = task(labels=tf.constant([[1.], [0.]]), predictions=predictions)
        gradients = tape.gradient(loss, model.score.trainable_variables)
        optimizer.apply_gradients(zip(gradients, model.score.trainable_variables))
    learned = model.score_pairs(users, items, ids).numpy()[:, 0]
    assert learned[0] > learned[1] + .5


def test_zero_personalization_removes_the_direct_learned_correction():
    model = ranker(personalization=0)
    ids = tf.constant(["article", "devotional"])
    model.score_pairs(tf.eye(2), tf.eye(2), ids)
    model.score.layers[-1].bias.assign([100.])
    np.testing.assert_allclose(model.score_pairs(tf.eye(2), tf.eye(2), ids).numpy()[:, 0], [.5, .95])


def test_disabled_similarity_features_cannot_change_the_learned_content_representation():
    model = ContentModel(
        ["item"], ["text/plain", "video/mp4"], ["en"], ["A", "B"], ["x", "y"], 4,
        semantic_embedding_dim=2, unique_editorial_types=["article", "devotional"],
        unique_collection_ids=["one", "two"],
        weights={"similarity": {key: float(key == "language") for key in
                 ("semantic", "categories", "labels", "language", "mime", "type", "collections")}},
    )
    features = {
        "content_id": tf.constant(["item", "item"]), "language_tag": tf.constant(["en", "en"]),
        "content_type": tf.constant(["text/plain", "video/mp4"]),
        "editorial_type": tf.constant(["article", "devotional"]),
        "collection_ids": tf.ragged.constant([["one"], ["two"]]),
        "category_multi_hot": tf.eye(2), "label_multi_hot": tf.eye(2), "embedding": tf.eye(2),
    }
    representations = model(features).numpy()
    np.testing.assert_array_equal(representations[0], representations[1])


class Query(tf.Module):
    def __call__(self, ids):
        return tf.ones([tf.shape(ids)[0], 1])


def test_anonymous_related_preserves_context_preferences_and_shared_behavior_after_export(tmp_path):
    from trainer.behavior import BehaviorFeatures

    content = pd.DataFrame(dict(
        content_id=["source", "article", "study"], content_type=["text/plain"] * 3,
        language_tag=["en"] * 3, editorial_type=["article", "article", "study"], labels=[[]] * 3,
        embedding=[[1., 0.], [.64, np.sqrt(1. - .64 ** 2)], [.45, np.sqrt(1. - .45 ** 2)]],
    ))
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    dataset, *_ = build_content_dataset(content, categories)
    weights = {"similarity": {key: float(key == "semantic") for key in
               ("semantic", "categories", "labels", "language", "mime", "type", "collections")},
               "typePreferences": [{"type": "study", "weight": .8}], "defaultTypePreference": .2}
    item = ContentModel(content.content_id.tolist(), ["text/plain"], ["en"], [], [], 2)
    lookup = build_item_query_model(item, dataset)
    lookup.table.assign(tf.zeros_like(lookup.table))
    similarity = ContentSimilarityIndex(content, categories, ["default\x1fen"], [tf.range(3)], 2, weights)
    model = RankingModel(UserModel(["known"], 0, 0, 2), item, weights,
                         dict(zip(content.content_id, content.editorial_type)),
                         source_query_model=lookup, content_similarity=similarity)
    ids = tf.constant(["article", "study"])
    sources = tf.constant(["source"] * 2)
    vectors = tf.zeros([2, 2])
    model.score_pairs(vectors, vectors, ids, source_id=sources)
    model.score.layers[-1].bias.assign([-10.])
    model.source_score.layers[-1].bias.assign([2.])
    expected = np.array([.64 * .6, .45 * .9])
    for user in ("", "unknown"):
        np.testing.assert_allclose(model.score_pairs(vectors, vectors, ids, source_id=sources,
                                  user_id=tf.constant([user] * 2)).numpy()[:, 0], expected, rtol=1e-6)
        effects = model.explain_pairs(vectors, vectors, ids, tf.constant([user] * 2), sources).numpy()
        np.testing.assert_allclose(effects[:, 0], expected, rtol=1e-6)
        np.testing.assert_array_equal(effects[:, 1:], np.zeros([2, 3]))
    known = model.score_pairs(vectors, vectors, ids, source_id=sources,
                             user_id=tf.constant(["known"] * 2)).numpy()[:, 0]
    np.testing.assert_allclose(known, expected - 8. + np.log([.64, .45]), rtol=1e-6)

    model.behavior_model = BehaviorFeatures(dict(edges=[
        dict(kind="global", source_id="source", content_id="article", cohort_key="", score=10.),
        dict(kind="cohort", source_id="source", content_id="study", cohort_key="group", score=10.),
    ], memberships=[dict(user_id="unknown", cohort_key="group")],
       neighbors=[["unknown", "study", 1.]], ratings=[["unknown", "study", 1.]]), similarity)
    population = model.score_pairs(vectors, vectors, ids, source_id=sources,
                                   user_id=tf.constant([""] * 2)).numpy()[:, 0]
    np.testing.assert_allclose(population, expected + [.5, 0.], rtol=1e-6)
    users = tf.data.Dataset.from_tensor_slices({"user_id": ["known"]})
    query = _build_user_query_model(model.user_model, users)
    index = _ContextRankedTopK(query, model, tf.constant(content.content_id.tolist()),
                              lookup(tf.constant(content.content_id.tolist())),
                              ["default\x1fen"], [[0, 1, 2]], 2)
    module = tf.Module()
    module.index = index

    @tf.function(input_signature=[tf.TensorSpec([None], tf.string)])
    def related(user_id):
        scores, candidates = module.index(user_id, tf.fill(tf.shape(user_id), "default"),
            tf.fill(tf.shape(user_id), "en"), tf.fill(tf.shape(user_id), "source"))
        return {"scores": scores, "content_ids": candidates}

    tf.saved_model.save(module, str(tmp_path), signatures={"related": related})
    loaded = tf.saved_model.load(str(tmp_path)).signatures["related"]
    actual = loaded(user_id=tf.constant(["", "unknown", "known"]))
    assert actual["content_ids"].numpy().tolist() == [[b"article", b"study"]] * 3
    np.testing.assert_allclose(actual["scores"].numpy()[:2], [population, population], rtol=1e-6)
    np.testing.assert_allclose(actual["scores"].numpy()[2], known + [.5, 0.], rtol=1e-6)


class ReverseRanker(tf.Module):
    def score_pairs(self, users, items, ids, source_id=None, user_id=None):
        return -items


def test_source_conditioned_head_learns_opposite_choices_without_changing_general_preferences(tmp_path):
    random.seed(42)
    np.random.seed(42)
    tf.random.set_seed(42)
    content = pd.DataFrame(dict(
        content_id=["source-a", "source-b", "article", "devotional", "unrelated"],
        content_type=["text/plain"] * 5, language_tag=["en"] * 5,
        editorial_type=["", "", "article", "devotional", "devotional"],
        labels=[[]] * 5, embedding=[[1., 0.], [0., 1.], [1., 1.], [1., 1.], [-1., -1.]],
    ))
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    dataset, _, _, _, _ = build_content_dataset(content, categories)
    weights = {"similarity": {key: float(key == "semantic") for key in
               ("semantic", "categories", "labels", "language", "mime", "type", "collections")},
               "typePreferences": [{"type": "article", "weight": 0.}, {"type": "devotional", "weight": .9}]}
    item = ContentModel(content.content_id.tolist(), ["text/plain"], ["en"], [], [], 8, 2,
                        unique_editorial_types=["article", "devotional"])
    lookup = build_item_query_model(item, dataset)
    # Isolate the ranking head with distinct fixed tower representations; retrieval learning is
    # covered separately. No event outcome is present in these item representations.
    lookup.table.assign(tf.concat([tf.zeros([1, 8]), tf.eye(5, 8)], axis=0))
    model = RankingModel(UserModel(["profile"], 0, 0, 8), item, weights,
                         dict(zip(content.content_id, content.editorial_type)), lookup,
                         ContentSimilarityIndex(content, categories, ["reading\x1fen"], [tf.range(5)], 5, weights))
    ids = tf.constant(["article", "devotional", "article", "devotional"])
    sources = tf.constant(["source-a", "source-a", "source-b", "source-b"])
    users, items = tf.zeros([4, 8]), lookup(ids)
    general = model.score_pairs(users, items, ids).numpy()
    cold = model.score_pairs(users, items, ids, source_id=sources).numpy().ravel()
    assert cold[0] < cold[1] and cold[2] < cold[3]
    task = BoscaRanker(model).task
    optimizer = tf.keras.optimizers.Adam(.025)
    for _ in range(180):
        with tf.GradientTape() as tape:
            predicted = model.score_pairs(users, items, ids, source_id=sources)
            loss = task(labels=tf.constant([[1.], [0.], [0.], [1.]]), predictions=predicted)
        variables = model.source_score.trainable_variables
        optimizer.apply_gradients(zip(tape.gradient(loss, variables), variables))
    learned = model.score_pairs(users, items, ids, source_id=sources).numpy().ravel()
    assert learned[0] > learned[1] + .4 and learned[3] > learned[2] + .4
    np.testing.assert_allclose(model.score_pairs(users, items, ids), general)
    unrelated = tf.constant(["unrelated"])
    unrelated_score = model.score_pairs(users[:1], lookup(unrelated), unrelated, source_id=sources[:1])
    assert np.isfinite(unrelated_score.numpy()).all()
    assert unrelated_score.numpy()[0, 0] < learned.min()


    # Export the actually learned source head, rather than only a manually assigned correction.
    module = tf.Module()
    module.model = model

    @tf.function(input_signature=[tf.TensorSpec([None], tf.string), tf.TensorSpec([None], tf.string)])
    def serve(content_id, source_id):
        item_embeddings = module.model.source_query_model(content_id)
        user_embeddings = tf.zeros_like(item_embeddings)
        return {"scores": module.model.score_pairs(user_embeddings, item_embeddings, content_id,
                                                   source_id=source_id)}

    tf.saved_model.save(module, str(tmp_path), signatures={"serving_default": serve})
    loaded = tf.saved_model.load(str(tmp_path)).signatures["serving_default"]
    np.testing.assert_allclose(loaded(content_id=ids, source_id=sources)["scores"].numpy().ravel(),
                               learned, rtol=1e-5, atol=1e-5)
    np.testing.assert_allclose(loaded(content_id=unrelated, source_id=sources[:1])["scores"], unrelated_score, atol=1e-5)


def test_related_refill_pages_stay_in_facet_remove_self_and_stop_for_unknown_sources():
    index = _ContextRankedTopK(Query(), ReverseRanker(), tf.constant(["a", "b", "c", "excluded"]),
                              tf.constant([[1.], [2.], [3.], [-100.]]), ["reading\x1fen"], [[0, 1, 2]], 1)
    scores, ids = tf.function(index.__call__)(
        tf.constant(["profile"] * 4), tf.constant(["reading"] * 4), tf.constant(["en"] * 4),
        tf.constant(["a", "a", "a", "missing"]), tf.constant([0, 1, 2, 0]),
    )
    np.testing.assert_array_equal(ids, [[b"b"], [b"c"], [b""], [b""]])
    np.testing.assert_allclose(scores, [[-2.], [-3.], [0.], [0.]])


def test_full_catalog_ranking_selects_item_a_retrieval_shortlist_would_drop(tmp_path):
    index = _ContextRankedTopK(
        Query(), ReverseRanker(), tf.constant(["retrieval-winner", "ranking-winner", "excluded"]),
        tf.constant([[10.], [1.], [-100.]]), ["reading\x1fen"], [[0, 1]], 1,
    )
    module = tf.Module()
    module.index = index

    @tf.function(input_signature=[tf.TensorSpec([None], tf.string), tf.TensorSpec([None], tf.string),
                                  tf.TensorSpec([None], tf.string)])
    def serve(ids, contexts, languages):
        scores, content_ids = module.index(ids, contexts, languages)
        return {"scores": scores, "content_ids": content_ids}

    tf.saved_model.save(module, str(tmp_path), signatures={"serving_default": serve})
    loaded = tf.saved_model.load(str(tmp_path)).signatures["serving_default"]
    results = loaded(ids=tf.constant(["profile", "profile"]), contexts=tf.constant(["reading", "unknown"]),
                     languages=tf.constant(["en", "en"]))
    np.testing.assert_array_equal(results["content_ids"].numpy(), [[b"ranking-winner"], [b""]])
    np.testing.assert_allclose(results["scores"].numpy(), [[-1.], [0.]])


@pytest.mark.parametrize("learned_logit", [-100., -2., 0., 2., 100.])
def test_related_top_k_preserves_similarity_order_for_signed_logits(tmp_path, learned_logit):
    content = pd.DataFrame(dict(
        content_id=["source", "related", "partial", "unrelated"],
        content_type=["text/plain"] * 4, language_tag=["en"] * 4,
        embedding=[[1., 0.], [1., 0.], [.5, .8660254], [0., 1.]], labels=[[]] * 4,
    ))
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    dataset, *_ = build_content_dataset(content, categories)
    weights = {"similarity": {key: float(key == "semantic") for key in
               ("semantic", "categories", "labels", "language", "mime", "type", "collections")}}
    item = ContentModel(content.content_id.tolist(), ["text/plain"], ["en"], [], [], 2)
    lookup = build_item_query_model(item, dataset)
    # Equal embeddings isolate source similarity from candidate-specific learned preference.
    lookup.table.assign(tf.zeros_like(lookup.table))
    model = RankingModel(UserModel(["u"], 0, 0, 2), item, weights, source_query_model=lookup,
                         content_similarity=ContentSimilarityIndex(content, categories,
                             ["reading\x1fen"], [tf.range(4)], 3, weights))
    ids = tf.constant(["related", "partial", "unrelated"])
    users = tf.zeros([3, 2])
    sources = tf.constant(["source"] * 3)
    model.score_pairs(users, lookup(ids), ids, source_id=sources)
    model.score.layers[-1].bias.assign([learned_logit])
    with tf.GradientTape() as tape:
        scores = model.score_pairs(users, lookup(ids), ids, source_id=sources)
        loss = BoscaRanker(model).task(labels=tf.constant([[1.], [0.], [0.]]), predictions=scores)
    gradients = tape.gradient(loss, model.score.trainable_variables)
    assert all(np.isfinite(gradient.numpy()).all() for gradient in gradients)
    assert scores[0, 0] > scores[1, 0] > scores[2, 0]
    np.testing.assert_allclose(model.score_pairs(users, lookup(ids), ids), learned_logit + .75)
    np.testing.assert_allclose(model.score_pairs(users, lookup(ids), ids, source_id=sources,
                                                disabled_group="content"), learned_logit)
    index = _ContextRankedTopK(lookup, model, tf.constant(content.content_id.tolist()),
                              lookup(tf.constant(content.content_id.tolist())),
                              ["reading\x1fen"], [[0, 1, 2, 3]], 3)
    module = tf.Module()
    module.index = index

    @tf.function(input_signature=[tf.TensorSpec([None], tf.string)])
    def related(source_id):
        ranked_scores, ranked_ids = module.index(tf.fill(tf.shape(source_id), "u"),
            tf.fill(tf.shape(source_id), "reading"), tf.fill(tf.shape(source_id), "en"), source_id)
        return {"scores": ranked_scores, "content_ids": ranked_ids}

    tf.saved_model.save(module, str(tmp_path), signatures={"related": related})
    loaded = tf.saved_model.load(str(tmp_path)).signatures["related"]
    results = loaded(source_id=tf.constant(["source"]))
    np.testing.assert_array_equal(results["content_ids"], [[b"related", b"partial", b"unrelated"]])
    np.testing.assert_allclose(results["scores"], scores.numpy().T, atol=1e-5)
