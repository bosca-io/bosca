package bosca.ide.repository

import java.net.URI

/** Normalizes HTTP(S), SSH URI, and SCP-style Git remotes to host/path identity. */
object BoscaGitRemoteNormalizer {
    fun normalize(value: String): String? {
        val remote = value.trim().removeSuffix("/")
        if (remote.isEmpty()) return null

        val scp = SCP_STYLE.matchEntire(remote)
        if (scp != null && !remote.contains("://")) {
            val host = scp.groupValues[1].lowercase()
            val path = normalizePath(scp.groupValues[2])
            return "$host/$path"
        }

        val uri = runCatching { URI(remote) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = normalizePath(uri.path ?: return null)
        return "$host/$path"
    }

    private fun normalizePath(value: String): String =
        value.trim('/').removeSuffix(".git")

    private val SCP_STYLE = Regex("(?:[^@/]+@)?([^:/]+):(.+)")
}
