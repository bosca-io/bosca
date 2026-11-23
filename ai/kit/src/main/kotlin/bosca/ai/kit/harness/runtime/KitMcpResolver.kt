package bosca.ai.kit.harness.runtime

import bosca.ai.agents.model.McpTransportType
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.kit.harness.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** Uses existing Agent/tool assignments; registry access never becomes a runtime or browser grant. */
class KitMcpResolver(
    private val agents: AgentService,
    private val registrations: McpServerRegistrationService,
    private val json: Json,
) {
    private val configurationJson = Json(json) { ignoreUnknownKeys = false; isLenient = false }

    suspend fun catalog(agentKey: String, profile: KitHarnessConfiguration): KitMcpCatalog {
        val agent = agents.getByKey(agentKey) ?: return KitMcpCatalog(emptyList(), emptyList(), emptyList())
        val servers = agents.getTools(agent.id).mapNotNull { it.mcpServerId }.distinct().map { id ->
            registrations.get(id) ?: throw KitMcpConfigurationException("An assigned MCP registration is missing. Review this agent's tools.")
        }
        val selections = (agent.configuration as? JsonObject)?.get("harnessMcp")?.let {
            try { configurationJson.decodeFromJsonElement(ListSerializer(KitMcpSelection.serializer()), it) }
            catch (_: IllegalArgumentException) { throw KitMcpConfigurationException("The agent's MCP selection is invalid. Review harnessMcp.") }
        } ?: servers.map { KitMcpSelection(it.key) }
        requireConfiguration(selections.size <= 16 && selections.map { it.key }.distinct().size == selections.size,
            "The agent's MCP selection must contain at most 16 unique connections.")
        val connections = mutableListOf<KitMcpConnection>()
        val options = mutableListOf<KitMcpOption>()
        val notices = mutableListOf<String>()
        for (selection in selections.sortedBy { it.key }) {
            requireConfiguration(selection.key.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && selection.key != "bosca",
                "An MCP registration key is invalid or reserved. Review this agent's tools.")
            val server = servers.firstOrNull { it.key == selection.key }
            requireConfiguration(server != null, "MCP ${selection.key} is not assigned to this agent. Review its tools.")
            val registration = requireNotNull(server)
            options.add(KitMcpOption(selection.key, registration.name.take(128), selection.required))
            try {
                requireConfiguration(registration.enabled, "MCP ${selection.key} is disabled. Enable the registration or remove the selection.")
                val configuration = (registration.configuration as? JsonObject)?.get("harness")?.let {
                    try { configurationJson.decodeFromJsonElement(KitMcpConfiguration.serializer(), it) }
                    catch (_: IllegalArgumentException) { null }
                }
                requireConfiguration(configuration != null, "MCP ${selection.key} needs a valid harness connection configuration.")
                val value = requireNotNull(configuration)
                val references = value.environment.values + value.headers.values
                requireConfiguration(references.all { it.environment in profile.mcpSecretEnvironment &&
                    it.environment.matches(Regex("[A-Z][A-Z0-9_]{0,127}")) &&
                    !it.environment.startsWith("BOSCA_") && it.prefix in listOf("", "Bearer ", "Basic ") },
                    "MCP ${selection.key} uses an unapproved secret reference. Review the runtime profile.")
                requireConfiguration(value.environment.keys.all { it.matches(Regex("[A-Z][A-Z0-9_]{0,127}")) && !it.startsWith("BOSCA_") } &&
                    value.headers.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,127}")) },
                    "MCP ${selection.key} has invalid environment or header names.")
                val command = if (registration.transportType == McpTransportType.STDIO) {
                    requireConfiguration(value.url == null && value.headers.isEmpty(), "MCP ${selection.key} has invalid stdio settings.")
                    val approved = profile.mcpExecutables[value.executable]
                    requireConfiguration(approved != null && approved.isNotEmpty() && approved.first().startsWith("/") &&
                        approved.all { !it.contains('\u0000') },
                        "MCP ${selection.key} needs an executable packaged and approved in the runtime profile.")
                    requireNotNull(approved)
                } else {
                    val uri = try { value.url?.let(::URI) } catch (_: IllegalArgumentException) { null }
                        catch (_: java.net.URISyntaxException) { null }
                    requireConfiguration(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                        uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
                        value.executable == null && value.environment.isEmpty(),
                        "MCP ${selection.key} needs an HTTP endpoint without embedded credentials, query, or fragment. Use header secret references.")
                    emptyList()
                }
                connections.add(KitMcpConnection(selection.key, registration.name.take(128), registration.transportType,
                    selection.required, value.url, command, value.environment, value.headers))
            } catch (error: KitMcpConfigurationException) {
                if (selection.required) throw error
                notices.add(requireNotNull(error.message))
            }
        }
        return KitMcpCatalog(connections, options, notices)
    }

    private fun requireConfiguration(condition: Boolean, message: String) {
        if (!condition) throw KitMcpConfigurationException(message)
    }
}
