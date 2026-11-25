package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.di.ObjectProvider
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.jobs.KubernetesJobSecrets
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.serialization.UUID

/** Kit's Kubernetes adapter; the existing controller remains the only resource materializer. */
class KubernetesKitRuntimeProvider(private val jobs: ObjectProvider<KubernetesJobDispatchService>) : KitRuntimeProvider {
    override suspend fun ensure(configuration: KitHarnessConfiguration, key: String, environment: Map<String, String>, secrets: KitRuntimeSecrets): UUID =
        jobs.get().dispatch(KubernetesJobRequest(configuration.profile, key, environment,
            secrets = KubernetesJobSecrets(secrets.keyId, secrets.nonce, secrets.ciphertext, secrets.names), servicePort = 8080))

    override suspend fun status(configuration: KitHarnessConfiguration, reference: UUID): KitRuntimeExecution? = jobs.get().getExecution(reference)?.let {
        val name = it.jobName
        val namespace = it.namespace
        val endpoint = if (name == null || namespace == null) null else configuration.endpointTemplate
            .replace("{job}", name).replace("{namespace}", namespace).replace("{cluster}", it.clusterId.toString())
        KitRuntimeExecution(it.createdAt, it.status.isTerminal, endpoint, it.image, it.imageId)
    }

    override suspend fun isStopped(configuration: KitHarnessConfiguration, reference: UUID): Boolean = jobs.get().getResult(reference) != null

    override suspend fun stop(configuration: KitHarnessConfiguration, reference: UUID) { jobs.get().cancel(reference) }
}
