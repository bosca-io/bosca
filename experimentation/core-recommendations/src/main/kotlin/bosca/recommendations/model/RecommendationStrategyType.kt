package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Defines the *signal* a strategy uses to generate content recommendations.
 *
 * The system is a candidate-generation → filter → rank pipeline where **personalization happens
 * entirely at read time** (the learned ranker, dismissal filtering, rating re-ranking), so nothing
 * is ever stored per profile. Each type names the source of the signal:
 * [TRENDING] materializes a global popularity pool; [CO_ENGAGEMENT] materializes a behavioral item→item
 * mapping ("people who engaged with this also engaged with…"); [COHORT_CO_ENGAGEMENT] materializes that
 * same behavioral mapping *conditioned on cohort* ("people like you who engaged with this…");
 * [PERSONALIZED] is a trained model served live per request and stores nothing. Content-based *similarity*
 * is produced by the separately trained content model's `similar` signature and needs no strategy row.
 *
 * The constant names are the serialized form (matching the uppercase GraphQL enum values);
 * the SQL [bosca.db.mapper.EnumMapper] lowercases on bind and uppercases on read for the
 * `recommendations.strategy_type` Postgres enum, so no `@SerialName` is used here.
 */
@Serializable
enum class RecommendationStrategyType {
    /**
     * Popularity signal: content with the highest recent interaction velocity across all users,
     * computed via an analytics query over the Iceberg event warehouse. Materialized on a schedule as
     * a single global pool keyed by this strategy. Served as the `trending` surface.
     */
    TRENDING,

    /**
     * Behavioral signal — audience **co-engagement**: for a given item, the other items that the same
     * users also engaged with ("people who engaged with this also engaged with…"). Evaluates an
     * analytics query of co-occurrence edges (`source_id`, `co_engaged_id`, `score`) and materializes
     * them as a source→co-engaged mapping, served item-anchored at an item's context (e.g. a content or
     * product page) as the `coEngaged` surface — distinct from content *similarity*, which is about the
     * content itself rather than audience behavior.
     */
    CO_ENGAGEMENT,

    /**
     * "People like you" signal — audience co-engagement **conditioned on cohort**: for a given item, the
     * other items that members of any of the viewer's cohorts (one membership per distinct value from their
     * useAsCohort Personalization Signals) also engaged with. Evaluates an analytics query of per-cohort
     * co-occurrence edges (`cohort_key`, `source_id`, `co_engaged_id`, `score`) and materializes them keyed
     * by `(cohort_key, source_id)`. Served item-anchored, merged across the viewer's memberships, and folded into
     * `recommended` as "People also viewed by people like you"; a viewer with no memberships falls back to the
     * whole-crowd [CO_ENGAGEMENT] set. Like the other materialized types it stores nothing per profile — a
     * cohort is an aggregate, not a person.
     */
    COHORT_CO_ENGAGEMENT,

    /**
     * Personalized signal from a trained TFRS (TensorFlow Recommenders) two-tower model served via
     * TensorFlow Serving. Trained offline on interaction data from the warehouse and served **live per
     * request** — retrieved and re-ranked on demand for the requesting profile and cached per profile —
     * so it materializes nothing. Powers the `forYou` personalized feed and the per-viewer re-rank that
     * `recommended` applies to the item-context blend.
     */
    PERSONALIZED,
}
