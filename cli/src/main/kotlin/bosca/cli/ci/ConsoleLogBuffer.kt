package bosca.cli.ci

import bosca.cli.api.NetworkClient
import kotlin.uuid.Uuid

private val noOpUuid = Uuid.parse("00000000-0000-0000-0000-000000000000")

class ConsoleLogBuffer(
    private val stepName: String,
    private val secretValues: Set<String> = emptySet(),
) : LogBuffer(
    api = NoOpLocalCiApi,
    repositoryId = noOpUuid,
    runId = noOpUuid,
    jobId = noOpUuid,
    stepId = noOpUuid,
    secretValues = secretValues,
) {
    override suspend fun add(content: String, stream: String) {
        val masked = maskLocalSecrets(content)
        val prefix = if (stream == "stderr") "  \u001b[31m" else "  "
        val suffix = if (stream == "stderr") "\u001b[0m" else ""
        print("$prefix$masked$suffix\n")
    }

    override suspend fun flush() {}

    override suspend fun close() {}

    private fun maskLocalSecrets(content: String): String {
        var result = content
        for (secret in secretValues) {
            if (secret.isNotEmpty()) {
                result = result.replace(secret, "***")
            }
        }
        return result
    }
}

private object NoOpLocalCiApi : CiApi(NetworkClient("http://localhost:0"))
