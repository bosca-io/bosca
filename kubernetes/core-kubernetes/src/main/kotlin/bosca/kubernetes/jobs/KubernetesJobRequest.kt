package bosca.kubernetes.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Per-execution data for one Kubernetes Job.
 *
 * Stable workload configuration, credentials, resource requests, tolerations, and other pod
 * settings belong in the referenced `JobProfile` custom resource. This payload contains
 * only the values that vary for one execution.
 */
@Serializable
data class KubernetesJobRequest(
    /** `metadata.name` of the `JobProfile` that supplies the Job's pod template. */
    val profile: String,
    /** Stable producer key used to make Kubernetes Job creation idempotent across redelivery. */
    val idempotencyKey: String,
    /** Environment variables injected into the profile's worker container. */
    val environment: Map<String, String> = emptyMap(),
    /** Optional replacement for the profile worker container's arguments. */
    val arguments: List<String>? = null,
    /** Labels added to the generated Job and pod template. */
    val labels: Map<String, String> = emptyMap(),
    /** Annotations added to the generated Job and pod template. */
    val annotations: Map<String, String> = emptyMap(),
    /**
     * Maximum time this request may wait to become a Kubernetes Job.
     *
     * This bounds both missing-profile and capacity waits, allowing producers to size expiring
     * credentials for the complete queue-to-execution lifecycle.
     */
    val dispatchWaitTimeoutSeconds: Long = DEFAULT_DISPATCH_WAIT_TIMEOUT_SECONDS,
) : IJobDefinition {

    /** Validates the producer-controlled portion of this request before it reaches Kubernetes. */
    fun validate() {
        require(profile.length <= 253 && profile.split('.').all(::isDnsLabel)) {
            "Kubernetes Job profile must be a valid DNS subdomain"
        }
        require(idempotencyKey.isNotBlank()) {
            "Kubernetes Job idempotency key is required"
        }
        require(idempotencyKey.length <= 1_024) {
            "Kubernetes Job idempotency key must not exceed 1024 characters"
        }
        require(dispatchWaitTimeoutSeconds > 0) {
            "Kubernetes Job dispatch wait timeout must be positive"
        }
    }

    private fun isDnsLabel(value: String): Boolean =
        value.length in 1..63 && DNS_LABEL.matches(value)

    companion object {
        /** Default time a request may wait for a matching profile and available capacity. */
        const val DEFAULT_DISPATCH_WAIT_TIMEOUT_SECONDS = 86_400L
        private val DNS_LABEL = Regex("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")
    }
}
