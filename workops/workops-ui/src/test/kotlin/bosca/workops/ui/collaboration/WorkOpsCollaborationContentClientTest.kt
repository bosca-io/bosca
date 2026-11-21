package bosca.workops.ui.collaboration

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkOpsCollaborationContentClientTest {
    private val room = WorkOpsCollaborationRoom.parse("11111111-2222-4333-8444-555555555555.3")

    @Test
    fun `Content checks and binary persistence use the current participant credential and version`(): Unit = runBlocking {
        val snapshot = AtomicReference<ByteArray?>()
        val credential = AtomicReference("first-token")
        val received = ConcurrentLinkedQueue<String>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/prefix/api/v1/content/metadata/${room.metadataId}/document/collaboration") { call ->
            call.use {
                received += "${call.requestMethod} ${call.requestURI.rawQuery}"
                if (call.requestHeaders.getFirst("Authorization") != "Bearer ${credential.get()}") {
                    call.sendResponseHeaders(403, -1)
                } else when (call.requestMethod) {
                    "HEAD" -> call.sendResponseHeaders(200, -1)
                    "GET" -> {
                        val bytes = snapshot.get()
                        if (bytes == null) call.sendResponseHeaders(404, -1)
                        else { call.sendResponseHeaders(200, bytes.size.toLong()); call.responseBody.write(bytes) }
                    }
                    "POST" -> {
                        assertEquals("application/octet-stream", call.requestHeaders.getFirst("Content-Type"))
                        val incoming = call.requestBody.readAllBytes()
                        if (incoming.isNotEmpty()) snapshot.set(incoming)
                        val bytes = snapshot.get() ?: byteArrayOf(0, 0).also { snapshot.set(it) }
                        call.sendResponseHeaders(200, bytes.size.toLong()); call.responseBody.write(bytes)
                    }
                    else -> call.sendResponseHeaders(405, -1)
                }
            }
        }
        server.start()
        try {
            WorkOpsCollaborationContentClient("http://127.0.0.1:${server.address.port}/prefix/graphql").use { client ->
                client.authorize(room, "first-token")
                assertNull(client.load(room, "first-token"))
                assertContentEquals(byteArrayOf(0, 0), client.initialize(room, "first-token"))
                val bytes = byteArrayOf(0, 1, -1, -128, 127)
                client.store(room, "first-token", bytes)
                assertContentEquals(bytes, client.load(room, "first-token"))
                credential.set("refreshed-token")
                assertFailsWith<WorkOpsCollaborationDeniedException> { client.authorize(room, "first-token") }
                client.authorize(room, "refreshed-token")
                assertFailsWith<WorkOpsCollaborationDeniedException> { client.store(room, "first-token", byteArrayOf(5)) }
                assertContentEquals(bytes, client.load(room, "refreshed-token"))
                assertFailsWith<WorkOpsCollaborationDeniedException> { client.authorize(room, "") }
            }
            assertEquals(listOf("HEAD", "GET", "POST", "POST", "GET", "HEAD", "HEAD", "POST", "GET").map { "$it version=3" }, received.toList())
        } finally { server.stop(0); executor.close() }
    }

    @Test
    fun `upstream errors remain failures and redirects do not carry the credential elsewhere`(): Unit = runBlocking {
        val status = AtomicReference(503)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call -> call.use {
            call.responseHeaders.set("Location", "http://127.0.0.1:1/credential-must-not-follow")
            call.sendResponseHeaders(status.get(), -1)
        } }
        server.start()
        try {
            WorkOpsCollaborationContentClient("http://127.0.0.1:${server.address.port}/graphql").use { client ->
                assertFailsWith<IOException> { client.authorize(room, "token") }
                status.set(302)
                assertEquals("Content collaboration request failed with status 302", assertFailsWith<IOException> {
                    client.load(room, "token")
                }.message)
                status.set(400)
                assertFailsWith<WorkOpsCollaborationDeniedException> { client.authorize(room, "token") }
            }
        } finally { server.stop(0) }
    }
}
