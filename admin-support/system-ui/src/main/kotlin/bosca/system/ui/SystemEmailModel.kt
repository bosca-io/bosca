package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.ConfigurationInput
import bosca.system.ui.graphql.SystemIntegrationConfiguration
import bosca.system.ui.graphql.SystemSetConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable
class SystemEmailModel(
    val kind: String,
    val admin: Boolean = false,
    var draft: SystemEmailDraft = SystemEmailDraft(),
    var loaded: Boolean = false,
    var hasPassword: Boolean = false, var hasApiKey: Boolean = false, var hasVerificationKey: Boolean = false,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    init { require(kind in setOf("mailer", "smtp", "mailgun", "sendgrid")) }
    val title: String get() = when (kind) { "mailer" -> "Email"; "smtp" -> "SMTP"; "mailgun" -> "Mailgun"; else -> "SendGrid" }
    private val description: String get() = when (kind) {
        "mailer" -> "Email Provider Configuration"
        "smtp" -> "SMTP Email Integration Configuration"
        "mailgun" -> "Mailgun Email Integration Configuration"
        else -> "SendGrid Email Integration Configuration"
    }

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val config = ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables(kind)).configurations.configuration
        val value = configurationObject(config?.value)
        fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        draft = SystemEmailDraft(
            provider = text("type").ifBlank { "deployment" },
            host = text("host"), port = text("port").ifBlank { "587" }, security = text("security").ifBlank { "STARTTLS" },
            username = text("username"), domain = text("domain"), apiBaseUrl = text("apiBaseUrl").ifBlank { "https://api.mailgun.net" },
        )
        hasPassword = text("password").isNotEmpty()
        hasApiKey = text("apiKey").isNotEmpty()
        hasVerificationKey = text("webhookVerificationKey").isNotEmpty()
        loaded = true
    }

    suspend fun saveProvider(provider: String, ctx: RenderContext) = feedback.perform {
        require(kind == "mailer")
        draft.provider = provider
        require(provider in setOf("deployment", "smtp", "mailgun", "sendgrid")) { "Choose an email provider." }
        save(ctx) { put("type", if (provider == "deployment") JsonNull else JsonPrimitive(provider)) }
    }

    suspend fun saveSmtp(host: String, port: String, security: String, username: String, password: String, clearPassword: Boolean, ctx: RenderContext) = feedback.perform {
        require(kind == "smtp")
        draft = draft.copy(host = host, port = port, security = security, username = username, password = password, clearPassword = clearPassword)
        require(host.trim().isNotBlank() && host.trim().none(Char::isWhitespace)) { "Enter an SMTP hostname." }
        require(port.all(Char::isDigit) && port.toIntOrNull() in 1..65535) { "Port must be between 1 and 65535." }
        require(security in setOf("STARTTLS", "TLS", "NONE")) { "Choose a connection security option." }
        save(ctx) {
            val secret = secret(password, clearPassword, get("password"))
            require(username.trim().isNotEmpty() == secret.isNotEmpty()) { "Set both username and password, or clear both for an unauthenticated relay." }
            put("host", JsonPrimitive(host.trim())); put("port", JsonPrimitive(port.toInt())); put("security", JsonPrimitive(security))
            put("username", JsonPrimitive(username.trim())); put("password", JsonPrimitive(secret))
        }
    }

    suspend fun saveMailgun(apiKey: String, domain: String, baseUrl: String, ctx: RenderContext) = feedback.perform {
        require(kind == "mailgun")
        draft = draft.copy(apiKey = apiKey, domain = domain, apiBaseUrl = baseUrl)
        require(domain.isNotBlank()) { "Enter a verified sending domain." }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()) { "API base URL must be an absolute http or https URL." }
        save(ctx) {
            val secret = secret(apiKey.trim(), false, get("apiKey"))
            require(secret.isNotBlank()) { "Enter a Mailgun API key." }
            put("apiKey", JsonPrimitive(secret)); put("domain", JsonPrimitive(domain.trim())); put("apiBaseUrl", JsonPrimitive(baseUrl.trim()))
        }
    }

    suspend fun saveSendgrid(apiKey: String, clearApiKey: Boolean, verificationKey: String, clearVerificationKey: Boolean, ctx: RenderContext) = feedback.perform {
        require(kind == "sendgrid")
        draft = draft.copy(apiKey = apiKey, clearApiKey = clearApiKey, verificationKey = verificationKey, clearVerificationKey = clearVerificationKey)
        save(ctx) {
            put("apiKey", JsonPrimitive(secret(apiKey, clearApiKey, get("apiKey"))))
            put("webhookVerificationKey", JsonPrimitive(secret(verificationKey, clearVerificationKey, get("webhookVerificationKey"))))
        }
    }

    private suspend fun save(ctx: RenderContext, update: MutableMap<String, JsonElement>.() -> Unit) {
        require(loaded) { "Reload the page before saving settings." }
        // Read secrets under the current caller, rather than trusting a serialized live state.
        val current = ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables(kind)).configurations.configuration
        val value = configurationObject(current?.value).toMutableMap().apply(update)
        ctx.gql.execute(SystemSetConfiguration, SystemSetConfiguration.Variables(ConfigurationInput(key = kind, description = current?.description ?: description, public = false, value = JsonObject(value))))
        reload(ctx)
        feedback.message = "$title settings saved."
    }

    private fun configurationObject(value: JsonElement?): JsonObject {
        require(value == null || value is JsonNull || value is JsonObject) { "Configuration must contain a JSON object." }
        return value as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun secret(input: String, clear: Boolean, stored: JsonElement?): String =
        if (clear) "" else input.takeIf(String::isNotEmpty) ?: (stored as? JsonPrimitive)?.contentOrNull.orEmpty()
}

suspend fun systemEmail(kind: String): SystemEmailModel {
    val ctx = currentRenderContext()
    return SystemEmailModel(kind, admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
