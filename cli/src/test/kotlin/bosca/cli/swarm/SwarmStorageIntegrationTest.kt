package bosca.cli.swarm

import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmStorageIntegrationTest {
    @AfterTest
    fun restoreShell() { remoteShell = SshRemoteShell }

    @Test
    fun `real Docker retires old tasks and images while keeping running rollback and unrelated objects`() {
        assumeTrue("Docker is required for image retention integration", DockerClientFactory.instance().isDockerAvailable)
        // A separate daemon confines every deletion to this test, including when an assertion fails.
        GenericContainer("docker:29-dind").apply {
            withPrivilegedMode(true)
            withEnv("DOCKER_TLS_CERTDIR", "")
            waitingFor(Wait.forSuccessfulCommand("docker info").withStartupTimeout(Duration.ofMinutes(2)))
        }.use { engine ->
            engine.start()
            fun execute(body: String): String {
                val result = engine.execInContainer("sh", "-c", body)
                assertEquals(0, result.exitCode, "${result.stderr}\n$body")
                return result.stdout.trim()
            }
            execute("""
                set -eu
                docker swarm init --advertise-addr "${'$'}(hostname -i | awk '{print $1}')"
                docker pull alpine:3.21
                docker pull alpine:3.20
                old_digest=${'$'}(docker image inspect alpine:3.20 --format '{{index .RepoDigests 0}}' | cut -d@ -f2)
                docker image rm alpine:3.20
                docker pull "alpine:3.20@${'$'}old_digest"
                docker create --name seed alpine:3.21 true
                for version in current rollback retired foreign active created shared; do
                  docker commit --change "LABEL revision=${'$'}version" seed retention.example/api:${'$'}version
                done
                docker rm seed
                docker tag retention.example/api:shared unrelated.example/api:shared
                docker run --name retired --label com.docker.swarm.service.name=site1_api retention.example/api:retired true
                docker run --name foreign --label com.docker.swarm.service.name=other_api retention.example/api:foreign true
                docker run -d --name active --label com.docker.swarm.service.name=site1_api retention.example/api:active sleep 600
                docker create --name created --label com.docker.swarm.service.name=site1_api retention.example/api:created true
                docker volume create persistent-data
                docker service create --no-resolve-image --replicas 0 --name site1_api --label com.docker.stack.namespace=site1 retention.example/api:current
                docker service update --no-resolve-image --label-add revision=two site1_api
            """.trimIndent())
            remoteShell = RemoteShell { _, _, input, _ -> execute(input?.toString(Charsets.UTF_8).orEmpty().replace("sudo -n ", "")) }
            val config = SwarmConfig()
            val services = readSwarmServices(config)
            assertEquals("site1", services.single().stack)
            assertEquals("retention.example/api:current", services.single().image)
            assertEquals("retention.example/api:current", services.single().previous)
            val pins = setOf("retention.example/api:current", "retention.example/api:rollback", "alpine:3.21")
            cleanupSwarmNode(config, "test-engine", pins, pins)
            val images = execute("docker image ls --format '{{.Repository}}:{{.Tag}}'").lines().toSet()
            assertFalse("retention.example/api:retired" in images)
            assertFalse(images.any { it.startsWith("alpine:3.20") || it.startsWith("alpine:<none>") })
            for (version in listOf("current", "rollback", "foreign", "active", "created", "shared")) {
                assertTrue("retention.example/api:$version" in images, version)
            }
            val containers = execute("docker container ls -a --format '{{.Names}}'").lines().toSet()
            assertFalse("retired" in containers)
            assertTrue(containers.containsAll(setOf("foreign", "active", "created")))
            assertEquals("persistent-data", execute("docker volume ls --format '{{.Name}}'"))
            // Idempotence matters: re-running a deployment must not erode its rollback retention.
            cleanupSwarmNode(config, "test-engine", pins, pins)
            assertEquals(images, execute("docker image ls --format '{{.Repository}}:{{.Tag}}'").lines().toSet())
        }
    }
}
