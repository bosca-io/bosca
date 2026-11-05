package bosca.cli.harness

import bosca.ai.kit.harness.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.BufferOverflowException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.uuid.Uuid

/** One warm native ACP process, fenced by generation and owner epoch. */
internal class HarnessRuntime(
    val sessionId: String,
    val generation: Long,
    val token: String,
    val expires: Long,
    private val command: List<String>,
    val roots: Map<String, Path>,
    val control: Path,
    private val versions: RuntimeVersions,
    val policy: String = "codex-0.160.1",
    val maxBytes: Long = 8L * 1024 * 1024 * 1024,
    private val privateNamespace: Boolean = false,
    private val environment: Map<String, String> = System.getenv(),
    val cli: String = "/usr/local/bin/bosca",
    private val maxJournalBytes: Long = 64L * 1024 * 1024,
    private val maxJournalTurns: Int = 256,
    private val egress: HarnessEgress? = null,
) {
    val instanceId: String = Uuid.random().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lifecycle = Mutex()
    private var phase = "PREPARED"
    private var epoch = 0L
    private var nativeSession: String? = null
    private var activeTurn: String? = null
    @Volatile private var process: Process? = null
    private var reader: Job? = null
    private var socket: WebSocketSession? = null
    private var nextId = 0L
    private val requests = mutableMapOf<JsonElement, HarnessRuntimeRequest>()
    private val callbacks = mutableSetOf<JsonElement>()
    private val tools = mutableSetOf<String>()
    private val turns = mutableMapOf<String, Boolean>()
    private val cancelledTurns = mutableSetOf<String>()
    private var initialization: Pair<JsonObject, JsonObject>? = null
    private val authentication = mutableMapOf<String, JsonObject>()
    private var sessionSetup: Pair<JsonObject, JsonObject>? = null
    private var archiveReceipt: RuntimeArchiveReceipt? = null
    private var journalBytes = 0L
    private var failure: String? = null
    private val secrets = environment.filter { (key, value) -> listOf("_TOKEN", "_KEY", "_SECRET", "_PASSWORD").any(key::endsWith) && value.length >= 8 }.values.toMutableSet()
    private val mcp = HarnessMcpConnections(control, environment, secrets, cli, egress)
    private val archive = HarnessArchive(roots, policy, maxBytes)

    init {
        require(token.length >= 32 && generation > 0 && maxBytes > 0)
        require(maxJournalBytes in 1024..(256L * 1024 * 1024) && maxJournalTurns in 1..10000)
        control.createDirectories(); HarnessArchive.chmod(control, 448)
        roots.values.forEach { it.createDirectories() }
        scope.launch {
            delay((expires * 1000 - System.currentTimeMillis()).coerceAtLeast(0))
            // Release blocked native I/O even when an archive is waiting for the protocol mutex.
            process?.let { stopHarnessProcess(it, privateNamespace) }
            lifecycle.withLock {
                // An in-flight start may have created a process before releasing the lifecycle lock.
                process?.let { stopHarnessProcess(it, privateNamespace) }
                mutex.withLock {
                    if (phase == "ARCHIVED") return@launch
                    phase = "FAILED"
                    failure = "The harness reached its hard-runtime deadline. The active turn was interrupted and was not replayed."
                }
                reader?.join()
            }
        }
    }

    private fun retireRequested() = turns.size >= maxJournalTurns || journalBytes >= maxJournalBytes / 2
    private fun identity() = RuntimeStatus(sessionId, generation, phase, instanceId, nativeSession, activeTurn, callbacks.size, tools.size, epoch,
        retireRequested(), failure, versions)
    suspend fun status(): RuntimeStatus = mutex.withLock { identity() }
    suspend fun claim(owner: Long): RuntimeStatus = mutex.withLock {
        check(owner >= epoch) { "Stale runtime owner" }
        if (owner > epoch) {
            epoch = owner
            socket?.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Ownership changed"))
            socket = null
        }
        identity()
    }

    suspend fun start(owner: Long): RuntimeStatus = lifecycle.withLock { mutex.withLock {
        check(owner == epoch) { "Stale runtime owner" }
        check(System.currentTimeMillis() / 1000 < expires) { "Runtime deadline expired" }
        if (phase == "READY") return@withLock identity()
        check(phase in listOf("PREPARED", "RESTORED")) { "Runtime cannot start in this phase" }
        val excludedSecrets = environment["BOSCA_MCP_SECRET_NAMES"]?.let { Json.parseToJsonElement(it).jsonArray.map { name -> name.jsonPrimitive.content } }.orEmpty()
        process = withContext(Dispatchers.IO) {
            ProcessBuilder(command).directory(roots.getValue("workspace").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                environment().clear()
                environment().putAll(this@HarnessRuntime.environment.filterKeys { !it.startsWith("BOSCA_") && it !in excludedSecrets })
            }.start()
        }
        phase = "READY"
        reader = scope.launch { readAdapter(requireNotNull(process)) }
        identity()
    } }

    suspend fun configure(connections: List<KitMcpConnection>, owner: Long): List<KitMcpStatus> = mutex.withLock {
        check(owner == epoch) { "Stale runtime owner" }
        check(phase == "READY" && activeTurn == null && (sessionSetup == null || mcp.plans == connections)) { "MCP configuration requires an idle prepared session" }
        mcp.configure(connections, roots.getValue("workspace"), sessionId)
    }

    suspend fun connect(connection: WebSocketSession, owner: Long) = mutex.withLock {
        check(phase == "READY" && epoch == owner) { "Runtime unavailable or owner is stale" }
        val previous = socket
        socket = connection
        if (previous !== connection) previous?.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Observer replaced"))
    }
    suspend fun disconnect(connection: WebSocketSession) = mutex.withLock { if (socket === connection) socket = null }

    /** WebSocket input is fenced to its observer; HTTP control messages use the current owner. */
    suspend fun receive(message: JsonObject, owner: Long, observer: WebSocketSession? = null) = mutex.withLock {
        check(phase == "READY" && epoch == owner) { "Runtime is draining or owner is stale" }
        check(observer == null || socket === observer) { "ACP observer was replaced" }
        val method = message.string("method")
        val params = message["params"]?.jsonObject ?: buildJsonObject { }
        // Cancelling an ACP observer leaves durable native operations intact. Turn cancellation
        // belongs to the fenced control endpoint, which records and sends session/cancel.
        if (method == "\$/cancelRequest" && message["id"] == null) return@withLock
        if (method == "session/set_config_option") check(params.string("sessionId") == nativeSession && activeTurn == null && callbacks.isEmpty() && tools.isEmpty()) { "Settings can only change between turns" }
        val cached = when {
            method == "initialize" && initialization != null -> {
                val (previous, result) = requireNotNull(initialization)
                check(listOf("protocolVersion", "clientCapabilities").all { previous[it] == params[it] }) { "Warm runtime capabilities differ" }
                result
            }
            method == "authenticate" -> authentication[params.string("methodId")]
            method in SESSION_METHODS && sessionSetup != null -> {
                val (previous, result) = requireNotNull(sessionSetup)
                check((method == "session/new" || params.string("sessionId") == nativeSession) && listOf("cwd", "mcpServers").all { previous[it] == params[it] }) { "Warm runtime session or MCP configuration differs" }
                JsonObject(result + ("sessionId" to JsonPrimitive(nativeSession)))
            }
            else -> null
        }
        if (cached != null) {
            deliver(buildJsonObject { put("jsonrpc", "2.0"); put("id", requireNotNull(message["id"])); put("result", cached) })
            return@withLock
        }
        if (method == "initialize" || method == "authenticate" || method in SESSION_METHODS) {
            val pending = requests.entries.singleOrNull { it.value.method == method || method in SESSION_METHODS && it.value.method in SESSION_METHODS }
            if (pending != null) {
                val previous = pending.value
                check(previous.owner < owner || observer != null && previous.observer !== observer) { "Native session setup is still in progress" }
                check(previous.method == method && if (method == "initialize")
                    listOf("protocolVersion", "clientCapabilities").all { previous.params[it] == params[it] }
                    else previous.params == params) { "Pending native setup differs from this request" }
                // Transfer the reply, retaining the one native operation already in flight.
                requests[pending.key] = previous.copy(originalId = requireNotNull(message["id"]), owner = owner, observer = observer)
                return@withLock
            }
        }
        val turn = if (method == "session/prompt") {
            val meta = params["_meta"]?.jsonObject ?: error("Missing turn identity")
            val id = requireNotNull(meta.string("turnId"))
            require(Uuid.parse(id).toString() == id) { "A durable turnId is required" }
            check(meta["generation"]?.jsonPrimitive?.long == generation) { "Stale runtime generation" }
            check(id !in turns && activeTurn == null && callbacks.isEmpty() && tools.isEmpty()) { "Turn already submitted or runtime busy" }
            check(!retireRequested()) { "Runtime journal requires archival before another turn" }
            journal(id, buildJsonObject { put("accepted", true); put("turnId", id) })
            activeTurn = id; turns[id] = false
            id
        } else null
        var forwarded = message
        val id = message["id"]
        if (method != null && id != null) {
            val remote = JsonPrimitive(++nextId)
            requests[remote] = HarnessRuntimeRequest(id, method, turn, params, owner, observer)
            forwarded = JsonObject(message + ("id" to remote))
        } else if (id != null && !callbacks.remove(id)) return@withLock
        // ACP sessions share this runtime's owned workspace, including relocated container state.
        if (method in SESSION_METHODS) forwarded = JsonObject(forwarded + ("params" to JsonObject(params + mapOf(
            "cwd" to JsonPrimitive(roots.getValue("workspace").toAbsolutePath().normalize().toString()),
            "mcpServers" to mcp.apply(params["mcpServers"]?.jsonArray ?: JsonArray(emptyList()))))))
        write(forwarded)
    }

    private suspend fun readAdapter(child: Process) {
        try {
            child.inputStream.buffered().use { input ->
                while (true) {
                    val line = runInterruptible { readMessage(input) } ?: break
                    mutex.withLock {
                        var message = Json.parseToJsonElement(line).jsonObject
                        if (message.string("method") == "session/request_permission") {
                            val params = message.getValue("params").jsonObject
                            val operation = buildJsonObject { put("toolCall", params["toolCall"] ?: JsonNull); put("options", params["options"] ?: JsonNull) }
                            val digest = MessageDigest.getInstance("SHA-256").digest(operation.toString().toByteArray()).toHexString()
                            message = withMeta(message, "boscaOperationDigest", digest)
                        }
                        message = redact(message).jsonObject
                        val id = message["id"]
                        val request = if ("method" !in message) requests.remove(id) else null
                        if (request != null) {
                            message = JsonObject(message + ("id" to request.originalId))
                            message["result"]?.jsonObject?.let { result ->
                                when (request.method) {
                                    "initialize" -> initialization = request.params to result
                                    "authenticate" -> authentication[requireNotNull(request.params.string("methodId"))] = result
                                    in SESSION_METHODS -> {
                                        nativeSession = result.string("sessionId") ?: request.params.string("sessionId") ?: nativeSession
                                        sessionSetup = request.params to result
                                    }
                                    "session/set_config_option" -> updateOptions(result["configOptions"])
                                }
                            }
                            request.turn?.let { turn ->
                                journal(turn, message); turns[turn] = true; activeTurn = null
                                if (message["result"]?.jsonObject?.string("stopReason") == "cancelled") { callbacks.clear(); tools.clear() }
                            }
                        } else {
                            if (id != null) {
                                callbacks += id
                                if (message.string("method") == "session/request_permission") message = withMeta(message, "boscaRuntimeRequestId", id.toString())
                            }
                            val update = message["params"]?.jsonObject?.get("update")?.jsonObject
                            when (update?.string("sessionUpdate")) {
                                "config_option_update" -> updateOptions(update["configOptions"])
                                "tool_call", "tool_call_update" -> update.string("toolCallId")?.let { tool ->
                                    if (update.string("status") in listOf("completed", "failed")) tools.remove(tool) else tools.add(tool)
                                }
                            }
                            activeTurn?.let { journal(it, message) }
                            if (id != null && activeTurn in cancelledTurns && message.string("method") == "session/request_permission") {
                                write(cancelPermission(id)); callbacks.remove(id)
                                return@withLock
                            }
                        }
                        if (request == null || request.owner == epoch && (request.observer == null || request.observer === socket)) deliver(message)
                    }
                }
            }
        } catch (_: BufferOverflowException) {
            mutex.withLock {
                failure = "The harness receipt journal exceeded its configured limit. The active turn was interrupted and was not replayed."
            }
        } finally {
            mutex.withLock {
                if (phase == "READY") phase = "FAILED"
                socket?.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Adapter stopped"))
                socket = null
            }
            if (failure != null) stopHarnessProcess(child, privateNamespace)
        }
    }

    // Bound native output before allocating its JSON tree, including a line with no delimiter.
    private fun readMessage(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
            if (value == '\n'.code) return bytes.toString(Charsets.UTF_8)
            if (bytes.size() >= maxJournalBytes) throw BufferOverflowException()
            bytes.write(value)
        }
    }

    private fun updateOptions(options: JsonElement?) {
        if (options != null) sessionSetup = sessionSetup?.let { (params, result) -> params to JsonObject(result + ("configOptions" to options)) }
    }
    private suspend fun deliver(message: JsonObject) {
        val target = socket ?: return
        try { target.send(Frame.Text(message.toString())) }
        catch (_: CancellationException) {
            // A closed session cancels its outgoing channel; only the caller's own cancellation propagates.
            currentCoroutineContext().ensureActive()
            if (socket === target) socket = null
        }
        catch (_: ClosedSendChannelException) { if (socket === target) socket = null }
        catch (_: java.io.IOException) { if (socket === target) socket = null }
    }
    private suspend fun write(message: JsonObject) = withContext(Dispatchers.IO) {
        requireNotNull(process).outputStream.apply { write((message.toString() + "\n").toByteArray()); flush() }
    }
    private suspend fun journal(turn: String, message: JsonObject) = withContext(Dispatchers.IO) {
        val bytes = (message.toString() + "\n").toByteArray()
        if (bytes.size > maxJournalBytes - journalBytes) throw BufferOverflowException()
        // A failed write or fsync can still leave bytes on disk; retain its reservation.
        journalBytes += bytes.size
        FileOutputStream(control.resolve("$turn.jsonl").toFile(), true).use { output ->
            output.write(bytes); output.fd.sync()
        }
    }
    private fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(secrets.sortedByDescending(String::length).fold(value.content) { text, secret -> text.replace(secret, "[redacted]") }) else value
    }

    suspend fun turn(id: String): RuntimeTurnReceipt? = mutex.withLock {
        val complete = turns[id] ?: return@withLock null
        withContext(Dispatchers.IO) {
            RuntimeTurnReceipt(complete, control.resolve("$id.jsonl").useLines { lines -> lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["accepted"] != JsonPrimitive(true) }.toList() })
        }
    }
    suspend fun cancel(turn: String?, owner: Long) = mutex.withLock {
        check(owner == epoch) { "Stale runtime owner" }
        if (turn == null || turn != activeTurn || !cancelledTurns.add(turn)) return@withLock
        val pending = control.resolve("$turn.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }.filter { it.string("method") == "session/request_permission" }
        pending.forEach { message -> message["id"]?.let { if (callbacks.remove(it)) write(cancelPermission(it)) } }
        write(buildJsonObject { put("jsonrpc", "2.0"); put("method", "session/cancel"); putJsonObject("params") { put("sessionId", nativeSession) } })
    }

    suspend fun archive(owner: Long): RuntimeArchiveReceipt = lifecycle.withLock {
        mutex.withLock {
            check(owner == epoch) { "Stale runtime owner" }
            archiveReceipt?.let { return@withLock it }
            check(phase in listOf("READY", "DRAINING") && activeTurn == null && callbacks.isEmpty() && tools.isEmpty() && requests.isEmpty()) { "Runtime has unfinished work" }
            phase = "DRAINING"
        }
        archiveReceipt?.let { return@withLock it }
        stop()
        val metadata = Json.encodeToJsonElement(RuntimeStatus.serializer(), status()).jsonObject
        val receipt = archive.export(control.resolve("archive.tar.gz"), metadata, secrets.toList())
        mutex.withLock { archiveReceipt = receipt; phase = "ARCHIVED" }
        receipt
    }
    suspend fun restore(path: Path, checksum: String, workspaceOnly: Boolean, owner: Long): RuntimeStatus = lifecycle.withLock {
        mutex.withLock {
            check(owner == epoch) { "Stale runtime owner" }
            if (phase == "RESTORED") return@withLock identity()
            check(phase == "PREPARED") { "Runtime cannot restore in this phase" }
        }
        if (status().phase == "RESTORED") return@withLock status()
        val manifest = archive.restore(path, checksum, sessionId, workspaceOnly)
        mutex.withLock {
            nativeSession = if (workspaceOnly) null else manifest.metadata.string("nativeSessionId")
            phase = "RESTORED"; identity()
        }
    }
    private suspend fun stop() { process?.let { stopHarnessProcess(it, privateNamespace) }; reader?.join() }
    suspend fun close() = withContext(NonCancellable) {
        scope.cancel()
        // Release native I/O before an archive's lifecycle lock or a writer's protocol mutex.
        process?.let { stopHarnessProcess(it, privateNamespace) }
        lifecycle.withLock {
            // Recheck after any in-flight start has finished.
            stop()
            mutex.withLock { phase = "DRAINING" }
        }
    }

    companion object {
        private val SESSION_METHODS = setOf("session/new", "session/load", "session/resume")
        private fun cancelPermission(id: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("result") { putJsonObject("outcome") { put("outcome", "cancelled") } } }
        private fun withMeta(message: JsonObject, key: String, value: String): JsonObject {
            val params = message.getValue("params").jsonObject
            val meta = params["_meta"]?.jsonObject.orEmpty() + (key to JsonPrimitive(value))
            return JsonObject(message + ("params" to JsonObject(params + ("_meta" to JsonObject(meta)))))
        }
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
