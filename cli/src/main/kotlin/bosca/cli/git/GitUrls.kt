package bosca.cli.git

import java.net.URI

/**
 * Pure helpers for interpreting the repository references and URLs the
 * git commands accept. Kept free of I/O so the parsing rules can be
 * exhaustively unit-tested.
 */
object GitUrls {

    /** An `owner/repo` reference. */
    data class RepoRef(val owner: String, val repo: String) {
        override fun toString(): String = "$owner/$repo"
    }

    /**
     * Parses an `owner/repo` reference, tolerating a trailing `.git`.
     * Throws [IllegalArgumentException] if the input is not exactly two
     * non-empty, slash-free segments.
     */
    fun parseRef(ref: String): RepoRef {
        val trimmed = ref.trim().removeSuffix("/").removeSuffix(".git")
        val parts = trimmed.split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "Expected an 'owner/repo' reference but got '$ref'"
        }
        return RepoRef(parts[0], parts[1])
    }

    /**
     * True when [value] looks like a clone URL rather than an
     * `owner/repo` reference — i.e. an `scheme://` URL or an `scp`-style
     * `user@host:path` address. Used by `clone` to decide whether to
     * resolve via GraphQL or hand the string straight to git.
     */
    fun looksLikeUrl(value: String): Boolean {
        val v = value.trim()
        return v.contains("://") || Regex("^[^/]+@[^/]+:").containsMatchIn(v)
    }

    /**
     * Extracts the host from an `https`/`http` clone URL, or null if the
     * value is not a parseable hierarchical URL with a host (e.g. an
     * `scp`-style address, which git authenticates over SSH rather than
     * via this helper).
     */
    fun host(url: String): String? = try {
        URI(url.trim()).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Derives a sensible default clone directory name from a clone URL,
     * mirroring git's own behaviour: the last path segment with any
     * trailing `.git` removed.
     */
    fun directoryNameFor(url: String): String? {
        val path = url.trim().removeSuffix("/").substringAfterLast('/')
        val name = path.removeSuffix(".git")
        return name.takeIf { it.isNotBlank() }
    }
}
