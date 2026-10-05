# Build the personalized towers

The personalized artifact learns from past reader-item examples. It has two **towers** in
`trainer/models.py`: `UserModel` calculates numbers for a reader, and `ContentModel` calculates
numbers for an item. Each tower is a series of **layers**. A layer takes numbers, performs a
calculation, and passes its output numbers to the next layer. For example, a dense layer can
multiply inputs `[1, 0]` by learned weights `[0.4, 0.7]`, add a bias of `0.1`, and produce
`1×0.4 + 0×0.7 + 0.1 = 0.5`. Training changes those weights and biases.

The final output of each tower is an **embedding**, or list of numbers. `embedding_dim` sets
how many numbers are in that list: 64 by default for both towers. Matching lengths let the
model multiply corresponding reader and item numbers and add them to score a pair. The
training chapter works through that score by hand.

## First, turn category names into numbers

Neural-network layers consume numeric arrays, not category names. `trainer/features.py` builds
a **multi-hot** row: one position per known category, with `1` for each category the item has.

Assume the category vocabulary is `['science', 'sports', 'technology']`:

| Item | Categories | Multi-hot row |
| --- | --- | --- |
| `article-9` | science | `[1, 0, 0]` |
| `article-10` | sports | `[0, 1, 0]` |
| `article-15` | science and technology | `[1, 0, 1]` |

The vocabulary sets the column order. `build_multi_hot_matrix` starts with zeros and sets
the positions of present categories to one:

```python
index = {key: i for i, key in enumerate(vocab)}
matrix = np.zeros((len(rows), len(index)), dtype=np.float32)
for r, row_id in enumerate(rows):
    for key in keys_by_row.get(row_id, []):
        col = index.get(key)
        if col is not None:
            matrix[r, col] = 1.0
```

`index` maps `science` to position 0, `sports` to 1, and `technology` to 2. `r` is the item's
row position. `col` is the category's column position. The function also handles an empty
vocabulary by producing a zero-width matrix; the dataset omits that optional feature.

A dense layer can learn how much each position matters. Imagine one output number with learned
weights `[0.4, -0.2, 0.7]` and bias `0`. For `article-15`'s `[1, 0, 1]` input, that number is
`1×0.4 + 0×(-0.2) + 1×0.7 + 0 = 1.1`. The actual category layer learns **16** outputs at
once, each with its own weights and bias. Its ReLU activation replaces a negative output with
zero. Training changes these weights from their initial values.

The personalized content tower uses these dense training arrays. The **content-only index**
from the previous chapter stores category membership sparsely; the two paths use the same
item information for different computations.

## Turn IDs into learned vectors

`UserModel` creates two layers for the reader ID:

```python
self.user_lookup = tf.keras.layers.StringLookup(
    vocabulary=unique_user_ids, mask_token=None
)
self.user_embedding = tf.keras.layers.Embedding(
    len(unique_user_ids) + 1, embedding_dim, embeddings_initializer="zeros"
)
```

`unique_user_ids` is the list of profile IDs included when the trainer builds this model.
Suppose it contains `['reader-1', 'reader-2']`. Follow the two code lines in order:

1. `StringLookup` gives each ID a row number. In this example, `reader-1` becomes `1` and
   `reader-2` becomes `2`. Row `0` is reserved for an ID not in `unique_user_ids`; the code
   calls that an **out-of-vocabulary** ID.
2. `Embedding` creates a table with `len(unique_user_ids) + 1` rows. For our two IDs, that is
   three rows: the reserved row and one per known ID. `embedding_dim` is the number of columns,
   64 by default. `embeddings_initializer="zeros"` means every number in this ID table starts
   at zero.

Later, `self.user_embedding(self.user_lookup(features["user_id"]))` runs those steps together.
If a batch contains `['reader-1', 'reader-2']`, the inner lookup produces `[1, 2]`; the outer
lookup retrieves table rows `1` and `2`. It produces two lists of 64 numbers, one per reader.
Those are **ID embeddings**, not finished recommendations or scores. During the first training
stage, positive reader-item examples can change the selected ID rows. The ranking stage then
keeps the towers fixed while learning its own scoring layers.

A **feature batch** is a dictionary where each key holds values for several examples. The
`features` dictionary can also carry configured user signals and earlier category activity.
Those inputs help make a reader vector even when a known profile has no interaction history;
its ID row has not learned anything yet.

## Combine the available user inputs

This excerpt is from `UserModel.call`:

```python
user_emb = self.user_embedding(self.user_lookup(features["user_id"]))
parts = [user_emb]
# ... pooled editorial-type and collection interests are appended here when present ...
if self.num_signal_tokens > 0 and "user_signal_multi_hot" in features:
    parts.append(self.signal_dense(features["user_signal_multi_hot"]))
if self.num_categories > 0 and "user_category_affinity" in features:
    affinity = self.affinity_dropout(features["user_category_affinity"], training=training)
    parts.append(self.affinity_dense(affinity))
return self.dense(tf.concat(parts, axis=1) if len(parts) > 1 else parts[0], training=training)
```

| Expression | Meaning |
| --- | --- |
| `features['user_id']` | Read the batch of reader IDs. |
| `user_lookup` then `user_embedding` | Change each string ID into an integer, then into a learned vector. |
| `parts.append(...)` | Add another numeric signal only when it exists. `signal_dense` projects configured profile signals; `affinity_dense` projects past category interests. A dense layer learns numeric weights for combining its inputs. |
| `tf.concat(parts, axis=1)` | Join each reader's vectors side by side. `axis=1` is the feature dimension, not the reader-row dimension. |
| `self.dense(...)` | Learn a combination and return one `embedding_dim`-wide vector per reader. |

The elided lines pool sparse editorial-type and direct-collection interests. Category
affinity used **during training** is built only from outcomes earlier than that example's
feature time, so its own answer is not fed back as an input. At serving, the saved reader row
uses the captured history available for that model version.

`affinity_dropout` is a Keras **dropout** layer. On each fitting step it sets a random 30% of
the affinity numbers to zero and scales the rest up so their total stays about the same. This
guards against **memorization**, also called **overfitting**: learning quirks of the training
rows that do not hold for new ones. With affinity sometimes hidden, the tower cannot rely on
affinity alone and also learns from the reader's ID and profile signals. The `training` flag
turns dropout off at serving time, so every recommendation sees the full input.

## Build the item vector

`ContentModel.call` starts with item ID, MIME type (file type), language, and any available
categories, labels, editorial type, direct collections, and text embedding. It looks up short
learned lists for values such as language and file type. For categories, it applies a dense
layer to the multi-hot row you calculated above.

When a text embedding is available, it is an **input** made earlier from the item's text. In
the code, `self.embedding_dense(features["embedding"])` changes that input into 32 numbers.
This is called a **projection**: each of the 32 outputs is a learned combination of the input
numbers, using the same multiply-and-add calculation and ReLU as the category example.

Before joining the pieces, `ContentModel.call` multiplies each property's part by that
property's normalized similarity weight from the saved context. These are the same weights the
content model uses. A signal whose weight is zero contributes only zeros. The learned item-ID
vector is not scaled. The code then joins these parts and applies two more dense layers. The
last one produces the item's 64-number **output embedding**. The text embedding and the output
embedding are separate lists produced for different purposes.

The content-only artifact from the previous chapter uses the supplied text embedding directly
in its similarity calculation. It does not produce this learned 64-number output.

**Check:** If a content item has no labels, must we invent a label for it? No. The optional
label branch is skipped when the vocabulary is empty. A training **label** (engagement target)
is also a different concept from an item's editorial **labels** (content features).
