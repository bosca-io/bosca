package bosca.cli.harness

import bosca.cli.Version
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import bosca.cli.harness.egress.EgressForwarder
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createDirectories

class HarnessServeCommand : CliktCommand(name = "serve") {
    override fun help(context: Context) = "Keep a native ACP harness warm and archive its state on request"
    private val host by option("--host").default("0.0.0.0")
    private val port by option("--port").int().default(8080)
    private val root by option("--root", help = "State directory; defaults to the managed image paths")
    private val privatePidNamespace by option("--private-pid-namespace", help = "Stop all remaining writers in this container's private PID namespace before archival").flag()

    override fun run() = runBlocking {
        val environment = harnessEnvironment(System.getenv())
        // Runtimes without direct internet access reach approved destinations through a loopback
        // forwarder that adds the generation's egress credentials, so no process sees the token.
        val forwarder = HarnessEgress.settings(environment)?.let { (proxy, credentials) -> EgressForwarder(proxy, credentials).start(this) }
        try {
            val server = harnessServer(runtime(environment, forwarder?.let { HarnessEgress(it.port, HarnessEgress.noProxy(environment["BOSCA_ENDPOINT"])) }), host, port)
            val stopped = CompletableDeferred<Unit>()
            server.monitor.subscribe(ApplicationStopped) { stopped.complete(Unit) }
            withContext(Dispatchers.IO) { server.start(wait = false) }
            try {
                if (forwarder == null) stopped.await()
                else select {
                    stopped.onAwait { }
                    // Without its forwarder the harness cannot reach approved destinations; stop so the generation is replaced.
                    async { forwarder.join() }.onAwait { error("Egress forwarder stopped") }
                }
            } finally {
                if (!stopped.isCompleted) withContext(NonCancellable + Dispatchers.IO) { server.stop() }
            }
        } finally { forwarder?.close() }
    }

    private fun runtime(environment: Map<String, String>, egress: HarnessEgress?): HarnessRuntime {
        root?.let {
            val base = Path.of(it).createDirectories()
            HarnessArchive.chmod(base, 448)
        }
        // The managed layout mounts fresh storage over /state, hiding directories created in the image.
        val home = (root?.let { Path.of(it, "home") } ?: Path.of("/state/home")).createDirectories()
        val installation = Path.of(environment["BOSCA_RUNTIME_HOME"] ?: "/opt/runtime")
        val descriptor = HarnessDescriptor.read(installation)
        val versions = descriptor.versions(installation, Version.current)
        val paths = root?.let { base -> HarnessArchive.ROOTS.associateWith { Path.of(base, it) } }
            ?: mapOf("workspace" to Path.of("/workspace"), "harness" to Path.of("/state/harness"), "scratch" to Path.of("/state/scratch"))
        return HarnessRuntime(
            sessionId = environment.getValue("BOSCA_CHAT_SESSION_ID"), generation = environment.getValue("BOSCA_RUNTIME_GENERATION").toLong(),
            token = environment.getValue("BOSCA_RUNTIME_TOKEN"), expires = environment.getValue("BOSCA_RUNTIME_EXPIRES").toLong(),
            command = Json.parseToJsonElement(environment.getValue("BOSCA_ACP_COMMAND")).jsonArray.map { it.jsonPrimitive.content },
            roots = paths, control = root?.let { Path.of(it, "control") } ?: Path.of("/state/control"),
            privateNamespace = root == null || privatePidNamespace, policy = descriptor.statePolicy, versions = versions,
            environment = environment + egress?.environment().orEmpty() + mapOf(
                "HOME" to home.toAbsolutePath().normalize().toString(),
                "TMPDIR" to paths.getValue("scratch").toAbsolutePath().normalize().toString(),
                "CODEX_HOME" to paths.getValue("harness").toAbsolutePath().normalize().toString(),
                "CLAUDE_CONFIG_DIR" to paths.getValue("harness").toAbsolutePath().normalize().toString(),
                "CLAUDE_CODE_TMPDIR" to paths.getValue("scratch").toAbsolutePath().normalize().toString(),
            ),
            maxBytes = environment["BOSCA_ARCHIVE_MAX_BYTES"]?.toLong() ?: 8L * 1024 * 1024 * 1024,
            maxJournalBytes = environment["BOSCA_JOURNAL_MAX_BYTES"]?.toLong() ?: 64L * 1024 * 1024,
            maxJournalTurns = environment["BOSCA_JOURNAL_MAX_TURNS"]?.toInt() ?: 256,
            egress = egress,
        )
    }
}
