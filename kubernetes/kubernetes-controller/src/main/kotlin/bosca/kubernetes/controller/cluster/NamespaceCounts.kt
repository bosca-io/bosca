package bosca.kubernetes.controller.cluster

/**
 * Per-namespace count snapshot served from the informer cache. Used
 * by [NamespacesRoute] to fill in the `workloads` / `pods` / `services`
 * fields on each namespace row without fanning out to per-namespace
 * list calls on every request.
 *
 * Zero values are returned for namespaces with no observed resources
 * yet — including the case where the informer cache hasn't fully
 * warmed (the studio renders `0` rather than `-` for both cases,
 * which is acceptable for the brief warm-up window after the first
 * read of a cluster).
 */
data class NamespaceCounts(
    val workloads: Int,
    val pods: Int,
    val services: Int,
) {
    companion object {
        val ZERO = NamespaceCounts(0, 0, 0)
    }
}
