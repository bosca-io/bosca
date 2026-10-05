"""Shared helpers for validating an exported recommender SavedModel.

Every test that trains a model uses ``assert_valid_recommender`` to prove the produced artifact honors the
served contract the Kotlin ``TfServingClient`` depends on: three signatures, string-in / content_ids+scores
out, correctly shaped, finite scores, ids drawn from the known corpus, and deterministic inference.
"""

import numpy as np
import tensorflow as tf

REQUIRED_SIGNATURES = {"serving_default", "similar", "similar_users", "rank"}


def decode_ids(id_tensor):
    return [s.decode("utf-8") if isinstance(s, bytes) else str(s) for s in id_tensor.numpy()]


def _all_finite(tensor):
    return bool(tf.reduce_all(tf.math.is_finite(tf.cast(tensor, tf.float32))).numpy())


def assert_valid_recommender(testcase, loaded, known_ids, top_k, sample_user, sample_item=None):
    """Asserts the loaded SavedModel exposes a valid, deterministic recommender serving surface."""
    known = set(known_ids)
    sample_item = sample_item if sample_item is not None else known_ids[0]

    signatures = set(loaded.signatures.keys())
    testcase.assertTrue(
        REQUIRED_SIGNATURES.issubset(signatures),
        f"missing signatures: {REQUIRED_SIGNATURES - signatures}",
    )

    # serving_default(user_id, context_type) -> content_ids (string) + scores (float), aligned shape.
    serve = loaded.signatures["serving_default"](
        user_id=tf.constant([sample_user]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
    )
    testcase.assertIn("content_ids", serve)
    testcase.assertIn("scores", serve)
    ids, scores = serve["content_ids"], serve["scores"]
    testcase.assertEqual(ids.dtype, tf.string)
    testcase.assertEqual(int(ids.shape[0]), 1)
    testcase.assertEqual(int(scores.shape[0]), 1)
    testcase.assertLessEqual(int(ids.shape[1]), top_k)
    testcase.assertEqual(int(ids.shape[1]), int(scores.shape[1]))
    testcase.assertTrue(_all_finite(scores))
    for content_id in decode_ids(ids[0]):
        if not content_id:
            continue
        testcase.assertIn(content_id, known)

    # Inference is deterministic: the same query returns byte-identical ids + scores.
    serve_again = loaded.signatures["serving_default"](
        user_id=tf.constant([sample_user]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
    )
    testcase.assertEqual(decode_ids(ids[0]), decode_ids(serve_again["content_ids"][0]))
    np.testing.assert_array_equal(scores.numpy(), serve_again["scores"].numpy())

    # similar(content_id, context_type) -> same contract (top_k + 1 candidates: self-match included).
    similar = loaded.signatures["similar"](
        content_id=tf.constant([sample_item]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
    )
    testcase.assertLessEqual(int(similar["content_ids"].shape[1]), top_k + 1)
    testcase.assertTrue(_all_finite(similar["scores"]))
    for content_id in decode_ids(similar["content_ids"][0]):
        if not content_id:
            continue
        testcase.assertIn(content_id, known)

    # similar_users(user_id) -> nearest users by learned embedding (same content_ids/scores envelope);
    # aligned, finite, and — for a known user — self is among the returned neighbors.
    neighbors = loaded.signatures["similar_users"](user_id=tf.constant([sample_user]))
    testcase.assertIn("content_ids", neighbors)
    testcase.assertIn("scores", neighbors)
    testcase.assertEqual(tuple(neighbors["content_ids"].shape), tuple(neighbors["scores"].shape))
    testcase.assertTrue(_all_finite(neighbors["scores"]))
    testcase.assertIn(sample_user, decode_ids(neighbors["content_ids"][0]))

    # rank(user_id, content_id) -> one finite score per pair.
    ranked = loaded.signatures["rank"](
        user_id=tf.constant([sample_user]), content_id=tf.constant([sample_item])
    )
    testcase.assertEqual(tuple(ranked["scores"].shape), (1,))
    testcase.assertTrue(_all_finite(ranked["scores"]))


def assert_valid_content_model(testcase, loaded, known_ids, top_k, sample_item=None):
    """Asserts the content (item->item) SavedModel exposes a valid, deterministic ``similar`` surface.

    The content model is item-keyed only — no user tower — so it has no ``serving_default(user)`` or
    ``rank``; it exists to answer ``similar(content_id)``.
    """
    known = set(known_ids)
    sample_item = sample_item if sample_item is not None else known_ids[0]

    testcase.assertIn("similar", loaded.signatures)
    similar = loaded.signatures["similar"](
        content_id=tf.constant([sample_item]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
    )
    testcase.assertIn("content_ids", similar)
    testcase.assertIn("scores", similar)
    ids, scores = similar["content_ids"], similar["scores"]
    testcase.assertEqual(ids.dtype, tf.string)
    testcase.assertEqual(int(ids.shape[0]), 1)
    testcase.assertLessEqual(int(ids.shape[1]), top_k + 1)  # +1: an item is its own nearest neighbor
    testcase.assertEqual(int(ids.shape[1]), int(scores.shape[1]))
    testcase.assertTrue(_all_finite(scores))
    for content_id in decode_ids(ids[0]):
        if not content_id:
            continue
        testcase.assertIn(content_id, known)

    # Inference is deterministic: the same query returns byte-identical ids.
    again = loaded.signatures["similar"](
        content_id=tf.constant([sample_item]), context_type=tf.constant(["default"]), language_tag=tf.constant(["en"])
    )
    testcase.assertEqual(decode_ids(ids[0]), decode_ids(again["content_ids"][0]))
