package bosca.artifacts.ui

import kotlin.test.*

class ArtifactPresentationTest {
    private fun repository(type: String, name: String = "tool", namespace: String = "builds") = ArtifactRepositoryView("repo", "ns", namespace, name, type, "", "")
    @Test fun `file URLs follow registry layouts and only expose addressable roles`() {
        assertTrue(artifactDownload(repository("raw"), "1.0", "file", "a b.zip").endsWith("/raw/builds/tool/1.0/a%20b.zip"))
        assertTrue(artifactDownload(repository("maven", "com.example.tool"), "1.0", "jar", "tool.jar").endsWith("/maven/builds/com/example/tool/1.0/tool.jar"))
        assertTrue(artifactDownload(repository("npm", namespace = "_unscoped"), "1.0", "tarball", "").endsWith("/npm/tool/-/tool-1.0.tgz"))
        assertTrue(artifactDownload(repository("npm", namespace = "@team"), "1.0", "tarball", "tool.tgz").endsWith("/npm/@team/tool/-/tool.tgz"))
        assertTrue(artifactDownload(repository("helm"), "1.0", "chart", "").endsWith("/helm/builds/charts/tool/1.0"))
        assertTrue(artifactDownload(repository("ml"), "1.0", "model", "").endsWith("/ml/builds/tool/1.0"))
        assertEquals("", artifactDownload(repository("docker"), "1.0", "manifest", "manifest.json"))
        assertEquals("", artifactDownload(repository("npm"), "1.0", "metadata", "package.json"))
        assertEquals("", artifactDownload(repository("helm"), "1.0", "other", "chart.tgz"))
        assertEquals("", artifactDownload(repository("raw"), "1.0", "file", ""))
    }
    @Test fun `publication status distinguishes failed current verification from historical success`() {
        fun result(attempts: Int, verifiedAttempt: Int?, error: String = "") = ArtifactPublicationView("id", "dest", "v1", "commit", attempts, "published", "verified", verifiedAttempt, error, emptyList())
        assertEquals("Verified", result(2, 2).status)
        assertEquals("Failed", result(3, 2, "remote unavailable").status)
        assertEquals("Published; verification pending", result(3, 2).status)
    }
    @Test fun `pagination rejects malformed and negative offsets`() {
        assertEquals(0L, artifactOffset("-1"))
        assertEquals(0L, artifactOffset("bad"))
        assertEquals(30L, artifactOffset("30"))
    }
}
