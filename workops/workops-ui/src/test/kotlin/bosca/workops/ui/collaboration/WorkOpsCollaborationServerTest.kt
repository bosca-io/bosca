package bosca.workops.ui.collaboration

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.documents.yjs.ProseMirrorYjsBridge
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import bosca.documents.Content
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.test.resources.SharedNatsContainer
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses the actual Studio provider against the WorkOps-owned Netty/YKS transport. */
class WorkOpsCollaborationServerTest {
    @Test
    fun `Studio providers collaborate persist refresh credentials and lose access through Content`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = executor
        val tokens = AtomicReference(setOf("editor-a", "editor-b", "editor-c"))
        val snapshots = ConcurrentHashMap<String, ByteArray>()
        val gets = ConcurrentHashMap<String, AtomicInteger>()
        val seeds = ConcurrentHashMap<String, AtomicInteger>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        lateinit var collaboration: WorkOpsCollaborationServer
        lateinit var peerCollaboration: WorkOpsCollaborationServer
        upstream.createContext("/control") { call -> call.use {
            if (call.requestMethod == "POST") {
                tokens.set(Json.parseToJsonElement(call.requestBody.readAllBytes().decodeToString()).jsonObject.getValue("tokens")
                    .jsonArray.map { it.jsonPrimitive.content }.toSet())
            }
            val body = buildJsonObject {
                put("rooms", runBlocking { collaboration.activeDocumentCount() + peerCollaboration.activeDocumentCount() })
                putJsonObject("gets") { gets.forEach { (key, count) -> put(key, count.get()) } }
                putJsonObject("seeds") { seeds.forEach { (key, count) -> put(key, count.get()) } }
                putJsonObject("opens") { opens.forEach { (key, count) -> put(key, count.get()) } }
            }
                .toString().encodeToByteArray()
            call.responseHeaders.set("Content-Type", "application/json")
            call.sendResponseHeaders(200, body.size.toLong())
            call.responseBody.write(body)
        } }
        upstream.createContext("/api/v1/content/metadata/") { call -> call.use {
            val token = call.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
            val version = call.requestURI.rawQuery.substringAfter("version=")
            if (token !in tokens.get()) call.sendResponseHeaders(403, -1)
            else when (call.requestMethod) {
                "HEAD" -> call.sendResponseHeaders(200, -1)
                "GET" -> {
                    gets.computeIfAbsent(version) { AtomicInteger() }.incrementAndGet()
                    val snapshot = snapshots[version]
                    if (snapshot == null) call.sendResponseHeaders(404, -1)
                    else { call.sendResponseHeaders(200, snapshot.size.toLong()); call.responseBody.write(snapshot) }
                }
                "POST" -> {
                    val incoming = call.requestBody.readAllBytes()
                    if (incoming.isEmpty()) opens.computeIfAbsent(version) { AtomicInteger() }.incrementAndGet()
                    val state = requireNotNull(snapshots.compute(version) { _, existing ->
                        val doc = Doc()
                        try {
                            existing?.let { applyUpdate(doc, it) }
                            if (incoming.isEmpty() && doc.getXmlFragment("default").start == null) {
                                seeds.computeIfAbsent(version) { AtomicInteger() }.incrementAndGet()
                                val seed = if (version == "4") ProseMirrorYjsBridge.initializeDocument(ByteArray(0), Content(), "Existing document")
                                else ProseMirrorYjsBridge.toYDocUpdate(Json.parseToJsonElement("""{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Existing document"}]},{"type":"paragraph","content":[{"type":"text","text":"Original body","marks":[{"type":"bold"}]}]},{"type":"container","attrs":{"name":"details","version":4,"references":["one","two"]},"content":[{"type":"paragraph"}]}]}"""))
                                applyUpdate(doc, seed)
                            } else if (incoming.isNotEmpty()) applyUpdate(doc, incoming)
                            encodeStateAsUpdate(doc)
                        } finally { doc.destroy() }
                    })
                    call.sendResponseHeaders(200, state.size.toLong()); call.responseBody.write(state)
                }
                "PUT" -> { snapshots[version] = call.requestBody.readAllBytes(); call.sendResponseHeaders(202, -1) }
                else -> call.sendResponseHeaders(405, -1)
            }
        } }
        upstream.start()
        val application = BoscaApplication(ApplicationConfig.load("""
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                http2-enabled: false
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
        """.trimIndent().byteInputStream()), ownsServices = false)
        val nats = SharedNatsContainer().apply { start() }
        val connections = nats.newConnectionPool(2)
        val pubSub = NatsPubSubServiceImpl(Json, connections)
        collaboration = WorkOpsCollaborationServer(application, "http://127.0.0.1:${upstream.address.port}/graphql", permissionCheckIntervalMillis = 75, pubSub = pubSub)
        collaboration.install()
        val peerApplication = BoscaApplication(application.config, ownsServices = false)
        peerCollaboration = WorkOpsCollaborationServer(peerApplication, "http://127.0.0.1:${upstream.address.port}/graphql", permissionCheckIntervalMillis = 75, pubSub = pubSub)
        peerCollaboration.install()
        val peerEngine = NettyServerEngine(peerApplication, 0)
        val editorPage = AtomicReference("")
        application.router.post("/editor") { editorPage.set(call.request.bodyText()); call.respond(HttpStatusCode.Accepted) }
        application.router.get("/editor") { call.response.respondText(editorPage.get(), ContentType.Text.Html, HttpStatusCode.OK) }
        val engine = NettyServerEngine(application, 0)
        val startupFailure = AtomicReference<Throwable?>()
        val server = thread(isDaemon = true, name = "workops-collaboration-fixture", start = false) { engine.start() }
        server.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> startupFailure.set(failure) }
        server.start()
        val peerServer = thread(isDaemon = true, name = "workops-collaboration-peer", start = false) { peerEngine.start() }
        peerServer.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> startupFailure.set(failure) }
        peerServer.start()
        val log = File.createTempFile("workops-collaboration-", ".log")
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((engine.boundPort == null || peerEngine.boundPort == null) && System.nanoTime() < deadline) {
                startupFailure.get()?.let { throw AssertionError("Collaboration server failed to bind", it) }
                Thread.sleep(10)
            }
            val port = requireNotNull(engine.boundPort) { "Collaboration server did not bind" }
            val peerPort = requireNotNull(peerEngine.boundPort) { "Collaboration peer did not bind" }
            for (script in listOf("collaboration.mjs", "collaboration-editor.mjs", "collaboration-large.mjs")) {
                val process = ProcessBuilder(requireNotNull(System.getProperty("workops.test.node")), "src/test/client/$script")
                    .directory(File(requireNotNull(System.getProperty("workops.test.project")))).apply {
                        environment()["WORKOPS_COLLABORATION_URL"] = "ws://127.0.0.1:$port/collaboration"
                        environment()["WORKOPS_COLLABORATION_PEER_URL"] = "ws://127.0.0.1:$peerPort/collaboration"
                        environment()["WORKOPS_CONTENT_URL"] = "http://127.0.0.1:${upstream.address.port}"
                        redirectErrorStream(true); redirectOutput(log)
                    }.start()
                try {
                    assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Provider test timed out: ${log.readText()}")
                    assertEquals(0, process.exitValue(), log.readText())
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
        } finally {
            engine.stopWithoutHalting()
            peerEngine.stopWithoutHalting()
            runBlocking { connections.close() }
            nats.stop()
            upstream.stop(0)
            executor.close()
            log.delete()
        }
    }
}
