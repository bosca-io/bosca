package bosca.workops.service

import bosca.search.IndexStorageSystem
import bosca.search.model.SearchQuery
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.ai.AiOptInScope
import bosca.workops.model.ai.AiOptOutException
import bosca.workops.model.ai.BqlTranslation
import bosca.workops.model.ai.DuplicateSuggestion
import bosca.workops.model.ai.TriageSuggestion
import bosca.workops.model.task.Task
import bosca.workops.repository.AiOptInRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.TaskRepository
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@ServiceImplementation
class AiOptInServiceImpl(
    private val repository: AiOptInRepository,
) : AiOptInService {

    override suspend fun isEnabled(projectId: UUID?, taskId: UUID?): Boolean {
        if (!repository.orgEnabled()) return false
        if (projectId != null && !repository.projectEnabled(projectId)) return false
        if (taskId != null) {
            val taskFlag = repository.taskOverride(taskId)
            if (taskFlag == false) return false
        }
        return true
    }

    override suspend fun requireEnabled(projectId: UUID?, taskId: UUID?) {
        if (!repository.orgEnabled()) throw AiOptOutException(AiOptInScope.ORG, null)
        if (projectId != null && !repository.projectEnabled(projectId)) {
            throw AiOptOutException(AiOptInScope.PROJECT, projectId)
        }
        if (taskId != null) {
            val taskFlag = repository.taskOverride(taskId)
            if (taskFlag == false) throw AiOptOutException(AiOptInScope.TASK, taskId)
        }
    }

    override suspend fun setOrgEnabled(enabled: Boolean) = repository.setOrgEnabled(enabled)
    override suspend fun setProjectEnabled(projectId: UUID, enabled: Boolean) =
        repository.setProjectEnabled(projectId, enabled)

    override suspend fun setTaskEnabled(taskId: UUID, enabled: Boolean?) =
        repository.setTaskOverride(taskId, enabled)
}

@ServiceImplementation
class DuplicateSuggestionServiceImpl(
    private val searchService: SearchService,
    private val storageSystemService: StorageSystemService,
    private val optIn: AiOptInService,
) : DuplicateSuggestionService {

    override suspend fun suggest(text: String, projectId: UUID, limit: Int): List<DuplicateSuggestion> {
        optIn.requireEnabled(projectId = projectId)
        val system = storageSystemService.getAll()
            .firstOrNull { it.type == StorageSystemType.SEARCH && it.name.isNotBlank() }
            ?: return emptyList()
        val result = searchService.searchRaw(
            SearchQuery(
                query = text,
                offset = 0,
                limit = limit.coerceIn(1, 50),
                filter = listOf("projectId = \"$projectId\"", "deleted = false"),
                storageSystemId = system.id,
            )
        )
        return result.hits.mapNotNull { hit ->
            val idElement = hit["id"] ?: return@mapNotNull null
            val keyElement = hit["key"] ?: return@mapNotNull null
            val summaryElement = hit["summary"] ?: return@mapNotNull null
            val descriptionElement = hit["descriptionPlain"]
            val id = idElement.jsonPrimitive.content
            val key = keyElement.jsonPrimitive.content
            val summary = summaryElement.jsonPrimitive.content
            val description = if (descriptionElement == null) "" else descriptionElement.jsonPrimitive.content
            val score = jaccard(text, "$summary $description")
            DuplicateSuggestion(
                candidateTaskId = UUID.parse(id),
                candidateKey = key,
                candidateSummary = summary,
                lexicalScore = score,
                reasoning = "lexical match: ${(score * 100).toInt()}%",
            )
        }.sortedByDescending { it.lexicalScore }
    }

    private fun jaccard(a: String, b: String): Double {
        val tokensA = a.lowercase().split(WHITESPACE).filter { it.isNotBlank() }.toSet()
        val tokensB = b.lowercase().split(WHITESPACE).filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        return intersection / union
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}

@ServiceImplementation
class TriageSuggestionServiceImpl(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val optIn: AiOptInService,
) : TriageSuggestionService {

    override suspend fun suggest(taskId: UUID): TriageSuggestion {
        val task = taskRepository.getById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        optIn.requireEnabled(projectId = task.projectId, taskId = taskId)
        // The structured suggestion needs an LLM call; Phase 19
        // wires the binding. The contract is in place; admins
        // can author the rule today.
        throw PendingPhaseImplementationException(
            variant = "TriageSuggestionService.suggest", owningPhase = 19,
        )
    }

    override suspend fun acceptTriageSuggestion(
        taskId: UUID,
        suggestion: TriageSuggestion,
        actingPrincipalId: UUID,
    ): Task = throw PendingPhaseImplementationException(
        variant = "TriageSuggestionService.acceptTriageSuggestion", owningPhase = 19,
    )
}

@ServiceImplementation
class SummarizationServiceImpl(
    private val taskRepository: TaskRepository,
    private val optIn: AiOptInService,
) : SummarizationService {
    override suspend fun summarizeThread(taskId: UUID, kind: String): String {
        val task = taskRepository.getById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        optIn.requireEnabled(projectId = task.projectId, taskId = taskId)
        throw PendingPhaseImplementationException(
            variant = "SummarizationService.summarizeThread", owningPhase = 19,
        )
    }

    override suspend fun summarizeSprint(sprintId: UUID): String {
        throw PendingPhaseImplementationException(
            variant = "SummarizationService.summarizeSprint", owningPhase = 19,
        )
    }
}

@ServiceImplementation
class BqlTranslationServiceImpl(
    private val optIn: AiOptInService,
) : BqlTranslationService {
    override suspend fun translate(naturalLanguage: String, projectContextId: UUID?): BqlTranslation {
        optIn.requireEnabled(projectId = projectContextId)
        throw PendingPhaseImplementationException(
            variant = "BqlTranslationService.translate", owningPhase = 19,
        )
    }
}
