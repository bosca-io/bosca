# Events to observations

Sam opens `article-9`, scrolls, then completes it. Bosca may record three **events**: three
records of things that happened. Treating them as three separate examples would count one
visit three times. The trainer combines related events into one **observation**: a row saying
which reader saw which article, what happened, and when. This code is in
`trainer/observations.py → prepare_observations`.

## First, decide whether an event is usable

Some filtering happens before the trainer sees any events. The `recommender-interactions`
analytics query leaves out events whose browser user agent looks like a bot, crawler, or
link-preview fetcher.

The function then rejects event types it does not know how to score, events without a reader ID,
events after the training cutoff, conflicting copies of the same event, and events about
articles outside the **eligible catalog** (the list allowed to be recommended). It can attach
a scroll-depth event—how far Sam scrolled—to a page visit only when that visit identifies
exactly one article. Otherwise the scroll could be credited to the wrong article.

It then groups events by visit, article, and **attribution**: information about the
recommendation request, if any, that showed the article. An open, interaction, or completion
can produce a label greater than zero. An article that was visible but ignored can get a zero
only after enough time has passed to tell that Sam did not engage with it during that visit.
The delay is the **attribution window**. This avoids declaring an article ignored while Sam
is still deciding what to do.

## Make the label and the starting weight

Here is the point where a prepared event becomes a row. `first` is the first event in the group;
`label`, `confidence`, and `created` were calculated from that group's events.

```python
observations.append(dict(user_id=first.user_id, content_id=content_id, label=label,
                         sample_weight=confidence, retrieval_positive=label > 0,
                         interaction_created=created,
                         feature_time=group.exposure_started.min(),
                         **{key: first[key] for key in _ATTRIBUTION}))
```

| Code | Read it as | Why it is there |
| --- | --- | --- |
| `user_id=first.user_id` | Which reader this row is about | Training must connect behavior to a reader. |
| `content_id=content_id` | Which item this row is about | The models must connect the response to an eligible item. |
| `label=label` | The training target: the number assigned to what the reader did, from 0 to 1 | Ranking uses this known result to learn how to score similar pairs. |
| `sample_weight=confidence` | The starting strength of this evidence | A completion is stronger evidence than a simple open. |
| `retrieval_positive=label > 0` | A Boolean: `True` or `False` | Retrieval trains only on positive event pairs. This is a rule created by data preparation, not a prediction. |
| `interaction_created`, `feature_time` | When the response happened and when its inputs were available | Historical user features must precede the outcome they are used to predict. |

The last expression, `**{key: first[key] for key in _ATTRIBUTION}`, copies attribution fields
from the event into the observation. In Python, `**` adds the resulting key-value pairs to
the dictionary. Those fields include the recommendation context, source item, model version,
and request ID when the event was tied to a recommendation. They let later training use the
actual request context instead of guessing it from unrelated activity.

**Consumption** means how much of an item Sam read, expressed as a fraction from 0 to 1. When
no reading amount was measured, the trainer assigns these labels (training targets):
page open `0.5`, interaction `0.75`, and completion `1.0`. Their usual starting confidence
weights are `0.25`, `1.0`, and `2.0`. A completed guide step or guide progress event is a
special case: its label still starts at `1.0`, but its confidence weight starts at `1.0`
instead of `2.0`. A full-guide completion keeps the `2.0` completion weight.

A **qualified ignored exposure** means the article was visible long enough to count as shown,
but Sam did not engage with it during the attribution window. It gets label `0.0` and starting
weight `0.1`. The trainer uses these numbers to learn from past actions. A `0.5` label for a
page open does not mean Sam had a measured 50% chance of liking the article.

## Explicit feedback has its own rule

A rating or dismissal can replace an **implicit observation**—one inferred from actions such
as opening or scrolling—for the same reader-item pair. The following lines run on the feedback
table (`fb`):

```python
fb.loc[fb.feedback_source.eq("dismissal"), "label"] = 0.0
fb["sample_weight"] = 5.0
fb["retrieval_positive"] = fb.feedback_source.eq("rating") & fb.label.ge(0.5)
```

The trainer keeps one feedback row per reader-item pair: a dismissal wins over any rating, and
otherwise the latest rating wins. That row replaces every implicit observation for the same
pair.

`fb.loc[condition, 'label']` selects matching rows and writes their label. A dismissal is always
zero. `fb.feedback_source.eq('rating')` is `True` only for rating rows. `fb.label.ge(0.5)` means
“label greater than or equal to 0.5.” The `&` requires **both** conditions to be true.

| Our example row | `label` | Starting `sample_weight` | `retrieval_positive` | Reason |
| --- | ---: | ---: | --- | --- |
| Completed `article-9`, with no consumption measurement | `1.0` | `2.0` | `True` | Completion is positive engagement. |
| Dismissed `article-10` | `0.0` | `5.0` | `False` | Explicit rejection must not teach retrieval to bring it forward. |
| Rated `article-11` at `0.25` | `0.25` | `5.0` | `False` | It has a nonzero ranking target but is below the rating threshold for retrieval. |

The displayed weights assume the response happened at the snapshot time. After these rules, the
trainer multiplies every `sample_weight` by a **recency factor**. The factor halves after the
configured half-life, 30 days by default. An older completion keeps its label of `1.0` but has
less influence during fitting. The code calculates `factor = 2^(−age_days / half_life_days)`.
With a 30-day half-life, a completion's starting weight of `2.0` becomes `2×1 = 2.0` today,
`2×0.5 = 1.0` after 30 days, and `2×0.25 = 0.5` after 60 days. The label stays `1.0`.

## Why keep `label` and `retrieval_positive` separate?

The two learning stages ask different questions. **Retrieval** asks which pairs are positive enough
to teach broad reader-item matching. **Ranking** learns from all labeled pairs, including rejection
and weaker feedback. The `0.25` rating demonstrates why `label > 0` cannot be the only rule for
explicit feedback.

**Check:** Is `retrieval_positive` a model output? No. It is a Boolean column calculated before
training. In the next chapter, it becomes a Boolean mask that selects rows.
