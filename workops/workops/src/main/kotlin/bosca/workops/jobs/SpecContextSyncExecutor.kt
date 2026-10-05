package bosca.workops.jobs

import bosca.content.metadata.model.ContentLinkTarget
import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.service.ContentEntityLinkService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.DocumentNode
import bosca.documents.MentionAttributes
import bosca.documents.MentionNode
import bosca.documents.Content
import bosca.documents.TextNode
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.slug.service.SlugService
import bosca.workops.model.spec.CreateSpecContextInput
import bosca.workops.model.spec.SpecContextType
import bosca.workops.service.SpecContextService
import bosca.workops.service.SpecService
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

data class ResolvedMention(
    val id: UUID,
    val label: String,
    val entityType: String,
    val contextType: SpecContextType,
)

@JobDefinition(SpecContextSyncJob::class, "workops", "spec-context-sync")
class SpecContextSyncExecutor(
    private val specService: SpecService,
    private val entityLinkService: ContentEntityLinkService,
    private val contextService: SpecContextService,
    private val documentService: DocumentService,
    private val metadataService: MetadataService,
    private val slugService: SlugService,
    private val profileService: ProfileService,
) : AbstractJobExecutor<SpecContextSyncJob>(SpecContextSyncJob.serializer()) {

    private val mentionPattern = Regex("@([\\w][\\w.-]*[\\w]|\\w+)")

    override suspend fun execute() {
        val specId = getJobDefinition().specId ?: return
        sync(specId)
    }

    internal suspend fun sync(specId: UUID) {
        val spec = specService.getById(specId) ?: return

        val existingContexts = contextService.listBySpec(specId)
        val existingKeys = existingContexts.map { "${it.contextType}:${it.targetId}" }.toMutableSet()

        val entityLinks = entityLinkService.listByMetadata(spec.metadataId)
        for (link in entityLinks) {
            val contextType = mapLinkTarget(link.targetType) ?: continue
            addContext(specId, contextType, link.targetId, existingKeys, spec.ownerProfileId)
        }

        // The document version comes from the metadata itself — spec.version is the
        // spec row's optimistic-lock counter, unrelated to document revisions.
        val metadataVersion = metadataService.getById(spec.metadataId)?.version ?: return
        resolveTextMentions(spec.metadataId, metadataVersion, specId, existingKeys, spec.ownerProfileId)
        resolveYjsMentions(spec.metadataId, metadataVersion, specId, existingKeys, spec.ownerProfileId)
    }

    private suspend fun resolveTextMentions(
        metadataId: UUID,
        version: Int,
        specId: UUID,
        existingKeys: MutableSet<String>,
        ownerProfileId: UUID,
    ) {
        val document = documentService.getDocument(metadataId, version) ?: return
        val content = document.content ?: return
        val existingMentionLabels = collectMentionLabels(content.document)
        val text = extractText(content.document)
        val mentionNames = mentionPattern.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.lowercase() !in existingMentionLabels }
            .toSet()
        if (mentionNames.isEmpty()) return

        val resolved = mutableMapOf<String, ResolvedMention>()
        for (name in mentionNames) {
            val mention = resolveBySlug(name) ?: resolveByProfile(name) ?: continue
            resolved[name] = mention
            addContext(specId, mention.contextType, mention.id.toString(), existingKeys, ownerProfileId)
        }
        if (resolved.isEmpty()) return

        try {
            if (replaceTextMentions(content.document, resolved)) {
                val metadata = metadataService.getById(metadataId) ?: return
                documentService.setDocument(
                    metadata,
                    DocumentInput(
                        title = document.title,
                        content = Content(document = content.document),
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to update JSON document for metadata {}: {}", metadataId, e.message)
        }
    }

    private suspend fun resolveYjsMentions(
        metadataId: UUID,
        version: Int,
        specId: UUID,
        existingKeys: MutableSet<String>,
        ownerProfileId: UUID,
    ) {
        try {
            val collaboration = documentService.getCollaboration(metadataId, version) ?: return
            val mentionResolver = MentionResolver()
            val slugs = mentionResolver.extractSlugs(collaboration.content)
            if (slugs.isEmpty()) return

            val resolved = mutableMapOf<String, ResolvedEntity>()
            for (slug in slugs) {
                val mention = resolveBySlug(slug) ?: resolveByProfile(slug) ?: continue
                addContext(specId, mention.contextType, mention.id.toString(), existingKeys, ownerProfileId)
                resolved[slug] = ResolvedEntity(mention.id.toString(), mention.label, mention.entityType)
            }
            if (resolved.isEmpty()) return

            val delta = mentionResolver.resolveMentions(collaboration.content, resolved)
            if (delta != null) {
                val current = documentService.getCollaboration(metadataId, version) ?: return
                val merged = yks.utils.Doc().let { doc ->
                    yks.utils.applyUpdate(doc, current.content)
                    yks.utils.applyUpdate(doc, delta)
                    yks.utils.encodeStateAsUpdate(doc)
                }
                documentService.setCollaboration(
                    DocumentCollaborationInput(metadataId, version, merged)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to update Yjs document for metadata {}: {}", metadataId, e.message)
        }
    }

    private fun replaceTextMentions(node: DocumentNode, resolved: Map<String, ResolvedMention>): Boolean {
        var changed = false
        val newContent = node.content.flatMap { child ->
            if (child is TextNode) {
                val expanded = expandTextNode(child, resolved)
                if (expanded.singleOrNull() !== child) changed = true
                expanded
            } else {
                if (replaceTextMentions(child, resolved)) changed = true
                listOf(child)
            }
        }
        if (changed) node.content = newContent
        return changed
    }

    private fun expandTextNode(node: TextNode, resolved: Map<String, ResolvedMention>): List<DocumentNode> {
        val text = node.text
        val matches = mentionPattern.findAll(text).filter { it.groupValues[1] in resolved }.toList()
        if (matches.isEmpty()) return listOf(node)

        val result = mutableListOf<DocumentNode>()
        var lastEnd = 0
        for (match in matches) {
            if (match.range.first > lastEnd) {
                val before = text.substring(lastEnd, match.range.first)
                result.add(TextNode(text = before, marks = node.marks))
            }
            val mentionName = match.groupValues[1]
            val mention = resolved[mentionName]
                ?: error("missing resolved mention for '$mentionName'")
            result.add(
                MentionNode(
                    attributes = MentionAttributes(
                        id = mention.id.toString(),
                        label = mention.label,
                        entityType = mention.entityType,
                    )
                )
            )
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            val after = text.substring(lastEnd)
            result.add(TextNode(text = after, marks = node.marks))
        }
        return result
    }

    private fun collectMentionLabels(node: DocumentNode): Set<String> {
        val labels = mutableSetOf<String>()
        if (node is MentionNode) {
            val label = node.attributes.label
            if (label != null) labels.add(label.lowercase())
        }
        for (child in node.content) {
            labels.addAll(collectMentionLabels(child))
        }
        return labels
    }

    private suspend fun resolveBySlug(name: String): ResolvedMention? {
        val slug = slugService.get(name.lowercase()) ?: return null
        val profileId = slug.profileId
        val metadataId = slug.metadataId
        val collectionId = slug.collectionId
        return when {
            profileId != null -> {
                val profile = profileService.getById(profileId)
                ResolvedMention(profileId, profile.name, "profile", SpecContextType.PROFILE)
            }

            metadataId != null -> {
                val metadata = metadataService.getById(metadataId) ?: return null
                ResolvedMention(metadataId, metadata.name, "metadata", SpecContextType.METADATA)
            }

            collectionId != null -> {
                ResolvedMention(collectionId, name, "collection", SpecContextType.COLLECTION)
            }

            else -> null
        }
    }

    private suspend fun resolveByProfile(name: String): ResolvedMention? {
        val allProfiles = profileService.getAll(0, 1000)
        for (profile in allProfiles) {
            val attrs = profileService.getAttributes(profile.id)
            val attrName = attrs.getAttributeString("bosca.profiles.name", "value")
            if (attrName != null && attrName.equals(name, ignoreCase = true)) {
                return ResolvedMention(profile.id, attrName, "profile", SpecContextType.PROFILE)
            }
        }
        for (profile in allProfiles) {
            if (profile.name.equals(name, ignoreCase = true)) {
                return ResolvedMention(profile.id, profile.name, "profile", SpecContextType.PROFILE)
            }
        }
        return null
    }

    private fun extractText(node: DocumentNode): String {
        val sb = StringBuilder()
        if (node is TextNode) {
            sb.append(node.text)
        }
        for (child in node.content) {
            sb.append(' ')
            sb.append(extractText(child))
        }
        return sb.toString()
    }

    private suspend fun addContext(
        specId: UUID,
        contextType: SpecContextType,
        targetId: String,
        existingKeys: MutableSet<String>,
        ownerProfileId: UUID,
    ) {
        val key = "${contextType}:${targetId}"
        if (key in existingKeys) return
        try {
            contextService.add(
                specId = specId,
                input = CreateSpecContextInput(
                    contextType = contextType,
                    targetId = targetId,
                ),
                actingProfileId = ownerProfileId,
                actingPrincipalId = UUID.NIL,
            )
            existingKeys.add(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to sync context {} for spec {}: {}", key, specId, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpecContextSyncExecutor::class.java)

        fun mapLinkTarget(target: ContentLinkTarget): SpecContextType? = when (target) {
            ContentLinkTarget.METADATA -> SpecContextType.METADATA
            ContentLinkTarget.COLLECTION -> SpecContextType.COLLECTION
            ContentLinkTarget.PROFILE -> SpecContextType.PROFILE
            ContentLinkTarget.TASK -> SpecContextType.TASK
            ContentLinkTarget.SPEC -> SpecContextType.SPEC
            ContentLinkTarget.PROJECT -> SpecContextType.PROJECT
            ContentLinkTarget.GIT_REPOSITORY -> SpecContextType.GIT_RESOURCE
            ContentLinkTarget.CHAT_CHANNEL -> SpecContextType.CHAT_CHANNEL
            ContentLinkTarget.AI_SESSION -> SpecContextType.AI_SESSION
            ContentLinkTarget.EXTERNAL_URI -> SpecContextType.EXTERNAL_URI
            ContentLinkTarget.REQUIREMENT -> null
            ContentLinkTarget.PROGRAM -> null
        }
    }
}
