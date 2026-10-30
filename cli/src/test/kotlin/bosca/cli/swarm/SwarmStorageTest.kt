package bosca.cli.swarm

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmStorageTest {
    private val repository = "registry.example:5000/bosca/api"
    private val config = SwarmConfig().withSecrets()
    private val stacks = setOf("infra", "edge", "site1", "site2")
    private fun image(version: String, vararg aliases: String) =
        SwarmStoredImage("sha256:$version", listOf("$repository:$version", *aliases))

    @AfterTest
    fun restoreShell() { remoteShell = SshRemoteShell }

    @Test
    fun `retention preserves configured running rollback and unrelated workloads`() {
        val images = listOf("current", "incoming", "rollback", "older", "running", "foreign").map { image(it) } +
            image("shared", "unrelated.example/api:v1") + SwarmStoredImage("dangling")
        val containers = listOf(
            SwarmStoredContainer("retired", "sha256:older", "$repository:older", "site1_api", "exited"),
            SwarmStoredContainer("active", "sha256:running", "$repository:running", "site2_api", "restarting"),
            SwarmStoredContainer("other", "sha256:foreign", "$repository:foreign", "another_api", "exited"),
        )
        val pins = setOf("$repository:current", "$repository:incoming", "$repository:rollback")
        val plan = planSwarmImageCleanup(images, containers, stacks, pins, pins)
        assertEquals(listOf("retired"), plan.containers.map { it.id })
        assertEquals(listOf("sha256:older"), plan.images.map { it.id })
    }

    @Test
    fun `full containerd references and digest aliases protect the same image`() {
        val images = listOf(
            SwarmStoredImage("sha256:one", listOf("$repository:v1@sha256:one", "$repository:alias@sha256:one")),
            SwarmStoredImage("sha256:two", null, listOf("$repository@sha256:two")),
        )
        for (pin in listOf("$repository:v1", "$repository@sha256:one", "$repository:v1@sha256:one")) {
            val plan = planSwarmImageCleanup(images, emptyList(), stacks, setOf(pin), setOf(pin))
            assertEquals(listOf("sha256:two"), plan.images.map { it.id }, pin)
        }
        assertEquals("docker.io/library/alpine", swarmImageRepository("alpine:3.21"))
        assertEquals("docker.io/library/alpine", swarmImageRepository("docker.io/library/alpine:3.21@sha256:x"))
        assertEquals("registry.example:5000/bosca/api", swarmImageRepository("$repository:v1@sha256:x"))
    }

    @Test
    fun `created paused and unmanaged containers protect their images`() {
        val states = listOf("created", "running", "paused", "restarting", "removing")
        val images = states.map { image(it) } + image("standalone")
        val containers = states.map { SwarmStoredContainer(it, "sha256:$it", "$repository:$it", "site1_api", it) } +
            SwarmStoredContainer("manual", "sha256:standalone", "$repository:standalone", null, "exited")
        val plan = planSwarmImageCleanup(images, containers, stacks, emptySet(), setOf("$repository:incoming"))
        assertTrue(plan.images.isEmpty())
        assertTrue(plan.containers.isEmpty())
    }

    @Test
    fun `duplicate image identities preserve all aliases before choosing candidates`() {
        val images = listOf(image("old"), image("old", "foreign.example/api:old"))
        assertTrue(planSwarmImageCleanup(images, emptyList(), stacks, emptySet(), setOf("$repository:incoming")).images.isEmpty())
    }

    @Test
    fun `history survives repeated deploys and prefers the last successful deployment after partial failure`() {
        val initial = SwarmStorageState(listOf(SwarmStoredService("site1_api", "site1", "$repository:v1", "$repository:v0")), SwarmImageHistory())
        val first = nextSwarmImageHistory(initial, setOf("$repository:v2"), stacks)
        assertEquals(setOf("$repository:v1"), first.previous)
        val repeated = SwarmStorageState(listOf(SwarmStoredService("site1_api", "site1", "$repository:v2")), first)
        assertEquals(first, nextSwarmImageHistory(repeated, first.current, stacks))
        val partial = repeated.copy(services = listOf(SwarmStoredService("site1_api", "site1", "$repository:failed")))
        assertEquals(first.current, nextSwarmImageHistory(partial, setOf("$repository:v3"), stacks).previous)
        assertEquals(first.previous, nextSwarmImageHistory(partial, first.current, stacks).previous)
        assertEquals(setOf("$repository:v0"), nextSwarmImageHistory(initial, setOf("$repository:v1"), stacks).previous)
    }

    @Test
    fun `preflight refuses low capacity before uploading or pulling and honors cleanup opt out`() {
        val calls = mutableListOf<String>()
        remoteShell = RemoteShell { _, _, input, _ ->
            val body = input?.toString(Charsets.UTF_8).orEmpty()
            calls += body
            if (body.contains("df -Pk")) "1024 /\n1024 /\n1024 /" else ""
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            prepareSwarmStorage(config.copy(imageRetention = SwarmImageRetention(enabled = false)))
        }
        assertTrue(failure.message.orEmpty().contains("Insufficient disk space"))
        assertFalse(calls.any { it.contains("docker image rm") || it.contains("docker container rm") || it.contains("docker pull") })
    }

    @Test
    fun `preflight attempts conservative recovery then fails if space is still insufficient`() {
        val calls = mutableListOf<String>()
        remoteShell = RemoteShell { _, _, input, _ ->
            val body = input?.toString(Charsets.UTF_8).orEmpty()
            calls += body
            if (body.contains("df -Pk")) "1024 /\n1024 /\n1024 /" else ""
        }
        assertFailsWith<IllegalArgumentException> { prepareSwarmStorage(config) }
        assertTrue(calls.any { it.contains("docker image ls") })
        assertFalse(calls.any { it.contains("docker pull") || it.contains("history.XXXXXX") })
    }

    @Test
    fun `successful finalization only removes omitted services inside current managed stacks`() {
        val calls = mutableListOf<String>()
        val obsolete = SwarmStoredService("site1_retired-git", "site1", "$repository:git")
        val foreign = SwarmStoredService("unrelated_git", "unrelated", "$repository:foreign")
        var removed = false
        remoteShell = RemoteShell { _, _, input, _ ->
            val body = input?.toString(Charsets.UTF_8).orEmpty()
            calls += body
            when {
                body.contains("docker service ls -q") -> (listOf(foreign) + if (removed) emptyList() else listOf(obsolete))
                    .joinToString("\n") { Json.encodeToString(SwarmStoredService.serializer(), it) }
                body.contains("service rm 'site1_retired-git'") -> { removed = true; "" }
                else -> ""
            }
        }
        finishSwarmStorage(config.copy(imageRetention = SwarmImageRetention(enabled = false)), SwarmStorageState(listOf(obsolete, foreign), SwarmImageHistory()))
        assertTrue(removed)
        assertFalse(calls.any { it.contains("service rm 'unrelated_git'") })
        assertTrue(calls.indexOfFirst { it.contains("service rm") } < calls.indexOfFirst { it.contains("history.XXXXXX") })
        assertFalse(calls.any { it.contains("docker image rm") || it.contains("docker container rm") })
    }

    @Test
    fun `deployment retains omitted services through rollout and never finalizes a failed update`() {
        val config = config.copy(imageRetention = SwarmImageRetention(enabled = false))
        val expected = expectedServices(config)
        val bundle = Files.createTempDirectory("swarm-retention-flow-")
        try {
            for (failUpdate in listOf(true, false)) {
                val calls = mutableListOf<String>()
                var sitesDeployed = false
                var retired = false
                remoteShell = RemoteShell { target, command, input, _ ->
                    val body = if (command == "bash -se") input?.toString(Charsets.UTF_8).orEmpty() else command
                    calls += body
                    when {
                        body.contains("{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}") -> "active true"
                        body.contains("{{.Swarm.LocalNodeState}}") -> "active"
                        body.contains("{{.Swarm.NodeID}}") -> if (target == config.manager.ssh) "manager" else "worker"
                        body == "sudo -n docker node ls -q" -> "manager\nworker"
                        body.contains("docker node inspect") -> "true"
                        body == "uname -m" -> "x86_64"
                        body.contains("df -Pk") -> "107374182400 /\n107374182400 /\n107374182400 /"
                        body.contains("docker service ls -q") -> (expected + if (retired) emptySet() else setOf("site1_old"))
                            .joinToString("\n") { name -> Json.encodeToString(SwarmStoredService.serializer(),
                                SwarmStoredService(name, name.substringBefore('_'), "$repository:current")) }
                        body.startsWith("sudo -n docker service ls --format") -> expected.joinToString("\n") { "$it|1/1" }
                        body.startsWith("sudo -n docker service inspect") -> expected.joinToString("\n") {
                            "$it|${if (failUpdate && sitesDeployed && it == "site1_server") "rollback_completed" else "completed"}"
                        }
                        body.contains("docker stack deploy") -> {
                            if (body.contains("site1.yml")) sitesDeployed = true
                            ""
                        }
                        body.startsWith("sudo -n cat") && body.contains("database-bootstrap.sha256") -> databaseBootstrapFingerprint(config)
                        body.contains("docker service rm") -> { retired = true; "" }
                        else -> ""
                    }
                }
                if (failUpdate) {
                    assertTrue(assertFailsWith<IllegalStateException> {
                        deploySwarm(config, bundle, skipPublicCheck = true)
                    }.message.orEmpty().contains("rollback_completed"))
                    assertFalse(retired)
                    assertFalse(calls.any { it.contains("history.XXXXXX") || it.contains("docker image rm") || it.contains("docker container rm") })
                } else {
                    deploySwarm(config, bundle, skipPublicCheck = true)
                    assertTrue(retired)
                    assertTrue(calls.indexOfLast { it.startsWith("sudo -n docker service inspect") } < calls.indexOfFirst { it.contains("docker service rm") },
                        "Service removal must follow the final rollout readiness check")
                    assertTrue(calls.any { it.contains("history.XXXXXX") })
                }
                assertFalse(calls.any { it.contains("--prune") })
            }
        } finally {
            Files.delete(bundle)
        }
    }

    @Test
    fun `finalization does not claim repositories belonging to other stacks`() {
        val foreign = SwarmStoredService("other_api", "other", "foreign.example/api:current")
        val stale = SwarmStoredImage("sha256:foreign", listOf("foreign.example/api:old"))
        val calls = mutableListOf<String>()
        remoteShell = RemoteShell { _, _, input, _ ->
            val body = input?.toString(Charsets.UTF_8).orEmpty()
            calls += body
            when {
                body.contains("df -Pk") -> "107374182400 /\n107374182400 /\n107374182400 /"
                body.contains("docker service ls -q") -> Json.encodeToString(SwarmStoredService.serializer(), foreign)
                body.contains("docker image ls") -> Json.encodeToString(SwarmStoredImage.serializer(), stale)
                else -> ""
            }
        }
        finishSwarmStorage(config, SwarmStorageState(listOf(foreign), SwarmImageHistory()))
        assertFalse(calls.any { it.contains("docker image rm") || it.contains("docker container rm") || it.contains("docker service rm") })
    }

    @Test
    fun `invalid retention settings fail configuration validation`() {
        assertFailsWith<IllegalArgumentException> { config.copy(imageRetention = SwarmImageRetention(minimumFreeGiB = 0)).validate() }
        val oldConfig = Json.decodeFromString(SwarmConfig.serializer(), "{}")
        assertEquals(SwarmImageRetention(), oldConfig.imageRetention)
    }
}
