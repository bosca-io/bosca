package bosca.ai.kit.releasenotes

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.koogJson
import bosca.ai.kit.configuration.KitJson
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotesCommit
import bosca.workops.model.release.ReleaseNotesGenerationInput
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class KitReleaseNotesAIServiceTest {

    @Test
    fun `structured specialist drafts source store fields and sends the tagged Git evidence`() = runTest {
        val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Completion))
        val notes = LocalizedReleaseNotes("en-US", "Faster startup", "Faster startup", "Try startup")
        val response = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            Json.parseToJsonElement(
                """{"notes":${Json.encodeToString(LocalizedReleaseNotes.serializer(), notes)}}""",
            ).let { it as kotlinx.serialization.json.JsonObject },
        )
        val executor = getMockExecutor {
            mockLLMAnswer(response) onRequestContains "Improve startup"
        }
        val kitJson = KitJson(koogJson(Json { ignoreUnknownKeys = true; isLenient = true }))
        val service = KitReleaseNotesAIService(executor, KitModels(model), kitJson)

        val result = service.generate(
            ReleaseNotesGenerationInput(
                projectName = "Companion App",
                versionName = "1.1.0",
                previousVersionName = "1.0.0",
                sourceLocale = "en-US",
                commits = listOf(ReleaseNotesCommit("companion-app", "b".repeat(40), "Improve startup")),
                changes = emptyList(),
            ),
        )

        assertEquals(notes, result)
    }
}
