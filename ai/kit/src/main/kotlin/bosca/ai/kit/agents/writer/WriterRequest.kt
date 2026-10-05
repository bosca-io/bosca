package bosca.ai.kit.agents.writer

import bosca.ai.chat.model.ChatMessageInput
import bosca.content.metadata.model.BibleChapter
import kotlinx.serialization.Serializable

/**
 * The writer sub-agent's typed request: the authoring task plus the Scripture to quote, carried as
 * structured [BibleChapter]s (fetched upstream) rather than text — the data class pushed down the
 * writer's graph.
 */
@Serializable
data class WriterRequest(
    val task: ChatMessageInput,
    val scripture: List<BibleChapter> = emptyList(),
)
