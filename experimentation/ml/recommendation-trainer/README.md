# Recommendation trainer

The content model is rebuilt from eligible content. The personalized model learns from analytics
observations and explicit profile feedback, then exports retrieval and ranking signatures. Valid
exports continue through the existing automatic promotion path; A/B is independent of training.

## Analytics observations

`recommender-interactions` supplies event ID, profile ID, content ID, app/session/page identifiers,
event type, consumption, reading depth, and impression visibility. Every page of the event and
feedback queries uses the same `asOf` boundary. Warehouse event timestamps are interpreted as UTC.

Consumption and scroll depth are **percentage points from 0 to 100**. For example, `1` means 1%,
not complete consumption. Omit consumption when it was not measured. Invalid values are reported
and treated as unknown.

Recommendation responses include request provenance in their object-valued `context` field:
`recommendation_context`, `recommendation_model_version`, `recommendation_request_id`, and, for
source-item requests, `recommendation_source_id`. Fetching recommendations does not record an exposure.
Clients forward these values with actual impression or engagement events in `element.extras`.
The browser analytics client also reads the corresponding `data-ba-recommendation-*` attributes
on a recommendation card or its ancestors. The recommended item remains the event's content reference;
the source ID identifies the item from which the recommendation was requested.

Training keeps different attributed requests separate. Contextless events and durable feedback remain
general preference evidence; candidate membership never supplies missing attribution. The dataset's
`source_id` is populated only when the recorded context matches the training context and the source is a
different, known catalog item. `source_attributed_rows` reports that population. Those rows train the
source-conditioned head; unattributed rows continue to teach general profile/item preference.

| Observation | Target when consumption is unknown | Confidence before recency |
| --- | ---: | ---: |
| Page open | 0.5 | 0.25 |
| Interaction | 0.75 | 1 |
| Completion | 1 | 2 |
| Saved guide step completion | 1 | 1 |
| Saved partial guide progress | 1 | 1 |
| Saved full guide completion | 1 | 2 |
| Qualified ignored exposure | 0 | 0.1 |
| Explicit rating | `(stars - 1) / 4` | 5 |
| Dismissal | 0 | 5 |

These targets express a training policy, not calibrated satisfaction probabilities. Measured
consumption replaces an unknown-consumption target; related scroll observations supply the maximum
observed depth. A completion with unknown consumption supplies its completion target. The latest
explicit rating supersedes implicit history for that user/content pair, and an active dismissal wins.

Bosca's guide progress mutation emits `Completion` analytics after a successful transaction commits,
using the existing server analytics client. Step events have `element_type=guide_step` and reference
both parent and step content; completing the final step also emits `element_type=guide` for the parent.
Occurrence timestamps come from saved progress, and event IDs include the profile, guide, version,
run start, and step. App and installation identities come from `X-App-ID` and `X-Installation-ID`.
BML forwards the installation identity and uses `BML_APP_ID` for the app header (`APP_VERSION` supplies
`X-App-Version`). No completion event is emitted for enrollment, unchanged progress, or rollback.

`recommender-guide-completions` reads PostgreSQL guide progress and history as historical fallback.
Active progress contributes one parent-guide observation and an observation for each completed step's
content. Completed history contributes a stronger parent-guide observation and the completed guide
version's step content. Starting a guide without completing a valid step contributes no positive label.
Only content in the eligible training catalog is retained. Full-guide confidence is twice step or partial
guide confidence at the same age; it is not a guarantee about the final recommendation score.

Guide-state observations use the progress row's `modified` time or history row's `completed` time,
not an individual step completion timestamp. They share the run's `asOf` cutoff and 365-day event
lookback. The cutoff excludes later state; it cannot reconstruct progress changed or deleted during
paging. Event IDs identify the saved state and step so repeated rows are deduplicated. These are positive
behavior observations, not user ratings; ratings and dismissals retain their override semantics.
Timestamped analytics replaces matching state observations. Active state matches by profile, guide,
version, and run start; a full-guide event links completion history to that run at the completion time.
Older steps without timestamped events retain their fallback. The server query installer and trainer
must both be updated before training with this source. BML applications also need the updated BML
runtime to forward the configured app identity.

Related events combine within an identified user/app/session/page visit. A page impression starts another
visit; when page-open boundaries are absent, a full attribution window of inactivity separates visits.
An explicitly bound page can retain reading quality throughout a long visit. Page boundaries for content
outside the training catalog also separate visits. Missing correlation identifiers preserve discrete
engagements but cannot establish a negative exposure. Repeated delivery of the same event/content row is deduplicated;
conflicting replays are reported and excluded. Genuine repeat visits remain separate observations.

Contentless scroll events attach only to visits with exactly one explicitly content-bound page impression.
The trainer does not infer content IDs from URLs or assign a feed's scroll depth to its cards. Unbound
page/depth events are reported. Applications must supply the page's content reference to use that signal.

An ignored impression requires at least 50% continuous visibility for one second, reliable session/page
identity, and a fully elapsed attribution window. Browser tracking emits `visible_ms` and
`visibility_threshold`; Compose's `dwell_ms` is also read. Engagement during the dwell period or ensuing
window, including after navigating away from a feed, explains the impression. Unverified or still-pending
impressions do not become negative targets.

Both training stages use confidence and recency weights. Retrieval uses only positive observations and
also scales their influence by engagement target. Category affinity uses the same weighted positive
evidence; dismissals, ignored exposures, and sub-neutral ratings cannot create a positive interest.

## Configuration and reporting

Analytics reads use offset pages of 20,000 rows, within Bosca's GraphQL list limit. Each page logs its
start, row count, cumulative count, and duration. Embeddings retain their separate 500-content-item
page size. Dataset preparation, previous-model neighbor capture, and fitting report their progress.
Retrieval computes full-catalog top-K metrics on evaluation passes; training still monitors mean loss.
Previous-model recommendations are computed once per distinct neighbor and language, then reused
when aggregating each user's neighbor features.

The trainer accepts these environment settings for network operations:

| Variable | Default | Meaning |
| --- | ---: | --- |
| `ML_TRAINER_ANALYTICS_PAGE_SIZE` | 20000 | Ordinary analytics rows per request; valid range 1–20000 |
| `ML_TRAINER_CONNECT_TIMEOUT_SECONDS` | 30 | GraphQL and artifact connection timeout |
| `ML_TRAINER_READ_TIMEOUT_SECONDS` | 600 | GraphQL and artifact read inactivity timeout |

Request timeouts preserve and surface the underlying failure. They do not limit the duration of a
training stage or a progressing streamed download. Kubernetes separately enforces the JobProfile's
`profile.job.activeDeadlineSeconds` for the whole run. Set the deployment's deadline and
`profile.job.ttlSecondsAfterFinished` in its operations values; the latter controls how long completed
pods and their logs remain available for diagnosis.

The existing training configuration JSON accepts:

| Key | Default | Meaning |
| --- | ---: | --- |
| `recency_half_life_days` | 30 | Confidence halves after this many days; eligible older rows remain in training. |
| `attribution_minutes` | 30 | Window for combining observations and explaining exposure with engagement. |
| `min_interactions` | 100 in the run orchestrator | Minimum prepared positive observations for personalized training. |

Half-life and attribution window must be finite and positive. Unknown timestamps retain their base
confidence but cannot qualify an ignored exposure. Each prepared run logs data-quality counts and
returns them in `training_data`: missing/invalid fields, duplicate/conflicting events, unattributed
reading signals, pending/ignored impressions, positive observations, targets, and total weight.

Deploy matching recommendation server and trainer artifacts: package `1.0.24` with installer `1.0.30`
refreshes the saved analytics query contracts. Retrain after that refresh. Existing serving models
continue to be used until a new valid artifact is promoted.

## Validation

Run `.venv/bin/python -m pytest --cov=trainer` in this directory. The owning Gradle module also has a
Trino/PostgreSQL integration test for the installer SQL; run it from the workspace root with
`./gradlew :experimentation:recommendations:test --tests '*RecommendationTrainingQueryIntegrationTest'`.

## Local chronological evaluation

Run from this directory:

```sh
.venv/bin/python evaluate.py --output /private/tmp/bosca-local-evaluation.json
```

The default benchmark uses a deterministic synthetic history with 44 profiles and 32 items. It fits
the existing retrieval model and ranking head with seeds 11, 29, and 47, comparing 30-day and 365-day
recency half-lives. Each fit uses at most 25 epochs with the existing training-loss early stopping.
All consumption measurements are missing in this fixture. Histories include qualified ignored
impressions, warm and sparse users, known cold profiles with and without signals, and changed interests
with stale profile signals. Two preference groups share each broad content category, so category
matching alone cannot fully recover preferences.

The evaluator trains only on events and feedback at or before the cutoff. User signals, affinities,
weights, popularity, and history seeds never use future interactions. Future completions with target
at least 0.75 and ratings of at least 4/5 supply relevance labels; future explicit rejection overrides
completion for the same pair. Future clicks and unobserved items do not establish rejection.

This is a **discovery evaluation**: every method uses the same context/language candidate pool and
excludes the user's earlier positive items. Targets outside that pool are excluded; reports include
future-positive and evaluated user/pair counts. Cold users are profiles already known at the cutoff,
not profiles first created afterward.

Methods compared:

- `popularity`: confidence/recency-weighted positive history aggregated across users.
- `content_history`: the production `ContentSimilarityIndex` scores averaged over the user's weighted
  positive item history, falling back to popularity for an empty history. This is a defined baseline,
  not the entire Kotlin placement assembler.
- `personalized_retrieval`: exact user/item tower scores.
- `personalized_reranked`: the ranking head reorders the top 16 retrieved eligible candidates.
- `personalized_full_catalog`: the same trained ranking head scores every eligible unseen candidate before top-K, matching the context model's candidate-selection approach. This feed-only comparison has no request source or previous behavioral snapshot.

The JSON report includes Recall@5, NDCG@5, reciprocal rank, hit rate, catalog coverage, cohort counts,
training-data quality, and mean/standard deviation across seeds. Each user's query has equal weight.
Seed variation measures training randomness; it is not a confidence interval for production uplift.
Scoring latency is measured after warmup over materialized embeddings, including full-catalog ranking
scores. It excludes TF Serving, network calls, feature loading, ANN, concurrency, and placement assembly.

For a quick smoke run, use `--seeds 11 --half-lives 30 --epochs 1`. Parameters also include `--k`,
`--retrieval-candidates`, `--context`, `--language`, and `--learning-rate`.

The trainer and evaluator default to a learning rate of `0.01`. A local training-only probe with six
opposing synthetic profiles and 240 ratings found unstable fitting at `0.1`; `0.01` fitted both
preference groups. It remains configurable and is not a universal optimum. With the former squared-error
ranking objective, the chronological fixture
at `0.01` averaged full-catalog NDCG@5 of 0.723/0.724 for 30/365-day half-lives across seeds 11/29/47,
versus 0.500/0.455 for content history and 0.880/0.864 for retrieval alone. This established the baseline
ranking-quality gap that motivated the loss comparison below.

Bosca analytics timestamps arrive as epoch milliseconds. The data loader converts event, feedback,
and guide-start timestamps to UTC before recency weighting and temporal matching. ISO timestamp
values are also accepted; numeric timestamps must not be interpreted as nanoseconds.

To evaluate a real **local** export, pass `--input /path/to/history.json`. The file has these fields:

| Field | Contents |
| --- | --- |
| `source` | `synthetic` or a description of the local export's origin |
| `snapshot_at`, `evaluation_end` | ISO timestamps with timezone; end must follow the feature snapshot |
| `users`, `content`, `categories`, `signals` | Arrays of records using the existing trainer DataFrame columns, captured at `snapshot_at` |
| `interactions` | Timestamped analytics records spanning training history and the later evaluation window |
| `feedback` | Timestamped feedback records using `feedback_created` |

Optional `users.scenario` labels add report groups without becoming model features. The input hash
identifies the exact data evaluated. An export's claimed feature snapshot must actually reflect the
cutoff: current profile/content attributes combined with old events can leak future information.
Unorderable event/feedback timestamps fail evaluation. Historical feedback needs its state at the
cutoff as well as subsequent changes; a dump of current ratings alone cannot reconstruct overwritten
ratings. Synthetic results and positive-only historical metrics cannot establish causal uplift or
correct exposure/position bias.

Evaluation trains in memory and writes only its local report. It does not call production services,
export serving artifacts, change promotion, or change the data used by production training.

## Comparing ranking objectives

The ranking head trains with confidence-weighted binary cross-entropy from logits, including soft
engagement targets between zero and one. Squared error previously forced unbounded retrieval scores
toward those numeric targets, penalizing even confidently correct negative scores. The new objective
preserves that confidence while teaching the residual correction. It adds no serving layers or live
weight controls: scores remain logits, context settings remain captured per trained version, and source
explanations remain score differences. Scores are not calibrated probabilities. Export manifests record
`ranking_objective: binary_crossentropy_logits`; existing completed versions retain their trained behavior.

```sh
TF_USE_LEGACY_KERAS=1 .venv/bin/python compare_ranking_losses.py \
  --output /private/tmp/bosca-ranking-loss-comparison.json
```

This pairs the former squared-error objective with the new objective using identical chronological
inputs, seeds, and settings. Defaults cover fixture seeds 173, 353, and 761, training seeds 11/29/47,
30/365-day half-lives, 25 epochs, and learning rate 0.01. The report saves every pair, including cohort
metrics and an input hash. It does not select a winner, export models, or promote artifacts. The loss
change was selected from its objective semantics and training-only pair ordering before these held-out
comparisons. Fresh synthetic seeds still share the fixture's assumptions; they cannot establish real-user
uplift. Use `--fixture-seeds 353 --seeds 11 --half-lives 30 --epochs 1` for an implementation smoke check.

Local paired results (18 comparisons, 36 fits):

| Fixture seed | Half-life (days) | Former squared-error NDCG@5 | Logit NDCG@5 | Retrieval NDCG@5 |
| --- | --- | --- | --- | --- |
| 173 | 30 | 0.7231 | 0.8759 | 0.8801 |
| 173 | 365 | 0.7240 | 0.8571 | 0.8635 |
| 353 | 30 | 0.7585 | 0.9113 | 0.9110 |
| 353 | 365 | 0.6801 | 0.9138 | 0.9138 |
| 761 | 30 | 0.6113 | 0.9103 | 0.9077 |
| 761 | 365 | 0.7311 | 0.8976 | 0.8998 |

Each row averages three training seeds. The full-catalog ranker improved in every paired run
(minimum NDCG@5 increase 0.0420), averaging 0.7047 → 0.8943 overall. Retrieval was identical between
paired fits and averaged 0.8960: the correction largely closes the measured gap, without demonstrating
consistent superiority over retrieval. Aggregate cohort results improved except cold profiles without
signals, which remained at 0.2500. That fixture limitation remains visible. The local installation had
zero interaction rows and four feedback rows at validation, so representative chronological acceptance
remains open.

## Diagnosing reranking

```sh
.venv/bin/python diagnose_ranking.py --output /private/tmp/bosca-ranking-diagnosis.json
```

This controlled experiment diagnoses the synthetic benchmark. It uses fixture seeds 173 and 307 and
training seeds 11, 29, and 47. Each retriever is fitted once, then its user/item embeddings and candidate
pools are fixed. The report first checks agreement between feature-based ranking and materialized
embedding scoring, measures weighted training error, and attributes per-user changes in NDCG@5.
Promoted items are classified by future positive outcomes and whether the ranker had any training
observation for that user/item pair. A few worsening examples retain item scores for inspection.

Six fresh scoring heads start with matching inference predictions:

| Probe | Controlled change |
| --- | --- |
| `concat` | Production correction MLP and objective, without the production retrieval residual; fresh initialization establishes the control |
| `concat_longer` | Raises the epoch cap from 25 to 100; keeps training-loss early stopping |
| `concat_dot` | Adds the retrieval dot product, standardized using training pairs only; its added input weights start at zero |
| `concat_broader_exposure` | Spreads the existing synthetic ignored impressions across all three nonpreferred groups |
| `concat_dot_broader_exposure` | Combines the preceding representation and exposure changes |
| `concat_balanced_weight` | Equalizes positive/zero-target total weight while preserving total training weight |

Compare these fresh heads against `concat`; the original production head uses its original
initialization and is reported separately. All controls reuse the production scoring-layer definitions,
weighted logit cross-entropy task, optimizer, shuffle seed, and early-stopping implementation. The longer
probe reports its actual epochs run, which can be below its cap. Diagnostic probability MSE applies
sigmoid to logits and divides by total weight; it is separate from the training objective. The
`--learning-rate` option defaults to 0.01.

The original fixture exposes each user to negative examples from only one other preference group.
The broader-exposure probe preserves positive observations, negative counts, and recency/confidence
weights; it changes only which synthetic nonpreferred items were shown. Neither it nor weight balancing
is a proposed replacement for real analytics: unobserved items cannot be relabeled as ignored, and an
ignored impression is not proof of dislike. Additional fixture seeds reshuffle histories under the same
generative assumptions; they do not establish production generalization. Future outcomes are used to
report results, never to select inputs, weights, stopping points, or a production winner.

For a quick implementation check, use `--fixture-seeds 173 --seeds 11 --epochs 1 --probe-epochs 1
--long-epochs 2`. The diagnostics are covered by `test_diagnose_ranking.py`.


## Context model inputs and serving

The **Train model** action captures the context's saved settings and queues a new version;
serving keeps the selected completed version until its replacement has exported and loaded successfully.
The content and personalized exports for that version share its eligible catalog and settings.

| Input | Training and model behavior |
| --- | --- |
| Semantic embeddings, categories, labels, language, MIME, `attributes.type`, direct collection memberships | Captured similarity features and weighted learned item-tower inputs. Collection membership is sparse, deduplicated, direct membership; it does not include ancestors. |
| Per-type 0–1 preferences | Captured cold-start baseline. Zero reduces preference; explicit context filters determine exclusion. A learned signed correction can reverse the baseline. |
| Profile/item observations and explicit feedback | Contrastive retrieval training and confidence/recency-weighted supervised ranking. Unattributed history still teaches general profile preferences. |
| Actual request context and source metadata ID | Matching attributed observations train the source-conditioned scoring head. A source is never inferred from unrelated historical activity. |
| Global and cohort co-engagement | Sparse analytics snapshots, scaled as `score / (score + 10)`, with learned gains and captured context influence. Multiple cohort memberships use the strongest matching edge. |
| Previous completed personalized model | Supplies neighbor recommendations as input features to the next model. Its predictions are not labels. The version is the one captured as active when this training job was queued. |
| Ratings | Signed affinity to rated items through content similarity, captured in the behavioral snapshot and included in model scoring. Kotlin does not apply a second rating correction to model results. |

For known profiles, source-conditioned personalized logits add `log(max(similarity, 1e-7))` to the learned
correction, scaled by the captured personalization weight. Similarity also scales the cold-start
baseline. This preserves similarity ordering even when learned logits are negative; zero similarity
receives a finite penalty rather than exclusion. Requests without a source have no similarity penalty.
Disabled sparse type/collection affinities contribute neither embeddings nor normalization mass.

Anonymous and unknown profile IDs use the captured content similarity and editorial type preference,
plus observed global co-engagement. Personal learned corrections, the personal similarity penalty,
cohort, neighbor, and rating contributions require a profile in the model's vocabulary. With no global
co-engagement contribution, an anonymous source request follows the content model's ranking.

`behavior.json` travels with its SavedModel. Training uses a previous snapshot only for observations whose
feature time is strictly after the snapshot became available. Earlier observations receive zero snapshot
features and still train general preferences. This deliberately avoids reconstructing historical edges or
letting a current outcome become its own input. The first snapshot uses initialized behavioral gains;
later observations allow those gains to learn. Current profile/cohort state is not retroactively applied
to earlier observations as a historical snapshot.

Each run trains new weights and optimizers from the current data. The previous personalized artifact
is used for behavioral inputs, not to resume training its weights. If that captured artifact has been
deleted (HTTP 404), training logs a warning and continues without its historical behavior snapshot or
model-derived neighbor recommendations. Current interactions, feedback, global/cohort edges, and ratings
still contribute, and the new export still passes the same validation before upload. Training does not
substitute a different model version or apply current behavioral data to historical observations. Other
download errors and invalid artifacts still fail the job.

The separate latest-artifact download supplies export comparison metrics, served-facet checks where
enabled, and version allocation for runs without a supplied version. Context jobs use their allocated
version even when all older artifacts have been deleted.

The personalized model exposes `related`, `co_engaged`, and `feed_page`; the content model exposes
`similar_page`. Inputs are profile/source IDs where applicable, the captured context, resolved language,
and offset. The graph scores its eligible facet before selecting each page. Related pages remove the
source item; co-engaged pages restrict candidates to behavioral edges. Kotlin preserves these model
scores, checks live eligibility and dismissals, and requests another page when filtering leaves a gap.
A source request without a usable personalized export uses content similarity. A source-less cold start
uses the existing SQL trending pool. Unknown sources return an empty result.

Before promotion, the trainer reloads each export and validates the actual serving signatures:
`related`, `feed_page`, `co_engaged`, `similar_page`, and `explain`, alongside the existing signatures.
The sampled context/language facet must return finite, unique, eligible results in score order;
source pages exclude the source, pagination does not repeat items, and exhausted/unknown-source queries
return empty. Explanations must have four finite contributions per item and preserve subsequent ranking.
Empty related/co-engaged pages are valid when no matches exist. These contract probes do not establish
recommendation quality or replace chronological evaluation.

`explain` runs only when GraphQL `sources` is selected. Kotlin batches candidates with the same captured
inference inputs and calls the exact version that ranked them. Each effect is the score difference when
a group is disabled: content default/source relevance, personalization (including neighbor/rating),
global co-engagement, or cohort co-engagement. Shared item representations remain in the model during
ablation; these are model contribution tests, not causal explanations. Only finite positive effects above
`1e-6` produce source labels. Missing or unavailable explanations return `[]` and do not change ranking.

The loader removes unretained local model directories while preserving active/loading selections and
the API's retained history (active/pinned plus five previous successful versions). Discovery failures
preserve local files. Retention on disk does not force every retained model into TF Serving memory.
