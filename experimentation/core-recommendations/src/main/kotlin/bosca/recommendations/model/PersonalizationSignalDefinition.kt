package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * An admin-configured **Personalization Signal** definition: how to derive a keyed, typed
 * personalization value from a profile attribute (or segment) for the recommender. Its [expression]
 * (JSONata) is evaluated at attribute write-time and the result cached on the attribute as a
 * `PersonalizationSignal { key, value }`; the trainer (user-tower features) and the cohort builder read
 * the cached values via Trino, keyed by [key].
 *
 * Multiple definitions may target the same [sourceId] (different [key]s). A [useAsCohort] definition must
 * be [PersonalizationSignalValueType.CATEGORICAL] or [PersonalizationSignalValueType.BOOLEAN] so each
 * membership has a finite label (enforced by the service). A profile may hold several distinct values for
 * one signal key; every value becomes an independent cohort membership.
 */
@BatchKey("id")
@Serializable
data class PersonalizationSignalDefinition(
    @Contextual
    val id: UUID = UUID.NIL,
    /** Stable signal identifier and feature/cohort membership name (e.g. `age_band`, `gender`). Unique. */
    val key: String,
    @ColumnName("source_type")
    val sourceType: PersonalizationSignalSourceType,
    /** The `ProfileAttributeType` id (ATTRIBUTE) or segment id (SEGMENT) this signal derives from. */
    @ColumnName("source_id")
    val sourceId: String,
    /** JSONata producing this signal's value from the attribute (value + confidence/verified/…); a null result excludes it. */
    val expression: String,
    @ColumnName("value_type")
    val valueType: PersonalizationSignalValueType,
    val priority: Int = 0,
    @ColumnName("use_as_feature")
    val useAsFeature: Boolean = true,
    @ColumnName("use_as_cohort")
    val useAsCohort: Boolean = false,
    val enabled: Boolean = true,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)
