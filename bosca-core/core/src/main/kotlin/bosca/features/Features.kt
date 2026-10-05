package bosca.features

import bosca.di.provideBlockingNoSuspend
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig

/** Resolves a feature flag consistently, defaulting absent configuration to disabled. */
internal fun featureEnabled(config: ApplicationConfig, key: String): Boolean {
    val value = config.propertyOrNull(key) ?: return false
    return value.getString().toBoolean()
}

object Features {

    private val application: BoscaApplication by lazy { provideBlockingNoSuspend() }

    private fun enabled(key: String): Boolean = featureEnabled(application.environment.config, key)

    val community by lazy { enabled("community.enabled") }

    /**
     * Gates the comments module (metadata comments, threaded replies, likes,
     * the moderation status workflow, and async auto-moderation). Defaults off
     * so a fresh `application.yaml` doesn't pull in the comments schema,
     * providers, and GraphQL surface; the default config in bosca-server sets
     * it on.
     */
    val comments by lazy { enabled("comments.enabled") }

    /**
     * Gates the chat + collaboration modules. Both depend on NATS — chat
     * persists messages on JetStream, and collaboration's federation
     * listener / dispatch event bus uses NATS pub/sub — so deployments
     * without NATS in their cluster opt the entire family out by leaving
     * this off. Defaults off so a fresh `application.yaml` doesn't
     * inadvertently require NATS; the default config in bosca-server
     * sets it to true.
     */
    val chat by lazy { enabled("chat.enabled") }

    val introspection by lazy { enabled("introspection.enabled") }

    /**
     * Gates the Work Ops modules (task / project / portfolio management,
     * workflows, boards, sprints, automation, SLAs, etc.). Deployments
     * that don't need project-management features can opt out by setting
     * this to false. Defaults off so a fresh `application.yaml` doesn't
     * inadvertently pull in the workops schema and migrations; the
     * default config in bosca-server sets it to true.
     */
    val workops by lazy { enabled("workops.enabled") }

    /**
     * Gates the ecommerce module (companies, catalogs, products, carts,
     * payments, subscriptions, promotions, inventory/fulfillment, plus the
     * `ecom` queue runner and its scheduled jobs). Deployments that don't
     * sell anything opt out by leaving this off, which drops the ecom
     * schema, providers, GraphQL surface, migrations, and the
     * `ecomQueueRunner` from both the server and the runner. Defaults off so
     * a fresh `application.yaml` doesn't inadvertently pull in commerce; the
     * default config in bosca-server / bosca-runner sets it via
     * `ECOMMERCE_ENABLED`.
     */
    val ecommerce by lazy { enabled("ecommerce.enabled") }

    val gateway by lazy { enabled("gateway.enabled") }

    /**
     * Gates the kubernetes module — cluster registration, workloads, pods,
     * networking, RBAC, helm lifecycle, plus the dedicated UIs for
     * cert-manager / Cilium / CloudNativePG. Defaults off so a fresh
     * `application.yaml` doesn't pull in the kubernetes schema or
     * migrations; deployments that need cluster administration through
     * Bosca Studio set this to true and stand up the companion
     * `kubernetes-controller` service.
     */
    val kubernetes by lazy { enabled("kubernetes.enabled") }

    /** When enabled the server also acts as an analytics processor, consuming events from NATS and writing to Iceberg. */
    val analyticsProcessor by lazy { enabled("analyticsProcessor.enabled") }

    /**
     * Gates the recommender's semantic content embeddings. When on, the index-time pipeline computes a
     * per-metadata embedding via the text-embeddings-inference sidecar and stores it in the
     * `metadata_embeddings` table. Defaults off so deployments without the embedding sidecar (and its
     * gated model) skip embedding computation entirely; the migration's (empty) table is harmless.
     */
    val embeddings by lazy { enabled("embedding.enabled") }
}
