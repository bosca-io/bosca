# Build the datasets

In `trainer/datasets.py`, `prepare_datasets` **joins** observations to eligible content: it
matches rows with the same article ID and puts their columns together. The result is a table
named `merged`. Each row now has a reader, an article, a response label, and known article
features. This chapter explains the two variables that are easy to confuse:
`retrieval_positive` is a **column of decisions**, while `retrieval_mask` is the **array copied
from that column** to select rows.

## Put named inputs into `interaction_features`

The function creates a Python dictionary. Each key names an input or training value. Each value
is a **NumPy array**: an ordered collection with one entry per row of `merged`. Position 0 in
every array refers to the same first observation, position 1 to the same second observation,
and so on. This shared order is why the masking code below matters.

```python
interaction_features = {
    "user_id": merged["user_id"].to_numpy(),
    "content_id": merged["content_id"].to_numpy(),
    "content_type": merged["content_type"].to_numpy(),
    "language_tag": merged["language_tag"].to_numpy(),
    "label": merged["label"].to_numpy(dtype=np.float32),
    "sample_weight": merged["sample_weight"].to_numpy(dtype=np.float32),
    "source_id": merged["source_id"].to_numpy(dtype=object),
    "behavior_features": behavior_features,
}
```

`merged['language_tag']` selects a table column. `.to_numpy()` turns that column into an array.
`dtype=np.float32` makes labels and weights numbers TensorFlow can use. The actual function adds
optional editorial type, category, label, collection, semantic embedding, reader signal, and
affinity fields when those features exist.

`source_id` is the article Sam was viewing when a recommendation caused this response. It is
empty unless the response was attributed to a request in the context being trained, and the
source is a different eligible item. `behavior_features` holds four numbers about population
behavior: co-engagement, cohort co-engagement, learned-neighbor affinity, and rating affinity.
Co-engagement, for example, records whether readers tended to engage with both a source article
and a candidate.

These numbers help ranking score a pair; `label` records the response to learn from.

### Where the behavior numbers come from

Each run that trains a personalized model reads the `recommender-behavior` analytics query. It returns the
co-engagement edges and cohort memberships that Bosca's recommendation strategies computed.
`capture_snapshot` combines those rows with the latest explicit ratings and the previous model's
nearest-neighbor predictions into a **behavior snapshot**. It records when the snapshot was
taken (`available_at`) and saves it with the model it trains as `behavior.json`.

One run uses two snapshots for different jobs:

| Snapshot | Used for | Why |
| --- | --- | --- |
| The one built **during this run** | The ranking model's `behavior_model` when it scores reader-item pairs, and the candidate filter for `co_engaged` | Ranking uses the behavior captured for this model version. The embedding-only `similar` and `similar_users` functions do not use this behavior term. |
| The one saved with the **previous** completed model | `behavior_features` on training rows recorded after that snapshot was taken; older rows get zeros | Training must see only numbers that existed when the recommendation was shown. |

Why not use this run's query for training rows too? Suppose Sam read `article-9`, was shown
`article-12`, and completed it. That completion may have raised the `article-9 → article-12`
co-engagement score the query returns today. Giving the row today's score would put part of the
answer into the inputs. Ranking would learn to trust co-engagement too much, because at serving
time no score can include a response that has not happened yet. This is called **leakage**.

The query cannot rebuild an older value. A strategy refresh deletes its edges and writes new
ones, so the tables hold only the current scores. The query's `created <= asOf` filter only
checks whether today's row existed by that time; it cannot return the score the row had then.
Cohort memberships have no time filter at all. A saved snapshot keeps the values that existed
when it was taken, and `available_at` shows which later rows may use it.

So this run's behavior snapshot affects the behavior term of the new model's **ranking**
scores. It is also available as training input for the **next** version. What ranking learns
in this run is how much to trust each of the four behavior signals: the trainable
`behavior_gain`. It learns from the outcomes of training rows that could use the previous
snapshot's values, then applies the gain to this run's captured values at serving time.
Item-to-item and reader-to-reader embedding similarity do not use this behavior term.

#### Work through one behavior term by hand

For a finished model version, both the captured values and learned gains stay fixed while it
serves. During training of a new version, the trainer can change the gain for each signal.
`score_pairs` adds this term when it ranks a reader-item pair:

```text
behavior term = sum over the four signals of:
                captured value × context weight × learned gain
```

| Part | Where it comes from | Different for each pair? |
| --- | --- | --- |
| **Captured value** | This run's behavior snapshot; its calculation depends on the signal, as shown below | Yes: it can depend on the reader, source item, and candidate item |
| **Context weight** | The context's saved `coEngagement`, `cohortCoEngagement`, `learnedNeighbor`, or `rating` setting (default `1.0`) | No: one per signal |
| **Learned gain** | `behavior_gain`, fitted using outcomes on training rows with values from the previous snapshot | No: one per signal |

The four captured values are prepared in different ways:

| Signal | How its value is prepared |
| --- | --- |
| Co-engagement | Count of distinct readers who engaged with both items in the last 90 days, scaled with `count / (count + 10)`. |
| Cohort co-engagement | The same count scale, using the strongest matching cohort edge for this reader. |
| Learned-neighbor | When a previous model is available, its predictions are combined across nearby readers, then scaled with `score / (1 + score)`. |
| Rating | Earlier ratings are changed to signed values (`2 × rating − 1`) and combined according to how similar the rated items are to this candidate. The result can be negative. |

Suppose today's query says 15 readers engaged with both `article-9` and `article-12`. The fresh
co-engagement value is `15 / (15 + 10) = 0.6`. Suppose training found that past co-engagement
predicted responses only weakly and learned a gain of `0.4`. With the default context weight,
this pair's co-engagement contribution is `0.6 × 1.0 × 0.4 = 0.24`. A pair that 40 readers
engaged with both gets `40 / 50 = 0.8`, and `0.8 × 1.0 × 0.4 = 0.32`.

The gain multiplies every pair's value by the same amount; it does not adjust individual
pairs. Training asks, “When co-engagement was high in the past, did readers actually
respond?” Serving then applies that answer to the current co-engagement numbers. Because the
`count / (count + 10)` scale is fixed, a value of `0.6` means the same thing in every snapshot,
so a gain learned from an older snapshot still applies to newer numbers.

Cohort co-engagement, learned-neighbor, and rating signals count only for readers in the
model's vocabulary. Anonymous or unknown readers get only population co-engagement. The
behavior term is also only one part of the score: the content baseline and the personalized
tower match are added separately, as the training chapter shows.

The learned-neighbor signal is the one that cannot come from SQL. Readers “near” Sam are the
nearest reader vectors in a trained model, and the model being trained has no final vectors
until after training uses the snapshot. The previous model's `similar_users` and
`serving_default` functions supply those neighbors and what they would be recommended.

On a first run, or when the previous model's files cannot be found, there is no previous
snapshot. Every training row's behavior numbers are then zero and there is no learned-neighbor
signal. With only zeros to learn from, `behavior_gain` keeps its starting value of `1`. Serving
then uses this run's query results with the context's saved behavior weights unchanged.

Here, **`features` means the dictionary of values passed to model code**. Its contents include
`label` and `sample_weight` because training needs them, even though the towers do not look at
those two values when calculating a recommendation. The towers pick out the inputs they use.

## See the three example rows before masking

Using the observations from the previous chapter, assume the rows are in this order:

| Row position | `content_id` | `label` | `retrieval_positive` | One feature: `language_tag` |
| ---: | --- | ---: | --- | --- |
| 0 | `article-9` | `1.0` | `True` | `en` |
| 1 | `article-10` | `0.0` | `False` | `en` |
| 2 | `article-11` | `0.25` | `False` | `en` |

Every array in `interaction_features` has three entries in that same order. If one array were
filtered without the others, a reader ID could be paired with the wrong content ID. The code
therefore applies **one mask to every field**.

## Copy the decision column into a Boolean mask

```python
retrieval_mask = merged["retrieval_positive"].to_numpy(dtype=bool)
```

For the three rows above, `retrieval_mask` is `[True, False, False]`. A **mask** is simply an
array of yes/no values: keep position 0, drop positions 1 and 2. `dtype=bool` ensures the values
are Booleans rather than strings or numbers.

```python
retrieval_features = {
    key: tf.ragged.boolean_mask(value, retrieval_mask) if isinstance(value, tf.RaggedTensor) else value[retrieval_mask]
    for key, value in interaction_features.items()
}
```

Read this comprehension as a loop: “For every `key` and `value` in `interaction_features`, put a
filtered value under the same key in `retrieval_features`.” Ordinary arrays use
`value[retrieval_mask]`. A **ragged tensor** stores variable-length lists, such as different
numbers of collections per item; it needs TensorFlow's `tf.ragged.boolean_mask` instead.

After this line, `retrieval_features['content_id']` contains only `article-9`. Its reader ID,
language, label, and weight have been filtered to that same row.

## Make the two learning datasets

```python
retrieval_features["sample_weight"] = retrieval_features["sample_weight"] * retrieval_features["label"]
retrieval_dataset = tf.data.Dataset.from_tensor_slices(retrieval_features)
ranking_dataset = tf.data.Dataset.from_tensor_slices(interaction_features)
```

A **`tf.data.Dataset`** is TensorFlow's stream of training examples. `from_tensor_slices` cuts
each array into rows, so one dataset element holds the same position from every key. Keras's
`fit` reads a dataset in batches and can cache or shuffle it along the way.

| Line | What it changes | Why |
| --- | --- | --- |
| Multiply retrieval weight by label | A weaker positive gets less retrieval influence than a stronger one with the same confidence and age. | Positive engagement has degrees, even after the yes/no selection. |
| `from_tensor_slices(retrieval_features)` | Makes one TensorFlow example per selected positive row. | The retrieval task should learn from positive reader-item pairs. |
| `from_tensor_slices(interaction_features)` | Makes one example per labeled row, including zero and weak labels. | The ranking task needs both favorable and unfavorable evidence. |

`prepare_datasets` also returns `content_dataset`, one row per candidate item, and
`user_dataset`, one row per eligible profile. The user dataset includes profiles with no
interaction history, so saved serving lookups can use their available signals. A **vocabulary**
dictionary accompanies the datasets and lists known IDs, categories, languages, and other values.

## One more gate before fitting

`run_training` always builds the content artifact when content exists. It starts the personalized
path only after eligible interactions plus feedback reach `min_interactions` (100 by default).
`_train_personalized` then counts the **prepared positive rows** in `retrieval_dataset` and skips
the personalized fit if fewer than the same minimum remain. A pile of dismissals can pass the raw
count and still fail the positive-row check. The content artifact can still be refreshed.

**Check:** With mask `[True, False, False]`, how many retrieval examples and ranking examples do
we have? One retrieval example; three ranking examples.
