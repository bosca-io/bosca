# Goal activation and interpretable experiment results

This spec defines optional activation for individual conversion goals, per-variation activation rules, and experiment results that distinguish overall impact from behavior after activation. It covers the Bosca experimentation backend, analytics aggregation, GraphQL result contract, and Studio configuration and results views.

Status: specification. This document defines required behavior and does not assert implementation or validation completion.

## Purpose

Experiment results must distinguish assignment, experiment eligibility, observation of a condition relevant to a goal, and the goal outcome. One experiment-level activation timestamp cannot express every goal's observation boundary or variation-specific conditions.

Keep experiment activation as the eligible intent-to-treat population boundary. Goal activation adds a descriptive cohort and an outcome window. It must never remove an otherwise eligible subject from the primary denominator.

The resulting analysis must answer:

- Did assignment change the outcome across all eligible subjects?
- How many eligible subjects encountered the condition relevant to the goal?
- What happened after that condition was observed?
- What happened among subjects who never encountered it?
- Did subjects encounter the expected condition, an unexpected condition, both, or neither?
- Is the overall comparison conclusive, or merely a raw difference?

## Activation model

### Experiment activation

Experiment activation remains optional. When configured, the first qualifying event after assignment establishes eligibility and the experiment activation timestamp. When absent, assignment is the eligibility boundary.

Assignment counts remain available independently for allocation and sample-ratio diagnostics.

### Goal activation

Each conversion goal may define:

- An optional default activation event filter.
- Zero or more per-variation activation rules.
- An optional maximum delay from experiment activation.
- An optional same-session requirement.
- An application scope, defaulting to the application captured at experiment activation.

Resolve goal activation independently for each goal and assigned variation. Use the first qualifying event within the applicable time, session, and application constraints.

| Rule mode | Meaning |
| --- | --- |
| `EVENT` | Use the variation rule's event filter. |
| `INHERIT` | Use the goal's default event filter. |
| `NOT_APPLICABLE` | This variation has no goal-activation stage. |

A variation without an explicit rule uses the default filter when one exists. Without either a rule or a default, it has no conditional activation result. Missing configuration, explicit `NOT_APPLICABLE`, and a configured rule with zero matching subjects must remain distinguishable.

Validate variation membership, mode/filter combinations, bounded event filters, and application identifiers. An `EVENT` rule requires its own filter. Normalize semantically equivalent configuration so no-op edits do not invalidate results. An effective activation-definition change invalidates aggregates and analysis reports through the existing analysis revision mechanism.

Persist activation configuration using strongly typed JSONB conventions. Expose schema-first GraphQL inputs and fields, with explicit resolvers. Existing goals without goal activation retain their behavior; existing GraphQL fields retain their signatures and meaning. Add KDoc for new service interface methods and model contracts.

## Populations and outcome windows

| Population | Definition |
| --- | --- |
| Assigned | Assignments retained for allocation and sample-ratio diagnostics. |
| Eligible | Subjects admitted by experiment activation, or assignments when experiment activation is absent, after applicable exclusions. |
| Goal activated | Eligible subjects whose first matching goal-activation event satisfies the resolved rule and constraints. |
| Not goal activated | Eligible subjects with an effective activation filter who have no qualifying match before the cutoff. |
| Not applicable | A variation explicitly configured without a goal-activation stage. |

For every effective activation filter:

`goal activated + not goal activated = eligible`

An eligible subject who never activates remains in the overall result for the assigned variation.

Every goal reports an overall outcome across all eligible subjects, starting at the experiment eligibility boundary. This is the intent-to-treat view and the only view permitted to drive automatic verdicts or rollout.

When goal activation is configured, also report:

- The outcome among activated subjects, using events strictly after first goal activation.
- The outcome among not-activated subjects, using the experiment eligibility boundary.
- Activation reach: activated subjects divided by eligible subjects.

Activation must respect assignment and experiment eligibility ordering. Apply configured same-session and maximum-delay constraints. Use the same semantic outcome definition across populations, with each population's explicit time boundary.

Conditional results are descriptive because activation can depend on post-assignment behavior. Do not present an activated treatment cohort against an unconditional control as a randomized comparison. A variation without an activation stage still reports its overall outcome; conditional fields are null, not zero.

## Observed-condition integrity

When variation-specific activation filters describe distinct observable conditions, evaluate those definitions across the eligible cohort. Classify each subject into exactly one segment relative to the assigned variation:

- Neither expected nor unexpected conditions observed.
- Expected condition only.
- Unexpected condition only.
- Both expected and unexpected conditions.

Keep every subject in the assigned variation's overall result. Unexpected-only and both-condition observations produce data-quality warnings. Only the expected condition establishes goal activation for conditional outcomes.

## Event semantics and destination attribution

Use semantic event filters to distinguish a meaningful action from surrounding or automatic telemetry. A content-selection outcome must require an interaction on the selectable content item and a target content reference. Heading, container, scroll, and automatic interaction events do not satisfy that outcome.

For visibility-based activation, consume qualifying visibility events and expose the visibility threshold and duration in the event definition. Do not equate assignment with observation or invent a separate rendered-but-unobserved stage that the telemetry cannot measure.

All event filters must support application scope. Events from an unlisted application, including administration or server applications, cannot activate a goal or satisfy its outcome.

Where a goal measures navigation following a content selection, destination attribution must:

1. Select a qualifying content-item interaction.
2. Extract the selected target content UUID from the interaction's inherited content reference.
3. Resolve that target's canonical destination route from content metadata or a stable route identifier.
4. Find the first later page impression for the same experiment subject and browser session, within five minutes, whose path matches the canonical route.
5. Count the subject once for the attributed outcome and retain stable event identifiers for deduplication.

Use the same algorithm for historical and newly received events. Reuse existing target references, stable element identifiers, identity, session, timestamp, and page-path fields where available; the feature does not require a separate exposure ID, interaction ID, or destination event format.

Report unresolved target UUIDs, unsupported content types, missing route identifiers, missing session IDs, and unmatched destinations separately. Missing or mismatched evidence must remain unattributed. Route resolution must use the owning application's content routes without embedding application-specific route maps in the platform specification.

## Aggregation invariants

All populations and outcomes must use the same captured exclusions, assignment attribution, application scope, event deduplication, and aggregation cutoff.

- Resolve excluded principals and every linked installation before constructing the subject cohort.
- Preserve the established principal-first event identity and assignment attribution rules. Identity links do not establish that two records represent one human.
- Deduplicate browser events by their stable logical event key, using `COALESCE(client_id, CAST(id AS VARCHAR))` where that is the event schema's established key.
- Freeze the eligible cohort and event-time cutoff within an aggregation run so all denominators reconcile. A later rerun may incorporate newly arrived warehouse data.
- Bound warehouse scans by the experiment window and cutoff. Validate table identifiers and bind query values; batch the captured cohort where needed.
- Count subjects for reach and conversion rates. Count distinct logical events for event intensity.
- Keep unexpected observations in the subject's assigned variation.
- Apply outcome windows and strict post-activation ordering consistently.
- Persist aggregates for GraphQL consumption. Studio must not query Trino directly.

Extend existing experimentation cohort, conversion, and session-duration aggregation rather than introducing a separate execution or segmentation subsystem. Preserve media-aware sessionization and the five-minute inactivity allowance for Session Length. Activated duration calculations use activity from goal activation forward; adding distribution summaries must preserve the metric's session rules.

## Numerical summaries and result contract

Extend the GraphQL result contract additively. Existing result fields retain their meaning. Expose:

- Activation configuration state, including unconfigured and not applicable.
- Assigned, eligible, activated, and not-activated subject counts.
- Reach and its interval.
- Overall, activated, and not-activated outcome summaries.
- Attributed destination subject and interaction counts where applicable.
- Expected-only, unexpected-only, both, and neither observation counts.
- Application-scope, exclusion, deduplication, and attribution diagnostics.
- Aggregation cutoff and last-updated time.

Every binary rate includes numerator, denominator, estimate, and a Wilson 95 percent interval. Pairwise inference uses the planned statistical test rather than treating a displayed rate interval as the comparison result.

Continuous metrics include observation count, mean, variance or standard deviation as appropriate to the contract, median, p75, p90, p95, p99, maximum, and applicable intervals. Provide these summaries for overall, activated, and not-activated populations. Report an interval for the difference between variations.

For visibility goals, exposure intensity includes activated subjects, distinct logical impression events, mean events per activated subject, median, p95, and p99. Repeated observations must remain distinguishable from broader reach.

Return null conditional fields when activation is unconfigured or not applicable. Represent a configured rule with zero activations explicitly so consumers can distinguish it from absent measurement.

## Statistical interpretation

Analyze the experiment's planned comparisons, including treatment-versus-control and treatment-versus-treatment comparisons where configured. Apply the configured family-wise frequentist correction across the planned comparison family. Bayesian analysis reports posterior decision quantities for the same comparisons.

A conclusive result must satisfy the configured statistical threshold, minimum practical effect, sample requirements, sample-ratio checks, and guardrails. Reach and conditional outcomes may explain the mechanism but cannot turn an inconclusive overall result into a winning verdict.

Use metric-appropriate intervals and Fisher's exact test for small conditional binary counts. Conditional comparisons remain descriptive regardless of statistical significance.

Produce deterministic conclusion reasons for conclusive, equivalent, inconclusive, insufficient-data, and data-quality-blocked states. Each conclusion must be reproducible from persisted results and identify the comparison, interval, threshold, and blocking reason where applicable.

Keep inconclusive raw lift neutral. Label frequentist evidence `1 − p` and Bayesian evidence `P(win)`.

## Studio configuration and results

Add an optional Activation section to each conversion goal. It edits the default event filter, per-variation mode and override, application scope, same-session constraint, and maximum delay. Explain that experiment activation defines eligibility and goal activation defines a descriptive post-assignment population.

Lead results with the deterministic conclusion and all-eligible outcome. Configuration and results remain usable for goals without activation.

### Required visualizations

| View | Required content |
| --- | --- |
| Variation funnel | Assigned → Eligible → Goal activated → Outcome → Attributed destination where those stages apply. Each stage shows count, rate, and denominator. Counts/percentages can be toggled without changing denominator definitions. |
| Reach versus conditional outcome | Activation reach on the x-axis, outcome rate among activated subjects on the y-axis, eligible sample size as point size, and 95 percent interval whiskers on both rates. |
| Overall outcome comparison | All-eligible estimates and confidence intervals plus planned pairwise differences in a forest or dot plot. This is the primary randomized view. |
| Continuous-metric distribution | Percentile profile or ECDF with Overall, Activated, and Not activated controls. Show p50, p75, p90, p95, and p99; identify the five-minute floor for Session Length. |
| Observation integrity | Assigned variation by expected-only, unexpected-only, both, and neither observations. |
| Exposure intensity | Subject counts and logical impression events per activated subject, including mean, median, p95, and p99. |

Use goal-specific terminology when it clarifies the measurement. For visibility goals, “Observed / exposed” may label activation; it must refer to the actual qualifying visibility event.

Every graph has an exact-value table containing its numerators, denominators, estimates, intervals, observation counts, relevant distribution summaries, population labels, cutoff, and last-updated time. Graphs and tables must consume the same backend contract and reconcile exactly.

### Interpretation and accessibility

- Label activated and not-activated results as descriptive.
- Keep raw lift neutral unless the overall planned comparison is conclusive.
- Keep interaction and verified destination outcomes as distinct stages.
- Show Not Applicable separately from zero and missing data; do not invent absent stages.
- Surface unexpected observations, duplicate delivery, exclusion coverage, cross-application contamination, unresolved routes, and unmatched destinations as diagnostics.
- Explain event definitions and denominators in tooltips.
- Use distinguishable shapes and accessible colors, with all information preserved in text and table fallbacks.

## Historical reaggregation

Historical data with the required fields uses the same activation and attribution rules as new data. Reaggregate with the captured principal and linked-installation exclusions, stable event deduplication, application scope, assignment ordering, and one chosen cutoff.

Report missing target-route mappings and unmatched destination paths as diagnostics. Do not label an otherwise verified historical outcome as inferred merely because it predates the aggregation feature. Existing experiments need not restart solely to apply the new aggregation rules when their recorded events supply the required evidence.

## Acceptance and validation

- [ ] Goals support an optional default filter and explicit per-variation `EVENT`, `INHERIT`, and `NOT_APPLICABLE` rules.
- [ ] Invalid variation keys, filter/mode combinations, unbounded filters, and invalid application identifiers fail clearly.
- [ ] Configuration round-trips through migrations, repository mapping, services, and GraphQL. Effective changes invalidate results; normalized no-op changes do not.
- [ ] Goals without activation retain their overall behavior and remain usable in Studio.
- [ ] Goal activation never removes eligible subjects from the overall denominator.
- [ ] Activated plus not activated equals eligible for every effective activation filter, including zero-match cases.
- [ ] Overall, activated, and not-activated outcomes use explicit denominators and the correct time boundaries.
- [ ] Explicit not-applicable and unconfigured activation remain distinguishable from zero activations.
- [ ] Wrong-condition and both-condition observations remain in assigned overall results and produce diagnostics.
- [ ] Content-selection goals reject heading, container, scroll, and automatic interaction telemetry.
- [ ] Destination attribution verifies the selected target, subject, browser session, event order, five-minute window, and canonical path.
- [ ] Exclusions, linked-installation exclusions, application scope, logical-event deduplication, and a fixed cutoff apply consistently to all results.
- [ ] Continuous metrics report all three population distributions through p99; exposure intensity distinguishes event repetition from reach.
- [ ] Only overall outcomes influence automatic verdicts and rollout. Conditional results cannot promote an inconclusive outcome.
- [ ] Stored results reproduce conclusions and their evidence or blocking reasons.
- [ ] Every Studio graph reconciles with its exact-value table and exposes its denominator and measurement definition.

Backend integration tests must exercise PostgreSQL persistence and Trino aggregation, including default and per-variation activation, inherited filters, no activation, not applicable, zero activation, unexpected and combined observations, exclusions, duplicate delivery, application scope, event ordering, session and delay constraints, cutoff reconciliation, and historical reaggregation.

Attribution tests must cover visibility qualification, target extraction, route resolution, exact matches, mismatches, expiry, duplicate delivery, session isolation, missing fields, unsupported content types, and unresolved routes.

Statistical tests must cover planned comparison families, correction, practical-effect and sample thresholds, guardrails, deterministic conclusions, and the restriction to overall outcomes for verdicts.

Studio component tests must cover conclusive, equivalent, inconclusive, insufficient-data, data-quality-blocked, not-applicable, unconfigured, zero-activation, unexpected-observation, unresolved-route, unmatched-destination, and long-tail distribution states, plus accessible table fallbacks.

## Out of scope

- A general branching or cyclic funnel builder.
- Using goal-activated populations to determine automatic rollout.
- Replacing the existing segmentation subsystem.
- Inferring a human identity from an installation or principal link.
