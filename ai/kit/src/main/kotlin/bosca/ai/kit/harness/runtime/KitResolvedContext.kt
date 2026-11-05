package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitContextManifest
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource

/** Ephemeral context bodies; only the manifest is persisted by the Runner. */
data class KitResolvedContext(
    val manifest: KitContextManifest,
    val references: List<bosca.ai.kit.harness.KitContextReference>,
    val instructions: String,
    val resources: List<Pair<String, String>>,
    val history: String = "",
    val selectionCount: Int = 0,
) {
    fun content(embeddedContext: Boolean): List<ContentBlock> = buildList {
        add(ContentBlock.Text(instructions + history))
        resources.forEach { (uri, text) ->
            add(if (embeddedContext) ContentBlock.Resource(EmbeddedResourceResource.TextResourceContents(text, uri, "text/plain"))
                else ContentBlock.Text(text))
        }
    }
}
