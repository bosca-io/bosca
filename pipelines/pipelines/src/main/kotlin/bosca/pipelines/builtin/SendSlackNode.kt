package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineSecretService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Action: POSTs `{ "text": … }` to a Slack incoming webhook. The webhook URL embeds a Slack token, so
 * it is resolved at execution from the named pipeline secret [webhookSecret] (AES/GCM-encrypted at rest
 * and never written into the graph, run snapshot, or trace). [webhookUrl] is a legacy inline fallback,
 * retained only so pre-secret graphs keep running; new nodes reference a secret. [text] is used when
 * set; otherwise the inbound value is rendered (a string value as-is, anything else as JSON). A
 * side-effect node — returns no output; non-2xx responses fail the node.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Slack Message",
    description = "Posts a message to a Slack incoming webhook — the configured text, or the inbound value when none is set.",
    group = "Core",
    subgroup = "Messaging",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Message",
            description = "The value posted as the message when no text is configured (a string as-is, anything else as JSON); ignored when text is set.",
            required = false,
        ),
    ],
    settings = [
        SettingSlot(
            name = "webhookSecret", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "Slack webhook secret", required = true,
            placeholder = "Pick a secret…",
            description = "A pipeline secret whose value is the full Slack incoming-webhook URL. Manage secrets under Pipelines → Secrets; the URL is resolved at run time and never stored in the pipeline.",
        ),
        SettingSlot(
            name = "text", control = SettingControl.TEXTAREA, label = "Message text",
            placeholder = "Leave blank to post the inbound value",
        ),
    ],
)
@Serializable
@SerialName("sendSlack")
class SendSlackNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val webhookSecret: String? = null,
    /** Legacy inline webhook URL, retained only so pre-secret graphs still resolve; new nodes use [webhookSecret]. */
    val webhookUrl: String? = null,
    val text: String? = null,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    private fun resolveMessage(context: PipelineContext, inputs: NodeInputs): String =
        text ?: when (val encoded = inputs.first?.encode(context.json)) {
            null -> ""
            is JsonPrimitive -> encoded.content
            else -> encoded.toString()
        }

    /** The incoming-webhook URL — the named secret's value when set, else the legacy inline URL. */
    private suspend fun resolveWebhookUrl(context: PipelineContext): String =
        webhookSecret?.takeIf { it.isNotBlank() }
            ?.let { provide<PipelineSecretService>().resolveForExecution(it, context.dryRun) }
            ?: webhookUrl?.takeIf { it.isNotBlank() }
            ?: error("Slack node '${name.ifBlank { id }}': set a webhook secret (or a legacy webhook URL)")

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val message = resolveMessage(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendSlack")
            // Record only the secret name — never resolve it in a dry run, so no token reaches the trace.
            webhookSecret?.let { put("webhookSecret", it) }
            put("text", message)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val message = resolveMessage(context, inputs)
        val url = resolveWebhookUrl(context)
        val body = buildJsonObject { put("text", message) }.toString()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                // The URL is secret; identify the failure by node, never by the resolved URL.
                check(response.isSuccessful) { "Slack node '${name.ifBlank { id }}' webhook returned HTTP ${response.code}" }
            }
        }
        return null
    }

    companion object {
        private val client = OkHttpClient()
    }
}
