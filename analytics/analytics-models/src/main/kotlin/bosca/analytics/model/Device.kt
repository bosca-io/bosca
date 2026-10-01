package bosca.analytics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    @SerialName("installation_id")
    val installationId: String,
    val manufacturer: String,
    val model: String,
    val platform: String,
    @SerialName("primary_locale")
    val primaryLocale: String,
    @SerialName("system_name")
    val systemName: String,
    val timezone: String,
    val type: String,
    val version: String,
) {
    /**
     * Resolves a [Condition.DeviceAttribute][bosca.experimentation.model.Condition.DeviceAttribute]
     * key against the typed fields of this device. Used by the targeting
     * rule evaluator to compare a rule's expected value against the
     * client-supplied device snapshot.
     *
     * ## Canonical key set
     *
     * The **canonical** form of every key is the Kotlin camelCase field
     * name, because that's what the admin UI's `commonDeviceKeys` list
     * exposes and what the GraphQL `AnalyticsDevice` input type declares.
     * Operators editing rules in the admin UI will only ever see the
     * canonical form. The aliases below exist solely so that rules
     * persisted before the canonical convention was settled, or rules
     * created by an external tool, still resolve correctly.
     *
     * | canonical key      | aliases (back-compat only)            | resolves to       |
     * |--------------------|---------------------------------------|-------------------|
     * | `installationId`   | `installation_id`                     | [installationId]  |
     * | `manufacturer`     | —                                     | [manufacturer]    |
     * | `model`            | —                                     | [model]           |
     * | `platform`         | —                                     | [platform]        |
     * | `primaryLocale`    | `primary_locale`, `locale`            | [primaryLocale]   |
     * | `systemName`       | `system_name`, `os`                   | [systemName]      |
     * | `timezone`         | `tz`                                  | [timezone]        |
     * | `type`             | `device_type`, `deviceType`           | [type]            |
     * | `version`          | `os_version`, `osVersion`             | [version]         |
     *
     * Unknown keys return `null`, which the targeting rule evaluator
     * treats the same as a non-matching condition. Adding a new alias
     * is fine; **adding a new canonical key** also requires adding it
     * to the admin UI's `commonDeviceKeys` array
     * (`web/administration/app/components/feature-flags/TargetingRulesEditor.vue`)
     * AND to the `AnalyticsDevice` GraphQL input AND to the analytics
     * SDK so the value actually arrives at evaluate time.
     */
    fun attributeValue(key: String): String? = when (key) {
        "installationId", "installation_id" -> installationId
        "manufacturer" -> manufacturer
        "model" -> model
        "platform" -> platform
        "primaryLocale", "primary_locale", "locale" -> primaryLocale
        "systemName", "system_name", "os" -> systemName
        "timezone", "tz" -> timezone
        "type", "device_type", "deviceType" -> type
        "version", "os_version", "osVersion" -> version
        else -> null
    }
}