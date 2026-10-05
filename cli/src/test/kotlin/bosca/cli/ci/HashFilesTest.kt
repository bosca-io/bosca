package bosca.cli.ci

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashFilesTest {

    @Test
    fun `hashFilesInDir returns consistent hash for same content`() {
        val dir = Files.createTempDirectory("hash-test").toFile()
        try {
            File(dir, "a.txt").writeText("hello")
            File(dir, "b.txt").writeText("world")

            val hash1 = hashFilesInDir(dir, listOf("*.txt"))
            val hash2 = hashFilesInDir(dir, listOf("*.txt"))
            assertEquals(hash1, hash2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hashFilesInDir returns different hash for different content`() {
        val dir = Files.createTempDirectory("hash-test").toFile()
        try {
            File(dir, "a.txt").writeText("hello")
            val hash1 = hashFilesInDir(dir, listOf("*.txt"))

            File(dir, "a.txt").writeText("goodbye")
            val hash2 = hashFilesInDir(dir, listOf("*.txt"))

            assertNotEquals(hash1, hash2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hashFilesInDir returns hex string of correct length`() {
        val dir = Files.createTempDirectory("hash-test").toFile()
        try {
            File(dir, "test.kt").writeText("fun main() {}")
            val hash = hashFilesInDir(dir, listOf("*.kt"))
            assertEquals(64, hash.length)
            assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hashFilesInDir with no matching files returns hash of empty input`() {
        val dir = Files.createTempDirectory("hash-test").toFile()
        try {
            File(dir, "test.txt").writeText("hello")
            val hash = hashFilesInDir(dir, listOf("*.py"))
            assertEquals(64, hash.length)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hashFilesInDir matches multiple globs`() {
        val dir = Files.createTempDirectory("hash-test").toFile()
        try {
            File(dir, "build.gradle.kts").writeText("plugins {}")
            File(dir, "settings.gradle.kts").writeText("rootProject.name = 'test'")
            File(dir, "README.md").writeText("readme")

            val hashBoth = hashFilesInDir(dir, listOf("*.gradle.kts", "*.md"))
            val hashGradleOnly = hashFilesInDir(dir, listOf("*.gradle.kts"))

            assertNotEquals(hashBoth, hashGradleOnly)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hashFilesInDir handles subdirectories with glob patterns`() {
        val dir = Files.createTempDirectory("hash-test").toFile()
        try {
            val subDir = File(dir, "src/main")
            subDir.mkdirs()
            File(subDir, "App.kt").writeText("fun main() {}")

            val hash = hashFilesInDir(dir, listOf("**/*.kt"))
            assertEquals(64, hash.length)

            val noMatch = hashFilesInDir(dir, listOf("*.kt"))
            assertNotEquals(hash, noMatch)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hashString returns consistent SHA-256 hex`() {
        val hash = hashString("test-key-123")
        assertEquals(64, hash.length)
        assertEquals(hashString("test-key-123"), hash)
        assertNotEquals(hashString("different-key"), hash)
    }
}
