package bosca.ai.kit.localization

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.koogJson
import bosca.ai.kit.configuration.KitJson
import bosca.localization.model.LocalizationAITranslationRequest
import bosca.localization.model.LocalizationAITranslationResult
import bosca.localization.model.LocalizationAITranslationSource
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class KitLocalizationAIServiceTest {

    @Test
    fun `structured translator returns localization-owned string and language pairs`() = runTest {
        val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Completion))
        val stringId = UUID.random()
        val translations = listOf(LocalizationAITranslationResult(stringId, "fr-FR", "Démarrage plus rapide"))
        val response = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            Json.parseToJsonElement(
                """{"translations":[{"stringId":"$stringId","languageTag":"fr-FR","text":"Démarrage plus rapide"}]}""",
            ).let { it as kotlinx.serialization.json.JsonObject },
        )
        val executor = getMockExecutor {
            mockLLMAnswer(response) onRequestContains "Faster startup"
        }
        val kitJson = KitJson(koogJson(Json { ignoreUnknownKeys = true; isLenient = true }))
        val service = KitLocalizationAIService(executor, KitModels(model), kitJson)

        val result = service.translate(
            LocalizationAITranslationRequest(
                sourceLanguageTag = "en-US",
                targetLanguageTags = listOf("fr-FR"),
                sources = listOf(
                    LocalizationAITranslationSource(
                        stringId = stringId,
                        key = "release-notes",
                        context = "Store release notes",
                        text = "Faster startup",
                    ),
                ),
            ),
        )

        assertEquals(translations, result)
    }
}
