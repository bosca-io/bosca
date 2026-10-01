package bosca.cli.swarm

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives node bootstrap through a recording [RemoteShell] instead of SSH. */
class SwarmBootstrapTest {
    private val config = SwarmConfig().withSecrets().copy(
        manager = SwarmManager("ops@manager", "10.0.0.10"),
        workers = listOf(SwarmWorker("ops@worker1", "10.0.0.11"), SwarmWorker("ops@worker2")),
    )

    /** One remote call: the target and the script or command it ran. */
    private data class Call(val target: String, val script: String)

    private class FakeNodes(private val answer: (target: String, script: String) -> String?) : RemoteShell {
        val calls = mutableListOf<Call>()
        override fun run(target: String, command: String, input: ByteArray?, capture: Boolean): String {
            // Scripts arrive on standard input to `bash -se`; plain commands arrive as the command.
            val script = if (command == "bash -se" && input != null) input.toString(Charsets.UTF_8) else command
            calls += Call(target, script)
            return answer(target, script) ?: ""
        }
    }

    private fun nodes(
        managerState: String = "inactive false",
        workerState: (String) -> String = { "inactive" },
        architecture: (String) -> String = { "x86_64" },
        workersInManagerCluster: Boolean = true,
        dataLabel: String = "true",
        mlLabel: String = "true",
    ) = FakeNodes { target, script ->
        when {
            script.contains("{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}") -> managerState
            script.contains("{{.Swarm.LocalNodeState}}") -> workerState(target)
            script.contains("node inspect") && script.contains("bosca_data") -> dataLabel
            script.contains("node inspect") && script.contains("bosca_ml") -> mlLabel
            script == "sudo -n docker node ls -q" -> if (workersInManagerCluster) {
                "node-manager\nnode-worker1\nnode-worker2"
            } else {
                "node-manager"
            }
            script.contains("join-token -q worker") -> "SWMTKN-1-token"
            script.contains("{{.Swarm.Error}}") -> "join timed out"
            script.contains("{{.Swarm.NodeAddr}}") -> "10.0.0.11"
            script.contains("{{.Swarm.NodeID}}") -> "node-" + target.substringAfter('@')
            script == "uname -m" -> architecture(target)
            else -> null
        }
    }

    @AfterTest
    fun restoreShell() {
        remoteShell = SshRemoteShell
    }

    @Test
    fun `a fresh cluster is initialized, joined and labelled`() {
        val fake = nodes()
        remoteShell = fake

        bootstrapNodes(config)

        val scripts = fake.calls.map { it.script }
        // Docker Engine is checked on every node before the cluster is touched.
        assertEquals(listOf("ops@manager", "ops@worker1", "ops@worker2"),
            fake.calls.filter { it.script.contains("command -v docker") }.map { it.target })
        assertTrue(fake.calls.any { it.target == "ops@manager" && it.script == "sudo -n docker swarm init --advertise-addr '10.0.0.10'" })
        assertTrue(fake.calls.any {
            it.target == "ops@worker1" && it.script ==
                "sudo -n docker swarm join --token 'SWMTKN-1-token' --advertise-addr '10.0.0.11' " +
                "--data-path-addr '10.0.0.11' '10.0.0.10:2377'"
        })
        assertTrue(fake.calls.any {
            it.target == "ops@worker2" &&
                it.script == "sudo -n docker swarm join --token 'SWMTKN-1-token' '10.0.0.10:2377'"
        })
        assertTrue(scripts.contains("sudo -n docker node update --label-add bosca_data=true 'node-manager'"))
        // The first worker holds the model volume; the label is set on the manager.
        val label = fake.calls.last()
        assertEquals("ops@manager", label.target)
        assertEquals(modelNodeLabelScript("node-worker1"), label.script)
    }

    @Test
    fun `bootstrap configures private and public firewall rules before Docker`() {
        val fake = nodes()
        remoteShell = fake

        bootstrapNodes(config)

        for (target in listOf("ops@manager", "ops@worker1", "ops@worker2")) {
            val calls = fake.calls.filter { it.target == target }
            val firewall = calls.first().script
            assertTrue(firewall.contains("ufw allow \"${'$'}{ssh_port}/tcp\""))
            assertTrue(firewall.contains("ufw allow in on"))
            assertTrue(firewall.contains("ufw allow out on"))
            assertTrue(firewall.contains("ufw --force enable"))
            assertTrue(calls.indexOfFirst { it.script.contains("ufw allow") } < calls.indexOfFirst { it.script.contains("command -v docker") })
            if (target == "ops@manager") {
                assertTrue(firewall.contains("ufw allow 80/tcp"))
                assertTrue(firewall.contains("ufw allow 443/tcp"))
                assertTrue(firewall.contains("address='10.0.0.10'"))
            } else {
                assertFalse(firewall.contains("ufw allow 80/tcp"))
                assertFalse(firewall.contains("ufw allow 443/tcp"))
            }
        }
        assertTrue(fake.calls.first { it.target == "ops@worker2" }.script.contains("ip -4 route get '10.0.0.10'"))
        assertTrue(fake.calls.first { it.target == "ops@worker2" }.script.contains("10.0.0.10/32"))
    }

    @Test
    fun `registry password can come from private config or environment`() {
        val auth = SwarmRegistryAuth(username = "operator", password = "inline-secret")
        assertEquals("inline-secret", registryPassword(auth) { "environment-secret" })
        assertEquals("environment-secret", registryPassword(auth.copy(password = "")) { "environment-secret" })
        assertFailsWith<IllegalArgumentException> {
            config.copy(registryAuth = auth.copy(password = "invalid\npassword")).validate()
        }
    }

    @Test
    fun `private root image registry login is kept alongside the platform registry`() {
        val rootAuth = SwarmRegistryAuth(
            server = "artifacts.example.org", username = "api_token",
            passwordEnv = "ROOT_REGISTRY_PASSWORD", password = "pull-token",
        )
        val site = config.sites.first().copy(
            rootImage = "artifacts.example.org/sites/home:0.0.4",
            rootPort = 8080,
            rootRegistryAuth = rootAuth,
        )
        val configured = config.copy(
            registryAuth = SwarmRegistryAuth(server = "artifacts.example.com", username = "platform", password = "platform-token"),
            sites = listOf(site) + config.sites.drop(1),
        )
        configured.validate()
        assertEquals(listOf("artifacts.example.com", "artifacts.example.org"), registryAuths(configured).map { it.server })
        assertEquals("pull-token", registryPassword(rootAuth) { "unused" })
        assertEquals(configured, swarmJson.decodeFromString(SwarmConfig.serializer(),
            swarmJson.encodeToString(SwarmConfig.serializer(), configured)))

        assertFailsWith<IllegalArgumentException> {
            configured.copy(sites = listOf(site.copy(rootImage = "other.example.org/sites/home:0.0.4")) + config.sites.drop(1)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            configured.copy(sites = listOf(site.copy(rootRegistryAuth = rootAuth.copy(server = "artifacts.example.com"))) + config.sites.drop(1)).validate()
        }
    }

    @Test
    fun `database bootstrap skips SQL when the successful fingerprint is unchanged`() {
        val fingerprint = databaseBootstrapFingerprint(config)
        assertEquals(fingerprint, databaseBootstrapFingerprint(config.copy(images = config.images + ("server" to "new-image"))))
        assertFalse(fingerprint == databaseBootstrapFingerprint(config.copy(sites = config.sites.mapIndexed { index, site ->
            if (index == 0) site.copy(secrets = site.secrets.copy(database = "new-password")) else site
        })))
        val fake = FakeNodes { _, body ->
            if (body.contains("database-bootstrap.sha256") && body.startsWith("sudo -n cat")) fingerprint else null
        }
        remoteShell = fake

        bootstrapDatabases(config)

        assertEquals(1, fake.calls.size)
        assertFalse(fake.calls.any { it.script.contains("db-bootstrap.sh") || it.script.contains("pg_isready") })
    }

    @Test
    fun `database bootstrap adopts a complete existing database without replaying SQL`() {
        val inventory = databaseInventoryScript(config, "postgres-container")
        val syntax = ProcessBuilder("bash", "-n").start()
        syntax.outputStream.use { it.write(inventory.toByteArray()) }
        assertEquals(0, syntax.waitFor(), syntax.errorStream.bufferedReader().readText())

        val fake = FakeNodes { _, body ->
            when {
                body.startsWith("sudo -n cat") -> ""
                body.contains("pg_isready") -> "postgres-container"
                body.contains("FROM pg_roles") -> "complete"
                else -> null
            }
        }
        remoteShell = fake

        bootstrapDatabases(config)

        assertTrue(fake.calls.any { it.script.contains("FROM pg_roles") && it.script.contains("pg_extension") })
        assertTrue(fake.calls.any { it.script.contains("database-bootstrap.sha256.XXXXXX") })
        assertFalse(fake.calls.any { it.script.contains("db-bootstrap.sh") })
    }

    @Test
    fun `database bootstrap runs SQL for a new database or changed credentials`() {
        for (marker in listOf("", "outdated")) {
            val fake = FakeNodes { _, body ->
                when {
                    body.startsWith("sudo -n cat") -> marker
                    body.contains("pg_isready") -> "postgres-container"
                    body.contains("FROM pg_roles") -> "incomplete"
                    else -> null
                }
            }
            remoteShell = fake

            bootstrapDatabases(config)

            assertTrue(fake.calls.any { it.script.contains("db-bootstrap.sh") })
            assertTrue(fake.calls.any { it.script.contains("database-bootstrap.sha256.XXXXXX") })
            assertEquals(marker.isBlank(), fake.calls.any { it.script.contains("FROM pg_roles") })
        }
    }

    @Test
    fun `generated firewall scripts parse for manager and workers`() {
        listOf(
            firewallScript(config, config.manager.advertiseAddress, manager = true),
            firewallScript(config, config.workers[0].advertiseAddress, manager = false),
            firewallScript(config, null, manager = false),
        ).forEach { body ->
            val process = ProcessBuilder("bash", "-n").start()
            process.outputStream.use { it.write(body.toByteArray()) }
            assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
        }
    }

    @Test
    fun `an existing cluster is reused without re-initializing or re-joining`() {
        val fake = nodes(managerState = "active true", workerState = { "active" })
        remoteShell = fake

        bootstrapNodes(config)

        assertFalse(fake.calls.any { it.script.contains("swarm init") || it.script.contains("swarm join --token") })
        assertFalse(fake.calls.any { it.script.contains("{{.Swarm.Cluster.ID}}") })
        assertEquals(2, fake.calls.count { it.target == "ops@manager" && it.script == "sudo -n docker node ls -q" })
    }

    @Test
    fun `deploy preflight accepts ready labelled nodes without changing them`() {
        val fake = nodes(managerState = "active true", workerState = { "active" })
        remoteShell = fake

        verifyBootstrappedNodes(config)

        assertFalse(fake.calls.any {
            it.script.contains("command -v docker") || it.script.contains("swarm init") ||
                it.script.contains("swarm join --token") || it.script.contains("node update")
        })
    }

    @Test
    fun `deploy requires bootstrap before uploading stacks`() {
        val fake = nodes()
        remoteShell = fake

        val failure = assertFailsWith<IllegalArgumentException> {
            deploySwarm(config, Path.of("/unused"), skipPublicCheck = true)
        }
        assertTrue(failure.message.orEmpty().contains("run bosca swarm bootstrap first"))
        assertFalse(fake.calls.any { it.script.contains("command -v docker") || it.script.contains("swarm init") })
    }

    @Test
    fun `deploy preflight rejects missing placement labels and foreign workers`() {
        remoteShell = nodes(managerState = "active true", workerState = { "active" }, dataLabel = "")
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyBootstrappedNodes(config) }
            .message.orEmpty().contains("bosca_data=true"))

        remoteShell = nodes(managerState = "active true", workerState = { "active" }, mlLabel = "")
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyBootstrappedNodes(config) }
            .message.orEmpty().contains("bosca_ml=true"))

        remoteShell = nodes(managerState = "active true", workerState = { "active" }, workersInManagerCluster = false)
        assertEquals("ops@worker1 is already in a different Swarm",
            assertFailsWith<IllegalArgumentException> { verifyBootstrappedNodes(config) }.message)
    }

    @Test
    fun `a worker in another cluster, a non-amd64 worker or a foreign manager state stops the deployment`() {
        remoteShell = nodes(managerState = "active true", workerState = { "active" }, workersInManagerCluster = false)
        assertEquals("ops@worker1 is already in a different Swarm",
            assertFailsWith<IllegalArgumentException> { bootstrapNodes(config) }.message)

        remoteShell = nodes(architecture = { if (it == "ops@worker2") "aarch64" else "x86_64" })
        assertEquals("ops@worker2 is aarch64; worker nodes must be x86_64 (amd64)",
            assertFailsWith<IllegalArgumentException> { bootstrapNodes(config) }.message)

        remoteShell = nodes(managerState = "active false")
        assertEquals("Manager host is already in an incompatible Swarm state: active false",
            assertFailsWith<IllegalStateException> { bootstrapNodes(config) }.message)

        remoteShell = nodes(workerState = { "pending" })
        assertEquals("Unexpected Swarm state on ops@worker1: pending",
            assertFailsWith<IllegalStateException> { bootstrapNodes(config) }.message)

        remoteShell = nodes(workerState = { "error" })
        assertEquals("Swarm worker ops@worker1 is in error state: join timed out",
            assertFailsWith<IllegalStateException> { bootstrapNodes(config) }.message)
    }

    @Test
    fun `waiting for services names each unhealthy service with its latest task error`() {
        val healthy = expectedServices(config).associateWith { "1/1" }
        val ls = { replicas: Map<String, String> -> replicas.entries.joinToString("\n") { "${it.key}|${it.value}" } }

        remoteShell = FakeNodes { _, script -> if (script.startsWith("sudo -n docker service ls")) ls(healthy) else null }
        waitServices(config, timeoutMillis = 0, pollMillis = 1)

        val degraded = healthy + ("site1_bml-message-server" to "0/1") - "site2_studio"
        remoteShell = FakeNodes { _, script ->
            when {
                script.startsWith("sudo -n docker service ls") -> ls(degraded)
                script.contains("service ps 'site1_bml-message-server'") -> "Starting 5 seconds ago task: non-zero exit (137)"
                else -> null
            }
        }
        val failure = assertFailsWith<IllegalStateException> { waitServices(config, timeoutMillis = 0, pollMillis = 1) }
        assertEquals(
            "Swarm services did not finish updating or reach one healthy replica:\n" +
                "  site1_bml-message-server (0/1, update none): Starting 5 seconds ago task: non-zero exit (137)\n" +
                "  site2_studio (missing, update none): no task",
            failure.message,
        )
    }

    @Test
    fun `service readiness waits for an active update and reports a rollback`() {
        var inspections = 0
        val fake = FakeNodes { _, body ->
            when {
                body.startsWith("sudo -n docker service ls") -> "infra_postgres|1/1"
                body.startsWith("sudo -n docker service inspect") -> {
                    inspections++
                    "infra_postgres|${if (inspections == 1) "updating" else "completed"}"
                }
                else -> null
            }
        }
        remoteShell = fake
        waitServices(config, timeoutMillis = 1_000, pollMillis = 1, expected = setOf("infra_postgres"))
        assertEquals(2, inspections)

        remoteShell = FakeNodes { _, body ->
            when {
                body.startsWith("sudo -n docker service ls") -> "infra_postgres|1/1"
                body.startsWith("sudo -n docker service inspect") -> "infra_postgres|rollback_completed"
                else -> null
            }
        }
        assertTrue(assertFailsWith<IllegalStateException> {
            waitServices(config, timeoutMillis = 1_000, pollMillis = 1, expected = setOf("infra_postgres"))
        }.message.orEmpty().contains("rollback_completed"))
    }

    @Test
    fun `status reports the last backup outcome when backups are configured`() {
        remoteShell = FakeNodes { _, script ->
            when {
                script.startsWith("sudo -n docker service ls") -> "infra_postgres 1/1"
                script.contains("last-success") -> "Last successful backup: 2026-09-28T03:30:00Z"
                else -> null
            }
        }
        assertEquals("infra_postgres 1/1\nBackups: not configured", swarmStatus(config))
        assertEquals(
            "infra_postgres 1/1\nLast successful backup: 2026-09-28T03:30:00Z",
            swarmStatus(config.copy(backup = SwarmBackup("sftp:backup@example.invalid:/bosca"))),
        )
    }
}
