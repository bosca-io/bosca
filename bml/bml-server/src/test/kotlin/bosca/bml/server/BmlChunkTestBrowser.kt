package bosca.bml.server

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One isolated browser keeps its in-memory cache while production fixture listeners are replaced. */
internal class BmlChunkTestBrowser(chrome: File, profile: File, private val log: File) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val messages = LinkedBlockingQueue<String>()
    private val failure = AtomicReference<Throwable?>()
    private val events = ArrayDeque<JsonObject>()
    private var sequence = 0
    private var socket: WebSocket? = null
    private val process: Process

    init {
        log.parentFile.mkdirs()
        process = ProcessBuilder(
            chrome.absolutePath, "--headless=new", "--disable-gpu", "--no-sandbox", "--no-first-run", "--no-default-browser-check",
            "--remote-debugging-port=0", "--user-data-dir=${profile.absolutePath}", "about:blank",
        ).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val portFile = profile.resolve("DevToolsActivePort")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!portFile.isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(portFile.isFile) { "Browser did not expose DevTools: ${diagnostics()}" }
            val port = portFile.readLines().first().toInt()
            val target = checkNotNull(awaitChunkBrowserPageTarget(deadline, process::isAlive) {
                val response = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                check(response.statusCode() == 200) { response.body() }
                Json.parseToJsonElement(response.body()).jsonArray.map { it.jsonObject }
            }) { "Browser did not publish a page target: ${diagnostics()}" }
            socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(
                URI(target.getValue("webSocketDebuggerUrl").jsonPrimitive.content),
                object : WebSocket.Listener {
                    private val text = StringBuilder()

                    override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                        text.append(data)
                        if (last) {
                            messages.add(text.toString())
                            text.setLength(0)
                        }
                        webSocket.request(1)
                        return CompletableFuture.completedFuture(null)
                    }

                    override fun onError(webSocket: WebSocket, error: Throwable) { failure.set(error) }
                },
            ).get(10, TimeUnit.SECONDS)
            command("Page.enable")
            command("Runtime.enable")
            command("Network.enable")
        } catch (cause: Throwable) {
            socket?.abort()
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            client.close()
            throw cause
        }
    }

    /** Waits for the fixture's explicit module-completion result, independently of browser exit. */
    fun result(url: String): String {
        events.clear()
        val navigation = command("Page.navigate", mapOf("url" to JsonPrimitive(url)))
        check("errorText" !in navigation) { "Navigation failed: $navigation; ${diagnostics()}" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val evaluated = command("Runtime.evaluate", mapOf(
                "expression" to JsonPrimitive("({url: location.href, complete: document.getElementById('result')?.dataset.complete === 'true', " +
                    "value: document.getElementById('result')?.textContent})"),
                "returnByValue" to JsonPrimitive(true),
            ))
            check("exceptionDetails" !in evaluated) { "Evaluation failed: $evaluated; ${diagnostics()}" }
            val document = evaluated["result"]?.jsonObject?.get("value")?.jsonObject
            val value = document?.get("value")?.jsonPrimitive?.contentOrNull
            if (document?.get("url")?.jsonPrimitive?.content == url &&
                document?.get("complete")?.jsonPrimitive?.booleanOrNull == true && value != null) return value
            Thread.sleep(50)
        }
        error("Module result did not become ready at $url: ${diagnostics()}")
    }

    private fun command(method: String, params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject {
        val id = ++sequence
        val body = JsonObject(mapOf("id" to JsonPrimitive(id), "method" to JsonPrimitive(method), "params" to JsonObject(params)))
        checkNotNull(socket).sendText(body.toString(), true).get(5, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            failure.get()?.let { throw IllegalStateException("DevTools connection failed: ${diagnostics()}", it) }
            check(process.isAlive) { "Browser exited while handling $method: ${diagnostics()}" }
            val text = messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["id"]?.jsonPrimitive?.intOrNull == id) {
                check("error" !in message) { "DevTools $method failed: $message; ${diagnostics()}" }
                return message.getValue("result").jsonObject
            }
            if (events.size == 30) events.removeFirst()
            events.addLast(message)
            check(message["method"]?.jsonPrimitive?.content != "Runtime.exceptionThrown") {
                "Browser module threw an exception: $message; ${diagnostics()}"
            }
        }
        error("DevTools timed out handling $method: ${diagnostics()}")
    }

    private fun diagnostics(): String = "events=$events; log=${log.readText().takeLast(4000)}"

    override fun close() {
        try {
            socket?.sendText(JsonObject(mapOf("id" to JsonPrimitive(++sequence), "method" to JsonPrimitive("Browser.close"))).toString(), true)
                ?.get(5, TimeUnit.SECONDS)
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Browser did not close: ${diagnostics()}" }
            check(process.exitValue() == 0) { "Browser exited with ${process.exitValue()}: ${diagnostics()}" }
        } finally {
            socket?.abort()
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            client.close()
        }
    }
}
