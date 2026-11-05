package bosca.ai.kit.harness.runtime

import ai.koog.prompt.executor.model.PromptExecutor
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.session.KitSessionService
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.security.encryption.EncryptionService
import bosca.security.service.ApiTokenService
import bosca.storage.service.ObjectStorageService
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json

@Providers
class KitRuntimeConfiguration {
    @Provider(singleton = true)
    fun telemetry(counter: bosca.counter.Counter, json: Json) = KitRuntimeTelemetry(counter, json)
    @Provider(singleton = true)
    fun mcp(agents: bosca.ai.agents.service.AgentService, registrations: bosca.ai.agents.service.McpServerRegistrationService, json: Json) =
        KitMcpResolver(agents, registrations, json)

    @Provider(singleton = true)
    fun policy(configurations: bosca.configuration.service.ConfigurationService,
               permissions: bosca.configuration.security.ConfigurationPermissionEvaluator,
               security: bosca.security.service.SecurityService, application: bosca.server.BoscaApplication, json: Json, mcp: KitMcpResolver) =
        KitHarnessPolicyResolver(configurations, permissions, security, application, json, mcp)

    @Provider(singleton = true)
    fun context(agents: bosca.ai.agents.service.AgentService, prompts: bosca.ai.prompts.service.PromptService,
                history: ChatHistoryService, metadata: bosca.content.metadata.service.MetadataService,
                documents: bosca.content.metadata.service.DocumentService, collections: bosca.content.collection.service.CollectionService,
                metadataPermissions: bosca.content.security.MetadataPermissionEvaluator,
                collectionPermissions: bosca.content.security.CollectionPermissionEvaluator,
                security: bosca.security.service.SecurityService, json: Json, memories: bosca.ai.memory.MemoryService,
                tasks: ObjectProvider<bosca.workops.service.TaskService>, specs: ObjectProvider<bosca.workops.service.SpecService>,
                requirements: ObjectProvider<bosca.workops.service.RequirementService>,
                taskPermissions: ObjectProvider<bosca.workops.service.TaskPermissionEvaluator>,
                specPermissions: ObjectProvider<bosca.workops.service.SpecPermissionEvaluator>,
                requirementPermissions: ObjectProvider<bosca.workops.service.RequirementPermissionEvaluator>,
                repositories: ObjectProvider<bosca.git.service.RepositoryService>, browse: ObjectProvider<bosca.git.service.RepositoryBrowseService>,
                repositoryPermissions: ObjectProvider<bosca.git.service.RepositoryAccessEvaluator>) =
        KitContextResolver(agents, prompts, history, metadata, documents, collections, metadataPermissions, collectionPermissions, security, json, memories,
            tasks, specs, requirements, taskPermissions, specPermissions, requirementPermissions, repositories, browse, repositoryPermissions)

    @Provider(singleton = true)
    fun runtimeProvider(jobs: ObjectProvider<KubernetesJobDispatchService>, swarm: KitSwarmRepository, application: BoscaApplication, json: Json): KitRuntimeProvider =
        ConfiguredKitRuntimeProvider(KubernetesKitRuntimeProvider(jobs), SwarmKitRuntimeProvider(swarm, application, json))

    @Provider(singleton = true)
    fun driver(repository: KitRuntimeRepository, provider: KitRuntimeProvider, tokens: ApiTokenService,
               encryption: EncryptionService, storage: ObjectStorageService, history: ChatHistoryService,
               sessions: KitSessionService, executor: PromptExecutor, json: Json, policy: KitHarnessPolicyResolver, context: KitContextResolver, telemetry: KitRuntimeTelemetry,
               delegations: bosca.ai.kit.harness.KitDelegationService) =
        KitRuntimeDriver(repository, provider, tokens, encryption, storage, history, sessions, executor, json, policy, context, telemetry, delegations)

    @Provider(singleton = true)
    fun recovery(repository: KitRuntimeRepository, telemetry: KitRuntimeTelemetry) = KitRuntimeRecovery(repository, telemetry)
}
