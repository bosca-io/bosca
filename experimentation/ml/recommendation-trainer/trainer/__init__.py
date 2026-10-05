"""Bosca recommendation trainer package.

Decomposed from the original single-file ``train.py`` into logically separated modules:

- ``data``        — loading training data from Bosca's analytics GraphQL
- ``features``    — pure numpy/pandas feature engineering (no TensorFlow)
- ``models``      — the two-tower Keras/TFRS model definitions
- ``datasets``    — turning DataFrames into ``tf.data.Dataset``s
- ``training``    — fitting the two-tower + ranking models
- ``content_only``— the content-only cold-start path (no interactions required)
- ``export``      — exporting the SavedModel with its retrieval/ranking signatures
- ``pipeline``    — ``run_training`` orchestration

``train.py`` stays a thin CLI + HTTP entrypoint that calls ``trainer.pipeline.run_training``.
"""

import os

# tensorflow-recommenders requires Keras 2, but TF 2.16+ defaults to Keras 3. This must be set before the
# first ``import tensorflow`` in the process. Importing any ``trainer.*`` submodule runs this __init__
# first, so the switch is in place before models/datasets/export import TF. The Docker image also sets
# TF_USE_LEGACY_KERAS=1; setting it here keeps local runs (uv run pytest / python train.py) consistent
# without relying on that env being pre-set. ``setdefault`` leaves an explicit override untouched.
os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")
