package bosca.kubernetes.controller.helm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Result of one helm CLI invocation. `output` is the combined
 * stdout+stderr stream — the runner intentionally merges them so the
 * caller can surface a single coherent error message when helm exits
 * non-zero.
 */
data class HelmCommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

/**
 * Boundary between [HelmRuntime] and the actual subprocess. Production
 * code uses [ProcessHelmCommandRunner]; tests inject a fake runner that
 * records the argv and emits a canned response. Keeping the runner
 * pluggable means the temp-file lifecycle (kubeconfig + values YAML
 * with `0600` perms, deletion in `finally`) can be tested without
 * actually launching a `helm` subprocess.
 */
interface HelmCommandRunner {
    suspend fun run(argv: List<String>, timeoutSeconds: Long): HelmCommandResult
}

/**
 * Default runner — drives a real `helm` subprocess via [ProcessBuilder].
 * Stdout and stderr are merged into a single output stream
 * (`redirectErrorStream`). On timeout, the process is forcibly
 * destroyed and the result's `timedOut` flag is set so the caller can
 * distinguish a hang from a clean non-zero exit.
 */
class ProcessHelmCommandRunner : HelmCommandRunner {
    override suspend fun run(argv: List<String>, timeoutSeconds: Long): HelmCommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            HelmCommandResult(exitCode = -1, output = output, timedOut = true)
        } else {
            HelmCommandResult(exitCode = process.exitValue(), output = output, timedOut = false)
        }
    }
}
