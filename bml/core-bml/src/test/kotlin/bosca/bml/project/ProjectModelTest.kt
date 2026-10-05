package bosca.bml.project

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectModelTest {

    @Test
    fun `compiled project round-trips through json`() {
        val project = CompiledProject(
            name = "demo",
            version = "1.0.0",
            pages = mapOf(
                "/" to CompiledPage(route = "/", renderObject = "gen.HomePage", prerendered = true, prerenderedHtml = "<h1>hi</h1>"),
                "/lists/{id}" to CompiledPage(route = "/lists/{id}", renderObject = "gen.ListsPage", metadataId = "m-1"),
            ),
            islandBundles = mapOf("sortable" to "sha256:abc"),
            stylesheets = mapOf("site" to ".a{}"),
            discovery = DiscoveryFiles(robots = "User-agent: *"),
        )
        val json = Json.encodeToString(CompiledProject.serializer(), project)
        val back = Json.decodeFromString(CompiledProject.serializer(), json)
        assertEquals(project, back)
    }

    @Test
    fun `sitemap lists static routes and excludes parameterized ones`() {
        val xml = DiscoveryGenerator.sitemap("https://x.test/", listOf("/", "/about", "/lists/{id}"))
        assertTrue(xml.contains("<loc>https://x.test/</loc>"), xml)
        assertTrue(xml.contains("<loc>https://x.test/about</loc>"), xml)
        assertFalse(xml.contains("{id}"), xml)
        assertTrue(xml.contains("<urlset"), xml)
    }

    @Test
    fun `robots references the sitemap`() {
        val robots = DiscoveryGenerator.robots("https://x.test", disallow = listOf("/admin"))
        assertTrue(robots.contains("User-agent: *"))
        assertTrue(robots.contains("Disallow: /admin"))
        assertTrue(robots.contains("Sitemap: https://x.test/sitemap.xml"))
    }

    @Test
    fun `llms lists pages`() {
        val llms = DiscoveryGenerator.llms("Site", "A demo.", listOf("/", "/about", "/lists/{id}"))
        assertTrue(llms.contains("# Site"))
        assertTrue(llms.contains("> A demo."))
        assertTrue(llms.contains("- /about"))
        assertFalse(llms.contains("{id}"))
    }

    @Test
    fun `content digest is deterministic and prefixed`() {
        val a = ContentDigest.sha256("hello")
        val b = ContentDigest.sha256("hello")
        val c = ContentDigest.sha256("world")
        assertEquals(a, b)
        assertEquals(a, ContentDigest.sha256("hello".toByteArray(Charsets.UTF_8)))
        assertEquals(
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            a,
        )
        assertNotEquals(a, c)
        assertTrue(a.startsWith("sha256:"))
        assertEquals(64, a.removePrefix("sha256:").length)
    }
}
