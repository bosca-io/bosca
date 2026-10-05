package bosca.git.graphql

import bosca.git.model.BranchInfo
import bosca.git.model.BranchProtectionRule
import bosca.git.model.Blob
import bosca.git.model.CommitInfo
import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusState
import bosca.git.model.CodeSearchResponse
import bosca.git.model.CodeSearchResult
import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.QuerySourceRef
import bosca.git.model.RepositorySearchResponse
import bosca.git.model.RepositorySearchResult
import bosca.git.model.Review
import bosca.git.model.ReviewComment
import bosca.git.model.ReviewStatus
import bosca.git.model.ScriptSourceRef
import bosca.git.model.TagInfo
import bosca.git.model.TaskCommitReference
import bosca.git.model.TaskPullRequestReference
import bosca.git.model.TreeEntry
import bosca.git.model.TreeEntryType
import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.model.WebhookEvent
import bosca.git.repository.ReviewCommentRepository
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises every pass-through field resolver on the small GraphQL type
 * controllers (review, webhook, browse, and misc groups) so the SDL surface
 * stays wired to the model fields.
 */
class GitTypeControllersTest {

    private val id = UUID.random()
    private val repositoryId = UUID.random()

    @Test
    fun `review and review comment controllers`() = runTest {
        val commentRepo = mockk<ReviewCommentRepository>(relaxed = true)
        val reviews = GitReviewController(commentRepo)
        val review = Review(
            id = id, pullRequestId = UUID.random(), reviewerId = UUID.random(),
            status = ReviewStatus.APPROVED, body = "LGTM",
        )
        assertEquals(id, reviews.id(review))
        assertEquals(review.pullRequestId, reviews.pullRequestId(review))
        assertEquals(review.reviewerId, reviews.reviewerId(review))
        assertEquals(ReviewStatus.APPROVED, reviews.status(review))
        assertEquals("LGTM", reviews.body(review))
        assertEquals(null, reviews.dismissedAt(review))
        assertEquals(null, reviews.dismissReason(review))
        assertEquals(review.created, reviews.created(review))
        reviews.comments(review)
        coVerify { commentRepo.findByReview(id) }

        val comments = GitReviewCommentController()
        val comment = ReviewComment(
            id = id, reviewId = UUID.random(), pullRequestId = UUID.random(), authorId = UUID.random(),
            filePath = "a.txt", oldLineNumber = 1, newLineNumber = 2, commitSha = "abc", content = "hm",
        )
        assertEquals(id, comments.id(comment))
        assertEquals(comment.reviewId, comments.reviewId(comment))
        assertEquals(comment.pullRequestId, comments.pullRequestId(comment))
        assertEquals(comment.authorId, comments.authorId(comment))
        assertEquals("a.txt", comments.filePath(comment))
        assertEquals(1, comments.oldLineNumber(comment))
        assertEquals(2, comments.newLineNumber(comment))
        assertEquals("abc", comments.commitSha(comment))
        assertEquals("hm", comments.content(comment))
        assertEquals(false, comments.outdated(comment))
        assertEquals(false, comments.resolved(comment))
        assertEquals(comment.created, comments.created(comment))
        assertEquals(comment.updated, comments.updated(comment))
    }

    @Test
    fun `branch protection rule controller`() {
        val c = GitBranchProtectionRuleController()
        val rule = BranchProtectionRule(
            id = id, repositoryId = repositoryId, pattern = "main",
            requirePullRequest = true, requiredApprovals = 2, dismissStaleReviews = true,
            requireCodeOwnerReview = true, requireStatusChecks = listOf("ci"),
            requireLinearHistory = true, allowForcePush = false, allowDeletion = false,
            restrictPushAccess = listOf(UUID.random()),
        )
        assertEquals(id, c.id(rule))
        assertEquals(repositoryId, c.repositoryId(rule))
        assertEquals("main", c.pattern(rule))
        assertEquals(true, c.requirePullRequest(rule))
        assertEquals(2, c.requiredApprovals(rule))
        assertEquals(true, c.dismissStaleReviews(rule))
        assertEquals(true, c.requireCodeOwnerReview(rule))
        assertEquals(listOf("ci"), c.requireStatusChecks(rule))
        assertEquals(true, c.requireLinearHistory(rule))
        assertEquals(false, c.allowForcePush(rule))
        assertEquals(false, c.allowDeletion(rule))
        assertEquals(rule.restrictPushAccess, c.restrictPushAccess(rule))
    }

    @Test
    fun `webhook and delivery controllers`() {
        val hooks = GitWebhookController()
        val hook = Webhook(id = id, repositoryId = repositoryId, url = "https://x", secret = "s", events = listOf(WebhookEvent.PUSH))
        assertEquals(id, hooks.id(hook))
        assertEquals(repositoryId, hooks.repositoryId(hook))
        assertEquals("https://x", hooks.url(hook))
        assertEquals(listOf(WebhookEvent.PUSH), hooks.events(hook))
        assertEquals(true, hooks.active(hook))
        assertEquals(hook.created, hooks.created(hook))

        val deliveries = GitWebhookDeliveryController()
        val delivery = WebhookDelivery(id = id, webhookId = hook.id, event = WebhookEvent.PUSH, payload = "{}", responseStatus = 200, success = true)
        assertEquals(id, deliveries.id(delivery))
        assertEquals(hook.id, deliveries.webhookId(delivery))
        assertEquals("PUSH", deliveries.event(delivery))
        assertEquals(200, deliveries.responseStatus(delivery))
        assertEquals(0, deliveries.retryCount(delivery))
        assertEquals(true, deliveries.success(delivery))
        assertEquals(delivery.created, deliveries.created(delivery))
    }

    @Test
    fun `browse controllers`() {
        val trees = GitTreeEntryController()
        val entry = TreeEntry(name = "f.txt", path = "src/f.txt", type = TreeEntryType.BLOB, mode = 33188, sha = "abc", size = 5L)
        assertEquals("f.txt", trees.name(entry))
        assertEquals("src/f.txt", trees.path(entry))
        assertEquals(TreeEntryType.BLOB, trees.type(entry))
        assertEquals(33188, trees.mode(entry))
        assertEquals("abc", trees.sha(entry))
        assertEquals(5L, trees.size(entry))

        val blobs = GitBlobController()
        val blob = Blob(content = "hi", size = 2L, sha = "abc", isBinary = false, mimeType = "text/plain")
        assertEquals("hi", blobs.content(blob))
        assertEquals(2L, blobs.size(blob))
        assertEquals("abc", blobs.sha(blob))
        assertEquals(false, blobs.isBinary(blob))
        assertEquals("text/plain", blobs.mimeType(blob))

        val commits = GitCommitInfoController()
        val info = CommitInfo(
            sha = "abc", message = "m", authorName = "a", authorEmail = "a@x", authorDate = "d1",
            committerName = "c", committerEmail = "c@x", committerDate = "d2", parentShas = listOf("p"),
        )
        assertEquals("abc", commits.sha(info))
        assertEquals("m", commits.message(info))
        assertEquals("a", commits.authorName(info))
        assertEquals("a@x", commits.authorEmail(info))
        assertEquals("d1", commits.authorDate(info))
        assertEquals("c", commits.committerName(info))
        assertEquals("c@x", commits.committerEmail(info))
        assertEquals("d2", commits.committerDate(info))
        assertEquals(listOf("p"), commits.parentShas(info))

        val branches = GitBranchInfoController()
        val branch = BranchInfo(name = "main", sha = "abc", ahead = 1, behind = 2)
        assertEquals("main", branches.name(branch))
        assertEquals("abc", branches.sha(branch))
        assertEquals(1, branches.ahead(branch))
        assertEquals(2, branches.behind(branch))

        val tags = GitTagInfoController()
        val tag = TagInfo(name = "v1", sha = "abc")
        assertEquals("v1", tags.name(tag))
        assertEquals("abc", tags.sha(tag))
    }

    @Test
    fun `misc controllers`() {
        val statuses = GitCommitStatusController()
        val status = CommitStatus(
            id = id, repositoryId = repositoryId, commitSha = "abc", context = "ci",
            state = CommitStatusState.SUCCESS, description = "ok", targetUrl = "https://ci",
        )
        assertEquals(id, statuses.id(status))
        assertEquals(repositoryId, statuses.repositoryId(status))
        assertEquals("abc", statuses.commitSha(status))
        assertEquals("ci", statuses.context(status))
        assertEquals(CommitStatusState.SUCCESS, statuses.state(status))
        assertEquals("ok", statuses.description(status))
        assertEquals("https://ci", statuses.targetUrl(status))
        assertEquals(status.created, statuses.created(status))

        val taskCommits = GitTaskCommitReferenceController()
        val tc = TaskCommitReference(id = id, repositoryId = repositoryId, taskKey = "GIT-1", commitSha = "abc")
        assertEquals(id, taskCommits.id(tc))
        assertEquals(repositoryId, taskCommits.repositoryId(tc))
        assertEquals("GIT-1", taskCommits.taskKey(tc))
        assertEquals("abc", taskCommits.commitSha(tc))
        assertEquals(tc.created, taskCommits.created(tc))

        val taskPrs = GitTaskPullRequestReferenceController()
        val tp = TaskPullRequestReference(id = id, repositoryId = repositoryId, taskKey = "GIT-1", pullRequestId = UUID.random(), pullRequestNumber = 3)
        assertEquals(id, taskPrs.id(tp))
        assertEquals(repositoryId, taskPrs.repositoryId(tp))
        assertEquals("GIT-1", taskPrs.taskKey(tp))
        assertEquals(tp.pullRequestId, taskPrs.pullRequestId(tp))
        assertEquals(3, taskPrs.pullRequestNumber(tp))
        assertEquals(tp.created, taskPrs.created(tp))

        val scriptRefs = GitScriptSourceRefController()
        val sr = ScriptSourceRef(UUID.random(), repositoryId, "p", "main", "abc")
        assertEquals(sr.scriptId, scriptRefs.scriptId(sr))
        assertEquals(repositoryId, scriptRefs.repositoryId(sr))
        assertEquals("p", scriptRefs.path(sr))
        assertEquals("main", scriptRefs.ref(sr))
        assertEquals("abc", scriptRefs.resolvedCommit(sr))

        val queryRefs = GitQuerySourceRefController()
        val qr = QuerySourceRef(UUID.random(), repositoryId, "p", "main", "abc")
        assertEquals(qr.queryId, queryRefs.queryId(qr))
        assertEquals(repositoryId, queryRefs.repositoryId(qr))
        assertEquals("p", queryRefs.path(qr))
        assertEquals("main", queryRefs.ref(qr))
        assertEquals("abc", queryRefs.resolvedCommit(qr))
    }

    @Test
    fun `pull request activity controller exposes the complete event`() {
        val pullRequestId = UUID.random()
        val authorId = UUID.random()
        val actorId = UUID.random()
        val assigneeId = UUID.random()
        val recipientIds = setOf(UUID.random(), UUID.random())
        val event = PullRequestEvent(
            repositoryId = repositoryId,
            pullRequestId = pullRequestId,
            number = 42,
            action = PullRequestEventAction.REVIEWED,
            title = "Review coverage",
            sourceBranch = "coverage",
            targetBranch = "main",
            authorId = authorId,
            taskKeys = setOf("GIT-42", "WORKSPACE-28"),
            repositoryName = "bosca-git",
            recipientIds = recipientIds,
            actorId = actorId,
            actorName = "Reviewer",
            body = "Approved",
            filePath = "src/Main.kt",
            lineNumber = 17,
            reviewStatus = ReviewStatus.APPROVED,
            assigneeId = assigneeId,
        )
        val controller = GitPullRequestActivityEventController()

        assertEquals(repositoryId, controller.repositoryId(event))
        assertEquals(pullRequestId, controller.pullRequestId(event))
        assertEquals(42, controller.number(event))
        assertEquals(PullRequestEventAction.REVIEWED, controller.action(event))
        assertEquals("Review coverage", controller.title(event))
        assertEquals("coverage", controller.sourceBranch(event))
        assertEquals("main", controller.targetBranch(event))
        assertEquals(authorId, controller.authorId(event))
        assertEquals(listOf("GIT-42", "WORKSPACE-28"), controller.taskKeys(event))
        assertEquals("bosca-git", controller.repositoryName(event))
        assertEquals(recipientIds.toList(), controller.recipientIds(event))
        assertEquals(actorId, controller.actorId(event))
        assertEquals("Reviewer", controller.actorName(event))
        assertEquals("Approved", controller.body(event))
        assertEquals("src/Main.kt", controller.filePath(event))
        assertEquals(17, controller.lineNumber(event))
        assertEquals(ReviewStatus.APPROVED, controller.reviewStatus(event))
        assertEquals(assigneeId, controller.assigneeId(event))
    }

    @Test
    fun `diff, blame, stats, and comparison controllers`() {
        val line = bosca.git.model.DiffLine(bosca.git.model.DiffLineType.ADD, null, 2, "+x")
        val lines = GitDiffLineController()
        assertEquals(bosca.git.model.DiffLineType.ADD, lines.type(line))
        assertEquals(null, lines.oldLineNumber(line))
        assertEquals(2, lines.newLineNumber(line))
        assertEquals("+x", lines.content(line))

        val secondLine = bosca.git.model.DiffLine(bosca.git.model.DiffLineType.CONTEXT, 2, 3, " context")
        val hunk = bosca.git.model.DiffHunk(1, 2, 3, 4, listOf(line, secondLine))
        val hunks = GitDiffHunkController()
        assertEquals(1, hunks.oldStart(hunk))
        assertEquals(2, hunks.oldCount(hunk))
        assertEquals(3, hunks.newStart(hunk))
        assertEquals(4, hunks.newCount(hunk))
        assertEquals(2, hunks.totalLineCount(hunk))
        assertEquals(listOf(line, secondLine), hunks.lines(hunk, null, null))
        assertEquals(listOf(secondLine), hunks.lines(hunk, 1, 1))

        val file = bosca.git.model.DiffFile("a", "b", bosca.git.model.DiffChangeType.MODIFY, listOf(hunk))
        val files = GitDiffFileController()
        assertEquals("a", files.oldPath(file))
        assertEquals("b", files.newPath(file))
        assertEquals(bosca.git.model.DiffChangeType.MODIFY, files.changeType(file))
        assertEquals(listOf(hunk), files.hunks(file))

        val blame = bosca.git.model.BlameLine(7, "abc", "a", "a@x", "text")
        val blames = GitBlameLineController()
        assertEquals(7, blames.lineNumber(blame))
        assertEquals("abc", blames.commitSha(blame))
        assertEquals("a", blames.authorName(blame))
        assertEquals("a@x", blames.authorEmail(blame))
        assertEquals("text", blames.content(blame))

        val stats = bosca.git.model.RepoStats(10L, 2, 3, 4, 5L)
        val statsC = GitRepoStatsController()
        assertEquals(10L, statsC.commitCount(stats))
        assertEquals(2, statsC.branchCount(stats))
        assertEquals(3, statsC.tagCount(stats))
        assertEquals(4, statsC.contributorCount(stats))
        assertEquals(5L, statsC.diskSizeBytes(stats))

        val cmp = bosca.git.model.ComparisonResult("main", "dev", emptyList(), listOf(file), 1, 2, 3)
        val cmps = GitComparisonResultController()
        assertEquals("main", cmps.baseRef(cmp))
        assertEquals("dev", cmps.headRef(cmp))
        assertEquals(emptyList(), cmps.commits(cmp))
        assertEquals(listOf(file), cmps.files(cmp))
        assertEquals(1, cmps.filesChanged(cmp))
        assertEquals(2, cmps.insertions(cmp))
        assertEquals(3, cmps.deletions(cmp))

        val tags = GitTagInfoController()
        val tag = bosca.git.model.TagInfo(
            name = "v2", sha = "abc", targetSha = "def", taggerName = "t",
            taggerEmail = "t@x", message = "rel", isAnnotated = true,
        )
        assertEquals("def", tags.targetSha(tag))
        assertEquals("t", tags.taggerName(tag))
        assertEquals("t@x", tags.taggerEmail(tag))
        assertEquals("rel", tags.message(tag))
        assertEquals(true, tags.isAnnotated(tag))
    }

    @Test
    fun `diff hunk lines are bounded below the GraphQL list limit`() {
        val lines = List(20_001) { index ->
            bosca.git.model.DiffLine(bosca.git.model.DiffLineType.ADD, null, index + 1, "+$index")
        }
        val hunk = bosca.git.model.DiffHunk(0, 0, 1, lines.size, lines)
        val controller = GitDiffHunkController()

        assertEquals(20_001, controller.totalLineCount(hunk))
        assertEquals(2_000, controller.lines(hunk, null, null).size)
        assertEquals(20_000, controller.lines(hunk, 0, Int.MAX_VALUE).size)
        assertEquals(lines.subList(20_000, 20_001), controller.lines(hunk, 20_000, 10))
    }

    @Test
    fun `search result and commit file result controllers`() {
        val search = GitSearchResultController()
        val hit = bosca.git.model.SearchResult("src/A.kt", 7, "fun a()")
        assertEquals("src/A.kt", search.filePath(hit))
        assertEquals(7, search.lineNumber(hit))
        assertEquals("fun a()", search.snippet(hit))

        val codeHit = CodeSearchResult(
            repositoryId = UUID.random(),
            repositoryName = "Bosca",
            repositorySlug = "bosca",
            filePath = "src/B.kt",
            language = "kotlin",
            snippet = "class B",
        )
        val codeResult = GitCodeSearchResultController()
        assertEquals(codeHit.repositoryId, codeResult.repositoryId(codeHit))
        assertEquals("Bosca", codeResult.repositoryName(codeHit))
        assertEquals("bosca", codeResult.repositorySlug(codeHit))
        assertEquals("src/B.kt", codeResult.filePath(codeHit))
        assertEquals("kotlin", codeResult.language(codeHit))
        assertEquals("class B", codeResult.snippet(codeHit))
        val codeResponse = CodeSearchResponse(listOf(codeHit), 7)
        assertEquals(listOf(codeHit), GitCodeSearchResponseController().results(codeResponse))
        assertEquals(7L, GitCodeSearchResponseController().estimatedHits(codeResponse))

        val repositoryHit = RepositorySearchResult(
            id = UUID.random(),
            name = "Bosca",
            slug = "bosca",
            description = "Repository",
            ownerId = UUID.random(),
            visibility = "PRIVATE",
            defaultBranch = "main",
            archived = false,
        )
        val repositoryResult = GitRepositorySearchResultController()
        assertEquals(repositoryHit.id, repositoryResult.id(repositoryHit))
        assertEquals("Bosca", repositoryResult.name(repositoryHit))
        assertEquals("bosca", repositoryResult.slug(repositoryHit))
        assertEquals("Repository", repositoryResult.description(repositoryHit))
        assertEquals(repositoryHit.ownerId, repositoryResult.ownerId(repositoryHit))
        assertEquals("PRIVATE", repositoryResult.visibility(repositoryHit))
        assertEquals("main", repositoryResult.defaultBranch(repositoryHit))
        assertEquals(false, repositoryResult.archived(repositoryHit))
        val repositoryResponse = RepositorySearchResponse(listOf(repositoryHit), 5)
        assertEquals(listOf(repositoryHit), GitRepositorySearchResponseController().results(repositoryResponse))
        assertEquals(5L, GitRepositorySearchResponseController().estimatedHits(repositoryResponse))

        val commits = CommitFileResultController()
        val result = bosca.git.service.CommitFileResult("abc", "main", "a.txt")
        assertEquals("abc", commits.commitSha(result))
        assertEquals("main", commits.branch(result))
        assertEquals("a.txt", commits.path(result))
    }
}
