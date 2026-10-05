package bosca.bml.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BmlProjectGeneratorTest {

    @Test
    fun `derives object names`() {
        val g = BmlProjectGenerator("p")
        assertEquals("HomePage", g.objectNameFor("home.bml"))
        assertEquals("WelcomeEmailPage", g.objectNameFor("welcome-email.bml"))
        assertEquals("MessagesWelcomeMessage", g.objectNameFor("messages/welcome.bml", "Message"))
        assertEquals("ListsPage", g.objectNameFor("lists.bml"))
        // Directory segments participate, so pages/library/index.bml and pages/read/index.bml
        // can never collide onto one object (and silently drop routes).
        assertEquals("PagesLibraryIndexPage", g.objectNameFor("pages/library/index.bml"))
        assertEquals("PagesReadIndexPage", g.objectNameFor("pages/read/index.bml"))
    }

    @Test
    fun `generates a kt file for each bml under the source root`() {
        val root = File.createTempFile("bml-proj", "").apply { delete(); mkdirs() }
        val src = File(root, "src").apply { mkdirs() }
        val homeSource = """<page route="/"><h1>{ "hi" }</h1></page>"""
        File(src, "home.bml").writeText(homeSource)
        File(src, "nested/about.bml").apply { parentFile.mkdirs() }
            .writeText("""<page route="/about"><p>about</p></page>""")
        File(src, "messages/welcome.bml").apply { parentFile.mkdirs() }
            .writeText("""<message key="welcome"><push><title>Hi</title><body>Welcome</body></push></message>""")
        val out = File(root, "out")

        val result = BmlProjectGenerator("bml.generated").generateAll(src, out)

        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(3, result.written.size)
        val home = File(out, "HomePage.kt")
        assertTrue(home.exists())
        val text = home.readText()
        assertTrue(text.contains("package bml.generated"))
        assertTrue(text.contains("public object HomePage : bosca.bml.render.BmlPageRenderer {"))
        assertTrue(text.contains("@BmlPage(route = \"/\")"))
        assertTrue(text.contains("override val renderRevision: String = \"${bmlRenderRevision(homeSource)}\""))
        assertTrue(File(out, "NestedAboutPage.kt").exists()) // directory segments participate in the name
        assertTrue(File(out, "MessagesWelcomeMessage.kt").exists())
    }

    @Test
    fun `missing source root yields nothing`() {
        val out = File.createTempFile("bml-out", "").apply { delete(); mkdirs() }
        val result = BmlProjectGenerator("p").generateAll(File("/no/such/dir"), out)
        assertTrue(result.written.isEmpty())
    }

    @Test
    fun `render revision includes compiler identity`() {
        val source = """<page route="/"/>"""
        assertNotEquals(
            bmlRenderRevision(source, compilerRevision = "compiler-a"),
            bmlRenderRevision(source, compilerRevision = "compiler-b"),
        )
    }

    @Test
    fun `project generation rejects messages using cross-file deferred components`() {
        val root = File.createTempFile("bml-project-deferred", "").apply { delete(); mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        File(source, "private-card.bml").writeText(
            """<component tag="private-card"><island name="account" render="deferred">Private</island></component>""",
        )
        File(source, "message.bml").writeText(
            """<message key="notice"><email><subject>Notice</subject><private-card/></email></message>""",
        )

        val result = BmlProjectGenerator("p").generateAll(source, File(root, "out"))

        assertTrue(result.errors.any { "unavailable in <message>" in it.message }, result.errors.toString())
    }

    @Test
    fun `project generation rejects cross-file client components in deferred fallbacks`() {
        val root = File.createTempFile("bml-project-fallback-client", "").apply { delete(); mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        File(source, "interactive-card.bml").writeText(
            """<component tag="interactive-card"><button @click="account.retry">Retry</button></component>""",
        )
        File(source, "fallback-wrapper.bml").writeText(
            """<component tag="fallback-wrapper"><interactive-card/></component>""",
        )
        File(source, "page.bml").writeText(
            """<page route="/"><island name="private" render="deferred">""" +
                """<fallback><fallback-wrapper/></fallback><p>Private</p></island></page>""",
        )

        val result = BmlProjectGenerator("p").generateAll(source, File(root, "out"))

        assertTrue(
            result.errors.any { "cannot contain <script client> or declarative actions" in it.message },
            result.errors.toString(),
        )
    }

    @Test
    fun `project generation rejects shared pages using cross-file private components`() {
        val root = File.createTempFile("bml-project-private-cache", "").apply { delete(); mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        File(source, "private-counter.bml").writeText(
            """<component tag="private-counter"><prop name="id" type="String" required/>""" +
                """<script server provides="counter" scope="server-session">Counter()</script>""" +
                """<island name="counter" :key="id"><button @click="counter.increment">+</button></island>""" +
                """</component>""",
        )
        File(source, "private-wrapper.bml").writeText(
            """<component tag="private-wrapper"><private-counter id="one"/></component>""",
        )
        File(source, "page.bml").writeText(
            """<page route="/" cache="shared" maxAge="60"><private-wrapper/></page>""",
        )
        File(source, "private-offer.bml").writeText(
            """<component tag="private-offer"><if flag="private-offer"><p>Offer</p></if></component>""",
        )
        File(source, "offer-wrapper.bml").writeText(
            """<component tag="offer-wrapper"><private-offer/></component>""",
        )
        File(source, "offers.bml").writeText(
            """<page route="/offers" cache="shared" maxAge="60"><offer-wrapper/></page>""",
        )

        val result = BmlProjectGenerator("p").generateAll(source, File(root, "out"))

        assertTrue(
            result.errors.any { "server-session state in its render closure" in it.message },
            result.errors.toString(),
        )
        assertTrue(
            result.errors.any { "feature flags through a component" in it.message },
            result.errors.toString(),
        )
    }
}
