package bosca.artifacts.maven.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies that the Maven route URL structure `/maven/{repository}/{standard maven path}`
 * produces the correct namespace and repository name for storage.
 *
 * The route handler extracts `{repository}` as the namespace and passes the remaining
 * path to [parseMavenPath]. The repository name stored is `"{groupId}.{artifactId}"`.
 *
 * These tests prove the invariant: all artifacts published to a single repository URL
 * (e.g., `/maven/bosca-maven/...`) will be stored under a single namespace (`bosca-maven`)
 * regardless of their Maven groupId or artifactId.
 */
class MavenRoutingTest {

    private fun deriveStorageKey(repositoryUrlSegment: String, mavenPath: String): Pair<String, String>? {
        val coords = parseMavenPath("/$mavenPath") ?: return null
        if (coords.version == null) return null
        val namespace = repositoryUrlSegment
        val repoName = "${coords.groupId}.${coords.artifactId}"
        return namespace to repoName
    }

    @Test
    fun `standard io_bosca artifact resolves to bosca-maven namespace`() {
        val (namespace, repoName) = deriveStorageKey(
            "bosca-maven",
            "io/bosca/framework-di/0.0.1/framework-di-0.0.1.jar"
        )!!
        assertEquals("bosca-maven", namespace)
        assertEquals("io.bosca.framework-di", repoName)
    }

    @Test
    fun `KMP platform artifact resolves to same namespace`() {
        val (namespace, repoName) = deriveStorageKey(
            "bosca-maven",
            "io/bosca/framework-di-js/1.0.0/framework-di-js-1.0.0.klib"
        )!!
        assertEquals("bosca-maven", namespace)
        assertEquals("io.bosca.framework-di-js", repoName)
    }

    @Test
    fun `deep group path resolves correctly`() {
        val (namespace, repoName) = deriveStorageKey(
            "bosca-maven",
            "org/apache/commons/commons-lang3/3.14/commons-lang3-3.14.jar"
        )!!
        assertEquals("bosca-maven", namespace)
        assertEquals("org.apache.commons.commons-lang3", repoName)
    }

    @Test
    fun `single-segment group resolves correctly`() {
        val (namespace, repoName) = deriveStorageKey(
            "bosca-maven",
            "com/lib/1.0/lib-1.0.jar"
        )!!
        assertEquals("bosca-maven", namespace)
        assertEquals("com.lib", repoName)
    }

    @Test
    fun `all artifacts share the same namespace regardless of group`() {
        val keys = listOf(
            "io/bosca/framework-di/1.0/framework-di-1.0.jar",
            "io/bosca/framework-di-js/1.0/framework-di-js-1.0.klib",
            "io/bosca/backend-framework-core/1.0/backend-framework-core-1.0.jar",
            "org/jetbrains/kotlin/kotlin-stdlib/2.0/kotlin-stdlib-2.0.jar",
        ).map { deriveStorageKey("bosca-maven", it)!! }

        val namespaces = keys.map { it.first }.toSet()
        assertEquals(setOf("bosca-maven"), namespaces)
    }

    @Test
    fun `different repository URL segments produce different namespaces`() {
        val (ns1, _) = deriveStorageKey("bosca-maven", "io/bosca/lib/1.0/lib-1.0.jar")!!
        val (ns2, _) = deriveStorageKey("third-party", "io/bosca/lib/1.0/lib-1.0.jar")!!

        assertEquals("bosca-maven", ns1)
        assertEquals("third-party", ns2)
    }

    @Test
    fun `metadata paths do not produce storage keys`() {
        val result = deriveStorageKey("bosca-maven", "io/bosca/framework-di/maven-metadata.xml")
        assertNull(result)
    }

    @Test
    fun `checksum paths are identified correctly`() {
        val coords = parseMavenPath("/io/bosca/framework-di/0.0.1/framework-di-0.0.1.jar.sha1")
        assertNotNull(coords)
        assertEquals(true, isChecksumFile(coords.filename))
    }

    @Test
    fun `repository name uses dot separator and avoids scope-breaking characters`() {
        val coords = parseMavenPath("/io/bosca/framework-di/0.0.1/framework-di-0.0.1.jar")!!
        val repoName = "${coords.groupId}.${coords.artifactId}"
        assertEquals("io.bosca.framework-di", repoName)
        assert(!repoName.contains(':')) { "repository name must not contain colons (scope delimiter)" }
        assert(!repoName.contains('/')) { "repository name must not contain slashes (scope path separator)" }
    }
}
