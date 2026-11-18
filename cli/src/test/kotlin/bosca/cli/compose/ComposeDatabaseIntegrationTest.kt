package bosca.cli.compose

import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeDatabaseIntegrationTest {
    @Test
    fun `split deployment database accepts all configured application pools concurrently`() {
        assumeTrue("Docker is required for database integration", DockerClientFactory.instance().isDockerAvailable)
        val directory = Files.createTempDirectory("compose-database-")
        try {
            val installation = directory.resolve("installation")
            installedComposeNginx(installation, separate = true)
            val yaml = Yaml()
            val compose = yaml.load<Map<String, Any>>(Files.readString(installation.resolve("compose.yaml")))
            @Suppress("UNCHECKED_CAST")
            val services = compose.getValue("services") as Map<String, Map<String, Any>>
            @Suppress("UNCHECKED_CAST")
            val command = services.getValue("postgres").getValue("command") as List<String>
            val connections = listOf("server", "atlas", "git", "artifacts").sumOf { service ->
                val config = yaml.load<Map<String, Map<String, Map<String, Any>>>>(
                    Files.readString(installation.resolve("config/$service.yaml")))
                (config.getValue("database").getValue("postgres").getValue("maxConnections") as Number).toInt()
            }
            GenericContainer("pgvector/pgvector:pg18").apply {
                withEnv("POSTGRES_USER", "fixture")
                withEnv("POSTGRES_PASSWORD", "fixture-password")
                withEnv("POSTGRES_DB", "fixture")
                withExposedPorts(5432)
                withCommand(*command.toTypedArray())
                waitingFor(Wait.forListeningPort())
            }.use { database ->
                database.start()
                val result = database.execInContainer("bash", "-ec", """
                    export PGPASSWORD=fixture-password
                    for ((index=0; index<$connections; index++)); do
                      PGAPPNAME=atlas-pool-fixture psql -h 127.0.0.1 -U fixture -d fixture -c 'SELECT pg_sleep(20)' >/dev/null 2>&1 &
                    done
                    for attempt in {1..100}; do
                      count=${'$'}(psql -h 127.0.0.1 -U fixture -d fixture -Atc "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'atlas-pool-fixture'")
                      if [ "${'$'}count" = "$connections" ]; then echo "${'$'}count connections accepted"; exit 0; fi
                      sleep 0.1
                    done
                    echo "Only ${'$'}count of $connections connections accepted" >&2
                    exit 1
                """.trimIndent())
                assertEquals(0, result.exitCode, result.stdout + result.stderr)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
