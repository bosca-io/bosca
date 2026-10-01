package bosca.git.graphql

import bosca.git.model.BlameLine
import bosca.git.model.Blob
import bosca.git.model.BranchInfo
import bosca.git.model.CommitInfo
import bosca.git.model.RepoStats
import bosca.git.model.TagInfo
import bosca.git.model.TreeEntry
import bosca.git.model.TreeEntryType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves all fields on [GitTreeEntry] representing a file or directory in a
 * repository tree listing.
 */
@TypeController(type = "GitTreeEntry")
class GitTreeEntryController : GraphQLController<TreeEntry> {

    @Field
    fun name(source: TreeEntry): String = source.name

    @Field
    fun path(source: TreeEntry): String = source.path

    @Field
    fun type(source: TreeEntry): TreeEntryType = source.type

    @Field
    fun mode(source: TreeEntry): Int = source.mode

    @Field
    fun sha(source: TreeEntry): String = source.sha

    @Field
    fun size(source: TreeEntry): Long? = source.size
}

/**
 * Resolves all fields on [GitBlob] representing the content and metadata of
 * a single file at a given revision.
 */
@TypeController(type = "GitBlob")
class GitBlobController : GraphQLController<Blob> {

    @Field
    fun content(source: Blob): String? = source.content

    @Field
    fun size(source: Blob): Long = source.size

    @Field
    fun sha(source: Blob): String = source.sha

    @Field
    fun isBinary(source: Blob): Boolean = source.isBinary

    @Field
    fun mimeType(source: Blob): String? = source.mimeType
}

/**
 * Resolves all fields on [GitCommitInfo] representing a single commit's
 * metadata from the revision log.
 */
@TypeController(type = "GitCommitInfo")
class GitCommitInfoController : GraphQLController<CommitInfo> {

    @Field
    fun sha(source: CommitInfo): String = source.sha

    @Field
    fun message(source: CommitInfo): String = source.message

    @Field
    fun authorName(source: CommitInfo): String = source.authorName

    @Field
    fun authorEmail(source: CommitInfo): String = source.authorEmail

    @Field
    fun authorDate(source: CommitInfo): String = source.authorDate

    @Field
    fun committerName(source: CommitInfo): String = source.committerName

    @Field
    fun committerEmail(source: CommitInfo): String = source.committerEmail

    @Field
    fun committerDate(source: CommitInfo): String = source.committerDate

    @Field
    fun parentShas(source: CommitInfo): List<String> = source.parentShas
}

/**
 * Resolves all fields on [GitBranchInfo] representing a branch's name and
 * divergence from the default branch.
 */
@TypeController(type = "GitBranchInfo")
class GitBranchInfoController : GraphQLController<BranchInfo> {

    @Field
    fun name(source: BranchInfo): String = source.name

    @Field
    fun sha(source: BranchInfo): String = source.sha

    @Field
    fun ahead(source: BranchInfo): Int = source.ahead

    @Field
    fun behind(source: BranchInfo): Int = source.behind
}

/**
 * Resolves all fields on [GitTagInfo] representing a lightweight or annotated tag.
 */
@TypeController(type = "GitTagInfo")
class GitTagInfoController : GraphQLController<TagInfo> {

    @Field
    fun name(source: TagInfo): String = source.name

    @Field
    fun sha(source: TagInfo): String = source.sha

    @Field
    fun targetSha(source: TagInfo): String? = source.targetSha

    @Field
    fun taggerName(source: TagInfo): String? = source.taggerName

    @Field
    fun taggerEmail(source: TagInfo): String? = source.taggerEmail

    @Field
    fun message(source: TagInfo): String? = source.message

    @Field
    fun isAnnotated(source: TagInfo): Boolean = source.isAnnotated
}

/**
 * Resolves all fields on [GitBlameLine] representing a single line of blame output
 * with its commit attribution.
 */
@TypeController(type = "GitBlameLine")
class GitBlameLineController : GraphQLController<BlameLine> {

    @Field
    fun lineNumber(source: BlameLine): Int = source.lineNumber

    @Field
    fun commitSha(source: BlameLine): String = source.commitSha

    @Field
    fun authorName(source: BlameLine): String = source.authorName

    @Field
    fun authorEmail(source: BlameLine): String = source.authorEmail

    @Field
    fun content(source: BlameLine): String = source.content
}

/**
 * Resolves all fields on [GitRepoStats] representing aggregate repository statistics.
 */
@TypeController(type = "GitRepoStats")
class GitRepoStatsController : GraphQLController<RepoStats> {

    @Field
    fun commitCount(source: RepoStats): Long = source.commitCount

    @Field
    fun branchCount(source: RepoStats): Int = source.branchCount

    @Field
    fun tagCount(source: RepoStats): Int = source.tagCount

    @Field
    fun contributorCount(source: RepoStats): Int = source.contributorCount

    @Field
    fun diskSizeBytes(source: RepoStats): Long = source.diskSizeBytes
}
