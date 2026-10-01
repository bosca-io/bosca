package bosca.ai.kit.agents.writer

import bosca.documents.Content
import kotlinx.serialization.Serializable

/**
 * The writer sub-agent's structured response. Either the finished tiptap [content], OR a request for
 * Scripture it could not write without — [needsScripture] with the [references] it needs. Modeling
 * the "I need source content" outcome as part of the response (rather than a thrown error or a magic
 * string) is what lets the planner re-plan: `WriteDocumentAction` records the references, the planner
 * runs `fetch_scripture`, then comes back to the writer with the chapters in hand.
 */
@Serializable
data class WriterResponse(
    val content: Content? = null,
    val needsScripture: Boolean = false,
    val references: List<String> = emptyList(),
)
