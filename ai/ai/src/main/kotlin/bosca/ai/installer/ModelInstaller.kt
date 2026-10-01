package bosca.ai.installer

import bosca.ai.models.model.ModelInput
import bosca.ai.models.service.ModelService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ModelInstaller(
    private val service: ModelService
) : PackageInstaller {
    override val version: String = "1.0.1"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        listOf(
            ModelInput(
                key = "model.google.chirp.hd.zephyr",
                name = "Chirp (Female Voice)",
                description = "",
                type = "en-US-Chirp3-HD-Zephyr",
                configuration = buildJsonObject {
                    put("languageTag", JsonPrimitive("en-US"))
                }
            ),
            ModelInput(
                key = "model.google.chirp.hd.algieba",
                name = "Chirp (Male Voice)",
                description = "",
                type = "en-US-Chirp3-HD-Algieba",
                configuration = buildJsonObject {
                    put("languageTag", JsonPrimitive("en-US"))
                }
            ),
            ModelInput(
                key = "model.metadata.discussion.questions",
                name = "Discussion Questions",
                description = "Generate Discussion Questions",
                type = "openai.chat.GPT4o",
                configuration = JsonObject(emptyMap())
            ),
            ModelInput(
                key = "model.metadata.reading.time",
                name = "Reading Time",
                description = "Generate Reading Time",
                type = "openai.chat.GPT4o",
                configuration = JsonObject(emptyMap())
            ),
            ModelInput(
                key = "model.metadata.description",
                name = "Description",
                description = "Generate Description",
                type = "openai.chat.GPT4o",
                configuration = JsonObject(emptyMap())
            ),
            ModelInput(
                key = "model.metadata.topics",
                name = "Topics",
                description = "Generate Topics",
                type = "openai.chat.GPT4o",
                configuration = JsonObject(emptyMap())
            ),
            ModelInput(
                key = "model.google.genai.image",
                name = "Google GenAI Image",
                description = "Google GenAI Image Generation",
                type = "gemini-3-pro-image-preview",
                configuration = JsonObject(emptyMap())
            ),
        ).forEach {
            val current = service.getByKey(it.key)
            if (current != null) {
                service.edit(current.id, it)
            } else {
                service.add(it)
            }
        }
    }
}