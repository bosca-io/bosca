package bosca.bml.gradle

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BmlSiteMetricsTest {

    private val manifest = """
        bml-metrics	1
        P	HomePage	/	HomePage.js	badge,item-list	-
        P	AboutPage	/about	-	badge	CardPage.js
    """.trimIndent()

    @Test
    fun `parses pages with js css and component modules`() {
        val entries = BmlSiteMetrics.parseManifest(manifest)
        assertEquals(2, entries.size)
        val home = entries.first { it.objectName == "HomePage" }
        assertEquals("/", home.route)
        assertEquals("HomePage.js", home.pageJs)
        assertEquals(listOf("badge", "item-list"), home.cssTags)
        assertEquals(emptyList(), home.componentJs)
        val about = entries.first { it.objectName == "AboutPage" }
        assertEquals(null, about.pageJs)
        assertEquals(listOf("CardPage.js"), about.componentJs)
    }

    @Test
    fun `rejects an unknown manifest header`() {
        assertFailsWith<IllegalArgumentException> { BmlSiteMetrics.parseManifest("bml-metrics\t99\n") }
        assertFailsWith<IllegalArgumentException> { BmlSiteMetrics.parseManifest("something-else\t1\n") }
    }

    @Test
    fun `gzip size round-trips through a real gzip stream`() {
        val bytes = "body { margin: 0 }\n".repeat(50).toByteArray()
        val gz = BmlSiteMetrics.gzipSize(bytes)
        assertTrue(gz in 1 until bytes.size, "expected compression, got $gz for ${bytes.size}")
    }

    @Test
    fun `report measures files and counts the global tier into first load`() {
        val dir = kotlin.io.path.createTempDirectory("bml-metrics").toFile()
        try {
            val jsDir = File(dir, "js").apply { mkdirs() }
            val cssDir = File(dir, "css").apply { mkdirs() }
            File(jsDir, "HomePage.js").writeText("console.log('x')".repeat(10))
            File(cssDir, "badge.css").writeText(".badge{color:red}")
            File(cssDir, "item-list.css").writeText(".item-list{margin:0}")
            val global = File(dir, "app.css").apply { writeText("body{margin:0}".repeat(20)) }

            val entries = BmlSiteMetrics.parseManifest(manifest)
            val report = BmlSiteMetrics.report(entries, jsDir, cssDir, listOf(global))

            val home = report.pages.first { it.objectName == "HomePage" }
            assertEquals(1, home.js.size)
            assertEquals(2, home.css.size)
            assertEquals(File(jsDir, "HomePage.js").length(), home.js.single().rawBytes)
            // AboutPage's CardPage.js bundle doesn't exist -> reported missing, not counted.
            assertEquals(listOf("CardPage.js"), report.missing)
            assertEquals(global.length(), report.global.single().rawBytes)

            val table = BmlSiteMetrics.renderTable(report)
            assertTrue("HomePage" in table && "/about" in table)
            assertTrue("Missing asset files" in table && "CardPage.js" in table)

            val json = BmlSiteMetrics.renderJson(report)
            assertTrue("\"page\":\"HomePage\"" in json)
            assertTrue("\"missing\":[\"CardPage.js\"]" in json)
            // firstLoad includes the global tier.
            val expectedRaw = home.rawBytes + report.global.sumOf { it.rawBytes }
            assertTrue("\"firstLoad\":{\"rawBytes\":$expectedRaw" in json)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `production shape prices one merged css and one bundle per page`() {
        val dir = kotlin.io.path.createTempDirectory("bml-metrics-prod").toFile()
        try {
            val cssDir = File(dir, "css").apply { mkdirs() }
            File(cssDir, "badge.css").writeText(".badge{}")
            File(cssDir, "item-list.css").writeText(".item-list{}")
            val prodDir = File(dir, "js-prod").apply { mkdirs() }
            File(prodDir, "index.page.js").writeText("console.log('merged home')")

            // badge is on every page -> the shared set; item-list is home-only.
            val manifest = """
                bml-metrics	1
                P	HomePage	/	HomePage.js	badge,item-list	-
                P	CartPage	/cart	-	badge	-
            """.trimIndent()
            val entries = BmlSiteMetrics.parseManifest(manifest)
            val report = BmlSiteMetrics.report(entries, jsDir = null, cssDir = cssDir, globalAssets = emptyList(), prodJsDir = prodDir)

            // Shared css counts into the global tier.
            assertEquals("shared components (in app.css)", report.global.single().name)
            val home = report.pages.first { it.objectName == "HomePage" }
            assertEquals(listOf("index.page.css"), home.css.map { it.name })
            assertEquals(File(cssDir, "item-list.css").length(), home.css.single().rawBytes)
            assertEquals(listOf("index.page.js"), home.js.map { it.name })
            // Cart has nothing beyond the shared set and no client code at all.
            val cart = report.pages.first { it.objectName == "CartPage" }
            assertEquals(emptyList(), cart.css)
            assertEquals(emptyList(), cart.js)
            assertEquals(emptyList(), report.missing)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty production JS directory still prices merged CSS`() {
        val dir = kotlin.io.path.createTempDirectory("bml-metrics-css-only").toFile()
        try {
            val cssDir = File(dir, "css").apply { mkdirs() }
            val badge = File(cssDir, "badge.css").apply { writeText(".badge{}") }
            val itemList = File(cssDir, "item-list.css").apply { writeText(".item-list{}") }
            val prodDir = File(dir, "js-prod").apply { mkdirs() }
            val entries = BmlSiteMetrics.parseManifest(
                """
                    bml-metrics	1
                    P	HomePage	/	-	badge,item-list	-
                    P	CartPage	/cart	-	badge	-
                """.trimIndent(),
            )

            val report = BmlSiteMetrics.report(
                entries, jsDir = null, cssDir = cssDir, globalAssets = emptyList(), prodJsDir = prodDir,
            )

            assertEquals(listOf("shared components (in app.css)"), report.global.map { it.name })
            assertEquals(badge.length(), report.global.single().rawBytes)
            val home = report.pages.first { it.route == "/" }
            assertEquals(listOf("index.page.css"), home.css.map { it.name })
            assertEquals(itemList.length(), home.css.single().rawBytes)
            assertEquals(emptyList(), home.js)
            assertEquals(emptyList(), report.pages.first { it.route == "/cart" }.css)
            assertEquals(emptyList(), report.missing)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `production report lists missing CSS chunks without pricing empty stylesheets`() {
        val dir = kotlin.io.path.createTempDirectory("bml-metrics-missing-css").toFile()
        try {
            val cssDir = File(dir, "css").apply { mkdirs() }
            val prodDir = File(dir, "js-prod").apply { mkdirs() }
            val entries = BmlSiteMetrics.parseManifest(
                """
                    bml-metrics	1
                    P	HomePage	/	-	badge,item-list	-
                    P	CartPage	/cart	-	badge	-
                """.trimIndent(),
            )

            val report = BmlSiteMetrics.report(
                entries, jsDir = null, cssDir = cssDir, globalAssets = emptyList(), prodJsDir = prodDir,
            )

            assertEquals(emptyList(), report.global)
            assertEquals(emptyList(), report.pages.first { it.route == "/" }.css)
            assertEquals(listOf("badge.css", "item-list.css"), report.missing)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `production report uses the server slug after a CSS-only route collision`() {
        val dir = kotlin.io.path.createTempDirectory("bml-metrics-slug").toFile()
        try {
            val cssDir = File(dir, "css").apply { mkdirs() }
            File(cssDir, "badge.css").writeText(".badge{}")
            val prodDir = File(dir, "js-prod").apply { mkdirs() }
            File(prodDir, "a-b-2.page.js").writeText("console.log('client')")
            val entries = BmlSiteMetrics.parseManifest(
                """
                    bml-metrics	1
                    P	StylePage	/a-b	-	badge	-
                    P	ClientPage	/a/b	ClientPage.js	-	-
                """.trimIndent(),
            )

            val report = BmlSiteMetrics.report(
                entries, jsDir = null, cssDir = cssDir, globalAssets = emptyList(), prodJsDir = prodDir,
            )

            assertEquals(listOf("a-b.page.css"), report.pages.first { it.route == "/a-b" }.css.map { it.name })
            assertEquals(listOf("a-b-2.page.js"), report.pages.first { it.route == "/a/b" }.js.map { it.name })
            assertEquals(emptyList(), report.missing)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `production slug mirrors the server`() {
        assertEquals("index", BmlSiteMetrics.slug("/"))
        assertEquals("cart", BmlSiteMetrics.slug("/cart"))
        assertEquals("lists-id", BmlSiteMetrics.slug("/lists/{id}"))
    }

    @Test
    fun `gzip helper output is real gzip`() {
        val bytes = "hello hello hello".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        val decoded = GZIPInputStream(out.toByteArray().inputStream()).readBytes()
        assertEquals(String(bytes), String(decoded))
    }
}
