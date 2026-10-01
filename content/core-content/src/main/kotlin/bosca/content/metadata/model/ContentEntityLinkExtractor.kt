package bosca.content.metadata.model

import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.documents.DocumentNode
import bosca.documents.ImageNode
import bosca.documents.MentionNode
import bosca.documents.TextNode
import bosca.documents.marks.Link
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ExtractedLink(
    val targetType: ContentLinkTarget,
    val targetId: String,
    val nodeType: String,
    val position: JsonElement? = null,
)

object ContentEntityLinkExtractor {

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val LINK_ROUTE_PATTERNS: List<Pair<Regex, ContentLinkTarget>> = listOf(
        Regex("/metadata/($UUID_PATTERN)") to ContentLinkTarget.METADATA,
        Regex("/collection/($UUID_PATTERN)") to ContentLinkTarget.COLLECTION,
        Regex("/profile/($UUID_PATTERN)") to ContentLinkTarget.PROFILE,
        Regex("/task/($UUID_PATTERN)") to ContentLinkTarget.TASK,
        Regex("/spec/($UUID_PATTERN)") to ContentLinkTarget.SPEC,
        Regex("/requirement/($UUID_PATTERN)") to ContentLinkTarget.REQUIREMENT,
        Regex("/project/($UUID_PATTERN)") to ContentLinkTarget.PROJECT,
        Regex("/program/($UUID_PATTERN)") to ContentLinkTarget.PROGRAM,
        Regex("/git/repository/($UUID_PATTERN)") to ContentLinkTarget.GIT_REPOSITORY,
        Regex("/chat/channel/($UUID_PATTERN)") to ContentLinkTarget.CHAT_CHANNEL,
        Regex("/ai/session/($UUID_PATTERN)") to ContentLinkTarget.AI_SESSION,
    )

    private val MENTION_PATTERN = Regex("@($UUID_PATTERN)")

    fun extract(content: Content): List<ExtractedLink> {
        val links = mutableListOf<ExtractedLink>()
        walkNode(content.document, path = emptyList(), depth = 0, links)
        return links.distinctBy { Triple(it.targetType, it.targetId, it.nodeType) }
    }

    private fun walkNode(
        node: DocumentNode,
        path: List<Int>,
        depth: Int,
        links: MutableList<ExtractedLink>,
    ) {
        extractFromNode(node, path, links)
        extractFromMarks(node, path, links)
        for ((index, child) in node.content.withIndex()) {
            walkNode(child, path + index, depth + 1, links)
        }
    }

    private fun extractFromNode(
        node: DocumentNode,
        path: List<Int>,
        links: MutableList<ExtractedLink>,
    ) {
        when (node) {
            is ContainerNode -> {
                val metadataId = node.attributes.metadataId
                if (metadataId != null) {
                    links.add(
                        ExtractedLink(
                            targetType = ContentLinkTarget.METADATA,
                            targetId = metadataId.toString(),
                            nodeType = "container",
                            position = positionJson(path),
                        )
                    )
                }
            }
            is ImageNode -> {
                val metadataId = node.attributes.metadataId
                if (metadataId != null && UUID_PATTERN.matches(metadataId)) {
                    links.add(
                        ExtractedLink(
                            targetType = ContentLinkTarget.METADATA,
                            targetId = metadataId,
                            nodeType = "image",
                            position = positionJson(path),
                        )
                    )
                }
                val src = node.attributes.src
                if (src != null) {
                    extractFromHref(src, "image", path, links)
                }
            }
            is MentionNode -> {
                val mentionId = node.attributes.id
                if (mentionId != null && UUID_PATTERN.matches(mentionId)) {
                    val targetType = when (node.attributes.entityType) {
                        "metadata" -> ContentLinkTarget.METADATA
                        "collection" -> ContentLinkTarget.COLLECTION
                        "profile" -> ContentLinkTarget.PROFILE
                        else -> ContentLinkTarget.METADATA
                    }
                    links.add(
                        ExtractedLink(
                            targetType = targetType,
                            targetId = mentionId,
                            nodeType = "mention",
                            position = positionJson(path),
                        )
                    )
                }
            }
            is TextNode -> {
                extractMentions(node.text, path, links)
            }
            else -> {}
        }
    }

    private fun extractFromMarks(
        node: DocumentNode,
        path: List<Int>,
        links: MutableList<ExtractedLink>,
    ) {
        for (mark in node.marks) {
            if (mark is Link) {
                val href = mark.attributes?.href ?: mark.attributes?.url
                if (href != null) {
                    val matched = extractFromHref(href, "link", path, links)
                    if (!matched) {
                        if (href.startsWith("http://") || href.startsWith("https://")) {
                            links.add(
                                ExtractedLink(
                                    targetType = ContentLinkTarget.EXTERNAL_URI,
                                    targetId = href,
                                    nodeType = "link",
                                    position = positionJson(path),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun extractFromHref(
        href: String,
        nodeType: String,
        path: List<Int>,
        links: MutableList<ExtractedLink>,
    ): Boolean {
        for ((pattern, targetType) in LINK_ROUTE_PATTERNS) {
            val match = pattern.find(href)
            if (match != null) {
                links.add(
                    ExtractedLink(
                        targetType = targetType,
                        targetId = match.groupValues[1],
                        nodeType = nodeType,
                        position = positionJson(path),
                    )
                )
                return true
            }
        }
        return false
    }

    private fun extractMentions(
        text: String,
        path: List<Int>,
        links: MutableList<ExtractedLink>,
    ) {
        for (match in MENTION_PATTERN.findAll(text)) {
            links.add(
                ExtractedLink(
                    targetType = ContentLinkTarget.PROFILE,
                    targetId = match.groupValues[1],
                    nodeType = "mention",
                    position = positionJson(path),
                )
            )
        }
    }

    private fun positionJson(path: List<Int>): JsonElement =
        buildJsonObject { put("path", path.joinToString(",")) }
}
