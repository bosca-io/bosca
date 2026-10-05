package bosca.kubernetes

import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Module bootstrap installed by `bosca-server`. Phase 1 has nothing
 * post-init to wire (cluster CRUD is fully covered by the
 * @Provider-registered services and the KSP-generated GraphQL
 * dispatchers). Future work — controller-callback listeners,
 * cluster-health pollers, helm-release pubsub bridges — installs
 * here.
 */
class KubernetesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        // intentionally empty — see class doc.
    }
}
