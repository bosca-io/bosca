"""pytest configuration for the recommendation-trainer test suite."""

import os

os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")


def pytest_configure(config):
    # Run tf.function bodies eagerly during tests so coverage traces the model methods (train_step,
    # compute_loss, call, score_embeddings) that otherwise execute only inside a compiled graph and show
    # as "uncovered" despite being exercised. The exported SavedModel still saves/loads its serving
    # signatures as graphs — only the in-process training runs eagerly here.
    import tensorflow as tf

    tf.config.run_functions_eagerly(True)
