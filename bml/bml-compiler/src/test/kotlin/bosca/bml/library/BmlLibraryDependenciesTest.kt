package bosca.bml.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BmlLibraryDependenciesTest {
    @Test
    fun `archives and exploded outputs expose metadata without producer classes or sources`() = temporary { root ->
        val manifest = BmlImportFixtures.manifest()
        val binary = archive(root, "design.jar", manifest)
        val output = File(root, "other-output").apply { mkdirs() }
        val other = BmlImportFixtures.manifest("example:other", packageName = "example.other")
        output.resolve(BmlLibraryArtifacts.MANIFEST_PATH).apply {
            parentFile.mkdirs()
            writeText(BmlLibraryArtifacts.encode(other))
        }
        val ordinaryJar = BmlImportFixtures.archive(File(root, "ordinary.jar"), mapOf("unrelated.txt" to "hello"))
        val classes = File(root, "classes").apply { mkdirs() }

        val dependencies = BmlLibraryDependencies.read(listOf(binary, ordinaryJar, classes, output))

        assertEquals(listOf(manifest, other), dependencies.map { it.manifest })
        assertEquals(listOf(binary.canonicalFile, output.canonicalFile), dependencies.map { it.artifact })
        assertEquals(manifest.components.first().props, dependencies.first().manifest.components.first().props)
    }

    @Test
    fun `canonical paths symlinks and identical diamond manifests deduplicate`() = temporary { root ->
        val manifest = BmlImportFixtures.manifest()
        val jar = archive(root, "design.jar", manifest)
        val link = Files.createSymbolicLink(File(root, "linked.jar").toPath(), jar.toPath()).toFile()
        val copy = archive(root, "copy.jar", manifest)

        val dependencies = BmlLibraryDependencies.read(listOf(jar, link, File(root, "./design.jar"), copy))

        assertEquals(1, dependencies.size)
        assertEquals(jar.canonicalFile, dependencies.single().artifact)
    }

    @Test
    fun `different versions or different contracts for one coordinate fail with both artifacts`() = temporary { root ->
        val manifest = BmlImportFixtures.manifest()
        val original = archive(root, "original.jar", manifest)
        for (conflict in listOf(
            manifest.copy(version = "2.0"),
            manifest.copy(components = manifest.components.map { it.copy(renderRevision = "changed") }),
        )) {
            val other = archive(root, "conflict.jar", conflict)
            val failure = assertFailsWith<IllegalArgumentException> {
                BmlLibraryDependencies.read(listOf(original, other))
            }
            assertTrue("example:design" in failure.message.orEmpty())
            assertTrue(original.path in failure.message.orEmpty())
            assertTrue(other.path in failure.message.orEmpty())
        }
    }

    @Test
    fun `distinct libraries cannot claim the same generated package`() = temporary { root ->
        val first = archive(root, "one.jar", BmlImportFixtures.manifest())
        val second = archive(root, "two.jar", BmlImportFixtures.manifest("example:other"))
        val failure = assertFailsWith<IllegalArgumentException> { BmlLibraryDependencies.read(listOf(first, second)) }
        assertTrue("generated package" in failure.message.orEmpty())
        assertTrue("example:design" in failure.message.orEmpty())
        assertTrue("example:other" in failure.message.orEmpty())
        assertTrue(first.path in failure.message.orEmpty())
        assertTrue(second.path in failure.message.orEmpty())
    }

    @Test
    fun `malformed unsupported and cyclic metadata retain artifact context`() = temporary { root ->
        val manifest = BmlImportFixtures.manifest()
        val encoded = BmlLibraryArtifacts.encode(manifest)
        val cyclic = manifest.copy(components = manifest.components.map {
            if (it.tag == "button") it.copy(dependencies = listOf(BmlLibraryArtifacts.componentId(manifest.library, "button"))) else it
        })
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        for (source in listOf(
            "not json",
            encoded.replace("\"formatVersion\": 5", "\"formatVersion\": 99"),
            encoded.replace("example.design.ButtonComponent", "wrong.ButtonComponent"),
            json.encodeToString(BmlLibraryManifest.serializer(), cyclic),
        )) {
            val artifact = BmlImportFixtures.archive(
                File(root, "bad.jar"), mapOf(BmlLibraryArtifacts.MANIFEST_PATH to source),
            )
            val failure = assertFailsWith<IllegalArgumentException> { BmlLibraryDependencies.read(listOf(artifact)) }
            assertTrue(artifact.path in failure.message.orEmpty())
            assertTrue(failure.cause != null)
        }
    }

    @Test
    fun `missing and unreadable dependency inputs fail instead of becoming absent libraries`() = temporary { root ->
        val missing = File(root, "missing.jar")
        val absent = assertFailsWith<IllegalArgumentException> { BmlLibraryDependencies.read(listOf(missing)) }
        assertTrue(missing.path in absent.message.orEmpty())
        val corrupt = File(root, "corrupt.jar").apply { writeText("not an archive") }
        val unreadable = assertFailsWith<IOException> { BmlLibraryDependencies.read(listOf(corrupt)) }
        assertTrue(corrupt.path in unreadable.message.orEmpty())
        assertTrue(unreadable.cause != null)
        val malformedDirectory = File(root, "bad-output").apply { mkdirs() }
        malformedDirectory.resolve(BmlLibraryArtifacts.MANIFEST_PATH).mkdirs()
        val invalid = assertFailsWith<IOException> { BmlLibraryDependencies.read(listOf(malformedDirectory)) }
        assertTrue(malformedDirectory.path in invalid.message.orEmpty())
    }

    @Test
    fun `subsequent discovery observes changed and removed manifests`() = temporary { root ->
        val artifact = archive(root, "design.jar", BmlImportFixtures.manifest())
        assertEquals("1.0", BmlLibraryDependencies.read(listOf(artifact)).single().manifest.version)
        archive(root, "design.jar", BmlImportFixtures.manifest(version = "2.0"))
        assertEquals("2.0", BmlLibraryDependencies.read(listOf(artifact)).single().manifest.version)
        BmlImportFixtures.archive(artifact, emptyMap())
        assertTrue(BmlLibraryDependencies.read(listOf(artifact)).isEmpty())
    }

    private fun archive(root: File, name: String, manifest: BmlLibraryManifest): File =
        BmlImportFixtures.archive(File(root, name), mapOf(BmlLibraryArtifacts.MANIFEST_PATH to BmlLibraryArtifacts.encode(manifest)))

    private fun temporary(block: (File) -> Unit) {
        val root = createTempDirectory("bml-library-dependencies").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
