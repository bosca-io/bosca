package bosca.kubernetes.jobs

/**
 * Stable names for the queue that carries requests to create Kubernetes Jobs.
 */
object KubernetesJobQueueNames {
    /** Physical NATS/Redis queue consumed exclusively by the Kubernetes controller. */
    const val queue = "kubernetes-jobs"

    /** DI provider name for producers of Kubernetes Job requests. */
    const val jobQueue = "kubernetesJobs"

    /** Job-catalog identifier for the generic dispatch request. */
    const val dispatch = "kubernetes-jobs-dispatch"
}
