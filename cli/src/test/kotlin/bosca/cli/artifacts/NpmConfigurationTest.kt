package bosca.cli.artifacts

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NpmConfigurationTest {
    @Test
    fun `npm user config honors overrides and home expansion`() {
        val environment = mapOf("HOME" to "/test/home")
        assertEquals(File("/test/home/.npmrc"), npmUserConfigFile(environment))
        assertEquals(File("/config/npmrc"), npmUserConfigFile(environment + ("NPM_CONFIG_USERCONFIG" to "/config/npmrc")))
        assertEquals(File("/test/home/custom/npmrc"), npmUserConfigFile(environment + ("npm_config_userconfig" to "~/custom/npmrc")))
        assertEquals(File("/lowercase/npmrc"), npmUserConfigFile(environment + mapOf(
            "NPM_CONFIG_USERCONFIG" to "/uppercase/npmrc",
            "npm_config_userconfig" to "/lowercase/npmrc",
        )))
    }

    @Test
    fun `npm settings preserve unrelated entries and replace duplicate Bosca settings on repeated login`() {
        val directory = Files.createTempDirectory("bosca-npm-config").toFile()
        try {
            val file = File(directory, ".npmrc")
            val unrelated = "# User settings\r\nregistry=https://registry.npmjs.org/\r\n" +
                "@other:registry=https://other.example/npm/\r\n//other.example/npm/:_authToken=other-token\r\n"
            file.writeText(unrelated + "@bosca:registry=https://old.example/npm/\r\n" +
                " @bosca:registry = https://duplicate.example/npm/\r\n" +
                "//artifacts.example/npm/:_authToken=old-token\r\n")
            val registry = artifactRegistryUrl("artifacts.example")
            configureNpmRegistry(file, registry, "first-token")
            configureNpmRegistry(file, registry, "replacement-token")

            assertEquals(unrelated + "@bosca:registry=https://artifacts.example/npm/\r\n" +
                "//artifacts.example/npm/:_authToken=replacement-token\r\n", file.readText())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `npm configuration follows an existing symlink`() {
        val directory = Files.createTempDirectory("bosca-npm-symlink").toFile()
        try {
            val target = File(directory, "settings/npmrc")
            target.parentFile.mkdirs()
            target.writeText("fund=false")
            val link = File(directory, ".npmrc")
            Files.createSymbolicLink(link.toPath(), target.toPath())

            configureNpmRegistry(link, artifactRegistryUrl("localhost:8084"), "test-token")

            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("fund=false\n@bosca:registry=http://localhost:8084/npm/\n" +
                "//localhost:8084/npm/:_authToken=test-token\n", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
