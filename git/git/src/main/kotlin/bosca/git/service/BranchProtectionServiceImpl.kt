package bosca.git.service

import bosca.git.model.BranchProtectionRule
import bosca.git.repository.BranchProtectionRepository
import bosca.service.annotation.ServiceImplementation
import bosca.serialization.UUID

/**
 * Evaluates branch protection rules by loading them from the repository layer
 * and matching the target branch against configured glob patterns.
 */
@ServiceImplementation
class BranchProtectionServiceImpl(
    private val repository: BranchProtectionRepository
) : BranchProtectionService {

    override suspend fun findByRepository(repositoryId: UUID): List<BranchProtectionRule> {
        return repository.findByRepository(repositoryId)
    }

    override suspend fun findById(id: UUID): BranchProtectionRule? {
        return repository.findById(id)
    }

    override suspend fun create(rule: BranchProtectionRule): BranchProtectionRule {
        return repository.create(rule)
    }

    override suspend fun update(rule: BranchProtectionRule): BranchProtectionRule {
        return repository.update(rule)
            ?: throw IllegalArgumentException("Branch protection rule not found: ${rule.id}")
    }

    override suspend fun delete(id: UUID) {
        repository.delete(id)
    }

    override suspend fun findMatchingRule(repositoryId: UUID, branchName: String): BranchProtectionRule? {
        val pattern = repository.findPatternsByRepository(repositoryId)
            .firstOrNull { BranchProtectionService.matchesGlob(it, branchName) }
            ?: return null
        return repository.findByRepositoryAndPattern(repositoryId, pattern)
    }
}
