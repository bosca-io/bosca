package bosca.workops.service

import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.artifact.AllocateAppBuildNumberInput
import bosca.workops.model.artifact.AppBuildNumberAllocation
import bosca.workops.model.artifact.AppBuildNumberAllocationResult
import bosca.workops.model.artifact.AppBuildPlatform
import bosca.workops.repository.AppBuildNumberRepository

@ServiceImplementation
class AppBuildNumberServiceImpl(
    private val repository: AppBuildNumberRepository,
) : AppBuildNumberService {

    override suspend fun allocate(input: AllocateAppBuildNumberInput): AppBuildNumberAllocationResult {
        val normalized = input.normalized()
        val versionScope = normalized.platform.versionScope(normalized.sourceVersion)
        return transaction {
            // Insert-before-lock makes the counter row itself the per-application/version mutex.
            // Concurrent first allocations block on the unique key, then lock the same row.
            repository.ensureCounter(normalized.platform.name, normalized.applicationId, versionScope)
            val counter = repository.lockCounter(normalized.platform.name, normalized.applicationId, versionScope)
                ?: error(
                    "Failed to create app build-number counter for " +
                        "${normalized.platform}/${normalized.applicationId}/$versionScope",
                )

            repository.find(
                normalized.repositoryId,
                normalized.sourceCommitSha,
                normalized.sourceVersion,
                normalized.platform.name,
                normalized.applicationId,
                normalized.buildKey,
            )?.let { return@transaction AppBuildNumberAllocationResult(it, reused = true) }

            val next = maxOf(counter.lastNumber + 1, normalized.minimumNumber)
            check(next <= normalized.platform.maximumNumber) {
                "${normalized.platform} build-number space exhausted for '${normalized.applicationId}' " +
                    "at ${counter.lastNumber} (maximum ${normalized.platform.maximumNumber})"
            }
            repository.updateCounter(
                normalized.platform.name,
                normalized.applicationId,
                versionScope,
                next,
                counter.version,
            ) ?: error(
                "App build-number counter changed while locked for " +
                    "${normalized.platform}/${normalized.applicationId}/$versionScope",
            )

            val allocation = repository.add(
                platform = normalized.platform.name,
                applicationId = normalized.applicationId,
                versionScope = versionScope,
                buildKey = normalized.buildKey,
                repositoryId = normalized.repositoryId,
                sourceCommitSha = normalized.sourceCommitSha,
                sourceVersion = normalized.sourceVersion,
                pipelineRunId = normalized.pipelineRunId,
                number = next,
                value = normalized.platform.format(next),
            )
            AppBuildNumberAllocationResult(allocation, reused = false)
        }
    }

    override suspend fun find(
        repositoryId: UUID,
        sourceCommitSha: String,
        sourceVersion: String,
        platform: AppBuildPlatform,
        applicationId: String,
        buildKey: String,
    ): AppBuildNumberAllocation? = repository.find(
        repositoryId = repositoryId,
        sourceCommitSha = sourceCommitSha.trim().lowercase(),
        sourceVersion = sourceVersion.trim(),
        platform = platform.name,
        applicationId = applicationId.trim(),
        buildKey = buildKey.trim().ifEmpty { DEFAULT_BUILD_KEY },
    )

    private fun AllocateAppBuildNumberInput.normalized(): AllocateAppBuildNumberInput {
        val app = applicationId.trim()
        val key = buildKey.trim().ifEmpty { DEFAULT_BUILD_KEY }
        val commit = sourceCommitSha.trim().lowercase()
        val source = sourceVersion.trim()
        require(app.isNotEmpty() && app.none(Char::isWhitespace)) {
            "applicationId must be non-blank and contain no whitespace"
        }
        require(key.matches(Regex(BUILD_KEY_PATTERN))) {
            "buildKey '$key' must contain only letters, digits, '.', '_', or '-'"
        }
        require(commit.matches(Regex(COMMIT_SHA_PATTERN))) {
            "sourceCommitSha must be a full SHA-1 or SHA-256 hexadecimal object id"
        }
        require(source.isNotEmpty()) { "sourceVersion must not be blank" }
        require(minimumNumber in 1..platform.maximumNumber) {
            "minimumNumber must be between 1 and ${platform.maximumNumber} for $platform"
        }
        return copy(
            applicationId = app,
            buildKey = key,
            sourceCommitSha = commit,
            sourceVersion = source,
        )
    }

    private companion object {
        const val DEFAULT_BUILD_KEY = "default"
        const val BUILD_KEY_PATTERN = "[A-Za-z0-9._-]+"
        const val COMMIT_SHA_PATTERN = "(?:[0-9a-f]{40}|[0-9a-f]{64})"
    }
}
