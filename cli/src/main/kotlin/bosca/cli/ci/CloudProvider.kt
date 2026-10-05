package bosca.cli.ci

import bosca.cli.update.UpdateChecker
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import kotlin.uuid.Uuid

interface CloudProvider {
    suspend fun createVm(
        profile: VmProfile,
        name: String,
        userData: String,
    ): String

    suspend fun deleteVm(vmId: String): Boolean

    val name: String
}

class DigitalOceanProvider(
    private val apiToken: String,
    private val selfDestructToken: String?,
) : CloudProvider {

    override val name = "digitalocean"

    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val baseUrl = "https://api.digitalocean.com/v2"

    override suspend fun createVm(
        profile: VmProfile,
        name: String,
        userData: String,
    ): String {
        val body = buildJsonObject {
            put("name", name)
            put("region", profile.region)
            put("size", profile.size)
            put("image", profile.image)
            put("user_data", userData)
            putJsonArray("ssh_keys") {}
            putJsonArray("tags") {
                add("bosca-ci")
                add("ephemeral")
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/droplets")
            .header("Authorization", "Bearer $apiToken")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw CloudProviderException(
                    "Failed to create droplet: HTTP ${resp.code} — ${resp.body?.string()?.take(200)}"
                )
            }
            val responseBody = resp.body?.string()
                ?: throw CloudProviderException("Empty response from DigitalOcean")
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val dropletId = json["droplet"]?.jsonObject?.get("id")?.jsonPrimitive?.long
                ?: throw CloudProviderException("No droplet ID in response")
            return dropletId.toString()
        }
    }

    override suspend fun deleteVm(vmId: String): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/droplets/$vmId")
            .header("Authorization", "Bearer $apiToken")
            .delete()
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            return resp.code == 204 || resp.code == 404
        }
    }

    fun buildUserData(
        serverUrl: String,
        ephemeralToken: String,
        ephemeralAgentId: Uuid,
        jobId: Uuid,
        timeoutSeconds: Int,
    ): String {
        val watchdogSeconds = timeoutSeconds + 600
        val selfDestructBlock = if (selfDestructToken != null) {
            """
            # Discover own droplet ID via metadata API
            DROPLET_ID=${'$'}(curl -sf http://169.254.169.254/metadata/v1/id)
            # Self-destruct watchdog — kills this VM if the agent hangs
            (sleep $watchdogSeconds && \
             curl -sf -X DELETE "https://api.digitalocean.com/v2/droplets/${'$'}DROPLET_ID" \
               -H "Authorization: Bearer $selfDestructToken" || shutdown -h now) &
            """.trimIndent()
        } else {
            "# No self-destruct token configured\n(sleep $watchdogSeconds && shutdown -h now) &"
        }

        return """
            #!/bin/bash
            set -e

            $selfDestructBlock

            # Install Bosca CLI
            curl -fsSL ${UpdateChecker.DEFAULT_INSTALL_SCRIPT_URL} | bash

            # Run ephemeral agent
            bosca ci agent start --ephemeral \
              --token "$ephemeralToken" \
              --url "$serverUrl" \
              --agent-id "$ephemeralAgentId" \
              --job-id "$jobId"

            # Shutdown after completion
            shutdown -h now
        """.trimIndent()
    }
}

class CloudProviderException(message: String) : RuntimeException(message)

fun createCloudProvider(providerName: String, agentConfig: AgentConfig?): CloudProvider {
    return when (providerName.lowercase()) {
        "digitalocean" -> DigitalOceanProvider(
            apiToken = agentConfig?.providerApiToken
                ?: throw CloudProviderException("No providerApiToken in agent config"),
            selfDestructToken = agentConfig.selfDestructToken,
        )
        else -> throw CloudProviderException("Unsupported provider: $providerName")
    }
}
