package bosca.workops.service

import bosca.db.transaction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.spec.CreateSpecContextInput
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecContextType
import bosca.workops.repository.SpecContextRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@ServiceImplementation
class SpecContextServiceImpl(
    private val contextRepository: SpecContextRepository,
    private val specRepository: SpecRepository,
    private val specHistoryRepository: SpecHistoryRepository,
    private val json: Json,
) : SpecContextService {

    override suspend fun listBySpec(specId: UUID): List<SpecContext> =
        contextRepository.listBySpec(specId)

    override suspend fun listBySpecAndType(specId: UUID, contextType: SpecContextType): List<SpecContext> =
        contextRepository.listBySpecAndType(specId, contextType)

    override suspend fun add(
        specId: UUID,
        input: CreateSpecContextInput,
        actingPrincipalId: UUID,
        actingProfileId: UUID,
    ): SpecContext = transaction {
        specRepository.getActiveById(specId)
            ?: throw WorkOpsNotFoundException("Spec", specId.toString())

        val saved = contextRepository.add(
            specId = specId,
            contextType = input.contextType,
            targetId = input.targetId,
            label = input.label,
            attributes = input.attributes,
            addedByProfileId = actingProfileId,
        )
        specHistoryRepository.add(
            specId = specId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = actingPrincipalId,
            changedByProfileId = actingProfileId,
            changes = json.encodeToJsonElement(
                ListSerializer(FieldChange.serializer()),
                listOf(FieldChange(fieldKey = "context", toValue = JsonPrimitive("added:${saved.id}"))),
            ),
        )
        saved
    }

    override suspend fun remove(
        specId: UUID,
        contextId: UUID,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
    ): Unit = transaction {
        contextRepository.delete(contextId, specId)
        specHistoryRepository.add(
            specId = specId,
            changedAt = OffsetDateTime.now(),
            changedByPrincipalId = actingPrincipalId,
            changedByProfileId = actingProfileId,
            changes = json.encodeToJsonElement(
                ListSerializer(FieldChange.serializer()),
                listOf(FieldChange(fieldKey = "context", toValue = JsonPrimitive("removed:$contextId"))),
            ),
        )
        Unit
    }
}
