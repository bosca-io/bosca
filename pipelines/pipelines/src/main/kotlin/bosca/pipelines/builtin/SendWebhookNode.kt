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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Action: POSTs the inbound value as JSON to a URL. Both the destination URL and the optional HMAC
 * signing key are resolved at execution from named pipeline secrets ([urlSecret]/[signingSecret],
 * AES/GCM-encrypted at rest and never written into the graph, run snapshot, or trace). [url]/[secret]
 * are legacy inline fallbacks retained only so pre-secret graphs keep running; new nodes reference
 * secrets. When a signing key is present the body is signed with HMAC-SHA256 into the
 * `X-Bosca-Signature` header so the receiver can verify origin. A side-effect node — returns no
 * output; non-2xx responses fail the node.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Webhook",
    description = "POSTs the inbound value as JSON to a URL, optionally signed with an HMAC secret.",
    group = "Core",
    subgroup = "Messaging",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Body",
            description = "The value POSTed as the JSON request body; when nothing is connected it posts a null body.",
            required = false,
        ),
    ],
    settings = [
        SettingSlot(
            name = "urlSecret", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "URL secret", required = true,
            placeholder = "Pick a secret…",
            description = "A pipeline secret whose value is the destination URL. Manage secrets under Pipelines → Secrets; the URL is resolved at run time and never stored in the pipeline.",
        ),
        SettingSlot(
            name = "signingSecret", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "Signing key secret",
            placeholder = "Pick a secret… (optional)",
            description = "Optional. A pipeline secret whose value is the HMAC-SHA256 signing key; when set, the body is signed in the X-Bosca-Signature header so the receiver can verify the request came from Bosca.",
        ),
    ],
)
@Serializable
@SerialName("sendWebhook")
class SendWebhookNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val urlSecret: String? = null,
    val signingSecret: String? = null,
    /** Legacy inline URL, retained only so pre-secret graphs still resolve; new nodes use [urlSecret]. */
    val url: String? = null,
    /** Legacy inline signing key, retained only so pre-secret graphs still resolve; new nodes use [signingSecret]. */
    val secret: String? = null,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /** The destination URL — the named secret's value when set, else the legacy inline URL. */
    private suspend fun resolveUrl(context: PipelineContext): String =
        urlSecret?.takeIf { it.isNotBlank() }
            ?.let { provide<PipelineSecretService>().resolveForExecution(it, context.dryRun) }
            ?: url?.takeIf { it.isNotBlank() }
            ?: error("Webhook node '${name.ifBlank { id }}': set a URL secret (or a legacy URL)")

    /** The HMAC signing key — the named secret's value when set, else the legacy inline key, else none. */
    private suspend fun resolveSigningKey(context: PipelineContext): String? =
        signingSecret?.takeIf { it.isNotBlank() }
            ?.let { provide<PipelineSecretService>().resolveForExecution(it, context.dryRun) }
            ?: secret?.takeIf { it.isNotBlank() }

    /** Whether a signing key is configured (either path) — for the dry-run trace, without resolving it. */
    private fun signingConfigured(): Boolean = !signingSecret.isNullOrBlank() || !secret.isNullOrBlank()

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendWebhook")
            // Record only the secret name — never resolve it in a dry run, so no URL/key reaches the trace.
            urlSecret?.let { put("urlSecret", it) }
            put("signed", signingConfigured())
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val url = resolveUrl(context)
        val signingKey = resolveSigningKey(context)
        val body = (inputs.first?.encode(context.json) ?: JsonNull).toString()
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
        signingKey?.let { builder.header("X-Bosca-Signature", sign(it, body)) }
        withContext(Dispatchers.IO) {
            client.newCall(builder.build()).execute().use { response ->
                // The URL may be secret; identify the failure by node, never by the resolved URL.
                check(response.isSuccessful) { "Webhook node '${name.ifBlank { id }}' returned HTTP ${response.code}" }
            }
        }
        return null
    }

    private fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val client = OkHttpClient()
    }
}
