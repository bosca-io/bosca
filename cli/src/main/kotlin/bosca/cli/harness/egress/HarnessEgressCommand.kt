package bosca.cli.harness.egress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking

/** Runs the Kit runtime egress proxy. */
class HarnessEgressCommand : CliktCommand(name = "egress") {
    override fun help(context: Context) = "Run the Kit runtime egress proxy for administrator-approved destinations"
    private val host by option("--host", help = "Listen address").default("0.0.0.0")
    private val port by option("--port", help = "Listen port").int().default(3128)
    private val boscaUrl by option("--bosca-url", envvar = "BOSCA_URL", help = "Bosca server base URL, such as http://server:8080").required()
    private val allow by option("--allow", envvar = "BOSCA_EGRESS_ALLOW",
        help = "Deployment-trusted Bosca host[:port] reachable by every authenticated runtime (port defaults to 443)")
        .split(",").multiple()

    override fun run() = runBlocking {
        val allowed = allow.flatten().map { it.trim() }.filter { it.isNotEmpty() }.map { EgressDestination.parse(it) }.toSet()
        val proxy = EgressProxy(EgressLookupCache(BoscaEgressLookup(boscaUrl)), allowed)
        val listener = proxy.start(this, host, port)
        echo("Egress proxy listening on $host:${listener.port}")
        try { listener.join() } finally { listener.close() }
    }
}
