# Build the content model

The content artifact answers: **“Given an item, which eligible items are similar?”** It can be
built even when no reader has interacted with anything. This is the path through
`trainer/content_only.py`, `trainer/content_similarity.py`, and
`trainer/export.py → export_content_model`.

## What goes into this path?

It uses the `content` and `categories` tables from chapter 1. MIME type says what kind of
file an item is, for example `text/html`. Other item features include language, categories,
labels, editorial type, direct collection memberships, and a semantic
embedding. A **semantic embedding** is a list of numbers made by a separate text model from
the item's extracted text. The text model has learned to place related passages in similar
directions in this number space. No one number is a named topic; the whole list is compared
with other lists. If a long item was split into pieces, the trainer combines the piece vectors
into one item vector. The content-only path compares these supplied numbers; it does not train
the text model that made them.

The `run_content_only_training` function accepts `feedback` and `signals` so its call shape
matches the personalized path. It does not use either one: this model is item-keyed, with no
reader tower.

## Build an index of eligible items

`export_content_model` removes duplicate IDs and sorts the catalog. It also identifies each
context and language **facet**: the subset of items eligible for that combination. It then
constructs the index:

```python
item_index = ContentSimilarityIndex(
    content,
    categories,
    context_types,
    candidate_indexes,
    item_top_k,
    weights=weights,
)
```

| Argument | Meaning |
| --- | --- |
| `content`, `categories` | The item properties to compare. |
| `context_types` | The saved context-and-language facets available for serving. |
| `candidate_indexes` | Which item positions are eligible in each facet. |
| `item_top_k` | How many results to keep for an item query. |
| `weights` | The saved importance of each similarity signal. |

An **index** here is stored item features plus lookup structures. It is not a fitted neural
network. Sparse membership structures store categories, labels, and collections that an item
actually has, rather than a huge row with a column for every possible category.

## Score one source item against candidates

Suppose the source is `article-9` (English, science). A candidate also in English gets a
language match. A candidate also in science gets category similarity. Each signal contributes
according to its saved weight.

This excerpt from `ContentSimilarityIndex._scores` shows how terms are added:

```python
scores = (
    self.weights["mime"] * tf.cast(self.content_types == self.content_types[item], tf.float32)
    + self.weights["language"] * tf.cast(self.languages == self.languages[item], tf.float32)
    + self.weights["categories"] * self.categories.scores(item)
    + self.weights["labels"] * self.labels.scores(item)
    + self.weights["type"] * tf.cast(
        (self.editorial_types == self.editorial_types[item]) & (self.editorial_types != ""), tf.float32,
    )
    + self.weights["collections"] * self.collections.scores(item)
)
```

`item` is the source item's numeric position in the index.
`self.languages == self.languages[item]` compares every candidate language with the source language. The result is
a true/false array; `tf.cast(..., tf.float32)` turns matches into `1.0` and nonmatches into `0.0`.
Multiplying by the language weight gives the language contribution. Category, label, and
collection methods calculate overlap scores instead of exact string equality.
These **similarity weights** set the importance of item properties. They are different from
`sample_weight`, which sets the influence of one training observation in the personalized path.

### Work through the addition by hand

For this teaching example, suppose the saved settings give raw similarity weights `1.0` for
MIME type, `0.5` for language, `0.5` for categories, and `0` for the other four signals.
`normalized_similarity` divides each weight by their sum, `2.0`. The weights used in the score
are therefore **0.50, 0.25, and 0.25**. Normalizing keeps their proportions while making the
whole similarity group sum to one.

Let the source `article-9` be `text/html`, English, science. Each feature comparison produces
a number between zero and one. With one category per item, a matching category scores one
and a different category scores zero:

| Candidate | MIME match | Language match | Category match | Arithmetic | Similarity score |
| --- | ---: | ---: | ---: | --- | ---: |
| `article-12`: same MIME, English, science | 1 | 1 | 1 | `0.50×1 + 0.25×1 + 0.25×1` | **1.00** |
| `article-10`: same MIME, English, sports | 1 | 1 | 0 | `0.50×1 + 0.25×1 + 0.25×0` | **0.75** |
| `article-13`: different MIME, English, science | 0 | 1 | 1 | `0.50×0 + 0.25×1 + 0.25×1` | **0.50** |

That is a **weighted sum**: multiply each comparison by its importance and add the results.
These settings and items are illustrative; the actual weights come from the saved context.
If an item has multiple categories, `self.categories.scores(item)` uses overlap divided by
the square root of the two category counts. For example, one shared category between a
one-category source and a two-category candidate scores `1 / √(1×2) ≈ 0.71`, rather than a
full `1.0` match. This keeps “one shared category” from counting as identical category sets.

If semantic embeddings exist, `_scores` also adds their nonnegative **cosine similarity**:
how closely two lists of numbers point in the same direction. Use the invented two-number
vectors from the first chapter. `[1, 0]` and `[0.8, 0.6]` point more alike than `[1, 0]` and
`[0, 1]`. Multiply matching positions and add: `1×0.8 + 0×0.6 = 0.8` for the first pair,
versus `1×0 + 0×1 = 0` for the second. Those vectors all have length 1, so these sums are
their cosine similarities. The code makes the same kind of comparison with the real,
longer vectors and treats a negative result as zero. A missing semantic embedding leaves the
other terms available. The illustrative settings above gave this term weight zero so the
earlier arithmetic stays small.

`default_scores` then applies the context's overall content weight and editorial type
preference. A zero type preference reduces a score; it does not make an item ineligible. Facet
membership, not preference, controls which items can appear.

Its multiplier is `content_weight × (1 + type_preference) / 2`. If the content weight is `1`
and the type preference is `0.5`, a similarity score of `1.00` becomes `0.75`; a score of
`0.75` becomes `0.5625`. This changes the score scale while preserving this example's order.

## Why this path exists

`article-12` may be newly published with no activity. On the next content-model build, its
known features can still place it near related items. The personalized path cannot learn an
item's behavioral history before any behavior exists.

The content SavedModel exports `similar` and `similar_page`. Both accept a source item plus
context and language; `similar_page` also accepts an offset for another page and leaves the
source item out of its results. The exported `serving_default` is an alias for the item-keyed
`similar` function, not a user feed. An `explain` function reports the content contribution
for a source-candidate pair.

**Check:** Does the content model learn a new 64-number item vector from feedback? No. This
content-only artifact calculates exact weighted similarity from supplied item features. The
next chapter introduces the separate learned content tower inside the personalized artifact.
