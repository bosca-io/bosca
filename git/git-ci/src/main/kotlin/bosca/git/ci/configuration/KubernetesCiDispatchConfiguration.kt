package bosca.git.ci.configuration

import bosca.kubernetes.jobs.KubernetesJobRequest

/**
 * CI runner labels that should be dispatched through the `kubernetes-jobs` queue.
 *
 * An empty set keeps ordinary polling agents in control. Each configured value is also a
 * Kubernetes custom-resource name, so invalid DNS names are rejected during application startup.
 */
data class KubernetesCiDispatchConfiguration(
    val profiles: Set<String> = emptySet(),
) {
    init {
        profiles.forEach { KubernetesJobRequest(it, "configuration").validate() }
    }

    val enabled: Boolean
        get() = profiles.isNotEmpty()

    companion object {
        val disabled = KubernetesCiDispatchConfiguration()
    }
}
