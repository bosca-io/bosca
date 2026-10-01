# Export and verify the models

After training, the model's numbers still live only in the running Python process. To answer
later recommendation requests—called **serving**—Bosca saves both the numbers and the
calculations that use them. TensorFlow calls that package a **SavedModel**. Its saved **graph**
is the connected sequence of calculations, not a chart or picture. `trainer/export.py` saves
each model and checks the saved files before keeping the version.

## Save a candidate, then reload it

The personalized export saves into a temporary candidate directory, loads that exact saved
graph, and probes it:

```python
loaded = tf.saved_model.load(candidate_dir)
validation = _validate_exported_recommender(
    loaded,
    set(_decode_ids(tf.gather(candidate_ids, candidate_indexes[validation_facet_index]))),
    set(vocabs["user_ids"]),
    test_user,
    validation_item,
    validation_context,
    validation_language,
)
os.replace(candidate_dir, version_dir)
```

| Line | Meaning | Why it matters |
| --- | --- | --- |
| `tf.saved_model.load(candidate_dir)` | Open the graph from disk, not the Python object still in memory. | Saving can reveal problems that fitting alone cannot. |
| `_validate_exported_recommender(...)` | Call the real serving signatures with known IDs, a context, and a language. | Check the contract clients depend on. |
| `os.replace(...)` | Move the valid candidate to its versioned directory. | A failed candidate does not become the completed version. |

The content artifact follows the same save, reload, and check pattern with
`_validate_exported_content_model`.

## What does the personalized export store?

Before saving, `export_model` runs the frozen item tower once over every eligible item and the
frozen reader tower once over every eligible profile. It keeps those vectors in lookup tables, so
serving does not recalculate a tower for a known ID.

| Question | How the saved graph answers it |
| --- | --- |
| Which items should this reader see? | Applies the full ranking score from the training chapter to every item in the requested context-and-language facet, then keeps the top results. |
| Which items resemble this item? | Compares the source item's vector with item vectors in the same facet. By default it uses brute-force search. With `use_scann` enabled, facets of at least 100 items use ScaNN; smaller facets still use brute-force search. |
| Which readers resemble this reader? | Compares reader vectors to find the nearest profiles. |

**Brute-force search** scores every item in the facet and keeps the best matches. **ScaNN** is
Google's approximate nearest-neighbor library: it groups similar vectors in advance and
searches promising groups. That can speed up a large search but may miss a close match.

A reader ID that was not in the vocabulary gets an empty personalized feed instead of results
from the reserved out-of-vocabulary row. The serving layer can then use the content model instead.

## What is a serving signature?

A **signature** is a named SavedModel function with specified inputs and outputs. The content
artifact's `similar_page`, for example, accepts a source `content_id`, `context_type`,
`language_tag`, and `offset`; it returns candidate IDs and scores. The personalized artifact
has functions including `feed_page` for a reader feed, `related` for suggestions based on an
article the reader is viewing, `co_engaged` for items connected by earlier reader activity,
`rank` for scores, and `explain` for score details.

Validation checks result shapes, finite scores, and returned IDs, where present, against the
allowed item facet or reader set. It compares repeated calls for selected functions. For the
paged `feed_page`, `related`, `co_engaged`, and `similar_page` functions, it also checks score
order and that pages do not repeat items. The source-keyed paged functions must leave the
source item out and return nothing for an unknown source. The unpaged `similar` function can include its
source item; this source-exclusion check applies to `similar_page`, not `similar`.
Validation also checks that `serving_default` and `feed_page` return no personalized items
for an unknown reader.

These checks establish that the graph can be served; they do **not** establish that its
choices are good for readers.

## Where does the finished model go?

`trainer/pipeline.py` uploads validated versions to Bosca Artifacts when an artifacts URL
is configured. **TensorFlow Serving** is Google's server program for SavedModels. It loads each
version from a folder and answers network requests by calling the model's signatures, so
Bosca's server can ask for recommendations without running Python. A separate model loader
downloads the selected versions from Bosca Artifacts into TensorFlow Serving's folders. The
trainer does not call TensorFlow Serving itself. A newly queued context version
keeps the current selected version serving until its replacement has exported and loaded.

The content artifact is built whenever eligible content exists. The personalized artifact
may report `skipped` when there are too few prepared positives. In that case the content
base can still be refreshed.

## Practice without starting a deployed training job

From the workspace root, run the focused Python tests:

```bash
cd experimentation/ml/recommendation-trainer
uv run pytest test_observations.py test_data.py test_content_only.py test_training.py test_serving_contract.py
```

Open `test_observations.py` while the tests run. Find the test where a dismissal overrides a
rating; explain why its `retrieval_positive` is false. Then open `test_training.py` and
find the two-stage fit. The tests let you inspect the behavior without publishing a model.

For a real context run, an administrator opens the context under **Recommendations →
Contexts** in Studio and uses **Train model**. **Train all contexts** on the Models page queues
every context. The trainer's `README.md` lists the environment and artifact requirements for
running `train.py` directly. Do not confuse a successful export with a quality result:
the [testing console](/recommendations/testing) exercises serving, while live outcomes or
an evaluation using later events than the ones used for training are needed to compare
recommendation quality.

## Trace the example one last time

1. `reader-1` completed `article-9`, dismissed `article-10`, and rated `article-11` at `0.25`.
2. Observation preparation assigned labels, weights, and `retrieval_positive` decisions.
3. The Boolean `retrieval_mask` kept only `article-9` for retrieval; ranking kept all three rows.
4. The content artifact compared item features without needing those responses.
5. The personalized towers learned reader and item vectors from positive pairs. Ranking then
   learned ordering from all labeled rows.
6. Both exports were reloaded and tested through their serving functions before versioning.

If you can explain each step without using an undefined variable, you can now read the
trainer from `pipeline.py` down to `export.py`.
