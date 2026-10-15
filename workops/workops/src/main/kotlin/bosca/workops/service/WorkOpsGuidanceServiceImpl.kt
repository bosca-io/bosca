package bosca.workops.service

import bosca.ai.agents.model.AgentResourceInput
import bosca.ai.agents.service.AgentResourceService
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.documents.MarkdownConverter
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.guidance.*
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.repository.WorkOpsGuidanceRepository
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@ServiceImplementation
class WorkOpsGuidanceServiceImpl(
    private val configurations: ConfigurationService,
    private val metadata: MetadataService,
    private val documents: DocumentService,
    private val resources: AgentResourceService,
    private val json: Json,
    private val revisions: WorkOpsGuidanceRepository,
) : WorkOpsGuidanceService {
    override suspend fun effective(projectId: UUID?): WorkOpsGuidance {
        val platform = stored(null)
        val project = if (projectId == null) null else stored(projectId)
        val policy = project?.policy ?: platform?.policy ?: WorkOpsGuidancePolicy()
        validatePolicy(policy)
        val guides = WorkOpsGuidancePhase.entries.map { phase ->
            val own = project?.guides?.singleOrNull { it.phase == phase }
            val shared = platform?.guides?.singleOrNull { it.phase == phase }
            val reference = own ?: shared
            if (reference == null) {
                val input = bundled(phase)
                WorkOpsGuide(phase, input.name, input.description, input.markdown, true)
            } else {
                val document = documents.getDocument(UUID.parse(reference.metadataId), reference.documentVersion)
                    ?: error("WorkOps guide document is missing: $phase")
                val body = document.content ?: error("WorkOps guide document is empty: $phase")
                WorkOpsGuide(phase, reference.name, reference.description, MarkdownConverter.toMarkdown(body),
                    inherited = if (projectId == null) false else own == null,
                    metadataId = UUID.parse(reference.metadataId), documentVersion = reference.documentVersion,
                    resourceId = UUID.parse(reference.resourceId))
            }
        }
        // Fingerprint choices as well as content, without including hidden upstream settings or document identities.
        val canonical = Json { encodeDefaults = true }.encodeToString(WorkOpsGuidanceInput.serializer(),
            WorkOpsGuidanceInput(policy, guides.map { WorkOpsGuideInput(it.phase, it.name, it.description, it.markdown.trim()) }))
        val ownPhases = project?.guides.orEmpty().map { it.phase }.toSet()
        val sharedPhases = platform?.guides.orEmpty().map { it.phase }.toSet()
        val revisionInput = buildJsonObject {
            put("content", json.parseToJsonElement(canonical))
            put("projectPolicy", project?.policy != null)
            put("platformPolicy", project?.policy == null && platform?.policy != null)
            put("projectGuides", kotlinx.serialization.json.JsonArray(WorkOpsGuidancePhase.entries
                .filter { it in ownPhases }
                .map { kotlinx.serialization.json.JsonPrimitive(it.name) }))
            put("platformGuides", kotlinx.serialization.json.JsonArray(WorkOpsGuidancePhase.entries
                .filter { it !in ownPhases && it in sharedPhases }
                .map { kotlinx.serialization.json.JsonPrimitive(it.name) }))
        }.toString()
        val revision = MessageDigest.getInstance("SHA-256").digest(revisionInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return WorkOpsGuidance(revision, policy, if (projectId == null) platform?.policy == null else project?.policy == null, guides)
    }

    override suspend fun save(projectId: UUID?, input: WorkOpsGuidanceInput, expectedRevision: String): WorkOpsGuidance {
        input.policy?.let(::validatePolicy)
        require(input.guides.map { it.phase }.distinct().size == input.guides.size) { "Guide phases must be unique" }
        input.guides.forEach(WorkOpsSkillCodec::validate)
        val bodies = input.guides.map { MarkdownConverter.fromMarkdown(it.markdown) }
        return transaction {
            val current = checkRevision(projectId, expectedRevision)
            val previous = stored(projectId)
            val previousGuides = previous?.guides.orEmpty()
            val references = input.guides.mapIndexed { index, guide ->
                val reference = previousGuides.firstOrNull { it.phase == guide.phase }
                val existing = current.guides.single { it.phase == guide.phase }
                if (reference != null && reference.name == guide.name && reference.description == guide.description &&
                    existing.markdown.trim() == MarkdownConverter.toMarkdown(bodies[index]).trim()) return@mapIndexed reference
                // New documents preserve the body used by previous revisions and review evidence.
                val document = metadata.add(null, null, MetadataInput(name = guide.name, languageTag = "en",
                    contentType = "bosca/v-document", searchable = false, recommendable = false,
                    document = DocumentInput(title = guide.name, content = bodies[index])))
                val resourceKey = "${key(projectId)}.${guide.phase.name.lowercase()}"
                val previousResource = resources.getByKey(resourceKey)
                val resourceInput = AgentResourceInput(key = resourceKey, name = guide.name, description = guide.description,
                    documentMetadataId = document.id, documentVersion = document.version,
                    gitRepositoryId = previousResource?.gitRepositoryId, gitPath = previousResource?.gitPath)
                val resource = if (previousResource == null) resources.add(resourceInput) else resources.edit(previousResource.id, resourceInput)
                GuideReference(guide.phase, guide.name, guide.description, document.id.toString(), document.version, resource.id.toString())
            }
            removeResources(previousGuides.filter { previous -> input.guides.none { it.phase == previous.phase } })
            val replacement = StoredGuidance(input.policy, references)
            if (replacement != previous && (previous != null || input.policy != null || references.isNotEmpty())) {
                configurations.setConfiguration(ConfigurationInput(key(projectId), "WorkOps workflow guidance",
                    json.encodeToJsonElement(StoredGuidance.serializer(), replacement), public = false, permissions = emptyList()))
            }
            effective(projectId)
        }
    }

    override suspend fun reset(projectId: UUID?, expectedRevision: String): WorkOpsGuidance = transaction {
        checkRevision(projectId, expectedRevision)
        stored(projectId)?.let {
            removeResources(it.guides)
            configurations.deleteConfiguration(key(projectId))
        }
        effective(projectId)
    }

    private suspend fun checkRevision(projectId: UUID?, expectedRevision: String): WorkOpsGuidance {
        revisions.lock()
        val current = effective(projectId)
        if (current.revision != expectedRevision) {
            throw WorkOpsValidationException("expectedRevision", "Guidance changed since it was loaded. Reload and reconcile your draft before saving.")
        }
        return current
    }

    private suspend fun removeResources(guides: List<GuideReference>) {
        for (guide in guides) resources.delete(UUID.parse(guide.resourceId))
    }

    override fun importSkill(phase: WorkOpsGuidancePhase, skill: String) = WorkOpsSkillCodec.decode(phase, skill)

    override suspend fun exportSkill(projectId: UUID?, phase: WorkOpsGuidancePhase): String =
        WorkOpsSkillCodec.encode(effective(projectId).guides.single { it.phase == phase })

    private suspend fun stored(projectId: UUID?): StoredGuidance? {
        val configuration = configurations.getByKey(key(projectId)) ?: return null
        val value = configurations.getValue(configuration.id)
            ?: error("Configuration value is missing: ${configuration.key}")
        return json.decodeFromJsonElement(StoredGuidance.serializer(), value)
    }

    private fun bundled(phase: WorkOpsGuidancePhase): WorkOpsGuideInput {
        val path = "/workops/guidance/${phase.name.lowercase()}/SKILL.md"
        val body = javaClass.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Bundled WorkOps guide is missing: $path")
        return importSkill(phase, body)
    }

    private fun validatePolicy(policy: WorkOpsGuidancePolicy) {
        require(policy.minimumLineCoverage.isFinite() && policy.minimumLineCoverage in 0.0..100.0 &&
            policy.minimumBranchCoverage.isFinite() && policy.minimumBranchCoverage in 0.0..100.0) {
            "Coverage thresholds must be finite percentages between 0 and 100"
        }
    }

    private fun key(projectId: UUID?) = "workops.guidance.${projectId ?: "default"}"

    @Serializable
    private data class StoredGuidance(val policy: WorkOpsGuidancePolicy?, val guides: List<GuideReference>)

    @Serializable
    private data class GuideReference(val phase: WorkOpsGuidancePhase, val name: String, val description: String,
        val metadataId: String, val documentVersion: Int, val resourceId: String)
}
