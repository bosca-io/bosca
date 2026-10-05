package bosca.analytics.compiler

import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.functions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsCompilerPluginTest {
    @Test
    fun `annotations do not manufacture generic function entry events`() {
        val work = newWorkDir()
        val source = File(work, "Subject.kt").apply {
            writeText(
                """
                package sample

                import bosca.analytics.AnalyticsIgnore
                import bosca.analytics.AutoInstrument

                @AutoInstrument(id = "subject", elementType = "service")
                class Subject {
                    fun inherited(): Int { return 42 }

                    @AnalyticsIgnore
                    fun ignored(): Int = -1

                    @AutoInstrument(id = "save", elementType = "action")
                    fun save(): String = "saved"

                    @AutoInstrument(id = "load", elementType = "operation")
                    suspend fun load(): String = "loaded"
                }
                """.trimIndent(),
            )
        }
        val output = File(work, "out").apply { mkdirs() }

        val (exit, messages) = compile(output, source)
        assertEquals(ExitCode.OK, exit, "compiler plugin failed:\n$messages")

        val loader = URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader)
        val subjectClass = loader.loadClass("sample.Subject").kotlin
        val subject = subjectClass.createInstance()
        assertEquals(42, subjectClass.functions.single { it.name == "inherited" }.call(subject))
        assertEquals(-1, subjectClass.functions.single { it.name == "ignored" }.call(subject))
        assertEquals("saved", subjectClass.functions.single { it.name == "save" }.call(subject))
        runBlocking { assertEquals("loaded", subjectClass.functions.single { it.name == "load" }.callSuspend(subject)) }
        val bytecode = File(output, "sample/Subject.class").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytecode.contains("recordAutoInstrumented"))
    }

    @Test
    fun `K2 plugin instruments Compose screens controls and scrolling`() {
        val work = newWorkDir()
        val source = File(work, "LibraryScreen.kt").apply {
            writeText(
                """
                package sample

                import androidx.compose.foundation.ScrollState
                import androidx.compose.foundation.clickable
                import androidx.compose.foundation.horizontalScroll
                import androidx.compose.foundation.layout.Column
                import androidx.compose.foundation.lazy.LazyColumn
                import androidx.compose.material3.Button
                import androidx.compose.material3.Checkbox
                import androidx.compose.material3.OutlinedTextField
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier
                import bosca.analytics.AnalyticsScreen
                import bosca.analytics.AutoInstrument

                @AnalyticsScreen(id = "library", path = "/library", title = "Library")
                @AutoInstrument(
                    id = "library",
                    elementType = "catalog",
                    trackVisibility = true,
                    visibilityThreshold = 0.75f,
                    visibilityDwellMillis = 250,
                )
                @Composable
                fun LibraryScreen(scrollState: ScrollState) {
                    Button(onClick = {}) { Text("Save") }
                    Checkbox(checked = false, onCheckedChange = {})
                    OutlinedTextField(value = "", onValueChange = {})
                    Column(Modifier.clickable(onClick = {})) { }
                    Column(Modifier.horizontalScroll(scrollState)) { }
                    LazyColumn { item { Text("Book") } }
                }
                """.trimIndent(),
            )
        }
        val ignoredSource = File(work, "IgnoredScreen.kt").apply {
            writeText(
                """
                package sample

                import androidx.compose.material3.Button
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import bosca.analytics.AnalyticsIgnore

                @AnalyticsIgnore
                @Composable
                fun IgnoredScreen() {
                    Button(onClick = {}) { Text("Ignored") }
                }
                """.trimIndent(),
            )
        }
        val output = File(work, "out").apply { mkdirs() }

        val (exit, messages) = compile(output, source, ignoredSource)
        assertEquals(ExitCode.OK, exit, "Compose compiler plugin failed:\n$messages")

        val bytecode = output.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .flatMap { it.readBytes().asIterable() }
            .toList()
            .toByteArray()
            .toString(Charsets.ISO_8859_1)
        assertTrue(bytecode.contains("AutoInstrumentedScreen"), "screen hook was not emitted")
        assertTrue(bytecode.contains("rememberAutoInstrumentedAction"), "action hook was not emitted")
        assertTrue(
            bytecode.contains("rememberAutoInstrumentedNullableBooleanChange"),
            "nullable change hook was not emitted",
        )
        assertTrue(bytecode.contains("autoInstrumentedInputModifier"), "input hook was not emitted")
        assertTrue(bytecode.contains("autoInstrumentedVisibilityModifier"), "visibility hook was not emitted")
        assertTrue(bytecode.contains("autoInstrumentedScrollState"), "scroll-state hook was not emitted")
        assertTrue(bytecode.contains("autoInstrumentedLazyListState"), "lazy-list hook was not emitted")
        assertTrue(bytecode.contains("/library"), "screen annotation path was not emitted")
        assertTrue(bytecode.contains("catalog"), "annotation element type was not emitted")
        val ignoredBytecode = File(output, "sample/IgnoredScreenKt.class").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ignoredBytecode.contains("bosca/analytics/compose"), "ignored screen was instrumented")
    }

    @Test
    fun `K2 plugin lets Navigation own destination page identity`() {
        val work = newWorkDir()
        val source = File(work, "Navigation.kt").apply {
            writeText(
                """
                package sample

                import androidx.compose.material3.Button
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import androidx.navigation3.runtime.NavEntry
                import androidx.navigation3.ui.NavDisplay

                @Composable
                fun CheckoutScreen() {
                    Button(onClick = {}) { Text("Checkout") }
                }

                @Composable
                fun App(backStack: List<String>) {
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = { key -> NavEntry(key) { CheckoutScreen() } },
                    )
                }
                """.trimIndent(),
            )
        }
        val output = File(work, "out").apply { mkdirs() }

        val (exit, messages) = compile(output, source)
        assertEquals(ExitCode.OK, exit, "Navigation compiler plugin failed:\n$messages")

        val bytecode = output.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .flatMap { it.readBytes().asIterable() }
            .toList()
            .toByteArray()
            .toString(Charsets.ISO_8859_1)
        assertTrue(bytecode.contains("AutoInstrumentedNavigation"), "navigation hook was not emitted")
        assertTrue(
            bytecode.contains("rememberAutoInstrumentedAction"),
            "destination controls were not instrumented",
        )
        assertFalse(
            bytecode.contains("AutoInstrumentedScreen"),
            "destination function also claimed page ownership",
        )
        assertTrue(bytecode.contains("sample.App"), "navigation source was not emitted")
    }

    @OptIn(CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
    @Test
    fun `command line options and disabled registration are deterministic`() {
        val processor = AnalyticsCommandLineProcessor()
        val configuration = CompilerConfiguration()
        processor.processOption(processor.pluginOptions.single { it.optionName == "enabled" }, "false", configuration)
        processor.processOption(processor.pluginOptions.single { it.optionName == "verbose" }, "true", configuration)
        assertEquals(false, configuration.get(AnalyticsConfigurationKeys.ENABLED))
        assertEquals(true, configuration.get(AnalyticsConfigurationKeys.VERBOSE))

        val storage = CompilerPluginRegistrar.ExtensionStorage()
        with(AnalyticsCompilerPluginRegistrar()) { storage.registerExtensions(configuration) }
        assertTrue(storage.registeredExtensions.isEmpty())
    }

    private fun compile(output: File, vararg sources: File): Pair<ExitCode, String> {
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() = Unit
            override fun hasErrors(): Boolean = false
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError || severity == CompilerMessageSeverity.WARNING) {
                    messages.appendLine("$severity: $message")
                }
            }
        }
        val pluginClasspath = pluginClasspath()
        assertTrue(pluginClasspath.isNotEmpty(), "analytics compiler plugin service files were not found")
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = sources.map(File::getAbsolutePath)
            destination = output.absolutePath
            classpath = System.getProperty("java.class.path")
            jvmTarget = "11"
            noStdlib = true
            noReflect = true
            pluginClasspaths = pluginClasspath.toTypedArray()
            pluginOptions = arrayOf("plugin:bosca.analytics:enabled=true")
        }
        return runCompiler(collector, arguments) to messages.toString()
    }

    private fun pluginClasspath(): List<String> {
        val service = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        return System.getProperty("java.class.path").split(File.pathSeparator).filter { entry ->
            val file = File(entry)
            when {
                file.isDirectory -> File(file, service).isFile
                file.isFile && file.extension == "jar" ->
                    runCatching { JarFile(file).use { it.getEntry(service) != null } }.getOrDefault(false)
                else -> false
            }
        }
    }

    private fun runCompiler(collector: MessageCollector, arguments: K2JVMCompilerArguments): ExitCode {
        val compiler = K2JVMCompiler()
        val method = compiler.javaClass.methods.first {
            it.name == "exec" &&
                it.parameterCount == 3 &&
                it.parameterTypes[0] == MessageCollector::class.java &&
                it.parameterTypes[1] == Services::class.java
        }
        return method.invoke(compiler, collector, Services.EMPTY, arguments) as ExitCode
    }

    private fun newWorkDir(): File = File.createTempFile("analytics-compiler", "").apply {
        delete()
        mkdirs()
    }

}
