package bosca.artifacts.maven.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure utility functions in [MavenRouteSupport].
 *
 * The [parseMavenPath] function receives the path AFTER the repository prefix
 * has been stripped by the route handler. It parses standard Maven repository
 * layout paths into coordinates.
 */
class MavenRoutesSupportTest {

    // ---------------------------------------------------------------
    // parseMavenPath
    // ---------------------------------------------------------------

    @Test
    fun `parseMavenPath parses a standard versioned artifact path`() {
        val coords = parseMavenPath("/io/bosca/framework-di/0.0.1/framework-di-0.0.1.jar")
        assertNotNull(coords)
        assertEquals("io.bosca", coords.groupId)
        assertEquals("framework-di", coords.artifactId)
        assertEquals("0.0.1", coords.version)
        assertEquals("framework-di-0.0.1.jar", coords.filename)
    }

    @Test
    fun `parseMavenPath parses a single-segment group`() {
        val coords = parseMavenPath("/com/lib/1.0/lib-1.0.jar")
        assertNotNull(coords)
        assertEquals("com", coords.groupId)
        assertEquals("lib", coords.artifactId)
        assertEquals("1.0", coords.version)
        assertEquals("lib-1.0.jar", coords.filename)
    }

    @Test
    fun `parseMavenPath parses deep group segments`() {
        val coords = parseMavenPath("/org/apache/commons/commons-lang3/3.14/commons-lang3-3.14.jar")
        assertNotNull(coords)
        assertEquals("org.apache.commons", coords.groupId)
        assertEquals("commons-lang3", coords.artifactId)
        assertEquals("3.14", coords.version)
        assertEquals("commons-lang3-3.14.jar", coords.filename)
    }

    @Test
    fun `parseMavenPath parses artifact-level metadata path`() {
        val coords = parseMavenPath("/io/bosca/framework-di/maven-metadata.xml")
        assertNotNull(coords)
        assertEquals("io.bosca", coords.groupId)
        assertEquals("framework-di", coords.artifactId)
        assertNull(coords.version)
        assertEquals("maven-metadata.xml", coords.filename)
    }

    @Test
    fun `parseMavenPath parses metadata checksum paths`() {
        val sha1 = parseMavenPath("/io/bosca/framework-di/maven-metadata.xml.sha1")
        assertNotNull(sha1)
        assertEquals("io.bosca", sha1.groupId)
        assertEquals("framework-di", sha1.artifactId)
        assertNull(sha1.version)
        assertEquals("maven-metadata.xml.sha1", sha1.filename)

        val sha256 = parseMavenPath("/com/example/lib/maven-metadata.xml.sha256")
        assertNotNull(sha256)
        assertEquals("com.example", sha256.groupId)

        val md5 = parseMavenPath("/com/example/lib/maven-metadata.xml.md5")
        assertNotNull(md5)
        assertEquals("com.example", md5.groupId)
    }

    @Test
    fun `parseMavenPath parses maven-metadata-local xml as metadata`() {
        val coords = parseMavenPath("/io/bosca/framework-di/maven-metadata-local.xml")
        assertNotNull(coords)
        assertEquals("io.bosca", coords.groupId)
        assertEquals("framework-di", coords.artifactId)
        assertNull(coords.version)
        assertEquals("maven-metadata-local.xml", coords.filename)
    }

    @Test
    fun `parseMavenPath parses maven-metadata-local checksum as metadata`() {
        val sha512 = parseMavenPath("/io/bosca/framework-di/maven-metadata-local.xml.sha512")
        assertNotNull(sha512)
        assertEquals("io.bosca", sha512.groupId)
        assertEquals("framework-di", sha512.artifactId)
        assertNull(sha512.version)
        assertEquals("maven-metadata-local.xml.sha512", sha512.filename)
    }

    @Test
    fun `parseMavenPath returns null for too-short paths`() {
        assertNull(parseMavenPath("/com/lib"))
        assertNull(parseMavenPath("/single"))
        assertNull(parseMavenPath("/"))
        assertNull(parseMavenPath(""))
    }

    @Test
    fun `parseMavenPath returns null when group would be empty`() {
        assertNull(parseMavenPath("/lib/1.0/file.jar"))
        assertNull(parseMavenPath("/lib/maven-metadata.xml"))
    }

    @Test
    fun `parseMavenPath rejects paths with traversal segments`() {
        assertNull(parseMavenPath("/com/../etc/passwd/1.0/file.jar"))
        assertNull(parseMavenPath("/com/example/.hidden/1.0/file.jar"))
    }

    // ---------------------------------------------------------------
    // parsePomCoordinates
    // ---------------------------------------------------------------

    @Test
    fun `parsePomCoordinates extracts coordinates from a standard POM`() {
        val pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.bosca</groupId>
              <artifactId>backend-framework-workops-jobs</artifactId>
              <version>4.7.17</version>
            </project>
        """.trimIndent()
        val coords = parsePomCoordinates(pom.byteInputStream())
        assertNotNull(coords)
        assertEquals("io.bosca", coords.groupId)
        assertEquals("backend-framework-workops-jobs", coords.artifactId)
        assertEquals("4.7.17", coords.version)
    }

    @Test
    fun `parsePomCoordinates inherits groupId and version from parent`() {
        val pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.bosca</groupId>
                <artifactId>parent</artifactId>
                <version>4.7.17</version>
              </parent>
              <artifactId>my-module</artifactId>
            </project>
        """.trimIndent()
        val coords = parsePomCoordinates(pom.byteInputStream())
        assertNotNull(coords)
        assertEquals("io.bosca", coords.groupId)
        assertEquals("my-module", coords.artifactId)
        assertEquals("4.7.17", coords.version)
    }

    @Test
    fun `parsePomCoordinates prefers project-level over parent`() {
        val pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.bosca.parent</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
              </parent>
              <groupId>io.bosca.child</groupId>
              <artifactId>my-module</artifactId>
              <version>2.0.0</version>
            </project>
        """.trimIndent()
        val coords = parsePomCoordinates(pom.byteInputStream())
        assertNotNull(coords)
        assertEquals("io.bosca.child", coords.groupId)
        assertEquals("my-module", coords.artifactId)
        assertEquals("2.0.0", coords.version)
    }

    @Test
    fun `parsePomCoordinates returns null for missing artifactId`() {
        val pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.bosca</groupId>
              <version>1.0</version>
            </project>
        """.trimIndent()
        assertNull(parsePomCoordinates(pom.byteInputStream()))
    }

    @Test
    fun `parsePomCoordinates returns null for malformed XML`() {
        assertNull(parsePomCoordinates("not xml at all".byteInputStream()))
    }

    // ---------------------------------------------------------------
    // mavenRole
    // ---------------------------------------------------------------

    @Test
    fun `mavenRole returns correct role for known extensions`() {
        assertEquals("pom", mavenRole("library-1.0.pom"))
        assertEquals("jar", mavenRole("library-1.0.jar"))
        assertEquals("sources", mavenRole("library-1.0-sources.jar"))
        assertEquals("javadoc", mavenRole("library-1.0-javadoc.jar"))
        assertEquals("aar", mavenRole("library-1.0.aar"))
        assertEquals("klib", mavenRole("library-1.0.klib"))
        assertEquals("module", mavenRole("library-1.0.module"))
        assertEquals("signature", mavenRole("library-1.0.jar.asc"))
    }

    @Test
    fun `mavenRole returns artifact for unknown extensions`() {
        assertEquals("artifact", mavenRole("library-1.0.zip"))
        assertEquals("artifact", mavenRole("README.txt"))
    }

    // ---------------------------------------------------------------
    // isChecksumFile
    // ---------------------------------------------------------------

    @Test
    fun `isChecksumFile returns true for checksum extensions`() {
        assertTrue(isChecksumFile("file.sha1"))
        assertTrue(isChecksumFile("file.sha256"))
        assertTrue(isChecksumFile("file.md5"))
    }

    @Test
    fun `isChecksumFile returns false for non-checksum filenames and null`() {
        assertFalse(isChecksumFile("file.jar"))
        assertFalse(isChecksumFile("file.pom"))
        assertFalse(isChecksumFile(null))
    }

    // ---------------------------------------------------------------
    // isMetadataChecksumFile
    // ---------------------------------------------------------------

    @Test
    fun `isMetadataChecksumFile identifies metadata checksum filenames`() {
        assertTrue(isMetadataChecksumFile("maven-metadata.xml.sha1"))
        assertTrue(isMetadataChecksumFile("maven-metadata.xml.sha256"))
        assertTrue(isMetadataChecksumFile("maven-metadata.xml.md5"))
        assertTrue(isMetadataChecksumFile("maven-metadata.xml.sha512"))
        assertTrue(isMetadataChecksumFile("maven-metadata-local.xml.sha1"))
        assertTrue(isMetadataChecksumFile("maven-metadata-local.xml.sha512"))
        assertFalse(isMetadataChecksumFile("maven-metadata.xml"))
        assertFalse(isMetadataChecksumFile("file.sha1"))
        assertFalse(isMetadataChecksumFile(null))
    }

    // ---------------------------------------------------------------
    // escapeXml
    // ---------------------------------------------------------------

    @Test
    fun `escapeXml replaces all special characters`() {
        assertEquals("&amp;", escapeXml("&"))
        assertEquals("&lt;", escapeXml("<"))
        assertEquals("&gt;", escapeXml(">"))
        assertEquals("&quot;", escapeXml("\""))
        assertEquals("&apos;", escapeXml("'"))
    }

    @Test
    fun `escapeXml handles mixed content`() {
        assertEquals(
            "a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;",
            escapeXml("a & b <c> \"d\" 'e'"),
        )
    }

    @Test
    fun `escapeXml leaves plain text unchanged`() {
        assertEquals("com.example.lib", escapeXml("com.example.lib"))
    }

    // ---------------------------------------------------------------
    // guessMavenContentType
    // ---------------------------------------------------------------

    @Test
    fun `guessMavenContentType returns correct content types`() {
        assertEquals("application/xml", guessMavenContentType("lib-1.0.pom"))
        assertEquals("application/java-archive", guessMavenContentType("lib-1.0.jar"))
        assertEquals("application/octet-stream", guessMavenContentType("lib-1.0.aar"))
        assertEquals("application/json", guessMavenContentType("lib-1.0.module"))
        assertEquals("text/plain", guessMavenContentType("lib-1.0.jar.asc"))
    }

    @Test
    fun `guessMavenContentType falls back to octet-stream for unknown extensions`() {
        assertEquals("application/octet-stream", guessMavenContentType("lib-1.0.zip"))
        assertEquals("application/octet-stream", guessMavenContentType("lib-1.0.klib"))
    }
}
