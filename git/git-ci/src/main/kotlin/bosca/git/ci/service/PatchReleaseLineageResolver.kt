package bosca.git.ci.service

import bosca.git.model.PipelineRun
import bosca.git.service.RepositoryBrowseService

/**
 * Resolves an explicitly marked patch tag to an earlier release in the same major/minor line.
 *
 * A patch release is opt-in: either the annotated tag message or a commit after the previous patch
 * tag must contain `Patch for [X.Y.Z]` (the brackets are optional) or the structured
 * `Bosca-Patch-For: X.Y.Z` trailer. The current tag must have the same major/minor and a strictly
 * greater patch component; skipped patch numbers are valid. Commit markers are considered only
 * after the repository's most recent lower ancestor patch tag, which prevents an old marker from
 * silently weakening later releases. The named base tag must be an ancestor of the tagged commit.
 */
internal class PatchReleaseLineageResolver(
    private val repositoryBrowse: RepositoryBrowseService,
) {

    suspend fun resolve(run: PipelineRun): PatchReleaseLineage? {
        val currentTag = run.ref.removePrefix(TAG_REF_PREFIX)
        if (currentTag == run.ref) return null
        val current = PatchVersion.parse(currentTag) ?: return null
        val tags = repositoryBrowse.listTags(run.repositoryId)
        val currentTagInfo = tags.firstOrNull { it.name == currentTag } ?: return null
        val lowerTags = tags.mapNotNull { tag ->
            PatchVersion.parse(tag.name)?.takeIf { it.isLowerPatchOf(current) }?.let { it to tag }
        }
        val commits = repositoryBrowse.listCommits(
            repositoryId = run.repositoryId,
            ref = run.commitSha,
            path = null,
            limit = MAX_PATCH_COMMITS,
            offset = 0,
        )
        val ancestorCommits = commits.mapTo(mutableSetOf()) { it.sha }
        val latestLowerCommit = lowerTags
            .filter { (_, tag) -> (tag.targetSha ?: tag.sha) in ancestorCommits }
            .maxByOrNull { (version, _) -> version.patch }
            ?.second
            ?.let { it.targetSha ?: it.sha }
            ?: return null
        val marker = findMarker(currentTagInfo.message, current)
            ?: commits.asSequence()
                .takeWhile { it.sha != latestLowerCommit }
                .mapNotNull { findMarker(it.message, current) }
                .firstOrNull()
            ?: return null
        val baseTagInfo = lowerTags
            .firstOrNull { (version, _) -> version.version == marker.version }
            ?.second
            ?: return null
        val baseCommitSha = baseTagInfo.targetSha ?: baseTagInfo.sha
        if (baseCommitSha !in ancestorCommits) return null

        return PatchReleaseLineage(
            currentVersion = current.version,
            baseVersion = marker.version,
            currentTag = currentTag,
            baseTag = baseTagInfo.name,
        )
    }

    private fun findMarker(message: String?, current: PatchVersion): PatchVersion? {
        if (message.isNullOrBlank()) return null
        return PATCH_MARKER.findAll(message)
            .mapNotNull { PatchVersion.parse(it.groupValues[1]) }
            .firstOrNull { it.isLowerPatchOf(current) }
    }

    private data class PatchVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
    ) {
        val version: String
            get() = "$major.$minor.$patch"

        fun isLowerPatchOf(other: PatchVersion): Boolean =
            major == other.major && minor == other.minor && patch < other.patch

        companion object {
            private val VERSION = Regex("""^(v?)(\d+)\.(\d+)\.(\d+)$""")

            fun parse(tag: String): PatchVersion? {
                val match = VERSION.matchEntire(tag) ?: return null
                return PatchVersion(
                    major = match.groupValues[2].toLongOrNull() ?: return null,
                    minor = match.groupValues[3].toLongOrNull() ?: return null,
                    patch = match.groupValues[4].toLongOrNull() ?: return null,
                )
            }
        }
    }

    companion object {
        private const val TAG_REF_PREFIX = "refs/tags/"
        private const val MAX_PATCH_COMMITS = 256
        private val PATCH_MARKER = Regex(
            """(?im)^\s*(?:Bosca-Patch-For:\s*|Patch\s+for\s+)\[?(v?\d+\.\d+\.\d+)\]?\s*$"""
        )
    }
}

/**
 * The version substitution authorized by a patch marker on one requiring repository tag.
 */
internal data class PatchReleaseLineage(
    val currentVersion: String,
    val baseVersion: String,
    val currentTag: String,
    val baseTag: String,
) {
    /**
     * Rewrites an exact current-version token in a requirement coordinate or tag ref to the base
     * version. Other pinned versions and unrelated numeric text remain untouched.
     */
    fun rewrite(value: String): String {
        var rewritten = replaceToken(value, "v$currentVersion", "v$baseVersion")
        rewritten = replaceToken(rewritten, currentVersion, baseVersion)
        return rewritten
    }

    private fun replaceToken(value: String, from: String, to: String): String =
        Regex("""(?<![A-Za-z0-9])${Regex.escape(from)}(?![A-Za-z0-9])""").replace(value, to)
}
