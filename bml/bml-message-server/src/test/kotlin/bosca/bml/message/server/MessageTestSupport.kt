@file:OptIn(org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class)

package bosca.bml.message.server

import bosca.bml.compiler.plugin.BmlAdditionalSourcesExtension
import bosca.bml.compiler.plugin.BmlConfigKeys
import com.sun.net.httpserver.HttpServer
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** An in-memory raw registry: version listing JSON + jar downloads, digests included. */
class FakeRegistry {
    private val versions = ConcurrentHashMap<String, MutableMap<String, ByteArray>>()
    lateinit var server: HttpServer
    val port: Int get() = server.address.port
    val url: String get() = "http://localhost:$port"

    fun publish(project: String, version: String, jar: ByteArray) {
        versions.computeIfAbsent(project) { ConcurrentHashMap() }[version] = jar
    }

    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/raw/bml-message/") { exchange ->
            val segments = exchange.requestURI.path.removePrefix("/raw/bml-message/").split('/')
            when {
                segments.size == 1 && segments[0] == "api" -> {
                    // Namespace discovery: every raw repository under bml-message.
                    val body = ("""{"namespace":"bml-message","repositories":[""" +
                        versions.keys.sorted().joinToString(",") { "\"$it\"" } + "]}").toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                segments.size == 2 && segments[0] == "api" -> {
                    val listed = versions[segments[1]]
                    if (listed == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        val body = buildString {
                            append("""{"name":"${segments[1]}","versions":[""")
                            append(
                                listed.entries.joinToString(",") { (version, bytes) ->
                                    """{"version":"$version","created":"2026-07-16T00:00:00Z","files":[""" +
                                        """{"filename":"${segments[1]}.jar","digest":"${sha256(bytes)}","mediaType":"application/java-archive"}]}"""
                                },
                            )
                            append("]}")
                        }.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
                segments.size == 3 -> {
                    val bytes = versions[segments[0]]?.get(segments[1])
                    if (bytes == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Compile a real message jar (templates + embedded manifest + a bundled public asset). */
fun buildMessageJar(label: String): ByteArray = buildJarFromSources(
    label,
    mapOf(
        "messages/welcome.bml" to """<message key="welcome"><email>""" +
            """<subject>Hello { message.recipientName }</subject>""" +
            """<p>content-$label</p><img src="{ message.assetsUrl }/logo.png"/></email></message>""",
    ),
)

/** A dual-channel template whose push action is producer-owned and must not affect email renders. */
fun buildDualChannelJar(): ByteArray = buildJarFromSources(
    "dual-channel",
    mapOf(
        "messages/invitation.bml" to """<message key="invitation">""" +
            """<email><subject>Invitation</subject><p>Email body</p></email>""" +
            """<push><title>Invitation</title><body>Push body</body>""" +
            """<action id="open" default>Open invitation</action></push></message>""",
    ),
)

/** A template that executes GraphQL, proving the render request's bearer reaches the API. */
fun buildGraphQLJar(): ByteArray = buildJarFromSources(
    "graphqly",
    mapOf(
        "messages/viewer.bml" to """<message key="viewer">""" +
            """<script server provides="viewer">ctx.gql.execute("query { viewer }").toString()</script>""" +
            """<email><subject>Viewer</subject><p>{ viewer }</p></email></message>""",
    ),
)

/** A jar whose template declares its payload (`message.payload(X.serializer())`). */
fun buildPayloadJar(): ByteArray = buildJarFromSources(
    "payloady",
    mapOf(
        "messages/enroll.bml" to """<message key="enroll">""" +
            """<script server provides="payload">message.payload(test.fixtures.EnrollPayload.serializer())</script>""" +
            """<email><subject>Enroll</subject><p>{ payload.courseName }</p></email></message>""",
    ),
    // The embedded test compiler runs WITHOUT the kotlinx.serialization plugin, so the fixture
    // hand-writes its serializer — real projects get theirs generated by the Gradle plugin.
    extraKotlin = mapOf(
        "EnrollPayload.kt" to """
            package test.fixtures

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.builtins.nullable
            import kotlinx.serialization.builtins.serializer
            import kotlinx.serialization.descriptors.buildClassSerialDescriptor
            import kotlinx.serialization.descriptors.element
            import kotlinx.serialization.encoding.CompositeDecoder
            import kotlinx.serialization.encoding.Decoder
            import kotlinx.serialization.encoding.Encoder
            import kotlinx.serialization.encoding.decodeStructure

            class EnrollPayload(val courseName: String, val classNumber: Int, val teacher: String? = null) {
                companion object {
                    fun serializer(): KSerializer<EnrollPayload> = Ser
                }

                private object Ser : KSerializer<EnrollPayload> {
                    override val descriptor = buildClassSerialDescriptor("test.fixtures.EnrollPayload") {
                        element<String>("courseName")
                        element<Int>("classNumber")
                        element<String?>("teacher")
                    }

                    override fun deserialize(decoder: Decoder): EnrollPayload = decoder.decodeStructure(descriptor) {
                        var courseName = ""
                        var classNumber = 0
                        var teacher: String? = null
                        while (true) {
                            when (val index = decodeElementIndex(descriptor)) {
                                0 -> courseName = decodeStringElement(descriptor, 0)
                                1 -> classNumber = decodeIntElement(descriptor, 1)
                                2 -> teacher = decodeNullableSerializableElement(descriptor, 2, String.serializer().nullable)
                                CompositeDecoder.DECODE_DONE -> break
                                else -> error("unexpected index: " + index)
                            }
                        }
                        EnrollPayload(courseName, classNumber, teacher)
                    }

                    override fun serialize(encoder: Encoder, value: EnrollPayload) = error("fixture never serializes")
                }
            }
        """.trimIndent(),
    ),
)

/** Compile a message jar from arbitrary `.bml` sources (path -> content). */
fun buildJarFromSources(label: String, sources: Map<String, String>, extraKotlin: Map<String, String> = emptyMap()): ByteArray {
    val work = File.createTempFile("bml-message-server-$label", "").apply { delete(); mkdirs() }
    val srcRoot = File(work, "bml").apply { mkdirs(); resolve("messages").mkdirs() }
    for ((path, content) in sources) {
        File(srcRoot, path).also { it.parentFile.mkdirs() }.writeText(content)
    }
    val ktOut = File(work, "kt")
    val resOut = File(work, "res")
    val config = CompilerConfiguration().apply {
        add(BmlConfigKeys.SOURCE_ROOTS, srcRoot.absolutePath)
        put(BmlConfigKeys.KOTLIN_OUTPUT_DIR, ktOut.absolutePath)
        put(BmlConfigKeys.RESOURCES_OUTPUT_DIR, resOut.absolutePath)
    }
    BmlAdditionalSourcesExtension().collectSources(Any(), config, { null }, emptyList())
    for ((name, content) in extraKotlin) File(ktOut, name).writeText(content)
    File(resOut, "bml/public").mkdirs()
    File(resOut, "bml/public/logo.png").writeBytes("png-bytes-$label".toByteArray(Charsets.UTF_8))

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
    check(exit == ExitCode.OK) { "message jar compile failed:\n$messages" }

    val jar = File(work, "$label.jar")
    JarOutputStream(jar.outputStream()).use { out ->
        for (root in listOf(classesDir, resOut)) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    return jar.readBytes()
}

/** A jar with the guardrail probe: `slow` delays past any sane render bound. */
fun buildGuardrailJar(): ByteArray = buildJarFromSources(
    "guardrails",
    mapOf(
        "messages/slow.bml" to """<message key="slow">""" +
            """<script server provides="wait">kotlinx.coroutines.delay(120_000); "done"</script>""" +
            """<email><subject>Slow</subject><p>{ wait }</p></email></message>""",
    ),
)

/**
 * A jar exercising `bml-inline`: a `<style bml-inline>` (simple rule + a retained `@media`) and
 * an `<img bml-inline>` gated behind the recipient name, so renders prove both the inlining and
 * that only images that ACTUALLY rendered attach.
 */
fun buildBmlInlineJar(): ByteArray = buildJarFromSources(
    "inliny",
    mapOf(
        "messages/fancy.bml" to """<message key="fancy"><email><subject>Fancy</subject>""" +
            """<style bml-inline>.cta { color: #ffffff } @media (max-width: 600px) { .cta { font-size: 18px } }</style>""" +
            """<p class="cta">Get started</p>""" +
            """<if (message.recipientName != null)><img bml-inline src="logo.png"/></if></email></message>""",
    ),
)

/** A jar exercising rewriting: a content link + an unsubscribe link the tracker must spare. */
fun buildTrackedJar(): ByteArray = buildJarFromSources(
    "tracked",
    mapOf(
        "messages/promo.bml" to """<message key="promo"><email><subject>Promo</subject>""" +
            """<p><a href="https://example.com/course?id=7">Open the course</a></p>""" +
            """<p><a href="{ message.unsubscribeUrl }">Unsubscribe</a></p></email></message>""",
    ),
)

/** Records [capture]d events for assertions; the contract's never-throw semantics need nothing else. */
class RecordingAnalytics : bosca.analytics.server.ServerAnalyticsClient {
    val events = java.util.concurrent.CopyOnWriteArrayList<bosca.analytics.model.Event>()
    override suspend fun capture(event: bosca.analytics.model.Event) { events.add(event) }
    override suspend fun captureForSubject(
        event: bosca.analytics.model.Event,
        userId: String?,
        installationId: String?,
        device: bosca.analytics.model.Device?,
    ) { events.add(event) }
    override suspend fun capture(events: bosca.analytics.model.Events) { this.events.addAll(events.events) }
    override suspend fun captureException(
        throwable: Throwable,
        fatal: Boolean,
        appId: String?,
        sessionId: String?,
        userId: String?,
        context: Map<String, Any?>,
    ) = Unit
    override suspend fun flush() = Unit
}

/** Records published (channel, json) pairs; subscriptions are not part of the tracking surface. */
class RecordingPubSub : bosca.pubsub.PubSubService {
    val published = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    override suspend fun <T> publish(channel: String, serializer: kotlinx.serialization.SerializationStrategy<T>, message: T) {
        published.add(channel to kotlinx.serialization.json.Json.encodeToString(serializer, message))
    }
    override fun <T> subscribe(channel: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>) =
        kotlinx.coroutines.flow.emptyFlow<bosca.pubsub.Message<T>>()
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
