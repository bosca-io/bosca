package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * How a [PersonalizationSignalDefinition]'s computed value is interpreted by the recommender — the
 * trainer's feature encoding and the cohort builder's treatment. The JSONata expression is responsible
 * for producing a value that matches this type (it may bucket a raw value down to a label).
 *
 * The constant names are the serialized form (uppercase GraphQL enum values); the SQL
 * [bosca.db.mapper.EnumMapper] lowercases on bind and uppercases on read for the
 * `recommendations.signal_value_type` Postgres enum, so no `@SerialName` is used here.
 */
@Serializable
enum class PersonalizationSignalValueType {
    /** A single string label (e.g. `"25-34"`, `"female"`) — StringLookup + embedding; usable as a cohort membership. */
    CATEGORICAL,

    /** An array of string labels (e.g. `["theology","worship"]`) — multi-hot encoded; feature-only. */
    MULTI_CATEGORICAL,

    /** A number — bucketized/normalized by the trainer; feature-only unless the expression buckets it to a label. */
    NUMERIC,

    /** A boolean — a binary feature; usable as a cohort membership. */
    BOOLEAN,
}
