package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.tools.bible.ComponentTextRenderer
import bosca.bible.components.filter
import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService

/**
 * Answers Scripture requests with text retrieved from [BibleService]. The model may select references,
 * but it never supplies, rewrites, or attributes the passage text shown to the user.
 */
class ProvideScriptureAction(
    private val bibleService: BibleService,
) : KitAction() {

    override val name: String = "provide_scripture"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.SCRIPTURE && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState =
        provide(state)

    /** Resolves and renders the exact installed Scripture for a routed request. */
    internal suspend fun provide(state: KitState): KitState {
        val bibles = bibleService.getBibles()
        val bible = selectBible(bibles, state.translation)
            ?: return state.respond(
                if (bibles.isEmpty()) {
                    "I can’t provide Bible text because no Bible translation is installed in Bosca."
                } else {
                    val available = bibles.joinToString(", ") { it.sourceLabel() }
                    "I couldn't find the requested translation \"${state.translation}\". Available translations: $available."
                },
            )

        if (state.references.isEmpty()) {
            return state.respond(
                "I need a specific Bible reference before I can retrieve exact text from ${bible.sourceLabel()}.",
            )
        }

        val passages = state.references.flatMap { requested ->
            bibleService.getReferences(bible, requested).mapNotNull { reference ->
                val chapter = bibleService.getChapter(bible, reference)
                val chapterComponents = chapter.getChapterComponents() ?: return@mapNotNull null
                val hasVerseNumbers = reference.references.any { it.number.isNotEmpty() }
                val components = if (hasVerseNumbers) chapterComponents.filter(reference) else chapterComponents
                val text = components?.let(ComponentTextRenderer::render).orEmpty()
                if (text.isBlank()) null else Passage(bibleService.getHumanLong(bible, reference), text)
            }
        }

        if (passages.isEmpty()) {
            return state.respond(
                "I couldn’t resolve exact Bible text for ${state.references.joinToString(", ")} in ${bible.sourceLabel()}.",
            )
        }

        val source = bible.sourceLabel()
        val response = buildString {
            append("Here are passages from ").append(source).append(":\n\n")
            passages.forEachIndexed { index, passage ->
                if (index > 0) append("\n\n")
                append("**").append(passage.reference).append("**\n")
                append(passage.text)
            }
            append("\n\nSource: ").append(source).append(", retrieved from Bosca's installed Bible content.")
        }
        return state.respond(response)
    }

    private fun selectBible(bibles: List<Bible>, requested: String): Bible? {
        if (requested.isBlank()) return bibles.firstOrNull { it.defaultVariant } ?: bibles.firstOrNull()
        return bibles.firstOrNull { bible ->
            listOf(
                bible.name,
                bible.nameLocal,
                bible.abbreviation,
                bible.abbreviationLocal,
                bible.systemId,
                bible.variant,
            ).any { it.equals(requested, ignoreCase = true) }
        }
    }

    private fun Bible.sourceLabel(): String {
        return if (abbreviation.isBlank() || abbreviation.equals(name, ignoreCase = true)) {
            name
        } else {
            "$name ($abbreviation)"
        }
    }

    private fun KitState.respond(text: String): KitState =
        copy(responded = true, response = KitResponse.Text(text))

    private data class Passage(val reference: String, val text: String)
}
