package bosca.ai.kit.harness.acp

import bosca.ai.kit.harness.KitModelCapabilities
import bosca.ai.kit.harness.KitModelOption
import bosca.ai.kit.harness.KitModelSelection
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Advertised ACP model and thought-level categories; native selector identifiers stay in the session. */
@OptIn(UnstableApi::class)
class AcpModelControls(private val json: Json) {
    fun capabilities(session: ClientSession): KitModelCapabilities {
        val model = option(session, "model")
        val reasoning = option(session, "thought_level")
        val normalized = KitModelCapabilities("", values(model), values(reasoning),
            KitModelSelection(model?.currentValue?.value, reasoning?.currentValue?.value))
        // Reasoning choices belong to the selected model. Changing effort alone does not stale the catalog.
        val revision = json.encodeToString(KitModelCapabilities.serializer(), normalized.copy(
            effective = KitModelSelection(model = normalized.effective.model)))
        return normalized.copy(version = MessageDigest.getInstance("SHA-256").digest(revision.encodeToByteArray())
            .joinToString("") { "%02x".format(it) })
    }

    /** Applies advertised choices within the caller's setup budget, model before reasoning options. */
    suspend fun apply(session: ClientSession, selected: KitModelSelection, timeoutMillis: Long = 30_000): KitModelCapabilities = withTimeout(timeoutMillis) {
        set(session, "model", selected.model, "model")
        set(session, "thought_level", selected.reasoning, "reasoning effort")
        capabilities(session).also {
            if (selected.model != null && it.effective.model != selected.model ||
                selected.reasoning != null && it.effective.reasoning != selected.reasoning) {
                throw KitModelConfigurationException("The harness did not confirm the requested model settings. No prompt was sent.")
            }
        }
    }

    private suspend fun set(session: ClientSession, category: String, value: String?, label: String) {
        if (value == null) return
        val option = option(session, category)
        if (option == null || values(option).none { it.value == value }) {
            throw KitModelConfigurationException("The selected $label is unavailable in this harness session. Review its settings before resuming.")
        }
        if (option.currentValue.value == value) return
        try {
            session.setConfigOption(option.id, SessionConfigOptionValue.StringValue(value))
        } catch (error: JsonRpcException) {
            throw KitModelConfigurationException("The harness rejected the selected $label. Review its settings before resuming.", error)
        }
    }

    private fun option(session: ClientSession, category: String): SessionConfigOption.Select? {
        if (!session.configOptionsSupported) return null
        val options = session.configOptions.value.filter { it.category?.value == category }
        if (options.size > 1) throw KitModelConfigurationException("The harness advertised ambiguous model settings.")
        return options.singleOrNull() as? SessionConfigOption.Select
    }

    private fun values(option: SessionConfigOption.Select?): List<KitModelOption> {
        val values = when (val options = option?.options) {
            is SessionConfigSelectOptions.Flat -> options.options
            is SessionConfigSelectOptions.Grouped -> options.groups.flatMap { it.options }
            null -> emptyList()
        }
        if (values.size > 256 || values.map { it.value }.distinct().size != values.size ||
            values.any { it.value.value.isBlank() || it.value.value.length > 256 || it.name.length > 256 }) {
            throw KitModelConfigurationException("The harness advertised invalid model settings.")
        }
        return values.map { KitModelOption(it.value.value, it.name) }
    }
}
