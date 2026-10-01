package bosca.cli.swarm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

private fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
    is Iterable<*> -> JsonArray(value.map(::json))
    else -> error("Unsupported value in Swarm stack: $value")
}
internal fun document(value: Any?) = swarmJson.encodeToString(JsonElement.serializer(), json(value)) + "\n"
private fun stack(
    services: Map<String, Any>,
    volumes: Map<String, Any> = emptyMap(),
    configs: Map<String, Any> = emptyMap(),
) = buildMap {
    put("version", "3.8")
    put("services", services)
    put("networks", mapOf("bosca" to mapOf("external" to true)))
    if (volumes.isNotEmpty()) put("volumes", volumes)
    if (configs.isNotEmpty()) put("configs", configs)
}

/** Where a service runs; the deployment labels the nodes (see `bootstrapNodes`). */
internal enum class Placement(val constraint: String) {
    /** The manager, which holds every bind-mounted data directory. */
    STATEFUL("node.labels.bosca_data == true"),
    /** The workers, so application load never competes with PostgreSQL for memory. */
    APPLICATION("node.labels.bosca_data != true"),
    /** One worker that keeps the recommendation model volume shared by the loader and TensorFlow Serving. */
    MODELS("node.labels.bosca_ml == true"),
}

/** Memory ceiling for a service; CPUs are shared across the worker without per-service caps. */
internal data class Limits(val memory: String)

/** Log rotation as in the Compose deployment: at most three 10 MB files per container. */
private val LOGGING = mapOf("driver" to "json-file", "options" to mapOf("max-size" to "10m", "max-file" to "3"))

/**
 * Swarm deployment policy. Stateless application services start their replacement before stopping the old
 * task and roll back when it never becomes healthy; stateful services and single-writer services stop first.
 * A [configDigest] rolls the service when bind-mounted configuration changes, since Swarm otherwise restarts
 * a service only when its spec changes.
 */
private fun deploy(
    placement: Placement,
    limits: Limits? = null,
    rolling: Boolean = false,
    configDigest: String? = null,
) = buildMap {
    put("placement", mapOf("constraints" to listOf(placement.constraint)))
    put("restart_policy", mapOf("condition" to "on-failure"))
    limits?.let { put("resources", mapOf("limits" to mapOf("memory" to it.memory))) }
    if (rolling) put("update_config", mapOf("order" to "start-first", "failure_action" to "rollback", "monitor" to "30s"))
    configDigest?.let { put("labels", mapOf("io.bosca.config-sha256" to it)) }
}

private fun configDigest(vararg files: String) = sha256(files.joinToString("\u0000"))

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * A liveness check; a failing check restarts the container, so [startPeriod] covers slow start-ups.
 * Checks use small native tools only (bash, BusyBox wget, curl, nc, pg_isready, redis-cli), never an
 * interpreter or runtime such as Node or Python, because they run every interval on every node.
 */
private fun healthcheck(test: List<String>, startPeriod: String, interval: String = "10s", retries: Int = 6) = mapOf(
    "test" to test, "interval" to interval, "timeout" to "5s", "retries" to retries, "start_period" to startPeriod,
)

/** HTTP 200 check for images that have bash but no curl or wget, such as the Debian-based Bosca images. */
internal fun bashHttpCheck(port: Int, path: String) = listOf(
    "CMD", "bash", "-c",
    "exec 3<>/dev/tcp/127.0.0.1/$port && " +
        "printf 'GET $path HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' >&3 && " +
        "head -n 1 <&3 | grep -q ' 200 '",
)

/** TCP connect check for images with bash and no HTTP health endpoint. */
internal fun bashTcpCheck(port: Int) = listOf("CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/$port")

private fun httpGetCheck(tool: String, url: String) = when (tool) {
    "wget" -> listOf("CMD", "wget", "-q", "-O", "/dev/null", url)
    else -> listOf("CMD", "curl", "-fsS", "-o", "/dev/null", url)
}

/** A Swarm config whose name carries a content digest, because Swarm configs cannot change in place. */
private fun swarmConfig(stack: String, key: String, file: String, content: String) =
    key to mapOf("file" to file, "name" to "$stack-$key-${sha256(content).take(12)}")

private fun service(image: String, deploy: Map<String, Any>, options: Map<String, Any?> = emptyMap()): Map<String, Any> =
    mapOf("image" to image, "networks" to listOf("bosca"), "logging" to LOGGING, "deploy" to deploy) +
        // deploy.labels mark the Swarm service; container labels change the task template and restart it.
        (deploy["labels"]?.let { mapOf("labels" to it) } ?: emptyMap()) +
        options.filterValues { it != null }.mapValues { (_, value) -> checkNotNull(value) }

/** Validate generated endpoint URLs before they can be deployed to a Swarm service. */
internal fun validateServiceUrls(services: Map<String, Any>, internalAliases: Set<String>) {
    val internalUrlKeys = setOf(
        "DATABASE_URL", "TRINO_DATABASE_URL", "NATS_HOST", "NATS_MONITORING_URL", "MEILISEARCH_URL",
        "STORAGE_ENDPOINT", "IMAGE_RESIZER_URL", "ICEBERG_DATABASE_URI", "ICEBERG_S3_ENDPOINT",
        "BML_MESSAGE_SERVER_URL", "ANALYTICS_SERVER_URL", "BOSCA_EMBEDDINGS_URL", "ML_TRAINER_URL",
        "RECOMMENDATIONS_TF_SERVING_URL", "API_URL", "COLLABORATION_URL", "GRAPHQL_URL", "NUXT_API_URL",
        "NUXT_WS_URL", "NUXT_GRAPHQL_URL", "NUXT_APOLLO_CLIENTS_DEFAULT_HTTP_ENDPOINT",
        "BML_GRAPHQL_ENDPOINT", "ARTIFACTS_URL", "BOSCA_URL",
    )
    for ((serviceName, definition) in services) {
        val environment = (definition as? Map<*, *>)?.get("environment") as? Map<*, *> ?: continue
        for ((key, rawValue) in environment) {
            val value = rawValue as? String ?: continue
            val endpoint = value.removePrefix("jdbc:")
            if (listOf("http://", "https://", "ws://", "wss://", "nats://", "postgresql://", "trino://")
                    .none(endpoint::startsWith)) continue
            val host = runCatching { URI.create(endpoint.substringBefore('{')).host }.getOrNull()
            require(!host.isNullOrBlank()) { "Invalid URL host for $serviceName.$key: $value" }
            require(key !in internalUrlKeys || '.' in host || host in internalAliases) {
                "Unknown Swarm service host for $serviceName.$key: $host"
            }
        }
    }
}

internal fun infraStack(config: SwarmConfig): Map<String, Any> {
    val root = config.dataRoot
    val images = config.images
    val common = config.secrets
    val services = buildMap<String, Any> {
        put("postgres", service(images.getValue("postgres"), deploy(Placement.STATEFUL), mapOf(
            "environment" to mapOf("POSTGRES_USER" to "bosca_admin", "POSTGRES_PASSWORD" to common.postgresAdmin,
                "POSTGRES_DB" to "postgres", "PGDATA" to "/var/lib/postgresql/18/docker"),
            "command" to listOf("postgres", "-c", "max_connections=${postgresMaxConnections(config)}"),
            "volumes" to listOf("$root/postgres:/var/lib/postgresql"),
            "healthcheck" to healthcheck(listOf("CMD", "pg_isready", "-h", "127.0.0.1", "-U", "bosca_admin", "-d", "postgres"), "60s"),
        )))
        // Mirrors the Kubernetes CloudNativePG Pooler: applications reach PostgreSQL through PgBouncer in
        // transaction mode, so their connection pools never exhaust the shared server.
        put("pgbouncer", service(images.getValue("pgbouncer"),
            deploy(Placement.STATEFUL, configDigest = configDigest(pgbouncerUserlist(config))), mapOf(
            "environment" to mapOf(
                "DATABASES" to pgbouncerDatabases(config),
                "PGBOUNCER_LISTEN_PORT" to PGBOUNCER_PORT.toString(),
                "PGBOUNCER_POOL_MODE" to "transaction",
                "PGBOUNCER_MAX_CLIENT_CONN" to "1000",
                "PGBOUNCER_DEFAULT_POOL_SIZE" to PGBOUNCER_POOL_SIZE.toString(),
                "PGBOUNCER_AUTH_TYPE" to "scram-sha-256",
                "PGBOUNCER_AUTH_FILE" to "/etc/pgbouncer-auth/userlist.txt",
            ),
            "volumes" to listOf("$root/config/pgbouncer/userlist.txt:/etc/pgbouncer-auth/userlist.txt:ro"),
            "healthcheck" to healthcheck(listOf("CMD", "nc", "-z", "127.0.0.1", PGBOUNCER_PORT.toString()), "10s"),
        )))
        put("nats", service(images.getValue("nats"), deploy(Placement.STATEFUL, configDigest = configDigest(natsConfig(config))), mapOf(
            "command" to listOf("-c", "/etc/nats/nats.conf"),
            "volumes" to listOf("$root/nats:/data", "$root/config/nats.conf:/etc/nats/nats.conf:ro"),
            "healthcheck" to healthcheck(httpGetCheck("wget", "http://127.0.0.1:8222/healthz"), "30s"),
        )))
        put("redis", service(images.getValue("redis"), deploy(Placement.STATEFUL), mapOf(
            "command" to listOf("redis-server", "--appendonly", "yes"),
            "volumes" to listOf("$root/redis:/data"),
            "healthcheck" to healthcheck(listOf("CMD-SHELL", "redis-cli ping | grep -q PONG"), "30s"),
        )))
        put("meilisearch", service(images.getValue("meilisearch"), deploy(Placement.STATEFUL), mapOf(
            // Daily consistent snapshots are what the backup copies; the live index files are not.
            "environment" to mapOf("MEILI_ENV" to "production", "MEILI_MASTER_KEY" to common.meilisearchKey,
                "MEILI_NO_ANALYTICS" to "true", "MEILI_SCHEDULE_SNAPSHOT" to "86400",
                "MEILI_SNAPSHOT_DIR" to "/meili_data/snapshots"),
            "volumes" to listOf("$root/meilisearch:/meili_data"),
            "healthcheck" to healthcheck(httpGetCheck("curl", "http://127.0.0.1:7700/health"), "60s"),
        )))
        put("s3proxy", service(images.getValue("s3proxy"), deploy(Placement.STATEFUL), mapOf(
            "environment" to mapOf("S3PROXY_AUTHORIZATION" to "aws-v2-or-v4", "S3PROXY_IDENTITY" to common.s3Access,
                "S3PROXY_CREDENTIAL" to common.s3Secret, "S3PROXY_ENDPOINT" to "http://0.0.0.0:8000",
                "S3PROXY_IGNORE_UNKNOWN_HEADERS" to "true", "JCLOUDS_PROVIDER" to "filesystem-nio2",
                "JCLOUDS_FILESYSTEM_NIO2_BASEDIR" to "/data"),
            "volumes" to listOf("$root/s3:/data"),
            "healthcheck" to healthcheck(bashTcpCheck(8000), "60s"),
        )))
        put("trino", service(images.getValue("trino"), deploy(Placement.STATEFUL,
            configDigest = configDigest(*trinoFiles(config).toSortedMap().map { (path, content) -> "$path\n$content" }.toTypedArray())), mapOf(
            "volumes" to listOf("$root/config/trino:/etc/trino:ro", "$root/trino:/data/trino"),
            "healthcheck" to healthcheck(httpGetCheck("curl", "http://127.0.0.1:8089/v1/info"), "120s"),
        )))
        put("imageprocessor", service(images.getValue("imageprocessor"),
            deploy(Placement.APPLICATION, Limits("1G"), rolling = true), mapOf(
            "healthcheck" to healthcheck(httpGetCheck("wget", "http://127.0.0.1:8003/health"), "30s", interval = "20s"),
        )))
        // Shared by every site, like the single Kubernetes deployment: embeddings carry no site data.
        put("text-embeddings-inference", service(images.getValue("text-embeddings-inference"),
            deploy(Placement.APPLICATION, Limits("6G")), mapOf(
            "command" to listOf("--model-id", "onnx-community/embeddinggemma-300m-ONNX", "--port", "80",
                "--dtype", "float32", "--pooling", "mean"),
            "volumes" to listOf("tei-cache:/data"),
            "healthcheck" to healthcheck(httpGetCheck("curl", "http://127.0.0.1:80/health"), "600s", interval = "20s", retries = 3),
        )))
        putAll(backupServices(config))
    }
    val configs = if (config.backup.repository.isBlank()) emptyMap() else mapOf(
        swarmConfig("infra", "backup-dump", "$root/config/backup/backup-dump.sh", BACKUP_DUMP_SCRIPT),
        swarmConfig("infra", "backup-snapshot", "$root/config/backup/backup.sh", BACKUP_SNAPSHOT_SCRIPT),
    )
    return stack(services, volumes = mapOf("tei-cache" to emptyMap<String, Any>()), configs = configs)
}

/**
 * Nightly backup, present only when a repository is configured: `backup-dump` writes a `pg_dump` of every
 * site database, and `backup` snapshots the dumps plus S3 objects, NATS data, Meilisearch snapshots,
 * Caddy certificates and configuration to the off-VM restic repository 30 minutes later.
 */
internal fun backupServices(config: SwarmConfig): Map<String, Any> {
    val backup = config.backup
    if (backup.repository.isBlank()) return emptyMap()
    val root = config.dataRoot
    val databases = config.sites.flatMap { listOf("${it.id}-bosca", "${it.id}-warehouse") }.joinToString(" ")
    return mapOf(
        "backup-dump" to service(config.images.getValue("postgres"), deploy(Placement.STATEFUL), mapOf(
            "entrypoint" to listOf("/bin/sh", "/scripts/backup-dump.sh"),
            "environment" to mapOf(
                "PGHOST" to "postgres", "PGUSER" to "bosca_admin", "PGPASSWORD" to config.secrets.postgresAdmin,
                "BACKUP_DATABASES" to databases, "BACKUP_HOUR" to "%02d".format(backup.hour),
            ),
            "volumes" to listOf("$root/backups:/backups"),
            "configs" to listOf(mapOf("source" to "backup-dump", "target" to "/scripts/backup-dump.sh", "mode" to 365)),
        )),
        "backup" to service(config.images.getValue("backup"), deploy(Placement.STATEFUL), mapOf(
            "entrypoint" to listOf("/bin/sh", "/scripts/backup.sh"),
            "environment" to mapOf(
                "RESTIC_REPOSITORY" to backup.repository, "RESTIC_PASSWORD" to config.secrets.backupPassword,
                "AWS_ACCESS_KEY_ID" to backup.accessKeyId, "AWS_SECRET_ACCESS_KEY" to backup.secretAccessKey,
                "BACKUP_HOUR" to "%02d".format(backup.hour), "KEEP_DAILY" to backup.keepDaily.toString(),
                "KEEP_WEEKLY" to backup.keepWeekly.toString(), "KEEP_MONTHLY" to backup.keepMonthly.toString(),
            ),
            "volumes" to listOf(
                "$root/backups:/data/postgres-dumps:ro", "$root/s3:/data/s3:ro", "$root/nats:/data/nats:ro",
                "$root/meilisearch/snapshots:/data/meilisearch-snapshots:ro", "$root/caddy-data:/data/caddy-data:ro",
                "$root/config:/data/config:ro", "$root/backup-state:/state",
            ),
            "configs" to listOf(mapOf("source" to "backup-snapshot", "target" to "/scripts/backup.sh", "mode" to 365)),
        )),
    )
}

internal fun siteEnvironment(config: SwarmConfig, site: SwarmSite): Map<String, String> {
    val name = site.id
    val hosts = site.hosts
    val domain = hosts.root
    val studio = "https://${hosts.studio}"
    val common = config.secrets
    val secret = site.secrets
    // Public URLs follow the Helm charts: the base domain serves the API, Studio is the app origin.
    return mapOf(
        "BOSCA_DEVELOPMENT" to "false", "OTEL_SDK_DISABLED" to "true",
        "JWT_DOMAIN" to domain, "JWT_ADMIN_DOMAIN" to "admin.$domain", "JWT_ISSUER" to domain,
        "JWT_SECRET" to secret.jwt, "JWT_COOKIE_SECURE" to "true",
        "SECURITY_ENCRYPTION_KEY" to secret.securityEncryption,
        "DATABASE_URL" to "jdbc:postgresql://pgbouncer:$PGBOUNCER_PORT/$name-bosca", "DATABASE_USER" to name,
        "DATABASE_PASSWORD" to secret.database,
        "TRINO_DATABASE_URL" to "jdbc:trino://trino:8089", "TRINO_DATABASE_USER" to "${name}_admin",
        "TRINO_DATABASE_READONLY_USER" to "${name}_readonly",
        "NATS_HOST" to "nats://nats:4222", "NATS_USERNAME" to name, "NATS_PASSWORD" to secret.nats,
        "NATS_MONITORING_URL" to "http://nats:8222",
        "REDIS_HOST" to "redis", "REDIS_PORT" to "6379", "REDIS_DATABASE" to site.redisDatabase.toString(),
        "REDIS_NAMESPACE" to "${name}_", "CACHE_TYPE" to "nats",
        "MEILISEARCH_URL" to "http://meilisearch:7700", "MEILISEARCH_API_KEY" to common.meilisearchKey,
        "MEILISEARCH_INDEX_PREFIX" to "${name}_",
        "STORAGE_TYPE" to "s3", "STORAGE_BUCKET" to "$name-storage", "STORAGE_ENDPOINT" to "http://s3proxy:8000",
        "STORAGE_ACCESS_KEY_ID" to common.s3Access, "STORAGE_ACCESS_KEY_SECRET" to common.s3Secret,
        "STORAGE_URL_PREFIX" to "https://$domain", "STORAGE_URL_UPLOAD_PREFIX" to "https://${hosts.upload}",
        "STORAGE_URL_SIGNER_SECRET_KEY" to secret.storageSigner,
        "PIPELINE_SECRET_KEY" to secret.pipeline, "APP_URL" to studio, "APP_ALLOWED_ORIGIN" to studio,
        "MAILER_UNSUBSCRIBE_URL" to "https://${hosts.notifications}/unsubscribe",
        "MAILER_PREFERENCES_URL" to "https://${hosts.notifications}/preferences",
        "SECURITY_ALERT_URL" to "https://${hosts.profiles}/security?tab=logins",
        // Every process that installs `security-initial` must see the site's generated passwords; the
        // application defaults are well-known development values.
        "INIT_ADMIN_PASSWORD" to secret.initialAdmin, "INIT_SA_PASSWORD" to secret.initialSa,
        // The server's development default is public, and the base domain exposes /api/* publicly.
        "GATEWAY_PROXY_SHARED_TOKEN" to secret.gatewayProxy,
        // Users sign in through Studio; the application files allow one configurable redirect origin.
        "OAUTH2_ALLOWED_REDIRECT" to studio,
        // Passkeys are registered from Studio, whose origin is not one of the server's default WebAuthn origins.
        "WEBAUTHN_EXTRA_ORIGIN" to studio,
        "GOOGLE_CLIENT_ID" to site.google.clientId, "GOOGLE_CLIENT_SECRET" to site.google.clientSecret,
        "OAUTH2_GOOGLE_REDIRECT" to "https://$domain/oauth2/google/callback",
        "OAUTH2_FACEBOOK_REDIRECT" to "https://$domain/oauth2/facebook/callback",
        // Kubernetes points admin callbacks at admin.<domain>, which this installation does not serve.
        "OAUTH2_GOOGLE_ADMIN_REDIRECT" to "https://$domain/oauth2/google/callback",
        "OAUTH2_FACEBOOK_ADMIN_REDIRECT" to "https://$domain/oauth2/facebook/callback",
        "GIT_URL" to "https://${hosts.git}", "IMAGE_RESIZER_URL" to "http://imageprocessor:8003",
        "EXPERIMENTATION_EVENTS_TABLE" to "${name}_warehouse.bosca.events",
        "EXPERIMENTATION_ASSIGNMENTS_TABLE" to "${name}_bosca.experimentation.assignments",
        "EXPERIMENTATION_POSTGRES_CATALOG" to "${name}_bosca",
        "ICEBERG_CATALOG" to name, "ICEBERG_NAMESPACE" to "bosca", "ICEBERG_TABLE" to "events",
        "ICEBERG_WAREHOUSE_LOCATION" to "s3://$name-warehouse/warehouse",
        "ICEBERG_DATABASE_URI" to "jdbc:postgresql://pgbouncer:$PGBOUNCER_PORT/$name-warehouse",
        "ICEBERG_DATABASE_USER" to "${name}_warehouse", "ICEBERG_DATABASE_PASSWORD" to secret.warehouse,
        "ICEBERG_S3_BUCKET" to "$name-warehouse", "ICEBERG_S3_ENDPOINT" to "http://s3proxy:8000",
        // S3Proxy accepts required S3 checksums, but not the SDK's optional streaming checksum trailer.
        "AWS_REGION" to "us-east-1", "AWS_REQUEST_CHECKSUM_CALCULATION" to "WHEN_REQUIRED",
        "ICEBERG_S3_ACCESS_KEY" to common.s3Access, "ICEBERG_S3_SECRET_KEY" to common.s3Secret,
        "MAILER_TYPE" to site.mailerType, "MAILER_FROM_NAME" to site.mailFromName, "MAILER_FROM_EMAIL" to site.mailFromEmail,
        "BML_MESSAGE_SERVER_URL" to "http://${name}-bml-message-server:9093",
        "ANALYTICS_SERVER_URL" to "http://${name}-collector:8081",
        "CI_KUBERNETES_JOB_PROFILES" to "", "KUBERNETES_ENABLED" to "false",
        "EMBEDDINGS_ENABLED" to "true", "BOSCA_EMBEDDINGS_URL" to "http://text-embeddings-inference:80",
        // Without Kubernetes, training runs on the site's long-lived trainer service.
        "ML_TRAINER_URL" to "http://${name}-recommendation-trainer:8090",
        // Explicit because every site's stack shares one network, where the short name `tf-serving`
        // would resolve to any site's TensorFlow Serving.
        "RECOMMENDATIONS_TF_SERVING_URL" to "http://${name}-tf-serving:8501",
    )
}

private fun webBrandEnvironment(
    fallbackName: String,
    name: String,
    logoUrl: String,
    primaryColor: String,
    accentColor: String,
): Map<String, String> = buildMap {
    put("BRAND_NAME", name.ifBlank { fallbackName })
    if (logoUrl.isNotBlank()) put("BRAND_LOGO_URL", logoUrl)
    if (primaryColor.isNotBlank()) put("BRAND_PRIMARY_COLOR", primaryColor)
    if (accentColor.isNotBlank()) put("BRAND_ACCENT_COLOR", accentColor)
}

internal fun siteStack(config: SwarmConfig, site: SwarmSite): Map<String, Any> {
    val name = site.id
    val hosts = site.hosts
    val images = config.images
    val root = config.dataRoot
    val env = siteEnvironment(config, site)
    val server = "http://${name}-server:8080"
    val artifacts = "http://${name}-artifacts:8084"
    val studio = "https://${hosts.studio}"
    // Mirrors the bosca-studio chart's ConfigMap. Studio appends /graphqlws to the bare WebSocket origin.
    val studioEnv = mapOf(
        "HOST" to "0.0.0.0", "PORT" to "3000",
        "REDIS_HOST" to "redis", "REDIS_PORT" to "6379", "REDIS_PREFIX" to "${name}_hocuspocus",
        "API_URL" to server, "COLLABORATION_URL" to server, "GRAPHQL_URL" to "$server/graphql",
        "SIGNUP_TOKEN_BASE_URL" to "$studio/content/signup-token/{token}",
        "NUXT_PUBLIC_SIGNUP_TOKEN_BASE_URL" to "$studio/content/signup-token/{token}",
        "NUXT_API_URL" to server, "NUXT_WS_URL" to "ws://${name}-server:8080",
        "NUXT_PUBLIC_WS_URL" to "wss://${hosts.studio}", "NUXT_PUBLIC_API_URL" to studio,
        "NUXT_PUBLIC_AUTH_DOMAIN" to hosts.root,
        "NUXT_GRAPHQL_URL" to "$server/graphql", "NUXT_PUBLIC_GRAPHQL_URL" to "$studio/graphql",
        "NUXT_APOLLO_CLIENTS_DEFAULT_HTTP_ENDPOINT" to "$server/graphql",
        "NUXT_PUBLIC_APOLLO_CLIENTS_DEFAULT_HTTP_ENDPOINT" to "$studio/graphql",
        "GIT_SERVER_URL" to "https://${hosts.git}", "GIT_BASE_URL" to "/",
        "NUXT_PUBLIC_GIT_SERVER_URL" to "https://${hosts.git}", "NUXT_PUBLIC_GIT_BASE_URL" to "/",
        "ARTIFACTS_URL" to "https://${hosts.artifacts}", "NUXT_PUBLIC_ARTIFACTS_URL" to "https://${hosts.artifacts}",
    )
    val bmlEnv = mapOf(
        "PORT" to "9093", "BML_MESSAGE_ARTIFACTS_URL" to site.bmlArtifactsUrl,
        "BML_MESSAGE_ARTIFACTS_TOKEN" to site.bmlArtifactsToken,
        "BML_MESSAGE_PROJECTS" to "bosca-messages", "BML_GRAPHQL_ENDPOINT" to "$server/graphql",
        "BML_MESSAGE_PUBLIC_URL" to "https://${hosts.messages}",
        "BML_MESSAGE_CACHE_DIR" to "/tmp/message-cache",
    )
    // Mirrors the tf-serving chart: the loader fetches promoted models from the artifacts server into the
    // shared volume, and serve.sh restarts TensorFlow Serving when the selected versions change.
    val modelEnv = mapOf(
        "ARTIFACTS_URL" to artifacts, "ARTIFACTS_NAMESPACE" to "model", "BOSCA_URL" to server,
        "BOSCA_API_TOKEN" to site.ml.boscaToken, "ARTIFACTS_API_TOKEN" to site.ml.artifactsPullToken,
        "CONTENT_MODEL_DIR" to "/models/recommender-content",
        "PERSONALIZED_MODEL_DIR" to "/models/recommender-personalized", "POLL_INTERVAL" to "15",
    )
    val trainerEnv = mapOf(
        "BOSCA_URL" to server, "BOSCA_API_TOKEN" to site.ml.boscaToken,
        "ARTIFACTS_URL" to artifacts, "ARTIFACTS_NAMESPACE" to "model",
        "ARTIFACTS_API_TOKEN" to site.ml.artifactsPushToken, "ARTIFACTS_PULL_API_TOKEN" to site.ml.artifactsPullToken,
    )
    fun bosca(image: String, environment: Map<String, String>, port: Int, startPeriod: String, limits: Limits?) =
        service(images.getValue(image), deploy(Placement.APPLICATION, limits, rolling = true), mapOf(
            "environment" to environment,
            "healthcheck" to healthcheck(bashHttpCheck(port, "/api/v1/live"), startPeriod),
        ))
    val services = mapOf(
        // Memory limits follow the production Helm values; the runner has none there either.
        "server" to bosca("server", env + mapOf("GIT_SYNC_LISTENERS" to "false", "SCHEDULER_ENABLED" to "true"),
            8080, "150s", Limits("768M")),
        // The runner binds its port only after migrations and package installs finish.
        "runner" to bosca("runner", env + mapOf("BOSCA_SERVER_PORT" to "8080", "GIT_SYNC_LISTENERS" to "true", "SCHEDULER_ENABLED" to "false",
            "ANALYTICS_PROCESSOR_ENABLED" to "true"), 8080, "300s", null),
        "collector" to bosca("collector", env + mapOf("BOSCA_SERVER_PORT" to "8081"), 8081, "120s", Limits("512M")),
        "git" to bosca("git", env, 8091, "120s", Limits("4G")),
        "artifacts" to bosca("artifacts", env + mapOf("BOSCA_SERVER_PORT" to "8084", "ANALYTICS_APP_ID" to "bosca-artifacts-server"),
            8084, "120s", Limits("2G")),
        // The message server binds its port only after fetching and validating every seed project.
        "bml-message-server" to bosca("bml", bmlEnv, 9093, "300s", Limits("1G")),
        "studio" to service(images.getValue("studio"), deploy(Placement.APPLICATION, Limits("1536M"), rolling = true), mapOf(
            "environment" to studioEnv,
            "healthcheck" to healthcheck(httpGetCheck("wget", "http://127.0.0.1:3000/health"), "60s", interval = "20s"),
        )),
        "profiles-web" to service(images.getValue("profiles-web"), deploy(Placement.APPLICATION, Limits("512M"), rolling = true), mapOf(
            "environment" to (mapOf(
                "BML_GRAPHQL_ENDPOINT" to "$server/graphql",
                "PROFILES_WEB_PUBLIC_URL" to "https://${hosts.profiles}",
                "PROFILES_WEB_COOKIE_DOMAIN" to site.profilesWeb.cookieDomain.ifBlank { hosts.root },
            ) + webBrandEnvironment(site.mailFromName, site.profilesWeb.brandName,
                site.profilesWeb.logoUrl, site.profilesWeb.primaryColor, site.profilesWeb.accentColor)),
            "healthcheck" to healthcheck(bashHttpCheck(9095, "/login"), "30s"),
        )),
        "notifications-web" to service(images.getValue("notifications-web"), deploy(Placement.APPLICATION, Limits("512M"), rolling = true), mapOf(
            "environment" to (mapOf("BML_GRAPHQL_ENDPOINT" to "$server/graphql") +
                webBrandEnvironment(site.mailFromName, site.notificationsWeb.brandName,
                    site.notificationsWeb.logoUrl, site.notificationsWeb.primaryColor, site.notificationsWeb.accentColor)),
            "healthcheck" to healthcheck(bashHttpCheck(9094, "/"), "30s"),
        )),
        "recommendation-trainer" to service(images.getValue("recommendation-trainer"),
            deploy(Placement.MODELS, Limits("4G")), mapOf(
            // Exports are staged in the container filesystem, like the Kubernetes Job's emptyDir.
            "environment" to trainerEnv,
            "healthcheck" to healthcheck(bashHttpCheck(8090, "/health"), "60s"),
        )),
        "recommendation-model-loader" to service(images.getValue("recommendation-model-loader"),
            deploy(Placement.MODELS, Limits("256M")), mapOf(
            "environment" to modelEnv,
            "volumes" to listOf("models:/models"),
        )),
        "tf-serving" to service(images.getValue("tf-serving"), deploy(Placement.MODELS, Limits("2G")), mapOf(
            "entrypoint" to listOf("/bin/bash", "/config/serve.sh"),
            "environment" to mapOf("MODELS_ROOT" to "/models", "MODEL_NAMES" to "recommender-content recommender-personalized",
                "REST_PORT" to "8501", "GRPC_PORT" to "8500", "POLL_WAIT_SECONDS" to "5"),
            "volumes" to listOf("models:/models:ro"),
            "configs" to listOf(mapOf("source" to "tf-serving-serve", "target" to "/config/serve.sh", "mode" to 365)),
            "healthcheck" to healthcheck(bashTcpCheck(8501), "60s"),
        )),
    ) + if (site.rootImage.isNotBlank()) mapOf(
        "root-web" to service(site.rootImage, deploy(Placement.APPLICATION, rolling = true), mapOf(
            "environment" to mapOf("BML_GRAPHQL_ENDPOINT" to "$server/graphql"),
        )),
    ) else emptyMap()
    @Suppress("UNCHECKED_CAST")
    val infraServices = infraStack(config)["services"] as Map<String, Any>
    validateServiceUrls(services, infraServices.keys + services.keys.map { "$name-$it" })
    return stack(
        services.mapValues { (serviceName, definition) ->
            definition + ("networks" to mapOf("bosca" to mapOf("aliases" to listOf("$name-$serviceName"))))
        },
        volumes = mapOf("models" to emptyMap<String, Any>()),
        configs = mapOf(swarmConfig(name, "tf-serving-serve", "$root/config/tf-serving/serve.sh", TF_SERVING_SERVE_SCRIPT)),
    )
}

internal fun edgeStack(config: SwarmConfig): Map<String, Any> {
    val root = config.dataRoot
    return stack(mapOf("caddy" to service(config.images.getValue("caddy"),
        deploy(Placement.STATEFUL, configDigest = configDigest(caddyfile(config))), mapOf(
        "ports" to listOf(80, 443).map { mapOf("target" to it, "published" to it, "protocol" to "tcp", "mode" to "host") },
        "volumes" to listOf("$root/config/Caddyfile:/etc/caddy/Caddyfile:ro", "$root/caddy-data:/data", "$root/caddy-config:/config"),
        "healthcheck" to healthcheck(httpGetCheck("wget", "http://127.0.0.1:2019/config/"), "30s"),
    ))))
}

/** Caddy matcher equivalent to a Gateway API `PathPrefix`, which matches whole path elements. */
private fun pathPrefix(vararg prefixes: String) = prefixes.joinToString(" ") { "$it $it/*" }

/**
 * One Caddy site block per public hostname, mirroring the Kubernetes HTTPRoutes: bosca-server owns the base
 * domain and its api/upload/ws hosts, Studio's host forwards its same-origin API paths, Profiles owns
 * its host, Notifications owns its host, and the messages host keeps the message server's asset and
 * tracking paths alongside existing notification links. The artifacts registry owns its host.
 * `route` keeps match order explicit.
 */
internal fun caddyfile(config: SwarmConfig) = config.sites.joinToString("\n\n", postfix = "\n") { site ->
    val name = site.id
    val hosts = site.hosts
    val serverHosts = if (site.rootImage.isBlank()) hosts.server else hosts.server.drop(1)
    val website = if (site.rootImage.isBlank()) "" else """${hosts.root}, ${hosts.www} {
    @server path ${pathPrefix("/graphql", "/oauth2", "/api/v1", "/files", "/content", "/graphqlws")}
    route {
        reverse_proxy @server ${name}-server:8080
        reverse_proxy ${name}-root-web:${site.rootPort}
    }
}

"""
    """${serverHosts.joinToString(", ")} {
    reverse_proxy ${name}-server:8080
}

${website}${hosts.studio} {
    @collector path ${pathPrefix("/api/v1/events", "/api/v1/installation")}
    @server path ${pathPrefix("/graphql", "/oauth2", "/api/v1", "/files", "/content", "/graphqlws")}
    route {
        reverse_proxy @collector ${name}-collector:8081
        reverse_proxy @server ${name}-server:8080
        reverse_proxy ${name}-studio:3000
    }
}

${hosts.profiles} {
    reverse_proxy ${name}-profiles-web:9095
}

${hosts.analytics} {
    reverse_proxy ${name}-collector:8081
}

${hosts.git} {
    reverse_proxy ${name}-git:8091
}

${hosts.artifacts} {
    reverse_proxy ${name}-artifacts:8084
}

${hosts.notifications} {
    reverse_proxy ${name}-notifications-web:9094
}

${hosts.messages} {
    @public path ${pathPrefix("/assets", "/c", "/o")}
    route {
        reverse_proxy @public ${name}-bml-message-server:9093
        reverse_proxy ${name}-notifications-web:9094
    }
}"""
}

internal const val PGBOUNCER_PORT = 6432

/** Server connections PgBouncer keeps per database and role, as in the Kubernetes Pooler. */
internal const val PGBOUNCER_POOL_SIZE = 25

/**
 * PostgreSQL connection limit: PgBouncer's pools for each site's operational and warehouse databases, plus
 * headroom for Trino (which reads PostgreSQL directly, as Kubernetes Trino reads the replica), the Iceberg
 * catalog in Trino, bootstrap sessions and administration. Never below the previous fixed limit.
 */
internal fun postgresMaxConnections(config: SwarmConfig) = maxOf(200, config.sites.size * 2 * PGBOUNCER_POOL_SIZE + 50)

/**
 * `[databases]` entries for the image's `DATABASES` variable. No credentials are embedded: PgBouncer logs
 * in to PostgreSQL as the connecting role, so each role still reaches only the databases it may connect to.
 * The image's PgBouncer 1.25.2 parser accepts hyphenated database keys without quotes.
 */
internal fun pgbouncerDatabases(config: SwarmConfig) = config.sites.flatMap { site ->
    listOf("${site.id}-bosca", "${site.id}-warehouse")
}.joinToString(",") { database -> "$database = host=postgres port=5432 dbname=$database" }

/** SCRAM needs the plain passwords: PgBouncer authenticates clients and then logs in to PostgreSQL with them. */
internal fun pgbouncerUserlist(config: SwarmConfig) = config.sites.joinToString("") { site ->
    "\"${site.id}\" \"${site.secrets.database}\"\n\"${site.id}_warehouse\" \"${site.secrets.warehouse}\"\n"
}

private fun natsConfig(config: SwarmConfig): String = buildString {
    appendLine("listen: 0.0.0.0:4222\nhttp: 8222\nmax_payload: 5Mb")
    appendLine("jetstream {\n  store_dir: /data/jetstream\n  max_mem_store: 1Gb\n  max_file_store: 10Gb\n}")
    appendLine("accounts {")
    for (site in config.sites) {
        appendLine("  ${site.id} {")
        appendLine("    jetstream: enabled")
        appendLine("    users: [{user: \"${site.id}\", password: \"${site.secrets.nats}\"}]")
        appendLine("  }")
    }
    appendLine("}")
}

private fun sqlLiteral(value: String) = "'" + value.replace("'", "''") + "'"
private fun databaseBootstrap(config: SwarmConfig): String = buildString {
    appendLine("#!/bin/sh\nset -eu\nexport PGPASSWORD=\"\$POSTGRES_PASSWORD\"")
    appendLine("psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U \"\$POSTGRES_USER\" -d postgres <<'SQL'")
    for (site in config.sites) {
        for ((suffix, role, password) in listOf(
            Triple("bosca", site.id, site.secrets.database),
            Triple("warehouse", "${site.id}_warehouse", site.secrets.warehouse),
        )) {
            val database = "${site.id}-$suffix"
            appendLine("SELECT 'CREATE ROLE $role LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role')")
            appendLine("\\gexec")
            appendLine("ALTER ROLE $role PASSWORD ${sqlLiteral(password)};")
            appendLine("SELECT 'CREATE DATABASE \"$database\" OWNER $role' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$database')")
            appendLine("\\gexec")
            appendLine("ALTER DATABASE \"$database\" OWNER TO $role;")
            appendLine("REVOKE CONNECT ON DATABASE \"$database\" FROM PUBLIC;")
            appendLine("GRANT CONNECT ON DATABASE \"$database\" TO $role;")
            if (suffix == "bosca") {
                // Backup restore sets this superuser parameter while it bulk-loads (PostgreSQL 15+).
                appendLine("GRANT SET ON PARAMETER session_replication_role TO $role;")
            }
        }
    }
    appendLine("SQL")
    for (site in config.sites) {
        for (extension in listOf("unaccent", "vector")) {
            appendLine("psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U \"\$POSTGRES_USER\" -d \"${site.id}-bosca\" -c \"CREATE EXTENSION IF NOT EXISTS $extension\"")
        }
    }
}

/** Changes only when the database bootstrap SQL or its site credentials change. */
internal fun databaseBootstrapFingerprint(config: SwarmConfig) = sha256(databaseBootstrap(config))

/** Trino configuration files keyed by their path below `config/trino`. */
private fun trinoFiles(config: SwarmConfig): Map<String, String> = buildMap {
    put("config.properties", "coordinator=true\nnode-scheduler.include-coordinator=true\nhttp-server.http.port=8089\ndiscovery.uri=http://localhost:8089\nquery.max-memory=2GB\nquery.max-memory-per-node=1GB\n")
    put("jvm.config", "-server\n-Xmx2G\n-XX:+UseG1GC\n")
    put("node.properties", "node.environment=bosca\nnode.data-dir=/data/trino\n")
    put("log.properties", "io.trino=INFO\n")
    put("access-control.properties", "access-control.name=file\nsecurity.config-file=/etc/trino/rules.json\n")
    val rules = mutableListOf<Map<String, String>>()
    for (site in config.sites) {
        val name = site.id
        for ((role, permission) in listOf("admin" to "all", "readonly" to "read-only")) {
            rules += mapOf("user" to "^${name}_$role$", "catalog" to "^${name}_(bosca|warehouse)$", "allow" to permission)
        }
        put("catalog/${name}_bosca.properties", """connector.name=postgresql
connection-url=jdbc:postgresql://postgres:5432/$name-bosca
connection-user=$name
connection-password=${site.secrets.database}
postgresql.array-mapping=AS_ARRAY
""")
        put("catalog/${name}_warehouse.properties", """connector.name=iceberg
iceberg.catalog.type=jdbc
iceberg.jdbc-catalog.catalog-name=$name
iceberg.jdbc-catalog.driver-class=org.postgresql.Driver
iceberg.jdbc-catalog.connection-url=jdbc:postgresql://postgres:5432/$name-warehouse
iceberg.jdbc-catalog.connection-user=${name}_warehouse
iceberg.jdbc-catalog.connection-password=${site.secrets.warehouse}
iceberg.jdbc-catalog.default-warehouse-dir=s3://$name-warehouse/warehouse
fs.native-s3.enabled=true
s3.region=us-east-1
s3.endpoint=http://s3proxy:8000
s3.path-style-access=true
s3.aws-access-key=${config.secrets.s3Access}
s3.aws-secret-key=${config.secrets.s3Secret}
""")
    }
    rules += mapOf("catalog" to ".*", "allow" to "none")
    put("rules.json", document(mapOf("catalogs" to rules)))
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

internal fun renderSwarm(config: SwarmConfig, destination: Path): Path {
    val marker = destination.resolve(".bosca-swarm-generated")
    require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
        "Refusing to replace unmanaged output directory: $destination"
    }
    Files.createDirectories(destination.parent)
    val staging = destination.resolveSibling(".${destination.fileName}.new-${UUID.randomUUID()}")
    val backup = destination.resolveSibling(".${destination.fileName}.old-${UUID.randomUUID()}")
    try {
        writeBundle(config, staging)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) Files.move(destination, backup)
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) Files.move(backup, destination)
            throw e
        }
        deleteTree(backup)
    } finally {
        deleteTree(staging)
    }
    return destination
}

private fun writeBundle(config: SwarmConfig, destination: Path) {
    Files.createDirectories(destination)
    try {
        Files.setPosixFilePermissions(destination, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        ))
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX file systems do not expose these permissions.
    }
    privateWrite(destination.resolve(".bosca-swarm-generated"), "Generated by bosca swarm. Contains secrets.\n")
    privateWrite(destination.resolve("stacks/infra.yml"), document(infraStack(config)))
    privateWrite(destination.resolve("stacks/edge.yml"), document(edgeStack(config)))
    config.sites.forEach { privateWrite(destination.resolve("stacks/${it.id}.yml"), document(siteStack(config, it))) }
    privateWrite(destination.resolve("config/Caddyfile"), caddyfile(config))
    privateWrite(destination.resolve("config/nats.conf"), natsConfig(config))
    privateWrite(destination.resolve("config/pgbouncer/userlist.txt"), pgbouncerUserlist(config))
    privateWrite(destination.resolve("config/tf-serving/serve.sh"), TF_SERVING_SERVE_SCRIPT)
    if (config.backup.repository.isNotBlank()) {
        privateWrite(destination.resolve("config/backup/backup-dump.sh"), BACKUP_DUMP_SCRIPT)
        privateWrite(destination.resolve("config/backup/backup.sh"), BACKUP_SNAPSHOT_SCRIPT)
    }
    privateWrite(destination.resolve("config/db-bootstrap.sh"), databaseBootstrap(config))
    trinoFiles(config).forEach { (path, content) -> privateWrite(destination.resolve("config/trino/$path"), content) }
    privateWrite(destination.resolve("buckets.json"), document(config.sites.flatMap { listOf("${it.id}-storage", "${it.id}-warehouse") }))
}
