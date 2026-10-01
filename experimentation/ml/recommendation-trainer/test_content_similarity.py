"""SavedModel behavior and storage regressions for exact sparse content similarity."""

import os
os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")

from pathlib import Path
import tempfile

import numpy as np
import pandas as pd
import pytest
import tensorflow as tf

from trainer.content_only import run_content_only_training
from trainer.content_similarity import ContentSimilarityIndex


def _export(content, categories, directory, top_k=50):
    result = run_content_only_training(content, categories, None, None, {"model_dir": directory, "top_k": top_k})
    path = Path(directory) / str(result["model_version"])
    return tf.saved_model.load(str(path)), path


def _query(model, ids, contexts, languages):
    return model.signatures["similar"](
        content_id=tf.constant(ids, tf.string), context_type=tf.constant(contexts, tf.string),
        language_tag=tf.constant(languages, tf.string),
    )


def _memberships_cosine(a, b):
    a, b = set(a), set(b)
    return len(a & b) / np.sqrt(len(a) * len(b)) if a and b else 0.0


def test_sparse_aligned_pairs_match_catalog_scores_including_empty_and_unknown_rows():
    content = pd.DataFrame(dict(
        content_id=["a", "b", "c"], content_type=["text", "text", "image"], language_tag=["en"] * 3,
        labels=[["one", "two"], ["two"], []], editorial_type=["article", "article", ""],
        collection_ids=[["A", "B"], ["B"], []], embedding=[[1., 0.], [0.6, 0.8], [0., 0.]],
    ))
    categories = pd.DataFrame({"content_id": ["a", "a", "b"], "category_id": ["X", "Y", "Y"]})
    index = ContentSimilarityIndex(content, categories, ["default\x1fen"], [tf.range(3)], 3)
    source = tf.repeat(content.content_id.tolist(), 3)
    candidate = tf.tile(content.content_id.tolist(), [3])
    expected = tf.concat([index._scores(i) for i in range(3)], axis=0)
    np.testing.assert_allclose(tf.function(index.pair_similarity)(source, candidate), expected, atol=1e-7)
    np.testing.assert_array_equal(index.pair_similarity(tf.constant(["missing", "a"]), tf.constant(["b", "missing"])), [0., 0.])


def _reference_score(a, b, categories):
    # Independent domain formula, not a reconstruction using the serving implementation's helpers.
    semantic = 0.0
    if a.embedding is not None and b.embedding is not None:
        norm = np.linalg.norm(a.embedding) * np.linalg.norm(b.embedding)
        if norm:
            semantic = np.dot(a.embedding, b.embedding) / norm
    return 0.75 / 7 * (
        float(a.content_type == b.content_type) + float(a.language_tag == b.language_tag)
        + _memberships_cosine(categories[a.content_id], categories[b.content_id])
        + _memberships_cosine([v.strip() for v in a.labels if v.strip()], [v.strip() for v in b.labels if v.strip()])
        + max(0.0, semantic)
    )


def test_saved_model_matches_independent_scores_and_top_k_across_facets():
    rng = np.random.default_rng(314159)
    content = pd.DataFrame({
        "content_id": [f"item-{i:02}" for i in range(24)],
        "content_type": ["text" if i % 3 else "video" for i in range(24)],
        "language_tag": ["en" if i % 4 else "es" for i in range(24)],
        "recommendation_contexts": [["default", "featured"] if i % 2 else ["default"] for i in range(24)],
        "labels": [rng.choice(["one", "two", " three ", ""], size=i % 5).tolist() for i in range(24)],
        "embedding": [None if i % 5 == 0 else rng.normal(size=8).tolist() for i in range(24)],
    })
    # Include a present-but-zero embedding, repeated memberships and items without categories.
    content.at[1, "embedding"] = [0.0] * 8
    category_map = {row.content_id: rng.choice(["a", "b", "c"], size=i % 4).tolist()
                    for i, row in enumerate(content.itertuples())}
    categories = pd.DataFrame([(item, key) for item, keys in category_map.items() for key in keys],
                              columns=["content_id", "category_id"])
    rows = list(content.itertuples())
    queries = [(a, context, language) for a in rows for context in ("default", "featured") for language in ("en", "es")]
    with tempfile.TemporaryDirectory() as directory:
        model, _ = _export(content.sample(frac=1, random_state=42), categories, directory, top_k=3)
        output = _query(model, [a.content_id for a, _, _ in queries], [c for _, c, _ in queries], [l for _, _, l in queries])
        for query, ids, scores in zip(queries, output["content_ids"].numpy(), output["scores"].numpy()):
            a, context, language = query
            expected = {b.content_id: _reference_score(a, b, category_map) for b in rows
                        if context in b.recommendation_contexts and b.language_tag == language}
            actual = {item.decode(): float(score) for item, score in zip(ids, scores) if item}
            assert len(actual) == min(4, len(expected))
            if not expected:
                np.testing.assert_array_equal(scores, np.zeros_like(scores))
                continue
            for item, score in actual.items():
                assert score == pytest.approx(expected[item], abs=2e-7)
            # Tied neighbors may exchange places within floating-point tolerance, but no better one is lost.
            cutoff = sorted(expected.values(), reverse=True)[len(actual) - 1]
            assert min(actual.values()) >= cutoff - 2e-7
            assert all(item in actual for item, score in expected.items() if score > cutoff + 2e-7)


def test_unknown_items_facets_empty_batches_and_padding_after_reload():
    content = pd.DataFrame({"content_id": ["b", "a"], "content_type": ["text"] * 2,
                            "language_tag": ["en", "es"], "labels": [[], []]})
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    with tempfile.TemporaryDirectory() as directory:
        model, _ = _export(content, categories, directory)
        output = _query(model, ["b", "unknown", "b", "b"], ["default", "default", "missing", "default"], ["en", "en", "en", "fr"])
        assert output["content_ids"].numpy().tolist() == [[b"b", b""], [b"", b""], [b"", b""], [b"", b""]]
        np.testing.assert_allclose(output["scores"].numpy(), [[1.5 / 7, 0], [0, 0], [0, 0], [0, 0]])
        empty = _query(model, [], [], [])
        assert empty["content_ids"].shape == (0, 2)
        assert empty["scores"].shape == (0, 2)


def test_tied_neighbors_are_stable_across_corpus_order():
    content = pd.DataFrame({"content_id": ["c", "a", "b"], "content_type": ["text"] * 3,
                            "language_tag": ["en"] * 3, "labels": [[], [], []]})
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    with tempfile.TemporaryDirectory() as directory:
        first, _ = _export(content, categories, directory, top_k=1)
        second, _ = _export(content.iloc[::-1], categories, directory, top_k=1)
        for model in (first, second):
            assert _query(model, ["c"], ["default"], ["en"])["content_ids"].numpy().tolist() == [[b"a", b"b"]]


def test_artifact_size_grows_with_memberships_not_item_times_vocabulary():
    sizes = []
    for count in (1000, 2000):
        content = pd.DataFrame({"content_id": [f"item-{i:04}" for i in range(count)],
                                "content_type": ["text"] * count, "language_tag": ["en"] * count,
                                "labels": [[f"label-{i}"] for i in range(count)]})
        categories = pd.DataFrame({"content_id": content["content_id"], "category_id": [f"category-{i}" for i in range(count)]})
        with tempfile.TemporaryDirectory() as directory:
            model, path = _export(content, categories, directory, top_k=3)
            # Include constants in saved_model.pb, lookup assets and checkpoints, not just variables.
            sizes.append(sum(file.stat().st_size for file in path.rglob("*") if file.is_file()))
            output = _query(model, ["item-0000"], ["default"], ["en"])
            assert output["content_ids"].numpy()[0, 0] == b"item-0000"
            np.testing.assert_allclose(output["scores"].numpy()[0], [3 / 7, 1.5 / 7, 1.5 / 7, 1.5 / 7], atol=2e-7)
    assert sizes[1] < 2.3 * sizes[0], sizes
    assert sizes[1] < 2_000_000, sizes


def test_facets_share_the_semantic_table():
    rng = np.random.default_rng(27)
    count = 100
    content = pd.DataFrame({"content_id": [f"item-{i:03}" for i in range(count)],
                            "content_type": ["text"] * count, "language_tag": ["en"] * count,
                            "embedding": rng.normal(size=(count, 256)).tolist()})
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    sizes = []
    for contexts in (["default"], [f"context-{i}" for i in range(10)]):
        content["recommendation_contexts"] = [contexts] * count
        with tempfile.TemporaryDirectory() as directory:
            model, path = _export(content, categories, directory)
            sizes.append(sum(file.stat().st_size for file in path.rglob("*") if file.is_file()))
            ids = _query(model, ["item-000"] * len(contexts), contexts, ["en"] * len(contexts))["content_ids"].numpy()
            assert all(np.array_equal(row, ids[0]) for row in ids)
    assert sizes[1] < 1.5 * sizes[0], sizes


@pytest.mark.parametrize("top_k,contexts,message", [(0, ["default"], "top_k must be positive"),
                                                 (3, [], "at least one serving facet")])
def test_invalid_index_configuration_is_not_promoted(top_k, contexts, message):
    content = pd.DataFrame({"content_id": ["a"], "content_type": ["text"], "language_tag": ["en"],
                            "recommendation_contexts": [contexts]})
    with tempfile.TemporaryDirectory() as directory:
        with pytest.raises(ValueError, match=message):
            _export(content, pd.DataFrame(columns=["content_id", "category_id"]), directory, top_k=top_k)
        assert list(Path(directory).iterdir()) == []


@pytest.mark.parametrize("with_semantics", [False, True])
def test_eager_and_traced_inference_agree_with_reference(with_semantics):
    content = pd.DataFrame({"content_id": ["a", "b", "c"], "content_type": ["text", "text", "video"],
                            "language_tag": ["en", "es", "en"], "labels": [["x", "y", "x"], ["x"], []],
                            "embedding": [[1.0, 0.0], [-1.0, 0.0], [0.0, 1.0]] if with_semantics else [None] * 3})
    categories = pd.DataFrame({"content_id": ["a", "b", "b"], "category_id": ["one", "one", "two"]})
    category_map = {"a": ["one"], "b": ["one", "two"], "c": []}
    index = ContentSimilarityIndex(content, categories, ["default\x1fen", "default\x1fes"],
                                   [tf.constant([0, 2]), tf.constant([1])], k=3)
    ids = tf.constant(["a", "b", "c", "a", "unknown", "a"])
    contexts = tf.constant(["default"] * 5 + ["missing"])
    languages = tf.constant(["en", "en", "en", "es", "en", "en"])
    eager = index(ids, contexts, languages)
    traced = tf.function(index)(ids, contexts, languages)
    np.testing.assert_array_equal(eager[1].numpy(), traced[1].numpy())
    np.testing.assert_allclose(eager[0].numpy(), traced[0].numpy(), atol=2e-7)
    rows = {row.content_id: row for row in content.itertuples()}
    for n, query in enumerate(["a", "b", "c", "a"]):
        for item, score in zip(eager[1].numpy()[n], eager[0].numpy()[n]):
            assert score == pytest.approx(_reference_score(rows[query], rows[item.decode()], category_map) if item else 0.0, abs=2e-7)
    np.testing.assert_array_equal(eager[1].numpy()[4:], [[b""] * 3] * 2)
    np.testing.assert_array_equal(eager[0].numpy()[4:], np.zeros((2, 3)))


def test_failed_content_contract_validation_keeps_previous_artifact(monkeypatch):
    from trainer import export

    content = pd.DataFrame({"content_id": ["a"], "content_type": ["text"], "language_tag": ["en"]})
    categories = pd.DataFrame(columns=["content_id", "category_id"])
    with tempfile.TemporaryDirectory() as directory:
        _export(content, categories, directory)

        def invalid(*args):
            raise ValueError("invalid content contract")

        monkeypatch.setattr(export, "_validate_exported_content_model", invalid)
        with pytest.raises(ValueError, match="invalid content contract"):
            _export(content, categories, directory)
        assert [entry.name for entry in Path(directory).iterdir()] == ["1"]
        previous = tf.saved_model.load(str(Path(directory) / "1"))
        assert _query(previous, ["a"], ["default"], ["en"])["content_ids"].numpy().tolist() == [[b"a"]]
