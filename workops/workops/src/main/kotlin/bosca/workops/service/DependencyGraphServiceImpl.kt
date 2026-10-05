package bosca.workops.service

import bosca.db.connection
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.dependency.BuildBlocker
import bosca.workops.model.dependency.BuildBlockerType
import bosca.workops.model.dependency.BuildReadiness
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.repository.ArtifactPublicationRepository
import bosca.workops.repository.CompatibilityTestResultRepository
import bosca.workops.repository.DependencyDeclarationRepository

@ServiceImplementation
class DependencyGraphServiceImpl(
    private val dependencyRepository: DependencyDeclarationRepository,
) : DependencyGraphService {

    override suspend fun transitiveConsumerProjectIds(projectId: UUID): List<UUID> {
        val results = mutableListOf<UUID>()
        connection().useStatement(
            """
            with recursive downstream as (
                select consumer_project_id, provider_project_id, 1 as depth,
                       array[provider_project_id] as path
                from workops.dependency_declaration
                where provider_project_id = ?
                union all
                select dd.consumer_project_id, dd.provider_project_id, d.depth + 1,
                       d.path || dd.provider_project_id
                from workops.dependency_declaration dd
                join downstream d on dd.provider_project_id = d.consumer_project_id
                where dd.consumer_project_id != all(d.path)
            )
            select distinct consumer_project_id from downstream order by consumer_project_id
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(projectId.toString()))
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(UUID.parse(rs.getString("consumer_project_id")))
                }
            }
        }
        return results
    }

    override suspend fun buildOrder(projectIds: List<UUID>): List<UUID> {
        if (projectIds.size < 2) return projectIds
        val inSet = projectIds.toSet()
        // Declared edges among the given projects only: provider -> consumer.
        val consumersByProvider = mutableMapOf<UUID, MutableList<UUID>>()
        val providerCount = mutableMapOf<UUID, Int>()
        for (consumer in projectIds) {
            for (declaration in dependencyRepository.listByConsumer(consumer)) {
                val provider = declaration.providerProjectId
                if (provider in inSet && provider != consumer) {
                    consumersByProvider.getOrPut(provider) { mutableListOf() } += consumer
                    providerCount[consumer] = (providerCount[consumer] ?: 0) + 1
                }
            }
        }
        if (consumersByProvider.isEmpty()) return projectIds
        // Kahn, seeded and drained in INPUT order so unconstrained ties keep the caller's ordering.
        val ordered = mutableListOf<UUID>()
        val ready = ArrayDeque(projectIds.filter { (providerCount[it] ?: 0) == 0 })
        val remaining = providerCount.toMutableMap()
        while (ready.isNotEmpty()) {
            val next = ready.removeFirst()
            ordered += next
            for (consumer in consumersByProvider[next].orEmpty()) {
                val left = (remaining[consumer] ?: 0) - 1
                remaining[consumer] = left
                if (left == 0) ready += projectIds.first { it == consumer }
            }
        }
        check(ordered.size == projectIds.size) {
            "project dependencies among the release's projects form a cycle — a build order cannot be derived"
        }
        return ordered
    }

    override suspend fun transitiveProviderProjectIds(projectId: UUID): List<UUID> {
        val results = mutableListOf<UUID>()
        connection().useStatement(
            """
            with recursive upstream as (
                select consumer_project_id, provider_project_id, 1 as depth,
                       array[consumer_project_id] as path
                from workops.dependency_declaration
                where consumer_project_id = ?
                union all
                select dd.consumer_project_id, dd.provider_project_id, d.depth + 1,
                       d.path || dd.consumer_project_id
                from workops.dependency_declaration dd
                join upstream d on dd.consumer_project_id = d.provider_project_id
                where dd.provider_project_id != all(d.path)
            )
            select distinct provider_project_id from upstream order by provider_project_id
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(projectId.toString()))
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(UUID.parse(rs.getString("provider_project_id")))
                }
            }
        }
        return results
    }
}

@ServiceImplementation
class BuildReadinessServiceImpl(
    private val depRepository: DependencyDeclarationRepository,
    private val compatRepository: CompatibilityTestResultRepository,
    private val artifactRepository: ArtifactPublicationRepository,
) : BuildReadinessService {

    override suspend fun check(projectId: UUID): BuildReadiness {
        val deps = depRepository.listByConsumer(projectId)
        val blockers = mutableListOf<BuildBlocker>()

        for (dep in deps) {
            when (dep.status) {
                DependencyStatus.INCOMPATIBLE -> blockers.add(
                    BuildBlocker(
                        blockerType = BuildBlockerType.INCOMPATIBLE_DEPENDENCY,
                        providerProjectId = dep.providerProjectId,
                        providerVersionId = dep.resolvedProviderVersionId,
                        description = "Dependency on ${dep.artifactCoordinates ?: dep.providerProjectId} is INCOMPATIBLE",
                    )
                )
                DependencyStatus.OUTDATED -> {
                    if (dep.resolvedProviderVersionId != null) {
                        val compats = compatRepository.listByConsumerVersion(dep.consumerProjectId, dep.consumerVersionId ?: continue)
                        val relevant = compats.filter { it.providerProjectId == dep.providerProjectId }
                        val failed = relevant.firstOrNull {
                            it.status == bosca.workops.model.compatibility.CompatibilityStatus.FAILED
                        }
                        val pending = relevant.isEmpty()
                        if (failed != null) {
                            blockers.add(
                                BuildBlocker(
                                    blockerType = BuildBlockerType.COMPILE_CHECK_FAILED,
                                    providerProjectId = dep.providerProjectId,
                                    providerVersionId = dep.resolvedProviderVersionId,
                                    description = "Compile check failed against ${dep.artifactCoordinates ?: dep.providerProjectId}",
                                    compatibilityTestId = failed.id,
                                )
                            )
                        } else if (pending) {
                            blockers.add(
                                BuildBlocker(
                                    blockerType = BuildBlockerType.COMPILE_CHECK_PENDING,
                                    providerProjectId = dep.providerProjectId,
                                    providerVersionId = dep.resolvedProviderVersionId,
                                    description = "Compile check hasn't run yet for ${dep.artifactCoordinates ?: dep.providerProjectId}",
                                )
                            )
                        }
                    }
                }
                DependencyStatus.CURRENT -> {
                    if (dep.resolvedProviderVersionId != null) {
                        val artifacts = artifactRepository.listByVersion(dep.resolvedProviderVersionId!!)
                        if (artifacts.none { it.status == bosca.workops.model.artifact.PublicationStatus.PUBLISHED }) {
                            blockers.add(
                                BuildBlocker(
                                    blockerType = BuildBlockerType.PROVIDER_ARTIFACTS_MISSING,
                                    providerProjectId = dep.providerProjectId,
                                    providerVersionId = dep.resolvedProviderVersionId,
                                    description = "Provider ${dep.artifactCoordinates ?: dep.providerProjectId} has no published artifacts",
                                )
                            )
                        }
                    }
                }
            }
        }
        return BuildReadiness(ready = blockers.isEmpty(), blockers = blockers)
    }
}
