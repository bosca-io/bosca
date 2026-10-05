# Train retrieval and ranking

`trainer/training.py → train_model` fits the personalized model in **two stages**. The datasets
chapter explains why the first stage sees only positive rows while the second sees all labeled
rows. **Retrieval** learns to bring plausible articles forward from the eligible catalog.
**Ranking** gives those articles a more detailed order. Here is what each stage does.

## Stage 1: fit the two towers for retrieval

`BoscaRecommender.compute_loss` receives a **batch**—several training examples processed
together—from `retrieval_dataset`. In this function,
`features` is the dictionary of batched arrays introduced in chapter 3:

```python
user_embeddings = self.user_model(_user_features(features), training=training)
content_embeddings = self.content_model(content_features, training=training)
loss = self.task(user_embeddings, content_embeddings, candidate_ids=features["content_id"],
                 sample_weight=features.get("sample_weight"), compute_metrics=not training)
```

`_user_features` selects the user tower's inputs from the batch. `content_features` is another
dictionary, built between the two tower calls and omitted here, with item ID, type, language,
and optional item features. The two tower calls output arrays of vectors: if a batch had 32
rows and `embedding_dim` is 64, each output would have shape **32 × 64**. The default batch size
is 8,192 rows.

| Argument to `self.task` | What it tells the retrieval task |
| --- | --- |
| `user_embeddings` | Where each reader is in the learned vector space. |
| `content_embeddings` | Where each observed item is in the same vector space. |
| `candidate_ids` | Which item each observed vector belongs to. |
| `sample_weight` | How strongly each selected positive row should affect the loss. In dataset preparation, this was multiplied by the positive row's label. |

`self.task` is a TensorFlow Recommenders **task**: a ready-made object that holds a loss
formula plus optional **metrics**. A metric is a number reported so people can watch progress.
Unlike the loss, it does not change any weights. `compute_metrics=not training` skips the
metrics while fitting, because this task's metric scores every item in the catalog and would
slow each step.

**Loss** is a number the optimizer tries to reduce by changing trainable layer weights. The
retrieval task teaches observed positive pairs to match more strongly than the other items in
the same batch. Those other items act as the wrong answers. When two readers in one batch both
completed `article-9`, it appears twice. `remove_accidental_hits=True` stops each copy from
counting as a wrong answer for the other reader.

The trainer uses **Adagrad**, an **optimizer**: the algorithm that turns gradients into actual
weight changes. Each step moves a weight against its gradient by a small amount. The base size
of that step is the **learning rate**, `0.01` by default. Adagrad also keeps a running total of
each weight's squared gradients and divides that weight's step by the total's square root.
Weights that have already received large or frequent updates take smaller steps, while rarely
updated ones take larger steps. That property makes Adagrad a common choice for embedding
tables, where most ID rows appear in only a few batches.

`mean_loss` is the average loss per example over an epoch; it is tracked to decide when to stop
fitting. It is a training measure, not proof that live recommendations improved.

### Do the retrieval math with two numbers

The retrieval task scores a reader-item pair with a **dot product**: multiply corresponding
vector positions and add them. Use tiny vectors only to see the arithmetic; real outputs are
usually 64 numbers wide.

| Pair | Toy vectors | Dot-product arithmetic | Score |
| --- | --- | --- | ---: |
| `reader-1` with its observed positive `article-9` | `[2, 1]` and `[1, 1]` | `2×1 + 1×1` | `3` |
| `reader-1` with another item's vector | `[2, 1]` and `[0, 1]` | `2×0 + 1×1` | `1` |

The task compares the observed item with other candidates in the training batch. It converts
these scores into a share for the observed item using **softmax**. First it applies an
**exponential** to each score: `e` is about `2.718`, so `e³ ≈ 20.09` and `e¹ ≈ 2.72`. Then
it divides the observed item's value by the total: `20.09 / (20.09 + 2.72) ≈ 0.88`.
Larger scores receive a larger share. The loss for this example is `−ln(0.88) ≈ 0.13`;
`ln` is the natural logarithm, the reverse of the exponential. You can read `−ln(share)`
as a penalty: a share near one gives a small penalty, and a share near zero gives a large one.
If this row's retrieval sample weight is `2`, its contribution is about `2 × 0.13 = 0.26`.
Real batches contain more candidates, and the task sums their weighted losses. The optimizer
adjusts tower weights so observed pairs tend to score higher than alternatives.

This penalty has a name: **cross-entropy**. It is `−ln` of the share the model gave to the
answer that actually happened. Here the answer is “which of the batch's items did this reader
engage with?”, so there are many possible answers and softmax splits the shares among them.
The ranking stage below uses a two-answer version of the same idea.

## Stage 2: freeze the towers and fit ranking

After retrieval, the code changes the towers' `trainable` flags:

```python
user_model.trainable = False
content_model.trainable = False
ranking_model = RankingModel(
    user_model, content_model, weights, vocabs.get("editorial_types_by_id"),
    source_query_model=build_item_query_model(content_model, content_dataset),
    content_similarity=content_similarity,
    behavior_model=behavior_model,
)
ranker = BoscaRanker(ranking_model)
```

**Freeze** means the next optimizer step does not change the towers' learned weights. The item
and reader vectors remain the ones retrieval just learned. `RankingModel` adds scoring logic
that can use a content baseline, the tower match, source-item relevance, and captured
behavioral signals. `behavior_model` is built from the saved behavior snapshot for this run.

For the basic reader-item match, the ranking model uses a dot product plus a learned
correction:

```python
relevance = tf.reduce_sum(user_embedding * content_embedding, axis=1, keepdims=True)
return relevance + self.score(tf.concat([user_embedding, content_embedding], axis=1), training=training)
```

Multiplication pairs each number in the reader vector with the number in the same position
of the item vector. `reduce_sum` adds those products into one score per pair. The `self.score`
network looks at both full vectors and learns a correction. The complete `score_pairs`
function combines this with the saved context's content and behavior terms. A score is used to
**order** eligible items; a larger score comes first.

The dot-product table above explains the `reduce_sum` part of this code. The ranking score is
more than that dot product: the learned correction, content baseline, and available behavior
signals can raise or lower the final result.

## Learn from all labels, including rejection

`BoscaRanker` receives `ranking_dataset`, so the dismissal of `article-10` and the weak rating
of `article-11` are present. Its task uses weighted **binary cross-entropy**.

- **Binary** refers to two endpoints: positive engagement (`1`) and no engagement (`0`). A
  prepared label can also fall between them, like Sam's `0.25` rating. Ranking scores each
  reader-article pair; retrieval compares an observed item with other items in a batch.
- **Cross-entropy** penalizes a score that does not fit the label. For label `1`, the penalty
  is `−ln(p)`; for label `0`, it is `−ln(1 − p)`. A label between them blends those penalties.
  Here `p` is a temporary 0–1 value calculated from the score for the loss, not a measured
  chance of engagement. Retrieval uses a related penalty across many items.
- **What it is used for:** the loss works with both the `0` and `1` endpoints and the partial
  labels made during observation preparation. A score strongly opposed to an endpoint label
  receives a large penalty.
- **Weighted** means each row's penalty is multiplied by its `sample_weight` before rows are
  averaged.

The next subsection works through the arithmetic. Here is the code:

```python
self.task = tfrs.tasks.Ranking(
    loss=tf.keras.losses.BinaryCrossentropy(from_logits=True),
    metrics=[tf.keras.metrics.BinaryCrossentropy(from_logits=True)],
)
loss = self.task(labels=labels, predictions=predictions, sample_weight=sample_weight)
```

The `label` is the known training target from observations; `predictions` are current ranking
scores. `sample_weight` controls each row's influence. A **logit** is a raw score that may be
negative or positive. `from_logits=True` tells the loss function it will receive those raw
scores. The served score remains a logit: use it to order articles, not as a claim that the
number is a measured chance of engagement. `metrics=[...]` reports the same cross-entropy for
monitoring; only `loss=` changes weights. The optimizer changes the ranking head's weights to
reduce that loss.

### What does that penalty do?

For calculating loss, the function temporarily converts a logit `s` to a number between zero
and one with `sigmoid(s) = 1 / (1 + e^(−s))`. A score of `+2` gives about `0.88`; a score of
`−2` gives about `0.12`. A completed item with label `1` gets a small penalty if the score is
`+2`: `−ln(0.88) ≈ 0.13`. A dismissed item with label `0` gets a large penalty for that same
score: `−ln(1 − 0.88) ≈ 2.13`. Moving the dismissal's score toward `−2` would reduce its
penalty to about `0.13`.

For a partial target such as the `0.25` rating, the penalty blends both sides:
`−[0.25 × ln(p) + 0.75 × ln(1 − p)]`, where `p = sigmoid(score)`. Each row's result is
multiplied by its `sample_weight`. The model therefore learns from both strong responses and
weaker ratings. The conversion is internal to the loss calculation; serving returns the
original logit for ordering.

**Why two stages?** Retrieval learns broadly useful vectors from positive examples. Ranking
then learns a more detailed order from both positive and negative evidence without changing
the vectors and indexes built by retrieval.

## Stop when training loss stops improving

Each stage is fitted by `_fit_to_convergence`, which calls TensorFlow's `fit` (the prologue
defines **fitting**). An **epoch** is one pass over the training examples. The helper caches
the examples in memory after the first pass and reshuffles them before every epoch.
Reshuffling matters for retrieval: the wrong answers for each row are the other items in its
batch, so new batches give it new comparisons. The configured epoch count, 50 by default, is a
maximum. Early stopping watches `mean_loss`, waits up to five epochs without sufficient improvement, and restores the best
weights seen during that fit. This controls fitting time; it is not a recommendation-quality
test.

**Check:** Why is `article-10` in ranking but absent from retrieval? Its dismissal is useful
negative evidence for ordering, but it must not teach the retrieval towers that it is a
desirable reader-item pair.
