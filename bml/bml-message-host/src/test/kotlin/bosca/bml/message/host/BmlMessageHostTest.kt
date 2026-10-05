@file:OptIn(org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class)

package bosca.bml.message.host

import bosca.bml.compiler.plugin.BmlAdditionalSourcesExtension
import bosca.bml.compiler.plugin.BmlConfigKeys
import bosca.bml.i18n.MessageCatalog
import bosca.bml.i18n.MessageSource
import bosca.bml.message.BmlMessageContext
import bosca.bml.message.BmlPushOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the hosting engine against real message jars: `.bml` → the K2 extension's
 * generated Kotlin + embedded manifest → embedded kotlinc → jar → child-classloader load,
 * discovery, render, fail-closed errors, and the drain-on-swap lifecycle.
 */
class BmlMessageHostTest {

    // ── jar building (the same pipeline the Gradle plugin drives) ─────────────

    private fun buildMessageJar(name: String, bml: String): File {
        val work = File.createTempFile("bml-message-host-$name", "").apply { delete(); mkdirs() }
        val srcRoot = File(work, "bml").apply { mkdirs() }
        File(srcRoot, "messages").mkdirs()
        File(srcRoot, "messages/welcome.bml").writeText(bml)

        val ktOut = File(work, "kt")
        val resOut = File(work, "res")
        val config = CompilerConfiguration().apply {
            add(BmlConfigKeys.SOURCE_ROOTS, srcRoot.absolutePath)
            put(BmlConfigKeys.KOTLIN_OUTPUT_DIR, ktOut.absolutePath)
            put(BmlConfigKeys.RESOURCES_OUTPUT_DIR, resOut.absolutePath)
        }
        BmlAdditionalSourcesExtension().collectSources(Any(), config, { null }, emptyList())

        val classesDir = File(work, "classes").apply { mkdirs() }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = ktOut.listFiles().orEmpty().filter { it.extension == "kt" }.map { it.absolutePath }
            destination = classesDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        val exit = runEmbeddedCompiler(collector, args)
        assertEquals(ExitCode.OK, exit, "message jar compile failed:\n$messages")

        val jar = File(work, "$name.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            for (root in listOf(classesDir, resOut)) {
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    out.putNextEntry(JarEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        return jar
    }

    /** Runs the embedded Kotlin compiler reflectively (avoids a literal shell-exec token). */
    private fun runEmbeddedCompiler(collector: MessageCollector, args: K2JVMCompilerArguments): ExitCode {
        val compiler = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            .getDeclaredConstructor().newInstance()
        val method = compiler.javaClass.methods.first {
            it.name == "exec" && it.parameterTypes.size == 3 && it.parameterTypes[1] == Services::class.java
        }
        return method.invoke(compiler, collector, Services.EMPTY, args) as ExitCode
    }

    private fun versionJar(label: String): File = buildMessageJar(
        "v-$label",
        """<message key="welcome"><email><subject>Subject $label</subject>""" +
            """<p>content-$label</p></email></message>""",
    )

    // ── load / discover / render ──────────────────────────────────────────────

    @Test
    fun `loads a compiled jar, indexes templates, and renders across the classloader boundary`() {
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(versionJar("one"), "1")
            assertEquals("1", host.activeVersion)
            assertEquals(setOf("welcome"), host.templateKeys)
            val rendered = runBlocking { host.render("welcome", BmlMessageContext()) }
            val email = rendered.email ?: error("email channel missing")
            assertEquals("Subject one", email.subject)
            assertTrue(email.html.contains("content-one"), email.html)
            assertTrue(email.text.contains("content-one"), email.text)
        }
    }

    @Test
    fun `loads and renders a companion push from the same compiled message jar`() {
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(
                buildMessageJar(
                    "push",
                    """<message key="welcome">""" +
                        """<push><title>Ada in Planning</title><body>Project update</body></push>""" +
                        """<email><subject>Email</subject><p>Email body</p></email></message>""",
                ),
                "1",
            )

            assertTrue(host.supportsPush("welcome"))
            val rendered = runBlocking {
                host.render(
                    "welcome",
                    BmlMessageContext(pushOptions = BmlPushOptions(threadId = "chat-planning")),
                )
            }
            assertEquals("Ada in Planning", rendered.push?.title)
            assertEquals("Project update", rendered.push?.body)
            assertEquals("chat-planning", rendered.push?.options?.threadId)
            val email = rendered.email ?: error("email channel missing")
            assertTrue("Email body" in email.html)
            assertTrue("Ada in Planning" !in email.html)
        }
    }

    @Test
    fun `loads a generalized message module and localizes its push channel`() {
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(
                buildMessageJar(
                    "message",
                    """<message key="welcome"><push>""" +
                        """<title t="welcome.push.title">Welcome, { message.recipientName }</title>""" +
                        """<body t="welcome.push.body">Your account is ready.</body></push>""" +
                        """<email><subject>Welcome</subject><p>Email body</p></email></message>""",
                ),
                "1",
            )
            val context = BmlMessageContext(
                recipientName = "Ada",
                locale = "es",
                messages = MessageSource.of(
                    defaultLocale = Locale.forLanguageTag("en"),
                    catalogs = mapOf(
                        "es" to MessageCatalog(
                            messages = mapOf(
                                "welcome.push.title" to "Bienvenida, {recipientName}",
                                "welcome.push.body" to "Tu cuenta está lista.",
                            ),
                        ),
                    ),
                ),
            )

            val rendered = runBlocking { host.render("welcome", context) }

            assertEquals("Bienvenida, Ada", rendered.push?.title)
            assertEquals("Tu cuenta está lista.", rendered.push?.body)
            assertTrue("Email body" in (rendered.email ?: error("email channel missing")).html)
        }
    }

    @Test
    fun `the embedded manifest reads without classloading`() {
        val manifest = assertNotNull(BmlMessageJarManifest.read(versionJar("m")))
        assertEquals(1, manifest.manifestVersion)
        assertEquals("bml.generated.BmlMessages", manifest.module)
        assertEquals(listOf("welcome"), manifest.templates.map { it.key })
        assertEquals("messages/welcome.bml", manifest.templates.single().source)
        assertEquals(false, manifest.templates.single().supportsPush)
    }

    // ── fail closed ───────────────────────────────────────────────────────────

    @Test
    fun `an unknown template key fails closed`() {
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(versionJar("x"), "1")
            val failure = assertFailsWith<BmlMessageHostException> {
                runBlocking { host.render("nope", BmlMessageContext()) }
            }
            assertTrue("nope" in failure.message.orEmpty(), failure.message.orEmpty())
        }
    }

    @Test
    fun `rendering before any version is loaded fails closed`() {
        val host = BmlMessageProjectHost(javaClass.classLoader)
        assertFailsWith<BmlMessageHostException> {
            runBlocking { host.render("welcome", BmlMessageContext()) }
        }
    }

    @Test
    fun `a jar without the module entry point is rejected and never activates`() {
        val work = File.createTempFile("bml-message-host-empty", "").apply { delete(); mkdirs() }
        val empty = File(work, "empty.jar")
        JarOutputStream(empty.outputStream()).use {}
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(versionJar("good"), "1")
            assertFailsWith<BmlMessageHostException> { host.swap(empty, "2") }
            // The bad artifact must not displace the working version (fail closed, keep serving).
            assertEquals("1", host.activeVersion)
            val rendered = runBlocking { host.render("welcome", BmlMessageContext()) }
            val email = rendered.email ?: error("email channel missing")
            assertTrue(email.html.contains("content-good"), email.html)
        }
    }

    // ── class unloading (hot reload must not leak retired versions) ───

    @Test
    fun `bisect A - a closed jar with no render unloads`() {
        // A real (non-inline) helper frame: an inline run{} would keep the jar local alive in THIS
        // interpreted frame until method exit, pinning the loader — a test artifact, not a leak.
        assertTrue(awaitCollected(loadCloseAndRef("bisect-a", render = false)), "load+close alone leaked the classloader")
    }

    @Test
    fun `bisect B - a rendered then closed jar unloads`() {
        assertTrue(awaitCollected(loadCloseAndRef("bisect-b", render = true)), "rendering pinned the classloader")
    }

    private fun loadCloseAndRef(label: String, render: Boolean): java.lang.ref.WeakReference<ClassLoader> {
        val jar = BmlMessageJar.load(versionJar(label), javaClass.classLoader)
        val ref = java.lang.ref.WeakReference(jar.templates.values.first().javaClass.classLoader)
        if (render) runBlocking { jar.render("welcome", BmlMessageContext()) }
        jar.close()
        return ref
    }

    @Test
    fun `a retired version's classes and classloader unload after the swap drains`() {
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(versionJar("unload-a"), "1")
            runBlocking { host.render("welcome", BmlMessageContext()) } // v1 serves
            // Weak refs only — taken in a helper frame so no strong ref survives on this frame.
            val (classRef, loaderRef) = weakRefsToActiveGeneration(host)

            host.swap(versionJar("unload-b"), "2")
            val fresh = runBlocking { host.render("welcome", BmlMessageContext()) }
            val email = fresh.email ?: error("email channel missing")
            assertTrue(email.html.contains("content-unload-b"), email.html)

            // After the swap dropped the last strong reference, GC must collect the retired
            // generation — otherwise every publish leaks a classloader in a long-lived server.
            assertTrue(
                awaitCollected(classRef) && awaitCollected(loaderRef),
                "retired template class/classloader were not garbage-collected after the swap " +
                    "(class=${classRef.get()}, loader=${loaderRef.get()}) — a leaked generation per publish",
            )
        }
    }

    /** Weak refs to the ACTIVE generation's template class + child loader (no strong refs escape). */
    private fun weakRefsToActiveGeneration(
        host: BmlMessageProjectHost,
    ): Pair<java.lang.ref.WeakReference<Class<*>>, java.lang.ref.WeakReference<ClassLoader>> {
        val templateClass = host.activeJarForTest!!.templates.values.first().javaClass
        return java.lang.ref.WeakReference<Class<*>>(templateClass) to
            java.lang.ref.WeakReference(templateClass.classLoader)
    }

    /** GC-poke loop: weak refs clear promptly once unreachable; bounded so a leak fails fast. */
    private fun awaitCollected(ref: java.lang.ref.WeakReference<*>): Boolean {
        repeat(50) {
            if (ref.get() == null) return true
            System.gc()
            Thread.sleep(50)
        }
        return ref.get() == null
    }

    // ── hot swap ────────────────────────────────────────────────

    @Test
    fun `a swap serves the new version immediately while the old drains its in-flight render`() {
        val gated = buildMessageJar(
            "gated",
            """<message key="welcome">""" +
                """<script server provides="g">bosca.bml.message.host.SwapGate.enter()</script>""" +
                """<email><subject>Gated</subject><p>old-{ g }</p></email></message>""",
        )
        val host = BmlMessageProjectHost(javaClass.classLoader)
        host.use {
            host.swap(gated, "1")
            runBlocking {
                val inFlight = async(Dispatchers.IO) { host.render("welcome", BmlMessageContext()) }
                SwapGate.awaitEntered() // the old version's render is now parked mid-flight

                host.swap(versionJar("two"), "2")
                assertEquals("2", host.activeVersion)
                // New renders serve the new version immediately…
                val fresh = host.render("welcome", BmlMessageContext())
                val freshEmail = fresh.email ?: error("email channel missing")
                assertTrue(freshEmail.html.contains("content-two"), freshEmail.html)

                // …while the in-flight render completes on the version it started on.
                SwapGate.open()
                val drained = inFlight.await()
                val drainedEmail = drained.email ?: error("email channel missing")
                assertTrue(drainedEmail.html.contains("old-gated"), drainedEmail.html)
            }
        }
    }
}
