package bosca.workops.model.spec

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension

/** Rich requirement content and its independently stored task description are replaced together. */
@Serializable
data class RequirementContentInput(
    val name: String,
    val markdownContent: String,
    val acceptanceCriteria: List<String>,
) {
    /** Rejects invalid fields and top-level acceptance-criteria headings; code and quoted examples are allowed. */
    fun validate() {
        require(name.isNotBlank() && markdownContent.isNotBlank()) { "Name and markdownContent must not be blank" }
        require(acceptanceCriteria.isNotEmpty() && acceptanceCriteria.all { it.isNotBlank() && '\n' !in it && '\r' !in it }) {
            "Acceptance criteria must be nonempty single-line strings"
        }
        require(acceptanceCriteria.map { it.trim() }.distinct().size == acceptanceCriteria.size) { "Acceptance criteria must be distinct" }
        val document = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(),
            TaskListItemsExtension.create(), AutolinkExtension.create())).build().parse(markdownContent)
        require(generateSequence(document.firstChild) { it.next }.none {
            it is Heading && it.level == 2 && it.plainText().trim().equals("Acceptance criteria", ignoreCase = true)
        }) { "Supply acceptance criteria separately" }
    }

    fun body(): String = markdownContent.trimEnd() + "\n\n## Acceptance criteria\n\n" +
        acceptanceCriteria.joinToString("\n") { "- ${it.trim()}" } + "\n"

    private fun Node.plainText(): String = when (this) {
        is Text -> literal
        is Code -> literal
        else -> generateSequence(firstChild) { it.next }.joinToString("") { it.plainText() }
    }
}

@Serializable
data class CreateSpecPlanInput(
    @Contextual val requestId: UUID,
    @Contextual val projectId: UUID,
    val name: String,
    val markdownContent: String,
    val requirements: List<RequirementContentInput>,
)

@Serializable
data class SetRequirementContentInput(
    val content: RequirementContentInput,
    val expectedVersion: Long,
    val expectedTaskVersion: Long,
    val metadataVersion: Int,
)

/** A principal-scoped receipt for an atomic plan or requirement creation. */
data class PlanCreation(
    val key: UUID,
    @ColumnName("principal_id") val principalId: UUID,
    val fingerprint: String,
    @ColumnName("target_id") val targetId: UUID?,
)
