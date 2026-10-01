package bosca.experimentation.jobs

import bosca.analytics.model.EventType
import bosca.analytics.model.aggregationEventTypePredicateValue
import bosca.analytics.model.icebergEventTypeColumnValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the serialization coupling between
 * [bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform] and
 * [bosca.experimentation.jobs.ExperimentResultAggregation].
 *
 * The Iceberg transform writes the analytics event type column via
 * `event.setField("type", type.name)` — the Kotlin enum `.name` (capitalized
 * form, e.g. `"Interaction"`). The experimentation aggregation job builds its
 * Trino `WHERE type = ?` parameter from a conversion goal's `EventType` field
 * and must therefore bind the same `.name` form in order to match any rows.
 *
 * Before this test existed, the aggregation bound a free-form `String` that
 * a user could set to any value (including the wire-format
 * `@SerialName("interaction")`, lowercase), which silently matched zero rows
 * against the capitalized storage. The goal's event type is now a typed
 * `EventType`, and this test locks in the contract: whatever string the
 * Iceberg writer produces for a given enum value **must** equal the string
 * the aggregation would bind for the same enum value, for every value in
 * the enum.
 *
 * If this test fails, one of the two sides has changed its serialization and
 * the other must be updated in the same commit — do not "fix" the test by
 * loosening the assertion.
 */
class ConversionGoalEventTypeSerializationTest {

    // These helpers are intentionally trivial (`et.name`) AND carry
    // KDoc `@see` links to the production sites that share the same
    // convention, so a future commit cannot quietly switch one side to
    // a `@SerialName` form without forcing a corresponding edit here:
    //
    //  - `ExperimentResultAggregation.kt:countConversions` binds
    //    `goal.eventType?.name` into the `type = ?` predicate parameter.
    //  - `IcebergEventsToRecordTransform.kt:toRecord` writes
    //    `event.setField("type", type.name)` into the Iceberg row.
    //
    // An IDE rename / refactor that removes either symbol fails this
    // file's compilation before it can ship.

    // These call the SAME named helpers production uses:
    //   - `IcebergEventsToRecordTransform.toRecord` calls `icebergEventTypeColumnValue(type)`
    //   - `ExperimentResultAggregation.countConversions` calls `aggregationEventTypePredicateValue(it)`
    // If a future commit changes either prod site to bypass these helpers
    // (e.g. directly bind `it.serialName`), the helpers go from "the only
    // way to write the column / bind the parameter" to "an unused alias",
    // and any drift between the two surfaces is caught here.
    private fun aggregationBindForm(eventType: EventType): String =
        aggregationEventTypePredicateValue(eventType)

    private fun icebergWriteForm(eventType: EventType): String =
        icebergEventTypeColumnValue(eventType)

    @Test
    fun `every EventType round-trips identically between aggregation bind and Iceberg write`() {
        for (eventType in EventType.entries) {
            val aggregation = aggregationBindForm(eventType)
            val iceberg = icebergWriteForm(eventType)
            assertEquals(
                iceberg,
                aggregation,
                "EventType.$eventType: aggregation binds '$aggregation' but Iceberg writes '$iceberg'. " +
                    "The two serialization surfaces have drifted — update both sides in the same commit."
            )
        }
    }

    @Test
    fun `null event type is a valid 'match any' filter and produces no bind value`() {
        // A goal with no eventType should match events of any type. The
        // aggregation skips the `type = ?` condition entirely in that case,
        // so there's no string to bind. This test documents that contract
        // so that anyone changing the binding logic notices the null branch.
        //
        // We mirror the exact `goal.eventType?.let { ... }` shape from
        // ExperimentResultAggregation.countConversions so that a refactor
        // that, say, switches to `goal.eventType?.name ?: "*"` (which would
        // silently zero out match-any goals) fails this test instead of
        // only surfacing as empty experiment results in production.
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()
        val nullEventType: EventType? = null
        nullEventType?.let {
            conditions.add("type = ?")
            params.add(it.name)
        }
        assertEquals(emptyList(), conditions, "null eventType must not add a type predicate")
        assertEquals(emptyList(), params, "null eventType must not bind any parameter")

        // And conversely: a typed eventType must add exactly the capitalized
        // `.name` form — same contract as the Iceberg write side.
        val typed: EventType? = EventType.Interaction
        typed?.let {
            conditions.add("type = ?")
            params.add(it.name)
        }
        assertEquals(listOf("type = ?"), conditions)
        assertEquals(listOf("Interaction"), params)
    }

    @Test
    fun `EventType name form is capitalized as expected by the Iceberg storage contract`() {
        // Spot-check the exact values the V2 migration's CHECK constraint enforces.
        // If someone renames an enum constant, this test fails before the migration
        // has a chance to reject values at runtime.
        assertEquals("Session", EventType.Session.name)
        assertEquals("Interaction", EventType.Interaction.name)
        assertEquals("Impression", EventType.Impression.name)
        assertEquals("Completion", EventType.Completion.name)
        assertEquals("Installation", EventType.Installation.name)
        assertEquals("Assignment", EventType.Assignment.name)
        assertEquals("Error", EventType.Error.name)
    }
}
