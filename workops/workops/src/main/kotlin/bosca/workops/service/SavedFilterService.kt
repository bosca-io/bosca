package bosca.workops.service

import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.bql.BqlParseException
import bosca.workops.model.bql.BqlParser
import bosca.workops.model.bql.BqlQuery
import bosca.workops.model.bql.BqlValidator
import bosca.workops.model.bql.SavedFilter
import bosca.workops.model.bql.SavedFilterInput
import bosca.workops.repository.SavedFilterRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@ServiceImplementation
class SavedFilterServiceImpl(
    private val repository: SavedFilterRepository,
    private val json: Json,
) : SavedFilterService {

    private val validator = BqlValidator()

    override suspend fun listForOwner(ownerProfileId: UUID, offset: Long, limit: Int): List<SavedFilter> =
        repository.listByOwner(ownerProfileId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun getById(id: UUID): SavedFilter? = repository.getById(id)

    override suspend fun getByIds(ids: List<UUID>): List<SavedFilter> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(ownerProfileId: UUID, input: SavedFilterInput): SavedFilter = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        val ast = parseAndValidate(input.bqlSource)
        val existing = repository.listByOwner(ownerProfileId, 0, 200).firstOrNull { it.name == input.name }
        if (existing != null) throw WorkOpsConflictException("SavedFilter", "${input.name} (owner=$ownerProfileId)")
        repository.add(
            SavedFilter(
                ownerProfileId = ownerProfileId,
                name = input.name,
                description = input.description,
                bqlSource = input.bqlSource,
                parsedAst = json.encodeToJsonElement(ast),
            )
        )
    }

    override suspend fun update(id: UUID, input: SavedFilterInput, expectedVersion: Long): SavedFilter = transaction {
        val existing = repository.getById(id)
            ?: throw WorkOpsNotFoundException("SavedFilter", id.toString())
        val ast = parseAndValidate(input.bqlSource)
        repository.update(
            existing.copy(
                name = input.name,
                description = input.description,
                bqlSource = input.bqlSource,
                parsedAst = json.encodeToJsonElement(ast),
                version = expectedVersion,
            )
        ) ?: throw OptimisticLockFailedException("SavedFilter", id)
    }

    override suspend fun delete(id: UUID) = repository.deleteById(id)

    private fun parseAndValidate(source: String): BqlQuery {
        val parsed = BqlParser(source).parse()
        val ast = parsed.query
        if (parsed.errors.isNotEmpty() || ast == null) {
            throw BqlParseException(parsed.errors)
        }
        val validation = validator.validate(ast)
        if (validation.isNotEmpty()) throw BqlParseException(validation)
        return ast
    }

    companion object {
        private const val MAX_PAGE = 200
    }
}
