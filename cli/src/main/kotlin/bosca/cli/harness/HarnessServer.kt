package bosca.cli.harness

import bosca.ai.kit.harness.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

internal fun harnessServer(runtime: HarnessRuntime, host: String, port: Int) = embeddedServer(Netty, host = host, port = port) {
    install(WebSockets) { pingPeriod = 30.seconds; maxFrameSize = 16L * 1024 * 1024 }
    monitor.subscribe(ApplicationStopped) { runBlocking { runtime.close() } }
    routing {
        intercept(ApplicationCallPipeline.Call) {
            val path = call.request.path()
            if (path !in listOf("/healthz", "/readyz")) {
                val provided = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.toByteArray() ?: byteArrayOf()
                val valid = MessageDigest.isEqual(provided, runtime.token.toByteArray()) && System.currentTimeMillis() / 1000 < runtime.expires && call.request.headers["X-Bosca-Generation"] == runtime.generation.toString()
                if (!valid) { call.respond(HttpStatusCode.Unauthorized); finish(); return@intercept }
                call.response.headers.append("X-Bosca-Instance", runtime.instanceId)
                val instance = call.request.headers["X-Bosca-Instance"]
                if (instance != runtime.instanceId && !(path == "/v1/claim" && instance == null)) {
                    call.respondText("Runtime instance changed", status = HttpStatusCode.Conflict); finish(); return@intercept
                }
                if (path != "/v1/claim" && call.request.headers["X-Bosca-Epoch"] != runtime.status().epoch.toString()) {
                    call.respondText("Stale runtime owner", status = HttpStatusCode.Conflict); finish(); return@intercept
                }
            }
        }
        get("/healthz") { call.respond(if (runtime.status().phase == "FAILED") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK) }
        get("/readyz") { call.respond(if (runtime.status().phase in listOf("PREPARED", "RESTORED", "READY")) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable) }
        get("/v1/runtime") { call.json(RuntimeStatus.serializer(), runtime.status()) }
        get("/v1/capabilities") { call.respondText(buildJsonObject {
            val versions = requireNotNull(runtime.status().versions)
            put("transportVersion", versions.transport); put("acpVersion", versions.acp); put("archiveFormatVersion", versions.archive); put("statePolicy", versions.statePolicy)
            put("adapter", versions.adapter); put("harness", versions.harness)
            putJsonObject("paths") { runtime.roots.forEach { (name, path) -> put(name, path.toString()) } }
        }.toString(), ContentType.Application.Json) }
        post("/v1/claim") { call.checked { call.json(RuntimeStatus.serializer(), runtime.claim(call.request.headers["X-Bosca-Epoch"]?.toLong() ?: 0)) } }
        post("/v1/start") { call.checked { call.json(RuntimeStatus.serializer(), runtime.start(call.runtimeOwner())) } }
        post("/v1/mcp") { call.checked {
            val plans = Json.decodeFromString(ListSerializer(KitMcpConnection.serializer()), call.receiveText())
            call.json(ListSerializer(KitMcpStatus.serializer()), runtime.configure(plans, call.runtimeOwner()))
        } }
        post("/v1/archive") { call.checked { call.json(RuntimeArchiveReceipt.serializer(), runtime.archive(call.runtimeOwner())) } }
        get("/v1/archive") { call.checked {
            check(runtime.status().phase == "ARCHIVED") { "Archive is unavailable" }
            call.respondFile(runtime.control.resolve("archive.tar.gz").toFile())
        } }
        post("/v1/restore") { call.checked {
            val mode = call.request.headers["X-Bosca-Restore-Mode"] ?: "full"
            require(mode in listOf("full", "workspace")) { "Unknown restore mode" }
            check(runtime.status().phase in listOf("PREPARED", "RESTORED")) { "Runtime cannot restore in this phase" }
            if (runtime.status().phase == "RESTORED") call.json(RuntimeStatus.serializer(), runtime.status()) else {
                val path = withContext(Dispatchers.IO) { Files.createTempFile(runtime.control, "restore-", ".tar.gz") }
                try {
                    val input = call.receiveChannel()
                    withContext(Dispatchers.IO) {
                        Files.newOutputStream(path).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var size = 0L
                            while (true) {
                                val read = input.readAvailable(buffer)
                                if (read < 0) break
                                size = Math.addExact(size, read.toLong())
                                require(size <= runtime.maxBytes) { "Archive exceeds configured limit" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    call.json(RuntimeStatus.serializer(), runtime.restore(path, requireNotNull(call.request.headers["X-Archive-Sha256"]), mode == "workspace", call.runtimeOwner()))
                } finally { withContext(Dispatchers.IO) { Files.deleteIfExists(path) } }
            }
        } }
        get("/v1/turns/{turn}") {
            val receipt = runtime.turn(requireNotNull(call.parameters["turn"]))
            if (receipt == null) call.respond(HttpStatusCode.NotFound) else call.json(RuntimeTurnReceipt.serializer(), receipt)
        }
        post("/v1/permission") { call.checked {
            val message = Json.parseToJsonElement(call.receiveText()).jsonObject
            require("method" !in message && "result" in message)
            runtime.receive(message, call.runtimeOwner())
            call.json(RuntimeStatus.serializer(), runtime.status())
        } }
        post("/v1/cancel") { call.checked {
            runtime.cancel(Json.parseToJsonElement(call.receiveText()).jsonObject.string("turnId"), call.runtimeOwner())
            call.json(RuntimeStatus.serializer(), runtime.status())
        } }
        post("/v1/shutdown") { call.checked {
            check(runtime.status().phase == "ARCHIVED") { "Archive must be prepared before shutdown" }
            call.json(RuntimeStatus.serializer(), runtime.status())
        } }
        webSocket("/acp") {
            val owner = call.runtimeOwner()
            try {
                runtime.connect(this, owner)
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = Json.parseToJsonElement(frame.readText()).jsonObject
                    try { runtime.receive(message, owner, this) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: IllegalArgumentException) { send(runtimeError(message, failure)) }
                    catch (failure: IllegalStateException) { send(runtimeError(message, failure)) }
                }
            } finally { runtime.disconnect(this) }
        }
    }
}

private fun runtimeError(message: JsonObject, failure: Exception) = Frame.Text(buildJsonObject {
    put("jsonrpc", "2.0"); put("id", message["id"] ?: JsonNull)
    putJsonObject("error") { put("code", -32000); put("message", failure.message ?: "Runtime request rejected") }
}.toString())
private suspend fun <T> ApplicationCall.json(serializer: KSerializer<T>, value: T) = respondText(Json.encodeToString(serializer, value), ContentType.Application.Json)
private fun ApplicationCall.runtimeOwner(): Long = requireNotNull(request.headers["X-Bosca-Epoch"]).toLong()
private suspend fun ApplicationCall.checked(action: suspend () -> Unit) {
    try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: IllegalArgumentException) { respondText(failure.message ?: "Invalid request", status = HttpStatusCode.BadRequest) }
    catch (failure: IllegalStateException) { respondText(failure.message ?: "Runtime request rejected", status = HttpStatusCode.Conflict) }
}
