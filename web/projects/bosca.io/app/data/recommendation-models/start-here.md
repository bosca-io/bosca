# Prologue: choose the next article

Meet Sam. Sam opens a feed of suggested articles. Bosca has to decide what to show next.
For this guide, Sam is a **reader**: the person receiving recommendations. Bosca stores a
**profile** for Sam, meaning a record of information associated with that person. The
trainer calls the profile ID `user_id`. We will use the made-up ID `reader-1`.

Here is what happened earlier. The names, articles, and actions in this story are invented:

| Article Sam saw | What Bosca knew before showing it | What Sam did afterward |
| --- | --- | --- |
| `article-9`: “How stars form” | English; category `science` | Completed it |
| `article-10`: “Weekend match recap” | English; category `sports` | Dismissed it |
| `article-11`: “Why batteries age” | English; category `science` | Rated it `0.25` on a 0–1 scale |

A **feature** is a detail Bosca can use *before* it chooses an article: language, category,
file type, and what it knows about Sam from earlier activity are examples. A **response** is
what Sam does *afterward*, such as completing or dismissing the article. The response in each
row is known now because those events happened in the past. For a new article, Bosca has the
features but cannot know Sam's response yet.

A **candidate** is an article eligible to be shown next. A **model** is the saved set of
calculations Bosca uses to score or choose candidates. Some calculations follow fixed rules;
others use numbers learned from past responses. Before looking at the real code, follow one
response into the next recommendation.

## Try it: see how a response changes later scores

Work through the five steps in the box. Turn article categories into numbers, set the points
for each category, and choose an article for Sam. Then record Sam's response, change the
scoring rule, and see what happens to a later pair of articles.

<!-- recommendation-playground -->

You set the first two weights by hand, then used a simple made-up update to see how a response
can affect later scores. Bosca's real content model compares more item details; its
personalized model learns many weights from past examples. The next sections show those
pieces one at a time.

In the Python code ahead, `features` is a dictionary of input arrays. For example,
`features['user_id']` holds profile IDs and `features['language_tag']` holds item languages.

## How do names become numbers?

The code knows a category as text, such as `science`. A calculation like multiplication cannot
use that word directly. One simple conversion is to choose an order for all known categories,
called a **vocabulary**. If the vocabulary is `['science', 'sports']`, the item with category
`science` becomes `[1, 0]` and the item with category `sports` becomes `[0, 1]`. Each position
answers “Does this item have this category?” An item can have more than one `1`. The towers
chapter shows the Python that makes these rows.

### How does a reader ID become numbers?

`reader-1` is the example profile's ID. The characters in that ID identify a record; they do
not say whether the person likes science. The personalized model has a **reader tower**, a
sequence of calculations for one reader, and an **item tower** for one article. To use an ID
in those calculations, the reader tower first assigns each known ID an integer position.
Imagine the known IDs are
`['reader-1', 'reader-2']`. In this example, the lookup gives `reader-1` position `1` and
`reader-2` position `2`; position `0` is reserved for an ID outside that known list.

An **embedding** is an ordered list of numbers a model associates with something. Another
name for an ordered list of numbers is a **vector**. An **embedding table** is a grid with one
such list in each row. Looking up `reader-1` means
taking row `1`. The real ID table has 64 numbers in each row by default. Here is a two-number
version so we can see every value:

| Position | ID | Row at the start | Possible row after training |
| ---: | --- | --- | --- |
| `0` | ID outside the known list | `[0, 0]` | Not used in this example |
| `1` | `reader-1` | `[0, 0]` | `[0.4, -0.1]` |
| `2` | `reader-2` | `[0, 0]` | `[0.1, 0.3]` |

The last column is invented. Bosca does **not** hardcode those values. Its ID rows start at
zero. During the first training stage, a positive past example, such as `reader-1` completing
`article-9`, selects row `1`. The reader tower combines that row with other available reader
information. The item tower calculates numbers for `article-9`. Training changes the ID row
and the towers' shared weights to make this observed pair match better. Repeating that process
with other positive examples can change row `1` further. Later, the ranking stage learns from
both positive and negative responses to order candidates while the towers' numbers stay fixed.
The individual numbers in row `1` do not have names such as “science interest”; their effect
depends on the rest of the tower and the item vectors they are compared with.

The ID row is useful because it can retain patterns specific to a known profile. It is only
one input. The reader tower also accepts saved information such as interests and earlier
category activity. A profile included when this model version is built but with no past
interactions still has an all-zero ID row; its saved interests can contribute to its reader
vector. After training, Bosca uses the saved values to score items. It does not need to wait
for a new response to look up that profile's row.

## What is a text embedding?

A **text embedding** is an ordered list of numbers calculated from a piece of text. It gives
that text a position in a number space so we can compare it with other text processed by the
same model. For example, the input might be the words “How stars form” and the output might
be a list such as `[1, 0]` in the tiny illustration below. Bosca's real text embeddings have
many more numbers. This is different from the reader ID embedding above: the ID row is learned
from recommendation examples, while a text embedding is calculated from an item's words by a
separate, previously trained model.

Here is the path in Bosca:

1. The content service extracts text from an item. When embeddings are enabled, it sends that
   text to the text embedding service.
2. A **tokenizer** splits the text into pieces called **tokens**. A token can be a word or part
   of a word. The text model turns those pieces into numbers it can calculate with.
3. The text model uses weights learned before this recommendation training run to combine the
   token information into one list of numbers. If the item is too long, Bosca sends smaller
   pieces and receives a list for each one.
4. The recommendation trainer averages the piece lists, accounting for repeated text where
   pieces overlap, then scales a nonzero result to length 1. It uses that one list as an item
   input. The recommender does not train the separate text model in this process.

Why call this a representation of *meaning*? The text model's training aims to put passages
about related ideas near each other in the number space, even when their words differ. No
single number is a field named “topic” or “science.” We judge the representation by comparing
the entire lists: related text should often produce lists that point in similar directions.
This is a learned pattern, so it can make mistakes.

### Compare two text embeddings by hand

Use the arrows below to compare two invented text embeddings. Pick a second article and watch
the arrow and calculation change. The numbers are tiny so we can work through them by hand;
Bosca's actual text embeddings have many more numbers.

<!-- recommendation-text-map -->

The arrows each have length 1. Their multiply-and-add result is therefore also their
**cosine similarity**: a number describing how closely the arrows point in the same direction.
The individual positions do not have labels like “star” or “football” in the real model.
Meaning comes from how whole vectors compare across many texts. This comparison is useful,
but it does not guarantee that every related pair will be close.

Why add this input when items already have categories? Two articles can discuss the same idea
without sharing an assigned category or the same exact words. The text vector gives the model
another way to compare them. Bosca still uses the explicit category, language, and other item
information alongside it.

## What does a layer do?

A **layer** is one step that turns input numbers into output numbers. In a **dense layer**, each
output is calculated by multiplying inputs by adjustable **weights**, adding the results, and
adding a **bias**. For example, inputs `[1, 0]`, weights `[0.4, 0.7]`, and bias `0.1` produce
`1×0.4 + 0×0.7 + 0.1 = 0.5`. Training adjusts the weights and bias. A layer can produce many
numbers at once; the next layer uses those numbers as its inputs. A tower is the full sequence
of these steps for either the reader or the item. The towers chapter follows the actual code.

Why learn those weights? They let the personalized model change how it combines reader and item
information based on past responses. For instance, training can change how strongly a reader's
recorded category interests affect the reader vector. The layer's arithmetic stays the same;
the numbers used in that arithmetic change.

Not every layer does the same calculation. A lookup layer turns an ID into its table row. A
dense layer mixes its input numbers using learned weights. Some dense layers use **ReLU**:
after the multiply-and-add step, any negative output is replaced with zero. For example,
`ReLU(-0.3) = 0` and `ReLU(0.5) = 0.5`. This lets a later layer react when a learned
combination is positive while ignoring it when that combination is zero. Without a step such
as ReLU between them, consecutive dense layers would amount to one larger multiply-and-add
calculation.

## Why are there two towers?

The **reader tower** combines what Bosca knows about a reader. The **item tower** combines
what Bosca knows about an item. They each return a list of 64 numbers by default:

```text
reader ID + saved reader information → reader tower → reader vector
item ID + item properties + optional text vector → item tower → item vector
reader vector × item vector, then add → match score
```

That last line is a **dot product**. Suppose the reader tower returned `[1, 0]`, one item tower
returned `[0.8, 0.6]`, and another returned `[0, 1]`. The first match score is
`1×0.8 + 0×0.6 = 0.8`; the second is `1×0 + 0×1 = 0`. These two-number outputs are invented
teaching values. Bosca's towers learn 64-number outputs from past reader-item examples. The
supplied text embedding above is an input to the item tower; its 64-number output is a separate
vector learned for matching readers with items.

Keeping reader and item calculations separate also lets Bosca calculate item vectors for the
eligible catalog and compare them with many different reader vectors. The export chapter shows
how the saved model stores and uses those vectors.

## What changes during training?

Training works through past examples with recorded evidence about what happened. The trainer
turns that evidence into a number called the **label** (or **training target**). Sam dismissed
`article-10`, so that example's label is `0`. The ranking model scores the pair, uses the
label to calculate a penalty, and adjusts itself so pairs like that one score lower next time.

A new article such as `article-12` has no label before there is usable evidence about Sam and
that article. The model can still score it using the information available before showing it.
Later, an action such as a dismissal can produce a label. Even without an action, a recorded
display that Sam could see but ignored for the attribution window can produce a low-confidence
`0` label. The observations chapter explains when such a display qualifies.

A label is a record of what happened, not a prediction. Sam's `0.25` rating of `article-11`
becomes the label `0.25`. It doesn't mean “a 25% chance Sam finishes it.” A prediction like
that would be a **calibrated probability**: of 100 recommendations each given a 25% chance,
about 25 would actually be completed. Bosca's scores are not calibrated probabilities either;
they are used only to put articles in order.

To decide how to adjust, training calculates a **loss**: a penalty for how the model scored a
past example. A smaller loss means the score fits that example's label better under the chosen
formula. Bosca's ranking score is a **logit**, a raw number on a different scale from its
0–1 labels. Its loss converts that score before comparing it with a label; the training
chapter shows the arithmetic. A **gradient** tells training which way to move each weight,
and roughly how far, to reduce the loss. Here is a deliberately tiny example with one weight
and a simpler loss formula:

```text
input = 1                 target = 1
score = weight × input
loss = (score − target)²

weight 0.2 → score 0.2 → loss (0.2 − 1)² = 0.64
weight 0.3 → score 0.3 → loss (0.3 − 1)² = 0.49
```

Raising the weight from `0.2` to `0.3` lowered the loss from `0.64` to `0.49`, so for this
example the gradient tells training to move the weight up. Real training uses many examples at
once and different loss formulas for retrieval and ranking; the training chapter explains both.
A **sample weight** can make one past example count more than another in that calculation.

Repeating this adjustment over the past examples to reduce the loss is called **fitting** the
model to those examples. Bosca stops when the loss stops improving or after a set number of full passes over the
examples, called **epochs**. The trainer calls TensorFlow's `fit` function to do
it, and the rest of this guide says “fit” for this step. A fitted model's weights are fixed
until the next training run fits a new version.

## Quick reference after the examples

| Word | Plain meaning |
| --- | --- |
| **Model** | A stored calculation that returns scores or suggested item IDs. Bosca has both fixed calculations and ones learned from examples. |
| **Candidate** | An eligible item the model could return. |
| **Context** | A saved recommendation setting that narrows eligible items and sets scoring weights, such as the placement being requested. |
| **Observation** | One training example connecting a reader, an item, what the reader did, and when. Several recorded actions can become one example. |
| **Label (training target)** | The known number assigned to a past reader-item example. In this guide, completing `article-9` gets `1`, dismissing `article-10` gets `0`, and rating `article-11` gets `0.25`. The model uses it while learning. |
| **Loss** | A number measuring how poorly a model's scores fit the known labels under a chosen formula. Fitting tries to make it smaller. |
| **Gradient** | For each weight, which direction and roughly how far to change it to reduce the loss. |
| **Fitting** | Adjusting a model's weights over many past examples to reduce its loss. TensorFlow's function for this is `fit`. |
| **Sample weight** | A number that controls how much a past example affects training. A larger weight gives that example more influence. |
| **Vocabulary** | An ordered list of known values for a feature, such as `['science', 'sports']`. Its order determines each value's position in a numeric row. |
| **Embedding (vector)** | An ordered list of numbers. Bosca can receive one from a separate text model, learn an ID's table row, or calculate a reader or item output from a tower. Those lists serve different steps. |
| **Tower** | The sequence of calculations that turns reader information or item information into a vector. |
| **Ranking head** | Later scoring layers that adjust the tower match using recorded responses and saved context settings. |
| **Tensor** | An array of numbers or strings consumed by TensorFlow; a batch holds values for several examples. |
| **Artifact** | The files saved for one model version so a serving process can load it. |

## What are we building?

The trainer saves two artifacts:

| Artifact | Question it answers | Inputs |
| --- | --- | --- |
| `recommender-content` | “Given `article-9`, which candidate items match its language, category, and other item properties?” | Eligible item features. Reader responses are not required. |
| `recommender-personalized` | “For `reader-1`, which eligible items should receive higher scores?” | Reader and item features, plus prepared observations to learn from. |

The content artifact adds weighted matches between item properties. The personalized artifact
learns reader and item embeddings in two neural-network “towers,” then learns a ranking
correction. Later chapters show the arithmetic of both approaches. Separate artifacts let the
content path run even when too few positive observations exist for personalized fitting.

## Read the entry point

`trainer/pipeline.py → run_training` loads data, builds the content model, and fits the
personalized model when enough data is available:

```python
users, interactions, content, categories, signals, feedback = load_data_from_bosca(
    bosca_url, bosca_token
)
if "context" in config:
    content = apply_context(content, config["context"])
```

`load_data_from_bosca` returns six pandas DataFrames: tables with named columns. It calls named
analytics queries through Bosca GraphQL and joins supplied semantic embeddings to content.
`apply_context` narrows the eligible item catalog. Both artifacts use that catalog.

| Variable | One row describes | Example field |
| --- | --- | --- |
| `users` | An eligible reader profile | `user_id` |
| `interactions` | A recorded event | `interaction_type` |
| `content` | A candidate item | `content_id`, `language_tag` |
| `categories` | One item-category membership | `category_id` |
| `signals` | A configured value about a reader | `signal_key`, `signal_value` |
| `feedback` | An explicit rating or dismissal | `feedback_label` |

The loader uses one `as_of` snapshot time for interaction and feedback queries. An event after
that cutoff should not train this version.

## Find the code

From the workspace root, open `experimentation/ml/recommendation-trainer/`. Follow these files in
order:

1. `trainer/data.py` loads tables.
2. `trainer/observations.py` makes labeled observations.
3. `trainer/datasets.py` builds TensorFlow datasets.
4. `trainer/content_similarity.py` builds the exact content path.
5. `trainer/models.py` defines the learned towers and ranking head.
6. `trainer/training.py` fits the models.
7. `trainer/export.py` saves and checks serving functions.

### The libraries the trainer uses

The code leans on a few Python libraries. You do not need to know them in advance; this is
what each one is for:

| Library | What it is | What the trainer uses it for |
| --- | --- | --- |
| **pandas** | A library for tables with named columns, called DataFrames | Loading query results and turning events into observations |
| **NumPy** | A library for fast arrays of numbers | Holding one column of values per feature before TensorFlow takes over |
| **TensorFlow** | Google's library for calculating with arrays of numbers (**tensors**) and for adjusting weights to reduce a loss | Every model calculation, plus saving the finished models |
| **Keras** | The part of TensorFlow for building models from layers (`tf.keras`) | The layers (`Dense`, `Embedding`, `StringLookup`, `Dropout`), the loss formulas, the optimizer, and the `fit` loop |
| **TensorFlow Recommenders** (imported as `tfrs`) | A TensorFlow add-on with ready-made pieces for recommendation models | The retrieval and ranking **tasks** in the training chapter, and the nearest-item search in the export chapter |

When the guide writes `tf.`, `tf.keras.`, or `tfrs.`, the code is calling one of these
libraries rather than Bosca code.

**Before continuing:** Which values in the example are features, and which are observed responses?
The answer: language and category are item features; completion, dismissal, and rating are responses.
