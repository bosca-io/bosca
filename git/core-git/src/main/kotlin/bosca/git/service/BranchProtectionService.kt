package bosca.git.service

import bosca.git.model.BranchProtectionRule
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages branch protection rules and evaluates whether a ref update is permitted
 * by matching the target branch name against configured glob patterns.
 */
interface BranchProtectionService : Service {

    /**
     * Retrieves all branch protection rules configured for a repository.
     */
    suspend fun findByRepository(repositoryId: UUID): List<BranchProtectionRule>

    /**
     * Retrieves a branch protection rule by its unique identifier.
     */
    suspend fun findById(id: UUID): BranchProtectionRule?

    /**
     * Creates a new branch protection rule and returns the persisted entity.
     */
    suspend fun create(rule: BranchProtectionRule): BranchProtectionRule

    /**
     * Updates an existing branch protection rule. Throws if the rule does not exist.
     */
    suspend fun update(rule: BranchProtectionRule): BranchProtectionRule

    /**
     * Deletes a branch protection rule by its identifier.
     */
    suspend fun delete(id: UUID)

    /**
     * Returns the first protection rule whose [BranchProtectionRule.pattern] matches
     * the given branch name (without the `refs/heads/` prefix), or null if unprotected.
     */
    suspend fun findMatchingRule(repositoryId: UUID, branchName: String): BranchProtectionRule?

    companion object {
        /**
         * Tests whether a glob [pattern] matches the given [name]. Supports `*`
         * (single segment wildcard), `**` (multi-segment wildcard), and `?`
         * (single character wildcard).
         */
        fun matchesGlob(pattern: String, name: String): Boolean {
            val matches = Array(pattern.length + 1) { BooleanArray(name.length + 1) }
            matches[0][0] = true

            var patternIndex = 0
            while (patternIndex < pattern.length) {
                when {
                    pattern[patternIndex] == '*' &&
                        patternIndex + 1 < pattern.length &&
                        pattern[patternIndex + 1] == '*' -> {
                        var nextPatternIndex = patternIndex + 2
                        if (nextPatternIndex < pattern.length && pattern[nextPatternIndex] == '/') {
                            nextPatternIndex++
                        }
                        for (nameIndex in 0..name.length) {
                            if (matches[patternIndex][nameIndex]) {
                                matches[nextPatternIndex][nameIndex] = true
                            }
                            if (nameIndex > 0 && matches[nextPatternIndex][nameIndex - 1]) {
                                matches[nextPatternIndex][nameIndex] = true
                            }
                        }
                        patternIndex = nextPatternIndex
                    }

                    pattern[patternIndex] == '*' -> {
                        val nextPatternIndex = patternIndex + 1
                        for (nameIndex in 0..name.length) {
                            if (matches[patternIndex][nameIndex]) {
                                matches[nextPatternIndex][nameIndex] = true
                            }
                            if (
                                nameIndex > 0 &&
                                name[nameIndex - 1] != '/' &&
                                matches[nextPatternIndex][nameIndex - 1]
                            ) {
                                matches[nextPatternIndex][nameIndex] = true
                            }
                        }
                        patternIndex = nextPatternIndex
                    }

                    pattern[patternIndex] == '?' -> {
                        for (nameIndex in 0 until name.length) {
                            if (name[nameIndex] != '/' && matches[patternIndex][nameIndex]) {
                                matches[patternIndex + 1][nameIndex + 1] = true
                            }
                        }
                        patternIndex++
                    }

                    else -> {
                        for (nameIndex in 0 until name.length) {
                            if (
                                pattern[patternIndex] == name[nameIndex] &&
                                matches[patternIndex][nameIndex]
                            ) {
                                matches[patternIndex + 1][nameIndex + 1] = true
                            }
                        }
                        patternIndex++
                    }
                }
            }

            return matches[pattern.length][name.length]
        }
    }
}
