package bosca.cli.swarm.runtime

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Uses curl already supplied by the CLI image; only this manager process opens the socket. */
internal class CurlSwarmEngine(private val socket: String, private val command: List<String> = listOf("curl")) : SwarmEngine {
    override suspend fun request(method: String, path: String, body: JsonElement?, headers: Map<String, String>): SwarmEngineResponse = withContext(Dispatchers.IO) {
        require(path.startsWith('/') && path.none { it == '\r' || it == '\n' })
        val process = ProcessBuilder(command + listOf("--silent", "--show-error", "--max-time", "20", "--config", "-"))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (process.isAlive) process.destroyForcibly() }
        }
        try { runInterruptible {
            val config = buildString {
                appendLine("unix-socket = ${JsonPrimitive(socket)}")
                appendLine("url = ${JsonPrimitive("http://localhost/v1.49$path")}")
                appendLine("request = ${JsonPrimitive(method)}")
                appendLine("header = \"Content-Type: application/json\"")
                headers.forEach { (name, value) -> appendLine("header = ${JsonPrimitive("$name: $value")}") }
                appendLine("write-out = \"\\n%{http_code}\"")
                body?.let { appendLine("data-binary = ${JsonPrimitive(it.toString())}") }
            }
            process.outputStream.bufferedWriter().use { it.write(config) }
            val bytes = process.inputStream.readNBytes(8 * 1024 * 1024 + 1)
            check(bytes.size <= 8 * 1024 * 1024 && process.waitFor() == 0) { "Docker Engine request failed. Review the manager socket and daemon." }
            val output = bytes.decodeToString()
            val status = output.substringAfterLast('\n').toInt()
            val text = output.substringBeforeLast('\n')
            SwarmEngineResponse(status, if (text.isBlank()) JsonNull else Json.parseToJsonElement(text))
        } } finally { cancelled.cancel(); if (process.isAlive) process.destroyForcibly() }
    }
}
