"""Fitting the two-tower retrieval model and the ranking head."""

import logging
import random
from time import perf_counter

import numpy as np
import tensorflow as tf

from trainer.models import BoscaRanker, BoscaRecommender, ContentModel, RankingModel, UserModel, build_item_query_model

log = logging.getLogger(__name__)


def train_model(
    retrieval_dataset: tf.data.Dataset,
    ranking_dataset: tf.data.Dataset,
    content_dataset: tf.data.Dataset,
    vocabs: dict,
    embedding_dim: int,
    epochs: int,
    batch_size: int,
    learning_rate: float,
    random_seed: int = 42,
    weights: dict | None = None,
    content_similarity=None,
    behavior_model=None,
) -> tuple[BoscaRecommender, "RankingModel"]:
    """Trains the two-tower retrieval model on positive pairs, then a ranking head on all labeled rows."""
    # Keep initialization repeatable alongside the explicitly seeded dataset shuffles below.
    random.seed(random_seed)
    np.random.seed(random_seed)
    tf.random.set_seed(random_seed)
    user_model = UserModel(
        vocabs["user_ids"],
        len(vocabs.get("signal_tokens", [])),
        len(vocabs.get("category_ids", [])),
        embedding_dim,
        unique_affinity_tokens=vocabs.get("affinity_tokens", []),
        weights=weights,
    )
    content_model = ContentModel(
        vocabs["content_ids"],
        vocabs["content_types"],
        vocabs["languages"],
        vocabs.get("category_ids", []),
        vocabs.get("label_ids", []),
        embedding_dim,
        vocabs.get("embedding_dim", 0),
        unique_editorial_types=vocabs.get("editorial_types", []),
        unique_collection_ids=vocabs.get("collection_ids", []),
        weights=weights,
    )

    model = BoscaRecommender(user_model, content_model, content_dataset)
    model.compile(optimizer=tf.keras.optimizers.Adagrad(learning_rate))

    log.info("Training retrieval (max %d epochs, early-stopped) with batch size %d...", epochs, batch_size)
    _fit_to_convergence(model, retrieval_dataset, epochs, batch_size, "retrieval", random_seed)

    # Second stage: train the ranking head on engagement. The towers are frozen so the retrieval/similar
    # embeddings (and the indexes built from them) stay intact while the head learns to order candidates.
    user_model.trainable = False
    content_model.trainable = False
    ranking_model = RankingModel(
        user_model, content_model, weights, vocabs.get("editorial_types_by_id"),
        source_query_model=build_item_query_model(content_model, content_dataset),
        content_similarity=content_similarity,
        behavior_model=behavior_model,
    )
    ranker = BoscaRanker(ranking_model)
    ranker.compile(optimizer=tf.keras.optimizers.Adagrad(learning_rate))
    log.info("Training ranking head (max %d epochs, early-stopped)...", epochs)
    _fit_to_convergence(ranker, ranking_dataset, epochs, batch_size, "ranking", random_seed + 1)
    user_model.trainable = True
    content_model.trainable = True

    return model, ranking_model


def _fit_to_convergence(
    model,
    dataset: tf.data.Dataset,
    max_epochs: int,
    batch_size: int,
    stage: str,
    random_seed: int,
) -> None:
    """Fits `model` until convergence rather than for a fixed epoch count.

    Epoch counts that suit one data volume under- or over-fit another. Early stopping makes the run
    self-tuning while every available row remains in the fit: it monitors training loss, restores the best
    weights, and caps work at `max_epochs`. Actual recommendation quality is measured from live outcomes,
    not by removing interactions from the model being built.
    """
    # Cache examples, then reshuffle each epoch so retrieval sees new in-batch negatives.
    train_data = dataset.cache().shuffle(100_000, seed=random_seed, reshuffle_each_iteration=True).batch(batch_size)
    monitor = "mean_loss"
    early_stop = tf.keras.callbacks.EarlyStopping(
        monitor=monitor, mode="min", patience=5, min_delta=1e-4, restore_best_weights=True,
    )
    epoch_number = 0
    epoch_started = 0.0
    last_report = 0.0

    def epoch_begin(epoch, logs):
        nonlocal epoch_number, epoch_started, last_report
        epoch_number = epoch + 1
        epoch_started = last_report = perf_counter()
        log.info("%s epoch %d/%d started", stage, epoch_number, max_epochs)

    def batch_end(batch, logs):
        nonlocal last_report
        now = perf_counter()
        if batch == 0 or now - last_report >= 30.0:
            log.info("%s epoch %d/%d batch %d: mean_loss=%.6f elapsed_seconds=%.1f",
                     stage, epoch_number, max_epochs, batch + 1, logs.get(monitor, float("nan")), now - epoch_started)
            last_report = now

    progress = tf.keras.callbacks.LambdaCallback(on_epoch_begin=epoch_begin, on_train_batch_end=batch_end)
    history = model.fit(
        train_data, epochs=max_epochs, callbacks=[early_stop, progress], verbose=2,
    )
    ran = len(history.history.get(monitor, history.history.get("loss", [])))
    log.info("%s stage: stopped after %d/%d epochs (monitor=%s)", stage, ran, max_epochs, monitor)
