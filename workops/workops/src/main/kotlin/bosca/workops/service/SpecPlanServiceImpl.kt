package bosca.workops.service

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.documents.MarkdownConverter
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.CreateSpecPlanInput
import bosca.workops.model.spec.RequirementContentInput
import bosca.workops.model.spec.Spec
import bosca.workops.repository.PlanCreationRepository
import java.security.MessageDigest
import kotlinx.serialization.json.Json

@ServiceImplementation
class SpecPlanServiceImpl(
    private val specService: SpecService,
    private val requirementService: RequirementService,
    private val metadataService: MetadataService,
    private val creationRepository: PlanCreationRepository,
    private val json: Json,
) : SpecPlanService {
    override suspend fun create(input: CreateSpecPlanInput, actingPrincipalId: UUID, actingProfileId: UUID): Spec {
        require(input.name.isNotBlank() && input.markdownContent.isNotBlank()) { "Plan name and markdownContent must not be blank" }
        require(input.requirements.isNotEmpty()) { "A plan needs at least one requirement" }
        input.requirements.forEach { it.validate() }
        // Parse before reserving or creating anything, including every requirement document.
        val content = MarkdownConverter.fromMarkdown(input.markdownContent)
        input.requirements.forEach { MarkdownConverter.fromMarkdown(it.body()) }
        val fingerprint = hash("plan\n" + json.encodeToString(CreateSpecPlanInput.serializer(), input))
        return transaction {
            val replay = reserve(input.requestId, actingPrincipalId, fingerprint)
            if (replay != null) return@transaction specService.getById(replay) ?: error("Previously created plan is deleted or inaccessible; use a new requestId")
            val spec = specService.create(CreateSpecInput(projectId = input.projectId, name = input.name), actingPrincipalId, actingProfileId, actingProfileId)
            val metadata = metadataService.getById(spec.metadataId) ?: error("Spec document metadata is missing")
            metadataService.setDocument(metadata, DocumentInput(title = input.name, content = content), CollaborationSyncMode.RESET)
            for (requirement in input.requirements) {
                requirementService.createWithContent(RequirementParent.SPEC, spec.id, requirement, actingPrincipalId, actingProfileId)
            }
            check(creationRepository.complete(input.requestId, actingPrincipalId, spec.id) == 1)
            spec
        }
    }

    override suspend fun addRequirement(specId: UUID, requestId: UUID, input: RequirementContentInput, actingPrincipalId: UUID, actingProfileId: UUID): Requirement {
        input.validate()
        MarkdownConverter.fromMarkdown(input.body())
        val fingerprint = hash("requirement\n$specId\n" + json.encodeToString(RequirementContentInput.serializer(), input))
        return transaction {
            val replay = reserve(requestId, actingPrincipalId, fingerprint)
            if (replay != null) return@transaction requirementService.getById(replay) ?: error("Previously created requirement is deleted or inaccessible; use a new requestId")
            specService.getById(specId) ?: error("Spec not found")
            val requirement = requirementService.createWithContent(RequirementParent.SPEC, specId, input, actingPrincipalId, actingProfileId)
            check(creationRepository.complete(requestId, actingPrincipalId, requirement.id) == 1)
            requirement
        }
    }

    private suspend fun reserve(key: UUID, principalId: UUID, fingerprint: String): UUID? {
        creationRepository.reserve(key, principalId, fingerprint)
        val receipt = creationRepository.getForUpdate(key, principalId) ?: error("Creation receipt is missing")
        require(receipt.fingerprint == fingerprint) { "requestId was already used with different input" }
        return receipt.targetId
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
