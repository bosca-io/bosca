package bosca.experimentation.graphql

import bosca.analytics.model.Device
import kotlinx.serialization.Serializable

/**
 * GraphQL-facing input mirror of [Device], exposed in the schema as
 * `AnalyticsDevice`.
 *
 * Mirrors the wire-format [Device] structurally but uses camelCase field
 * names (matching graphql-java's input-field convention) instead of the
 * snake_case `@SerialName`s that [Device] uses for analytics-collector
 * ingestion. The KSP-generated GraphQL dispatcher decodes input arguments
 * via `decodeFromJsonElement(serializer())`, so the keys graphql-java
 * forwards from the schema must match the serializer's field names — and
 * those would otherwise collide with the snake_case wire format.
 *
 * The GraphQL schema name is `AnalyticsDevice` to disambiguate from the
 * unrelated `DeviceInput` declared by the devices framework module. The
 * Kotlin class name is `AnalyticsDeviceInput` to mirror the schema name
 * with the `Input` suffix that's conventional for GraphQL input types.
 *
 * Translated to a wire-format [Device] at the GraphQL boundary in
 * [FeatureFlagQueryController.evaluate] before being passed into the
 * service layer.
 */
@Serializable
data class AnalyticsDeviceInput(
    val installationId: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val platform: String? = null,
    val primaryLocale: String? = null,
    val systemName: String? = null,
    val timezone: String? = null,
    val type: String? = null,
    val version: String? = null,
)

/**
 * Convert to the wire-format [Device]. Missing fields default to empty
 * strings rather than null because [Device] declares its fields as
 * non-nullable; clients that don't know one of the fields can simply
 * omit it from the GraphQL input and the resulting empty string will
 * fail every targeting condition that checks it.
 */
fun AnalyticsDeviceInput.toDevice(): Device = Device(
    installationId = installationId.orEmpty(),
    manufacturer = manufacturer.orEmpty(),
    model = model.orEmpty(),
    platform = platform.orEmpty(),
    primaryLocale = primaryLocale.orEmpty(),
    systemName = systemName.orEmpty(),
    timezone = timezone.orEmpty(),
    type = type.orEmpty(),
    version = version.orEmpty(),
)
