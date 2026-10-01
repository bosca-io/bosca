package bosca.cli.git

import java.io.File

/**
 * Thin wrapper around invoking the local `git` executable. Two modes:
 * [capture] for programmatic queries (e.g. reading a remote URL) and
 * [inherit] for user-facing operations (clone/push/pull) whose progress
 * output and prompts should stream straight through to the terminal.
 */
object GitCli {

    /** Result of a captured git invocation. */
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Runs `git [args]` capturing stdout/stderr. Does not throw on a
     * non-zero exit — inspect [Result.exitCode].
     */
    fun capture(vararg args: String, workingDir: File? = null): Result {
        val process = ProcessBuilder(listOf("git") + args).apply {
            if (workingDir != null) directory(workingDir)
        }.start()
        // Read both streams before waiting to avoid pipe-buffer deadlock.
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return Result(code, out.trim(), err.trim())
    }

    /**
     * Runs `git [args]` with the parent process's stdio so git's own
     * progress reporting and any interactive prompts reach the user.
     * Returns git's exit code.
     */
    fun inherit(vararg args: String, workingDir: File? = null): Int {
        val process = ProcessBuilder(listOf("git") + args).apply {
            if (workingDir != null) directory(workingDir)
            inheritIO()
        }.start()
        return process.waitFor()
    }

    /** True if `git` is resolvable on the PATH. */
    fun isAvailable(): Boolean = try {
        capture("--version").isSuccess
    } catch (_: Exception) {
        false
    }
}
