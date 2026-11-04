package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
class SystemPushModel(
    val admin: Boolean = false, var draft: SystemPushDraft = SystemPushDraft(),
    var loaded: Boolean = false, var hasServiceAccount: Boolean = false, var hasPrivateKey: Boolean = false,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val value = systemConfigurationObject(ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables("push")).configurations.configuration?.value)
        val fcm = value["fcm"]?.takeUnless { it is JsonNull }?.jsonObject
        val apns = value["apns"]?.takeUnless { it is JsonNull }?.jsonObject
        fun text(key: String) = apns?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
        draft = SystemPushDraft(
            enabled = value["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            fcmEnabled = fcm != null, apnsEnabled = apns != null,
            teamId = text("teamId"), keyId = text("keyId"), bundleId = text("bundleId"),
            sandbox = apns?.get("sandbox")?.jsonPrimitive?.booleanOrNull ?: true,
        )
        hasServiceAccount = !fcm?.get("serviceAccountJson")?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
        hasPrivateKey = text("privateKey").isNotEmpty()
        loaded = true
    }

    suspend fun save(enabled: Boolean, fcmEnabled: Boolean, serviceAccountJson: String, useDeploymentCredentials: Boolean, apnsEnabled: Boolean, teamId: String, keyId: String, bundleId: String, privateKey: String, clearPrivateKey: Boolean, sandbox: Boolean, ctx: RenderContext) = feedback.perform {
        draft = SystemPushDraft(enabled, fcmEnabled, serviceAccountJson, useDeploymentCredentials, apnsEnabled, teamId, keyId, bundleId, privateKey, clearPrivateKey, sandbox)
        require(loaded) { "Reload the page before saving settings." }
        val replacement = if (fcmEnabled && !useDeploymentCredentials && serviceAccountJson.isNotBlank()) Json.parseToJsonElement(serviceAccountJson) else JsonNull
        require(replacement is JsonNull || replacement is JsonObject) { "Service account JSON must be an object, or null to keep the stored credentials." }
        val current = ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables("push")).configurations.configuration
        val value = systemConfigurationObject(current?.value).toMutableMap().apply {
            put("enabled", JsonPrimitive(enabled))
            if (fcmEnabled) {
                val fcm = systemConfigurationObject(get("fcm")).toMutableMap()
                if (useDeploymentCredentials) fcm.remove("serviceAccountJson")
                else if (replacement is JsonObject) fcm["serviceAccountJson"] = JsonPrimitive(serviceAccountJson)
                put("fcm", JsonObject(fcm))
            } else remove("fcm")
            if (apnsEnabled) {
                val apns = systemConfigurationObject(get("apns")).toMutableMap().apply {
                    for ((key, input) in mapOf("teamId" to teamId, "keyId" to keyId, "bundleId" to bundleId)) {
                        if (input.isBlank()) remove(key) else put(key, JsonPrimitive(input.trim()))
                    }
                    val secret = systemStoredSecret(privateKey, clearPrivateKey, get("privateKey"))
                    if (secret.isBlank()) remove("privateKey") else put("privateKey", JsonPrimitive(secret))
                    put("sandbox", JsonPrimitive(sandbox))
                }
                put("apns", JsonObject(apns))
            } else remove("apns")
        }
        ctx.gql.execute(SystemSetConfiguration, SystemSetConfiguration.Variables(ConfigurationInput(key = "push", description = current?.description ?: "Push Notification Delivery Configuration", value = JsonObject(value), public = false)))
        reload(ctx)
        feedback.message = "Push settings saved."
    }
}

suspend fun systemPush(): SystemPushModel {
    val ctx = currentRenderContext()
    return SystemPushModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
