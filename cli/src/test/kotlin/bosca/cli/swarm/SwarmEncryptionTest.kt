package bosca.cli.swarm

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmEncryptionTest {
    private val passphrase = "a-long-test-passphrase-for-swarm".toCharArray()

    @Test
    fun `encrypt keeps nonsecret JSON readable and all credentials protected through later saves`() {
        val directory = Files.createTempDirectory("swarm-encryption-test-")
        try {
            val path = directory.resolve("config.json")
            val original = SwarmConfig().withSecrets().copy(
                registryAuth = SwarmRegistryAuth(username = "operator", password = "platform-password"),
                backup = SwarmBackup(secretAccessKey = "backup-secret-key"),
            ).let { config ->
                config.copy(sites = config.sites.mapIndexed { index, site ->
                    if (index == 0) site.copy(
                        bmlArtifactsToken = "bml-token",
                        rootImage = "registry.example.org/site/root:v1",
                        rootRegistryAuth = SwarmRegistryAuth(server = "registry.example.org", username = "api_token",
                            password = "root-password"),
                        ml = SwarmSiteMl("bosca-token", "push-token", "pull-token"),
                        google = SwarmSiteGoogle("google-client-id", "google-client-secret"),
                    ) else site
                })
            }
            saveConfig(path, original)
            transformSwarmConfig(path, path, encrypt = true, passphrase = passphrase)

            val encryptedText = Files.readString(path)
            val document = swarmJson.parseToJsonElement(encryptedText).jsonObject
            assertEquals("site1.example.invalid", document.getValue("sites").jsonArray[0].jsonObject.getValue("domain").jsonPrimitive.content)
            assertEquals(original.images, swarmJson.decodeFromJsonElement(SwarmConfig.serializer(),
                SwarmSecretEncryption.decrypt(document, passphrase)).images)
            listOf("platform-password", "backup-secret-key", "bml-token", "root-password", "bosca-token",
                "push-token", "pull-token", "google-client-secret", original.sites[0].secrets.initialAdmin, original.secrets.postgresAdmin)
                .forEach { assertFalse(encryptedText.contains(it), "Plaintext credential remained in config") }
            assertTrue(encryptedText.contains("google-client-id"), "Google client id is public and stays readable")
            assertTrue(document.getValue("registryAuth").jsonObject.getValue("password").jsonPrimitive.content.startsWith("ENC[v1:"))

            SwarmConfigFile(path) { passphrase.copyOf() }.use { file ->
                assertEquals(original, file.load())
                assertTrue(file.hasEncryptedSecrets)
                file.save(original.copy(sites = original.sites.mapIndexed { index, site ->
                    if (index == 0) site.copy(ml = site.ml.copy(boscaToken = "new-bosca-token")) else site
                }))
            }
            assertFalse(Files.readString(path).contains("new-bosca-token"))
            assertTrue(Files.readString(path).contains("site1.example.invalid"))
            if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path))
            }

            transformSwarmConfig(path, path, encrypt = false, passphrase = passphrase)
            assertTrue(Files.readString(path).contains("new-bosca-token"))
            assertFalse(Files.readString(path).contains("ENC[v1:"))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `wrong passphrase and damaged secret cannot replace encrypted config`() {
        val directory = Files.createTempDirectory("swarm-encryption-test-")
        try {
            val path = directory.resolve("config.json")
            saveConfig(path, SwarmConfig().withSecrets())
            transformSwarmConfig(path, path, encrypt = true, passphrase = passphrase)
            val intact = Files.readString(path)

            assertFailsWith<IllegalArgumentException> {
                transformSwarmConfig(path, path, encrypt = false, passphrase = "wrong-passphrase".toCharArray())
            }
            assertEquals(intact, Files.readString(path))

            val damaged = intact.replaceFirst("ENC[v1:", "ENC[v2:")
            privateWrite(path, damaged)
            assertFailsWith<IllegalArgumentException> {
                SwarmConfigFile(path) { passphrase.copyOf() }.use { it.load() }
            }
            assertEquals(damaged, Files.readString(path))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `loading encrypted legacy config encrypts newly generated secrets`() {
        val directory = Files.createTempDirectory("swarm-encryption-test-")
        try {
            val path = directory.resolve("config.json")
            val original = SwarmConfig().withSecrets()
            val legacy = swarmJson.encodeToString(SwarmConfig.serializer(), original)
                .replace(Regex(""",\s*"gatewayProxy":\s*"[0-9a-f]+""""), "")
            privateWrite(path, legacy)
            transformSwarmConfig(path, path, encrypt = true, passphrase = passphrase)

            val upgraded = SwarmConfigFile(path) { passphrase.copyOf() }.use { it.load() }

            assertTrue(upgraded.sites.all { it.secrets.gatewayProxy.length == 64 })
            val stored = Files.readString(path)
            upgraded.sites.forEach { assertFalse(stored.contains(it.secrets.gatewayProxy)) }
            assertTrue(swarmJson.parseToJsonElement(stored).jsonObject.getValue("sites").jsonArray[0]
                .jsonObject.getValue("secrets").jsonObject.getValue("gatewayProxy").jsonPrimitive.content.startsWith("ENC[v1:"))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
