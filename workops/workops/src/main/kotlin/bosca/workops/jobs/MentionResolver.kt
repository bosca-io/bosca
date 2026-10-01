package bosca.workops.jobs

import yks.types.YXmlElement
import yks.types.YXmlFragment
import yks.types.YXmlText
import yks.types.YType
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import yks.utils.encodeStateVector

data class ResolvedEntity(
    val id: String,
    val label: String,
    val entityType: String,
)

class MentionResolver : AutoCloseable {

    private val mentionPattern = Regex("@([\\w][\\w.-]*[\\w]|\\w+)")

    fun extractSlugs(content: ByteArray): Set<String> {
        if (content.isEmpty()) return emptySet()
        val doc = Doc()
        applyUpdate(doc, content)
        val fragment = doc.getXmlFragment("default")
        val slugs = mutableSetOf<String>()
        collectSlugs(fragment, slugs)
        return slugs
    }

    fun resolveMentions(
        content: ByteArray,
        resolved: Map<String, ResolvedEntity>,
    ): ByteArray? {
        if (content.isEmpty() || resolved.isEmpty()) return null
        val doc = Doc()
        applyUpdate(doc, content)
        val stateVectorBefore = encodeStateVector(doc)
        val fragment = doc.getXmlFragment("default")
        var changed = false
        walkAndReplace(fragment, resolved) { changed = true }
        if (!changed) return null
        return encodeStateAsUpdate(doc, stateVectorBefore)
    }

    private fun collectSlugs(fragment: YXmlFragment, slugs: MutableSet<String>) {
        for (child in fragment.toArray()) {
            when (child) {
                is YXmlElement -> {
                    if (child.tag != "mention") collectSlugs(child, slugs)
                }

                is YXmlText -> {
                    val text = child.toString()
                    for (match in mentionPattern.findAll(text)) {
                        slugs.add(match.groupValues[1])
                    }
                }
            }
        }
    }

    private fun walkAndReplace(
        fragment: YXmlFragment,
        resolved: Map<String, ResolvedEntity>,
        onChange: () -> Unit,
    ) {
        val children = fragment.toArray()
        var i = 0
        while (i < children.size) {
            val child = children[i]
            when (child) {
                is YXmlElement -> {
                    if (child.tag != "mention") {
                        walkAndReplace(child, resolved, onChange)
                    }
                }

                is YXmlText -> {
                    if (replaceInText(fragment, child, i, resolved)) {
                        onChange()
                        return walkAndReplace(fragment, resolved, onChange)
                    }
                }
            }
            i++
        }
    }

    private fun replaceInText(
        parent: YXmlFragment,
        textNode: YXmlText,
        textIndex: Int,
        resolved: Map<String, ResolvedEntity>,
    ): Boolean {
        val text = textNode.toString()
        val match = mentionPattern.findAll(text)
            .firstOrNull { it.groupValues[1] in resolved }
            ?: return false
        val slug = match.groupValues[1]
        val entity = resolved[slug]
            ?: error("missing resolved entity for '$slug'")

        val before = text.substring(0, match.range.first)
        val after = text.substring(match.range.last + 1)

        textNode.delete(0, text.length)
        if (before.isNotEmpty()) {
            textNode.insert(0, before)
        }

        val insertIndex = textIndex + 1
        val nodesToInsert = mutableListOf<YType>(YXmlElement("mention"))
        if (after.isNotEmpty()) {
            nodesToInsert.add(YXmlText())
        }
        parent.insert(insertIndex, nodesToInsert)

        val mention = parent.get(insertIndex) as YXmlElement
        mention.setAttribute("id", entity.id)
        mention.setAttribute("label", entity.label)
        mention.setAttribute("entityType", entity.entityType)

        if (after.isNotEmpty()) {
            val afterText = parent.get(insertIndex + 1) as? YXmlText
            afterText?.insert(0, after)
        }

        return true
    }

    override fun close() {
    }
}
