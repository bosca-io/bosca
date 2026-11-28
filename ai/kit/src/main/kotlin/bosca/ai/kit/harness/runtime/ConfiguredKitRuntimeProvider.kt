package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.ai.kit.harness.KitInfrastructure
import bosca.serialization.UUID

/** Selects the infrastructure pinned to the generation; native harness selection stays independent. */
class ConfiguredKitRuntimeProvider(private val kubernetes: KubernetesKitRuntimeProvider, private val swarm: SwarmKitRuntimeProvider) : KitRuntimeProvider {
    private fun provider(configuration: KitHarnessConfiguration): KitRuntimeProvider = when (configuration.infrastructure) {
        KitInfrastructure.KUBERNETES -> kubernetes
        KitInfrastructure.SWARM -> swarm
    }
    override suspend fun ensure(configuration: KitHarnessConfiguration, key: String, environment: Map<String, String>, secrets: KitRuntimeSecrets) = provider(configuration).ensure(configuration, key, environment, secrets)
    override suspend fun status(configuration: KitHarnessConfiguration, reference: UUID) = provider(configuration).status(configuration, reference)
    override suspend fun stop(configuration: KitHarnessConfiguration, reference: UUID) = provider(configuration).stop(configuration, reference)
    override suspend fun isStopped(configuration: KitHarnessConfiguration, reference: UUID) = provider(configuration).isStopped(configuration, reference)
}
