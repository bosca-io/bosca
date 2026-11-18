package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
class SystemMuxModel(
    val admin: Boolean = false, var draft: SystemMuxDraft = SystemMuxDraft(),
    var loaded: Boolean = false, var hasTokenId: Boolean = false, var hasTokenSecret: Boolean = false,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val value = systemConfigurationObject(ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables("mux")).configurations.configuration?.value)
        fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull
        draft = SystemMuxDraft(
            playbackPolicy = text("playbackPolicy").orEmpty().ifBlank { "public" },
            maxWaitSeconds = text("maxWaitSeconds") ?: "3600",
            videoQuality = text("videoQuality") ?: "default", maxResolution = text("maxResolutionTier") ?: "default",
            mp4Support = value["mp4Support"]?.jsonPrimitive?.booleanOrNull ?: true,
            subtitleLanguage = text("defaultSubtitleLanguage") ?: "en", test = value["test"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        hasTokenId = !text("tokenId").isNullOrEmpty()
        hasTokenSecret = !text("tokenSecret").isNullOrEmpty()
        loaded = true
    }

    suspend fun save(tokenId: String, tokenSecret: String, clearTokenId: Boolean, clearTokenSecret: Boolean, playbackPolicy: String, maxWaitSeconds: String, videoQuality: String, maxResolution: String, mp4Support: Boolean, subtitleLanguage: String, test: Boolean, ctx: RenderContext) = feedback.perform {
        draft = SystemMuxDraft(tokenId, tokenSecret, clearTokenId, clearTokenSecret, playbackPolicy, maxWaitSeconds, videoQuality, maxResolution, mp4Support, subtitleLanguage, test)
        require(loaded) { "Reload the page before saving settings." }
        require(playbackPolicy in setOf("public", "signed")) { "Choose a playback policy." }
        val wait = maxWaitSeconds.toLongOrNull()
        require(wait != null && wait >= 60) { "Max wait must be at least 60 seconds." }
        require(videoQuality in setOf("default", "basic", "plus", "premium")) { "Choose a video quality." }
        require(maxResolution in setOf("default", "1080p", "1440p", "2160p")) { "Choose a maximum resolution." }
        val current = ctx.gql.execute(SystemIntegrationConfiguration, SystemIntegrationConfiguration.Variables("mux")).configurations.configuration
        val value = systemConfigurationObject(current?.value).toMutableMap().apply {
            put("tokenId", JsonPrimitive(systemStoredSecret(tokenId, clearTokenId, get("tokenId"))))
            put("tokenSecret", JsonPrimitive(systemStoredSecret(tokenSecret, clearTokenSecret, get("tokenSecret"))))
            put("playbackPolicy", JsonPrimitive(playbackPolicy)); put("maxWaitSeconds", JsonPrimitive(wait))
            if (videoQuality == "default") remove("videoQuality") else put("videoQuality", JsonPrimitive(videoQuality))
            if (maxResolution == "default") remove("maxResolutionTier") else put("maxResolutionTier", JsonPrimitive(maxResolution))
            put("mp4Support", JsonPrimitive(mp4Support)); put("test", JsonPrimitive(test))
            if (subtitleLanguage.isEmpty()) remove("defaultSubtitleLanguage") else put("defaultSubtitleLanguage", JsonPrimitive(subtitleLanguage))
        }
        ctx.gql.execute(SystemSetConfiguration, SystemSetConfiguration.Variables(ConfigurationInput(key = "mux", description = current?.description ?: "Mux Video Integration Configuration", value = JsonObject(value), public = false)))
        reload(ctx)
        feedback.message = "Mux settings saved."
    }
}

suspend fun systemMux(): SystemMuxModel {
    val ctx = currentRenderContext()
    return SystemMuxModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
