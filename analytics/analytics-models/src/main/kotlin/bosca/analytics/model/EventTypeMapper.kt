package bosca.analytics.model

import bosca.db.mapper.EnumMapper

/**
 * Persists [EventType] using the enum's Kotlin `.name` form (e.g. `"Interaction"`).
 *
 * This matches what `IcebergEventsToRecordTransform` writes into the analytics
 * events table (`event.setField("type", type.name)`), so downstream consumers
 * — notably the experimentation aggregation job — can bind a conversion goal's
 * persisted event type directly into a Trino query without any additional
 * casing conversion. The default [EnumMapper] stores `name.lowercase()` and
 * reads via `uppercase()`, which would both break the Iceberg parity and fail
 * to resolve `EventType` values whose enum names are PascalCase.
 */
/**
 * Canonical storage form for an [EventType] in the analytics events
 * Iceberg table. Both the Iceberg writer
 * (`IcebergEventsToRecordTransform.toRecord`) and the experimentation
 * aggregation job (`ExperimentResultAggregation.countConversions`) MUST
 * route through this helper so that the two surfaces can never drift
 * (e.g. one switching to a `@SerialName` form while the other keeps
 * `.name`). The `ConversionGoalEventTypeSerializationTest` regression
 * guard locks this contract by calling this same function.
 */
fun eventTypeStorageName(eventType: EventType): String = eventType.name

/**
 * Value the Iceberg events writer puts into the `type` column for the
 * given [EventType]. Used by `IcebergEventsToRecordTransform.toRecord`
 * directly so that the regression-guard test
 * (`ConversionGoalEventTypeSerializationTest`) can call this exact symbol
 * and detect drift between the writer and the experimentation aggregation
 * binder. If you change this function you MUST change
 * [aggregationEventTypePredicateValue] in the same commit.
 */
fun icebergEventTypeColumnValue(eventType: EventType): String = eventTypeStorageName(eventType)

/**
 * Value the experimentation aggregation job binds into the Trino
 * `type = ?` predicate when filtering events for a conversion goal.
 * Mirror of [icebergEventTypeColumnValue] — both must produce the same
 * string for every [EventType] or the aggregation will silently match
 * zero rows. The regression-guard test calls this directly.
 */
fun aggregationEventTypePredicateValue(eventType: EventType): String = eventTypeStorageName(eventType)

object EventTypeMapper : EnumMapper<EventType>({ EventType.valueOf(it) }) {
    override fun bind(
        type: kotlin.reflect.KClass<*>,
        arguments: List<kotlin.reflect.KClass<*>>,
        stmt: java.sql.PreparedStatement,
        index: Int,
        value: EventType?
    ): java.sql.PreparedStatement {
        if (value == null) {
            stmt.setNull(index, java.sql.Types.VARCHAR)
        } else {
            stmt.setString(index, value.name)
        }
        return stmt
    }

    override fun map(
        type: kotlin.reflect.KClass<*>,
        arguments: List<kotlin.reflect.KClass<*>>,
        result: java.sql.ResultSet,
        index: Int
    ): EventType? = result.getString(index)?.let { EventType.valueOf(it) }

    override fun map(
        type: kotlin.reflect.KClass<*>,
        arguments: List<kotlin.reflect.KClass<*>>,
        result: java.sql.ResultSet,
        name: String
    ): EventType? = result.getString(name)?.let { EventType.valueOf(it) }
}
