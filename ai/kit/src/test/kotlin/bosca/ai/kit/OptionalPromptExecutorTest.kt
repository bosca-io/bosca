package bosca.ai.kit

import ai.koog.prompt.executor.model.PromptExecutor
import bosca.ai.kit.configuration.Configuration
import bosca.ai.kit.localization.KitLocalizationAIService
import bosca.ai.kit.metadata.KitMetadataAIService
import bosca.ai.kit.releasenotes.KitReleaseNotesAIService
import bosca.di.MissingObjectProvider
import bosca.di.MissingProviderException
import bosca.localization.model.LocalizationAITranslationRequest
import bosca.workops.model.release.ReleaseNotesGenerationInput
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptionalPromptExecutorTest {
    @Test
    fun `AI capabilities resolve without credentials and fail when invoked`() = runTest {
        val executor = MissingObjectProvider(PromptExecutor::class, null)
        val configuration = Configuration()
        val models = configuration.kitModels()
        val json = configuration.json(Json)
        KitMetadataAIService(executor, models, json, mockk(), mockk(), mockk(), mockk())
        val localization = KitLocalizationAIService(executor, models, json)
        val releaseNotes = KitReleaseNotesAIService(executor, models, json)

        val translationFailure = assertFailsWith<MissingProviderException> {
            localization.translate(LocalizationAITranslationRequest("en-US", listOf("fr-FR"), emptyList()))
        }
        val notesFailure = assertFailsWith<MissingProviderException> {
            releaseNotes.generate(ReleaseNotesGenerationInput("App", "1.0", null, "en-US", emptyList(), emptyList()))
        }
        assertTrue(translationFailure.message.orEmpty().contains("PromptExecutor"))
        assertTrue(notesFailure.message.orEmpty().contains("PromptExecutor"))
    }
}
