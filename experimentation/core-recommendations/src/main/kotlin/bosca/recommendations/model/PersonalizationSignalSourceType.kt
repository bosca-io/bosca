package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Where a [PersonalizationSignalDefinition] sources its raw value from.
 *
 * The constant names are the serialized form (matching the uppercase GraphQL enum values); the SQL
 * [bosca.db.mapper.EnumMapper] lowercases on bind and uppercases on read for the
 * `recommendations.signal_source_type` Postgres enum, so no `@SerialName` is used here.
 */
@Serializable
enum class PersonalizationSignalSourceType {
    /** A profile attribute type — [PersonalizationSignalDefinition.sourceId] is a `ProfileAttributeType` id. */
    ATTRIBUTE,

    /** A segment — [PersonalizationSignalDefinition.sourceId] is a segment id. */
    SEGMENT,
}
