package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** Resolves managed policy using current membership, within the initiating caller's authority ceiling. */
class KitHarnessPolicyResolver(
    private val configurations: ConfigurationService,
    private val permissions: ConfigurationPermissionEvaluator,
    private val security: SecurityService,
    private val application: BoscaApplication,
    private val json: Json,
    private val mcp: KitMcpResolver,
) {
    /** Current deployment limits apply to new admissions without changing an active turn's authority. */
    internal fun deployment(): KitHarnessDeployment {
        val value = requireNotNull(application.environment.config.propertyOrNull("kit.harness")) { "Kit harness is not configured" }
        return json.decodeFromString(KitHarnessDeployment.serializer(), value.getString()).also { deployment ->
            require(deployment.maxRuntimes > 0 && deployment.maxRuntimesPerPrincipal > 0) { "Invalid harness admission limits" }
            require(deployment.maxArchiveBytesPerPrincipal > 0) { "Invalid harness archive storage limit" }
            require(deployment.profiles.isNotEmpty() && deployment.profiles.map { it.profile }.distinct().size == deployment.profiles.size) { "Harness profiles must be unique" }
            deployment.profiles.forEach {
                require(it.image.matches(Regex("[^\\s@]+@sha256:[a-f0-9]{64}")) &&
                    it.imageId.matches(Regex("(?:[^\\s]*[@/])?sha256:[a-f0-9]{64}")) &&
                    (it.infrastructure != KitInfrastructure.SWARM || it.imageId == it.image.substringAfter('@')) &&
                    listOf(it.versions.cli, it.versions.adapter, it.versions.harness, it.versions.statePolicy).all(String::isNotBlank) &&
                    it.versions.transport == 1 && it.versions.acp == 1 && it.versions.archive == 1) { "Invalid harness runtime identity" }
                require(it.scopes.none { scope -> scope in setOf("ai:execute", "mcp:execute") || (scope.endsWith(":manage") && scope != "scripts:manage") || scope.endsWith(":admin") }) {
                    "Harness runtime scopes cannot grant administration or recursive agent execution"
                }
                require(it.profile.isNotBlank() && it.idleSeconds > 0 && it.cancellationSeconds > 0 && it.startupSeconds > 0 &&
                    it.credentialLifetimeSeconds > maxOf(it.idleSeconds, it.startupSeconds) && it.maxArchiveBytes > 0 &&
                    it.maxJournalBytes in 1024..(256L * 1024 * 1024) && it.maxJournalTurns in 1..10000 &&
                    it.connectionSeconds in 1..86400 && it.readinessSeconds in 1..86400 && it.restoreSeconds in 1..86400 &&
                    it.approvalSeconds in 1..604800 &&
                    it.promptSeconds > 0 && it.hardRuntimeSeconds in 1..it.credentialLifetimeSeconds &&
                    it.contextCharacters in 4096..131072 && it.scopes.isNotEmpty() && it.archiveSeconds in 1..86400 &&
                    (it.maxArchiveAgeSeconds == null || it.maxArchiveAgeSeconds in 1..3155760000L)) { "Invalid harness profile limits" }
                it.egressProxy?.let(::requireEgressProxy)
                it.egressHosts.forEach(::egressDestination)
            }
        }
    }

    suspend fun capture(authentication: AuthenticationContext): KitRuntimeAuthority {
        val principal = requireNotNull(authentication.principal()) { "Not authenticated" }
        val callerScopes = (principal as? ScopedAuthenticatedPrincipal)?.scopes?.sorted()
        return KitRuntimeAuthority(deployment().profiles.flatMap { it.scopes }.distinct()
            .filter { callerScopes == null || it in callerScopes }.sorted(),
            security.getPrincipalGroups(principal.id).filter(principal::hasGroup).map { it.id }.sortedBy { it.toString() }, callerScopes)
    }

    /** Uses the opt-in delegation policy rather than granting runtime tokens general chat override access. */
    internal suspend fun delegation(parent: KitHarnessResolution): KitDelegationPolicy {
        val current = policy()
        require(current.version == parent.policyVersion && current.id == parent.configurationId) { "Parent harness policy changed" }
        return requireNotNull(current.policy.delegation) { "Managed child harnesses are not enabled" }
    }

    /** Resolves an approved child with current membership and no extra scopes or MCP connections. */
    internal suspend fun resolveChild(principalId: UUID, parent: KitHarnessResolution, ceiling: KitRuntimeAuthority,
                                      profile: String, runtimeSeconds: Long, agent: String): KitHarnessResolution {
        val limits = delegation(parent)
        require(profile in limits.profiles) { "Child harness is not allowed" }
        require(isApprovalCurrent(principalId, parent)) { "Parent harness authority changed" }
        val current = policy()
        val original = current.profiles.getValue(profile)
        val config = original.copy(startupSeconds = minOf(original.startupSeconds, runtimeSeconds),
            promptSeconds = minOf(original.promptSeconds, runtimeSeconds), hardRuntimeSeconds = minOf(original.hardRuntimeSeconds, runtimeSeconds),
            idleSeconds = minOf(original.idleSeconds, runtimeSeconds))
        val authentication = authentication(principalId, ceiling)
        val principal = requireNotNull(authentication.principal())
        val groups = security.getPrincipalGroups(principalId).filter(principal::hasGroup).map { it.id }.sortedBy(UUID::toString)
        val scopes = config.scopes.intersect(ceiling.scopes.toSet()).sorted()
        require(scopes.isNotEmpty()) { "Child harness has no permitted scopes" }
        val catalog = mcp.catalog(agent, config)
        val connections = catalog.connections.filter { child -> parent.mcpConnections.any { child.copy(required = it.required) == it } }
        require(catalog.connections.filter { it.required }.all { required -> connections.any { it.key == required.key } }) { "Child requires an MCP connection outside the parent tool scope" }
        val model = current.policy.models[profile]
        val selection = model?.agentDefaults?.get(agent) ?: model?.defaults ?: KitModelSelection()
        return KitHarnessResolution(current.id, current.version, "DELEGATION", config,
            KitRuntimeAuthority(scopes, groups, scopes), mcpConnections = connections,
            mcpOptions = catalog.options.filter { option -> connections.any { it.key == option.key } }, mcpNotices = catalog.notices,
            modelPolicy = model, modelSettings = selection,
            modelSource = if (selection.model == null) "HARNESS" else "SYSTEM",
            reasoningSource = if (selection.reasoning == null) "HARNESS" else "SYSTEM")
    }

    /** Revalidates the immutable turn policy and caller authority before a native permission decision is used. */
    suspend fun isApprovalCurrent(principalId: UUID, resolution: KitHarnessResolution): Boolean {
        val current = policy()
        if (current.version != resolution.policyVersion || current.id != resolution.configurationId) return false
        val principal = security.getPrincipalById(principalId) ?: return false
        if (principal.deletedAt != null) return false
        val groups = security.getPrincipalGroups(principalId).map { it.id }.toSet()
        if (!groups.containsAll(resolution.authority.groups)) return false
        if (resolution.source == "CHAT") {
            val configuration = requireNotNull(configurations.getByKey("kit.harness"))
            if (!permissions.isAllowed(authentication(principalId, resolution.authority), configuration, PermissionAction.EXECUTE)) return false
        }
        return true
    }

    /** Current administrator-approved egress destinations, normalized to `host:port`. */
    internal suspend fun egressHosts(): List<String> = policy().policy.egressHosts.map(::egressDestination)

    /** Reads and validates current policy; a stale or revoked saved choice falls back to managed defaults. */
    suspend fun resolve(principalId: UUID, ceiling: KitRuntimeAuthority, choice: KitHarnessChoice?, agent: String): KitHarnessResolution {
        val (policy, version, configurationId, profiles) = policy()
        val authentication = authentication(principalId, ceiling)
        val configuration = configurations.getByKey("kit.harness")
        val allowed = configuration?.let { permissions.isAllowed(authentication, it, PermissionAction.EXECUTE) } ?: false
        val defaultProfile = policy.agentDefaults[agent] ?: policy.defaultProfile
        val requestedProfile = choice?.profile?.takeIf { allowed && choice.policyVersion == version && it in policy.allowedProfiles } ?: defaultProfile
        val catalog = mcp.catalog(agent, profiles.getValue(requestedProfile))
        val requestedMcp = choice?.mcpServers
        val notice = when {
            choice == null -> null
            !allowed -> "Custom harness access was removed. Managed settings now apply."
            choice.policyVersion != version -> "Harness policy changed. Managed settings now apply."
            choice.profile !in policy.allowedProfiles -> "The selected harness is unavailable. Managed settings now apply."
            requestedMcp != null && (requestedMcp.distinct().size != requestedMcp.size ||
                !catalog.options.map { it.key }.containsAll(requestedMcp) ||
                !requestedMcp.containsAll(catalog.options.filter { it.required }.map { it.key })) ->
                "Approved MCP connections changed. Managed settings now apply."
            choice.model != null && choice.model !in policy.models[choice.profile]?.allowedModels.orEmpty() ||
                choice.reasoning != null && choice.reasoning !in policy.models[choice.profile]?.allowedReasoning.orEmpty() ->
                "Approved model settings changed. Managed settings now apply."
            else -> null
        }
        val selected = if (choice != null && notice == null) choice.profile else policy.agentDefaults[agent] ?: policy.defaultProfile
        val config = profiles.getValue(selected)
        val effectiveCatalog = if (selected == requestedProfile) catalog else mcp.catalog(agent, config)
        val connections = effectiveCatalog.connections.filter { requestedMcp == null || notice != null || it.key in requestedMcp }
        val principal = requireNotNull(authentication.principal())
        val groups = security.getPrincipalGroups(principalId).filter(principal::hasGroup).map { it.id }.sortedBy { it.toString() }
        val scopes = config.scopes.intersect(ceiling.scopes.toSet()).sorted()
        require(scopes.isNotEmpty()) { "Current credentials do not permit the harness's Bosca scopes" }
        val modelPolicy = policy.models[selected]
        val agentModels = modelPolicy?.agentDefaults?.get(agent)
        val requestedModel = choice?.takeIf { notice == null }?.model
        val requestedReasoning = choice?.takeIf { notice == null }?.reasoning
        val modelSettings = KitModelSelection(requestedModel ?: agentModels?.model ?: modelPolicy?.defaults?.model,
            requestedReasoning ?: agentModels?.reasoning ?: modelPolicy?.defaults?.reasoning)
        fun source(requested: String?, agentValue: String?, system: String?) = when {
            requested != null -> "CHAT"
            agentValue != null -> "AGENT"
            system != null -> "SYSTEM"
            else -> "HARNESS"
        }
        return KitHarnessResolution(configurationId, version,
            if (choice != null && notice == null) "CHAT" else if (policy.agentDefaults.containsKey(agent)) "AGENT" else "SYSTEM",
            config, KitRuntimeAuthority(scopes, groups, ceiling.callerScopes), notice, choice,
            connections, effectiveCatalog.options, effectiveCatalog.notices, modelPolicy, modelSettings,
            source(requestedModel, agentModels?.model, modelPolicy?.defaults?.model),
            source(requestedReasoning, agentModels?.reasoning, modelPolicy?.defaults?.reasoning))
    }

    suspend fun settings(authentication: AuthenticationContext, session: KitRuntimeSession, agent: String): KitHarnessSettings {
        val principal = requireNotNull(authentication.principal())
        require(principal.id == session.principalId) { "Runtime belongs to another principal" }
        val ceiling = json.decodeFromJsonElement(KitRuntimeAuthority.serializer(), session.authority)
        val caller = capture(authentication)
        val access = ceiling.copy(groups = ceiling.groups.intersect(caller.groups.toSet()).toList(),
            callerScopes = intersectScopes(ceiling.callerScopes, caller.callerScopes))
        val (policy, version) = policy()
        val configuration = configurations.getByKey("kit.harness")
        val canOverride = configuration?.let { permissions.isAllowed(authentication(principal.id, access), it, PermissionAction.EXECUTE) } ?: false
        val requested = session.requestedSettings?.let { json.decodeFromJsonElement(KitHarnessChoice.serializer(), it) }
        val effective = session.resolvedSettings?.let { json.decodeFromJsonElement(KitHarnessResolution.serializer(), it) }
            ?: resolve(principal.id, access, requested, agent)
        val catalog = mcp.catalog(agent, effective.configuration)
        val modelPolicy = policy.models[effective.configuration.profile]
        val capabilities = effective.modelCapabilities
        return KitHarnessSettings(version, effective.configuration.profile, effective.source, canOverride,
            if (canOverride) policy.allowedProfiles else emptyList(), requested?.profile, effective.notice,
            if (canOverride) catalog.options else emptyList(), effective.mcpConnections.map { it.key }, requested?.mcpServers,
            (effective.mcpNotices + catalog.notices).distinct(),
            capabilities?.version, capabilities?.effective?.model, capabilities?.effective?.reasoning,
            effective.modelSource, effective.reasoningSource, requested?.model, requested?.reasoning,
            if (canOverride) capabilities?.models.orEmpty().filter { it.value in modelPolicy?.allowedModels.orEmpty() } else emptyList(),
            if (canOverride) capabilities?.reasoning.orEmpty().filter { it.value in modelPolicy?.allowedReasoning.orEmpty() } else emptyList())
    }

    suspend fun verify(authentication: AuthenticationContext, session: KitRuntimeSession, choice: KitHarnessChoice?, agent: String) {
        val settings = settings(authentication, session, agent)
        if (choice == null) return
        require(settings.canOverride) { "Configuration EXECUTE permission is required for custom harness settings" }
        require(settings.policyVersion == choice.policyVersion) { "Harness policy changed; reload settings" }
        require(choice.profile in settings.choices) { "Harness choice is not allowed" }
        if (choice.model != null || choice.reasoning != null) {
            require(choice.profile == settings.effectiveProfile && settings.capabilityVersion != null &&
                choice.capabilityVersion == settings.capabilityVersion) { "Harness capabilities changed; reload settings" }
            require(choice.model == null || settings.models.any { it.value == choice.model }) { "Model choice is not allowed or advertised" }
            require(choice.reasoning == null || settings.reasoningOptions.any { it.value == choice.reasoning }) { "Reasoning choice is not allowed or advertised" }
        }
        choice.mcpServers?.let { selected ->
            require(selected.distinct().size == selected.size && settings.mcpOptions.map { it.key }.containsAll(selected) &&
                selected.containsAll(settings.mcpOptions.filter { it.required }.map { it.key })) { "MCP selection must retain required connections and use only approved registrations" }
        }
    }

    private suspend fun authentication(principalId: UUID, ceiling: KitRuntimeAuthority): AuthenticationContext {
        val principal = requireNotNull(security.getPrincipalById(principalId)) { "Harness principal no longer exists" }
        require(principal.deletedAt == null) { "Harness principal was deleted" }
        val groups = security.getPrincipalGroups(principalId)
        val scoped = ScopedAuthenticatedPrincipal(principal, groups, ceiling.callerScopes, ceiling.groups.toSet(), 0)
        return object : AuthenticationContext(null, null) { override fun principal() = scoped }
    }

    private suspend fun policy(): Policy {
        val deployment = deployment()
        val configuration = configurations.getByKey("kit.harness")
        require(configuration?.public != true) { "Harness policy Configuration must be private" }
        val policy = configuration?.let {
            json.decodeFromJsonElement(KitHarnessPolicy.serializer(), requireNotNull(configurations.getValue(it.id)))
        } ?: KitHarnessPolicy(deployment.profiles.first().profile, deployment.profiles.map { it.profile })
        val profiles = deployment.profiles.associateBy { it.profile }
        require(policy.allowedProfiles.isNotEmpty() && policy.defaultProfile in policy.allowedProfiles &&
            policy.allowedProfiles.all { it in profiles } && policy.agentDefaults.values.all { it in policy.allowedProfiles }) { "Harness policy references unavailable profiles" }
        require(policy.models.keys.all { it in policy.allowedProfiles }) { "Model policy references unavailable profiles" }
        policy.delegation?.let {
            it.validate()
            require(it.profiles.all { profile -> profile in policy.allowedProfiles }) { "Child policy references unavailable profiles" }
        }
        policy.egressHosts.forEach(::egressDestination)
        policy.models.values.forEach { model ->
            require(model.allowedModels.distinct().size == model.allowedModels.size && model.allowedReasoning.distinct().size == model.allowedReasoning.size &&
                (model.allowedModels.isEmpty() == (model.defaults.model == null)) &&
                (model.allowedReasoning.isEmpty() == (model.defaults.reasoning == null))) { "Configurable model controls require explicit managed defaults" }
            (listOf(model.defaults) + model.agentDefaults.values).forEach { selection ->
                require((selection.model == null || selection.model in model.allowedModels) &&
                    (selection.reasoning == null || selection.reasoning in model.allowedReasoning)) { "Model defaults must be approved by policy" }
            }
        }
        // Runtime images, placement and budgets do not change the caller's approved tool authority.
        val authorityProfiles = JsonArray(deployment.profiles.map {
            JsonObject(json.encodeToJsonElement(KitHarnessConfiguration.serializer(), it).jsonObject.filterKeys { key ->
                key in setOf("profile", "boscaEndpoint", "authenticationMethod", "scopes", "mcpExecutables", "mcpSecretEnvironment")
            })
        })
        // Egress approvals are read live by the proxy lookup, so editing them does not reset saved choices or approvals.
        val revision = configuration?.id.toString() + json.encodeToString(KitHarnessPolicy.serializer(), policy.copy(egressHosts = emptyList())) +
            authorityProfiles.toString()
        val version = MessageDigest.getInstance("SHA-256").digest(revision.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return Policy(policy, version, configuration?.id, profiles)
    }

    private fun intersectScopes(first: List<String>?, second: List<String>?) = when {
        first == null -> second
        second == null -> first
        else -> first.intersect(second.toSet()).sorted()
    }

    private data class Policy(val policy: KitHarnessPolicy, val version: String, val id: UUID?, val profiles: Map<String, KitHarnessConfiguration>)
}
