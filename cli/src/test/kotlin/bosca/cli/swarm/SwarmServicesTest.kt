package bosca.cli.swarm

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
class SwarmServicesTest {
    private val config = SwarmConfig().withSecrets()
    private val site = config.sites.first()

    private fun services(stack: Map<String, Any>) = stack["services"] as Map<String, Map<String, Any>>
    private fun deployOf(service: Map<String, Any>) = service["deploy"] as Map<String, Any>
    private fun constraint(service: Map<String, Any>) =
        ((deployOf(service)["placement"] as Map<String, Any>)["constraints"] as List<String>).single()
    private fun limits(service: Map<String, Any>) =
        ((deployOf(service)["resources"] as Map<String, Any>?)?.get("limits") as Map<String, String>?)
    private fun environment(service: Map<String, Any>) = service["environment"] as Map<String, String>

    private fun allServices(config: SwarmConfig) =
        services(infraStack(config)) + services(edgeStack(config)) +
            config.sites.flatMap { services(siteStack(config, it)).entries }.associate { it.key to it.value }

    @Test
    fun `stateful services stay on the manager and application services on the workers`() {
        val infra = services(infraStack(config))
        listOf("postgres", "pgbouncer", "nats", "redis", "meilisearch", "s3proxy", "trino").forEach {
            assertEquals("node.labels.bosca_data == true", constraint(infra.getValue(it)), it)
        }
        listOf("imageprocessor", "text-embeddings-inference").forEach {
            assertEquals("node.labels.bosca_data != true", constraint(infra.getValue(it)), it)
        }
        assertEquals("node.labels.bosca_data == true", constraint(services(edgeStack(config)).getValue("caddy")))
        val site = services(siteStack(config, site))
        listOf("server", "runner", "collector", "git", "artifacts", "studio", "bml-message-server",
            "profiles-web", "notifications-web").forEach {
            assertEquals("node.labels.bosca_data != true", constraint(site.getValue(it)), it)
        }
        // The loader and TensorFlow Serving share a node-local volume, so they must land on the same node.
        listOf("recommendation-trainer", "recommendation-model-loader", "tf-serving").forEach {
            assertEquals("node.labels.bosca_ml == true", constraint(site.getValue(it)), it)
        }
    }

    @Test
    fun `internal URLs use valid hosts and site services have unique network aliases`() {
        val urls = siteEnvironment(config, site)
        assertEquals("us-east-1", urls["AWS_REGION"])
        assertEquals("WHEN_REQUIRED", urls["AWS_REQUEST_CHECKSUM_CALCULATION"])
        listOf(
            "DATABASE_URL", "TRINO_DATABASE_URL", "NATS_HOST", "NATS_MONITORING_URL",
            "MEILISEARCH_URL", "STORAGE_ENDPOINT", "IMAGE_RESIZER_URL", "ICEBERG_DATABASE_URI",
            "ICEBERG_S3_ENDPOINT", "BML_MESSAGE_SERVER_URL", "ANALYTICS_SERVER_URL",
            "BOSCA_EMBEDDINGS_URL", "ML_TRAINER_URL", "RECOMMENDATIONS_TF_SERVING_URL",
        ).forEach { key ->
            assertTrue(URI(urls.getValue(key).removePrefix("jdbc:")).host != null, key)
        }
        config.sites.forEach { site ->
            services(siteStack(config, site)).forEach { (serviceName, definition) ->
                val networks = definition["networks"] as Map<String, Map<String, List<String>>>
                assertEquals(listOf("${site.id}-$serviceName"), networks.getValue("bosca").getValue("aliases"))
            }
        }
    }

    @Test
    fun `each site's selected mail provider reaches its server and runner`() {
        val mailgunSite = site.copy(mailerType = "mailgun")
        val stack = services(siteStack(config.copy(sites = listOf(mailgunSite)), mailgunSite))
        assertEquals("mailgun", environment(stack.getValue("server"))["MAILER_TYPE"])
        assertEquals("mailgun", environment(stack.getValue("runner"))["MAILER_TYPE"])
        assertEquals(mailgunSite.mailFromEmail, environment(stack.getValue("server"))["MAILER_FROM_EMAIL"])
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = listOf(mailgunSite.copy(mailerType = "other"))).validate()
        }
    }

    @Test
    fun `memory limits follow the production Helm values while CPUs remain uncapped`() {
        val site = services(siteStack(config, site))
        val expected = mapOf(
            "server" to "768M", "collector" to "512M", "git" to "4G",
            "artifacts" to "2G", "studio" to "1536M", "bml-message-server" to "1G",
            "profiles-web" to "512M", "notifications-web" to "512M",
            "recommendation-trainer" to "4G", "recommendation-model-loader" to "256M",
            "tf-serving" to "2G",
        )
        expected.forEach { (name, limit) ->
            assertEquals(mapOf("memory" to limit), limits(site.getValue(name)), name)
        }
        assertNull(limits(site.getValue("runner")), "production sets no runner limit")
        val infra = services(infraStack(config))
        assertEquals(mapOf("memory" to "6G"), limits(infra.getValue("text-embeddings-inference")))
        assertEquals(mapOf("memory" to "1G"), limits(infra.getValue("imageprocessor")))
        allServices(config).forEach { (name, service) ->
            assertFalse(limits(service)?.containsKey("cpus") == true, name)
        }
    }

    @Test
    fun `server memory limits can be increased independently for each site`() {
        val largerSite = site.copy(serverMemory = "1536M")
        val configured = config.copy(sites = listOf(largerSite, config.sites[1]))
        configured.validate()
        assertEquals(mapOf("memory" to "1536M"), limits(services(siteStack(configured, largerSite)).getValue("server")))
        assertEquals(mapOf("memory" to "768M"), limits(services(siteStack(configured, configured.sites[1])).getValue("server")))
        listOf("", "0", "-1G", "1G\n", "invalid").forEach { memory ->
            assertFailsWith<IllegalArgumentException> {
                config.copy(sites = listOf(site.copy(serverMemory = memory))).validate()
            }
        }
    }

    @Test
    fun `every service rotates its logs and stateless application services roll start-first`() {
        allServices(config).forEach { (name, service) ->
            assertEquals(
                mapOf("driver" to "json-file", "options" to mapOf("max-size" to "10m", "max-file" to "3")),
                service["logging"],
                name,
            )
        }
        val rolling = setOf("server", "runner", "collector", "git", "artifacts", "studio", "bml-message-server",
            "profiles-web", "notifications-web", "imageprocessor")
        allServices(config).forEach { (name, service) ->
            val update = deployOf(service)["update_config"] as Map<String, String>?
            if (name in rolling) {
                assertEquals("start-first", update?.get("order"), name)
                assertEquals("rollback", update?.get("failure_action"), name)
            } else {
                assertNull(update, "$name stops before it starts a replacement")
            }
        }
    }

    @Test
    fun `health checks cover every service whose image provides a probe`() {
        // The model loader serves no HTTP, and production gives it no probe either.
        val unchecked = setOf("recommendation-model-loader")
        allServices(config).forEach { (name, service) ->
            if (name in unchecked) assertNull(service["healthcheck"], name) else assertTrue(service.containsKey("healthcheck"), name)
        }
        val site = services(siteStack(config, site))
        val server = site.getValue("server")["healthcheck"] as Map<String, Any>
        assertEquals(bashHttpCheck(8080, "/api/v1/live"), server["test"])
        assertEquals("150s", server["start_period"])
        assertEquals("8080", (site.getValue("runner")["environment"] as Map<String, String>)["BOSCA_SERVER_PORT"])
        assertEquals(bashHttpCheck(8080, "/api/v1/live"), (site.getValue("runner")["healthcheck"] as Map<String, Any>)["test"])
        assertEquals("300s", (site.getValue("runner")["healthcheck"] as Map<String, Any>)["start_period"])
        assertEquals(bashHttpCheck(8084, "/api/v1/live"), (site.getValue("artifacts")["healthcheck"] as Map<String, Any>)["test"])
        assertEquals(bashHttpCheck(8090, "/health"), (site.getValue("recommendation-trainer")["healthcheck"] as Map<String, Any>)["test"])
        assertEquals(bashHttpCheck(9095, "/login"), (site.getValue("profiles-web")["healthcheck"] as Map<String, Any>)["test"])
        assertEquals(bashHttpCheck(9094, "/"), (site.getValue("notifications-web")["healthcheck"] as Map<String, Any>)["test"])
    }

    @Test
    fun `health checks never start an interpreter or runtime`() {
        val lightweight = setOf("bash", "wget", "curl", "nc", "pg_isready", "redis-cli ping | grep -q PONG")
        allServices(config.copy(backup = SwarmBackup("sftp:backup@example.invalid:/bosca"))).forEach { (name, service) ->
            val test = (service["healthcheck"] as Map<String, Any>?)?.get("test") as List<String>? ?: return@forEach
            assertTrue(test[1] in lightweight, "$name uses ${test[1]}")
            val command = test.joinToString(" ")
            listOf("node", "python", "java", "ruby").forEach { runtime ->
                assertFalse(Regex("""\b$runtime\b""").containsMatchIn(command), "$name starts $runtime")
            }
        }
    }

    @Test
    fun `the bash HTTP check passes only on a 200 response`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/v1/live") { exchange -> exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.createContext("/api/v1/down") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
        server.start()
        try {
            fun run(check: List<String>) = ProcessBuilder(check.drop(1)).redirectErrorStream(true).start().waitFor()
            val port = server.address.port
            assertEquals(0, run(bashHttpCheck(port, "/api/v1/live")))
            assertNotEquals(0, run(bashHttpCheck(port, "/api/v1/down")))
            assertEquals(0, run(bashTcpCheck(port)))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `embeddings, training and model serving are wired per site`() {
        val environment = siteEnvironment(config, site)
        assertEquals("true", environment["EMBEDDINGS_ENABLED"])
        assertEquals("http://text-embeddings-inference:80", environment["BOSCA_EMBEDDINGS_URL"])
        assertEquals("http://site1-recommendation-trainer:8090", environment["ML_TRAINER_URL"])
        assertEquals("http://site1-tf-serving:8501", environment["RECOMMENDATIONS_TF_SERVING_URL"])
        assertEquals("false", environment["KUBERNETES_ENABLED"])

        val tei = services(infraStack(config)).getValue("text-embeddings-inference")
        assertEquals(
            listOf("--model-id", "onnx-community/embeddinggemma-300m-ONNX", "--port", "80", "--dtype", "float32", "--pooling", "mean"),
            tei["command"],
        )

        val withTokens = site.copy(ml = SwarmSiteMl("bosca-token", "push-token", "pull-token"))
        val stack = siteStack(config.copy(sites = listOf(withTokens)), withTokens)
        val site = services(stack)
        val trainer = environment(site.getValue("recommendation-trainer"))
        assertEquals("http://site1-server:8080", trainer["BOSCA_URL"])
        assertEquals("http://site1-artifacts:8084", trainer["ARTIFACTS_URL"])
        assertEquals("bosca-token", trainer["BOSCA_API_TOKEN"])
        assertEquals("push-token", trainer["ARTIFACTS_API_TOKEN"])
        assertEquals("pull-token", trainer["ARTIFACTS_PULL_API_TOKEN"])
        val loader = site.getValue("recommendation-model-loader")
        assertEquals("pull-token", environment(loader)["ARTIFACTS_API_TOKEN"])
        assertEquals(listOf("models:/models"), loader["volumes"])
        val serving = site.getValue("tf-serving")
        assertEquals(listOf("models:/models:ro"), serving["volumes"])
        assertEquals(listOf("/bin/bash", "/config/serve.sh"), serving["entrypoint"])
        val configs = stack["configs"] as Map<String, Map<String, String>>
        val serve = configs.getValue("tf-serving-serve")
        assertEquals("/srv/bosca/config/tf-serving/serve.sh", serve["file"])
        assertTrue(serve.getValue("name").matches(Regex("site1-tf-serving-serve-[0-9a-f]{12}")))
    }

    @Test
    fun `the TensorFlow Serving script matches the Helm chart`() {
        val chart = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("helm/tf-serving/files/serve.sh") }
            .firstOrNull(Files::isRegularFile)
        // The CLI can build outside the workspace; the comparison runs wherever the chart is present.
        if (chart != null) assertEquals(Files.readString(chart), TF_SERVING_SERVE_SCRIPT)
        assertTrue(TF_SERVING_SERVE_SCRIPT.startsWith("#!/usr/bin/env bash"))
        assertTrue(TF_SERVING_SERVE_SCRIPT.contains("tensorflow_model_server"))
    }

    @Test
    fun `each site runs its own artifacts server on its artifacts host`() {
        val artifacts = services(siteStack(config, site)).getValue("artifacts")
        val environment = environment(artifacts)
        assertEquals("8084", environment["BOSCA_SERVER_PORT"])
        assertEquals("jdbc:postgresql://pgbouncer:6432/site1-bosca", environment["DATABASE_URL"])
        assertEquals("site1", environment["NATS_USERNAME"])
        assertEquals("site1-storage", environment["STORAGE_BUCKET"])
        assertEquals("artifacts.site1.example.invalid", site.hosts.artifacts)
        assertTrue(caddyfile(config).contains("artifacts.site1.example.invalid {\n    reverse_proxy site1-artifacts:8084\n}"))
        val studio = environment(services(siteStack(config, site)).getValue("studio"))
        assertEquals("https://artifacts.site1.example.invalid", studio["NUXT_PUBLIC_ARTIFACTS_URL"])
    }

    @Test
    fun `backups run only when a repository is configured`() {
        assertTrue(backupServices(config).isEmpty())
        assertFalse(infraStack(config).containsKey("configs"))
        assertFalse(expectedServices(config).any { it.startsWith("infra_backup") })

        val backedUp = config.copy(backup = SwarmBackup("s3:https://s3.example.invalid/bucket/bosca", "key", "secret", hour = 4))
        val infra = infraStack(backedUp)
        val dump = services(infra).getValue("backup-dump")
        assertEquals("site1-bosca site1-warehouse site2-bosca site2-warehouse", environment(dump)["BACKUP_DATABASES"])
        assertEquals("04", environment(dump)["BACKUP_HOUR"])
        assertEquals("postgres", environment(dump)["PGHOST"], "dumps bypass the pooler")
        val snapshot = services(infra).getValue("backup")
        assertEquals("s3:https://s3.example.invalid/bucket/bosca", environment(snapshot)["RESTIC_REPOSITORY"])
        assertEquals(backedUp.secrets.backupPassword, environment(snapshot)["RESTIC_PASSWORD"])
        assertEquals("7", environment(snapshot)["KEEP_DAILY"])
        assertTrue((snapshot["volumes"] as List<String>).containsAll(listOf(
            "/srv/bosca/backups:/data/postgres-dumps:ro", "/srv/bosca/s3:/data/s3:ro",
            "/srv/bosca/meilisearch/snapshots:/data/meilisearch-snapshots:ro", "/srv/bosca/backup-state:/state",
        )))
        val configs = infra["configs"] as Map<String, Map<String, String>>
        assertEquals(setOf("backup-dump", "backup-snapshot"), configs.keys)
        assertTrue(expectedServices(backedUp).containsAll(setOf("infra_backup", "infra_backup-dump")))
        assertEquals(64, backedUp.secrets.backupPassword.length)
        // Meilisearch writes the consistent snapshots the backup copies.
        assertEquals("86400", environment(services(infra).getValue("meilisearch"))["MEILI_SCHEDULE_SNAPSHOT"])
    }

    @Test
    fun `backup and recommendation settings are validated`() {
        assertFailsWith<IllegalArgumentException> { config.copy(backup = SwarmBackup("s3:https://x.invalid/b")).validate() }
        assertFailsWith<IllegalArgumentException> { config.copy(backup = SwarmBackup(hour = 24)).validate() }
        assertFailsWith<IllegalArgumentException> { config.copy(backup = SwarmBackup(keepDaily = 0)).validate() }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = listOf(site.copy(ml = SwarmSiteMl(boscaToken = "a\nb")))).validate()
        }
        // A local path would live only inside the backup container.
        assertFailsWith<IllegalArgumentException> { config.copy(backup = SwarmBackup("/local/repo")).validate() }
        config.copy(backup = SwarmBackup("sftp:backup@example.invalid:/bosca")).validate()
    }

    @Test
    fun `the database bootstrap lets each site role run a backup restore`() {
        val destination = Files.createTempDirectory("bosca-swarm-test-").resolve("output")
        try {
            renderSwarm(config, destination)
            val bootstrap = Files.readString(destination.resolve("config/db-bootstrap.sh"))
            assertTrue(bootstrap.contains("GRANT SET ON PARAMETER session_replication_role TO site1;"))
            assertFalse(bootstrap.contains("session_replication_role TO site1_warehouse"))
            assertEquals(TF_SERVING_SERVE_SCRIPT, Files.readString(destination.resolve("config/tf-serving/serve.sh")))
            assertFalse(Files.exists(destination.resolve("config/backup")))
        } finally {
            Files.walk(destination.parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `one worker is labelled for the model volume`() {
        val script = modelNodeLabelScript("abc123")
        assertEquals(0, ProcessBuilder("bash", "-n").start().also { process ->
            process.outputStream.use { it.write(script.toByteArray()) }
        }.waitFor())
        assertTrue(script.contains("docker node update --label-add bosca_ml=true 'abc123'"))
        assertTrue(script.contains("--label-rm bosca_ml"))
    }

    @Test
    fun `the deployment waits for every rendered service and reports missing configuration`() {
        val expected = expectedServices(config)
        assertTrue(expected.containsAll(setOf(
            "infra_text-embeddings-inference", "infra_pgbouncer", "edge_caddy",
            "site1_artifacts", "site1_recommendation-trainer", "site1_recommendation-model-loader", "site1_tf-serving",
            "site2_artifacts",
        )))
        val notices = deploymentNotices(config)
        assertTrue(notices.any { it.startsWith("Backups are not configured") })
        assertTrue(notices.any { it.contains("site1") && it.contains("ml.boscaToken") })
        val configured = config.copy(
            backup = SwarmBackup("sftp:backup@example.invalid:/bosca"),
            sites = config.sites.map { it.copy(ml = SwarmSiteMl("a", "b", "c")) },
        )
        assertTrue(deploymentNotices(configured).isEmpty())
    }
}
