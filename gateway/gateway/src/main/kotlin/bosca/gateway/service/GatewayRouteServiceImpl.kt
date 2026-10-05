package bosca.gateway.service

import bosca.db.transaction
import bosca.gateway.model.GatewayConflictException
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayOptimisticLockFailedException
import bosca.gateway.model.GatewayRoute
import bosca.gateway.model.GatewayRouteInput
import bosca.gateway.model.isUniqueViolation
import bosca.gateway.repository.GatewayConfigVersionRepository
import bosca.gateway.repository.GatewayRouteRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class GatewayRouteServiceImpl(
    private val repository: GatewayRouteRepository,
    private val configVersionRepository: GatewayConfigVersionRepository,
) : GatewayRouteService {

    override suspend fun listAll(): List<GatewayRoute> = repository.getAll()

    override suspend fun getById(id: UUID): GatewayRoute? = repository.getById(id)

    override suspend fun listByGatewayId(gatewayId: UUID): List<GatewayRoute> =
        repository.getByGatewayId(gatewayId)

    override suspend fun create(input: GatewayRouteInput): GatewayRoute = transaction {
        validateHosts(input.hosts)
        val route = remapConflict(input) {
            repository.add(
                GatewayRoute(
                    gatewayId = input.gatewayId,
                    pathPattern = input.pathPattern,
                    hosts = input.hosts,
                    authMethod = input.authMethod,
                    stripPrefix = input.stripPrefix,
                    readGroups = input.readGroups,
                    writeGroups = input.writeGroups,
                    injectHeaders = input.injectHeaders,
                    sortOrder = input.sortOrder,
                )
            )
        }
        configVersionRepository.bump()
        route
    }

    override suspend fun update(id: UUID, input: GatewayRouteInput, expectedVersion: Long): GatewayRoute = transaction {
        validateHosts(input.hosts)
        val updated = remapConflict(input) {
            repository.update(
                id = id,
                gatewayId = input.gatewayId,
                pathPattern = input.pathPattern,
                hosts = encodeHosts(input.hosts),
                authMethod = input.authMethod,
                stripPrefix = input.stripPrefix,
                readGroups = encodeGroups(input.readGroups),
                writeGroups = encodeGroups(input.writeGroups),
                injectHeaders = Json.encodeToString(JsonElement.serializer(), input.injectHeaders),
                sortOrder = input.sortOrder,
                expectedVersion = expectedVersion,
            )
        } ?: throw rowMissingOrStale(id)
        configVersionRepository.bump()
        updated
    }

    override suspend fun toggleEnabled(id: UUID, enabled: Boolean, expectedVersion: Long): GatewayRoute = transaction {
        val updated = repository.toggleEnabled(id, enabled, expectedVersion)
            ?: throw rowMissingOrStale(id)
        configVersionRepository.bump()
        updated
    }

    override suspend fun delete(id: UUID, expectedVersion: Long): GatewayRoute = transaction {
        val deleted = repository.delete(id, expectedVersion)
            ?: throw rowMissingOrStale(id)
        configVersionRepository.bump()
        deleted
    }

    override suspend fun countByGatewayId(gatewayId: UUID): Long = repository.countByGatewayId(gatewayId)

    private suspend fun rowMissingOrStale(id: UUID): RuntimeException =
        if (repository.getById(id) == null) GatewayNotFoundException("GatewayRoute", id.toString())
        else GatewayOptimisticLockFailedException("GatewayRoute", id)

    /**
     * Remap a Postgres unique-violation on `(gateway_id, path_pattern)`
     * to a typed [GatewayConflictException]. The unique index is the
     * source of truth — services don't pre-check because the route
     * shape allows the same pattern across different gateways.
     */
    private inline fun <T> remapConflict(input: GatewayRouteInput, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (isUniqueViolation(e)) {
                throw GatewayConflictException("GatewayRoute", "${input.gatewayId}/${input.pathPattern}")
            }
            throw e
        }

    /**
     * Encodes a group list for the `string_to_array(...)` binding in
     * [GatewayRouteRepository.update]. Comma is the array delimiter
     * Postgres expects, so the group names themselves cannot contain a
     * comma without corruption. We reject those at the input boundary
     * rather than silently splitting them.
     */
    private fun encodeGroups(groups: List<String>): String {
        val invalid = groups.filter { it.contains(',') || it.contains('\n') || it.contains('\r') || it.isBlank() }
        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException(
                "Group names cannot be blank or contain commas, CR, or LF (offenders: $invalid)"
            )
        }
        return groups.joinToString(",")
    }

    /**
     * Same Postgres-array delimiter constraint as [encodeGroups]. Host
     * patterns cannot legitimately contain commas, CR, or LF — the host
     * grammar is restricted to ASCII letters, digits, `.`, `-`, and the
     * leading `*.` wildcard sentinel.
     */
    private fun encodeHosts(hosts: List<String>): String {
        validateHosts(hosts)
        return hosts.joinToString(",")
    }

    /**
     * A host entry must be either a literal hostname or a leading-`*.`
     * wildcard. Reject anything else so an operator typo doesn't get
     * silently treated as a literal that will never match.
     */
    private fun validateHosts(hosts: List<String>) {
        val invalid = hosts.filter { !isValidHostPattern(it) }
        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid host pattern(s): $invalid. Use a literal host (`api.example.com`) " +
                    "or a leading-`*.` wildcard (`*.example.com`)."
            )
        }
    }

    private fun isValidHostPattern(pattern: String): Boolean {
        if (pattern.isBlank()) return false
        if (pattern.contains(',') || pattern.contains('\n') || pattern.contains('\r')) return false
        val body = pattern.removePrefix("*.")
        if (body.isEmpty()) return false
        // Body is a sequence of DNS labels separated by `.`. Each label
        // is letters/digits/hyphens, not starting or ending with `-`.
        return body.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
