package bosca.git.github

import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubCreatePullRequestInput
import bosca.git.model.GitHubUpdatePullRequestInput
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.*

class GitHubClientTest {
    private val pair = GitHubRepositoryPair(UUID.random(), 123, "owner", "repo", "webhook", "token")
    private val pr = """{"id":456,"number":7,"node_id":"PR_456","title":"Change","body":"Description","state":"open","draft":true,"merged":false,"updated_at":"2026-10-02T10:00:00Z","user":{"id":8,"login":"author","type":"User"},"head":{"ref":"feature/one","sha":"${"1".repeat(40)}","repo":{"id":123}},"base":{"ref":"main","sha":"${"2".repeat(40)}","repo":{"id":123}}}"""
    private fun client(server: MockWebServer) = GitHubClient(baseUrl = server.url("/").toString().removeSuffix("/"))
    private fun verifyPair(server: MockWebServer) = server.enqueue(MockResponse.Builder().body("""{"id":123}""").build())
    @Test fun `verifies immutable repository identity and keeps credentials out of the Git URL`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"id":123,"full_name":"owner/repo"}""").build())
            val client = GitHubClient(baseUrl = server.url("/").toString().removeSuffix("/"))
            val pair = GitHubRepositoryPair(UUID.random(), 123, "owner", "repo", "webhook", "token")
            assertEquals("https://github.com/owner/repo.git", client.repositoryUrl(pair, "secret-token"))
            val request = server.takeRequest()
            assertEquals("/repos/owner/repo", request.url.encodedPath)
            assertEquals("Bearer secret-token", request.headers["Authorization"])
            assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
        }
    }

    @Test fun `refuses mismatched repositories and reports HTTP failures without response credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = GitHubClient(baseUrl = server.url("/").toString().removeSuffix("/"))
            val pair = GitHubRepositoryPair(UUID.random(), 123, "owner", "repo", "webhook", "token")
            server.enqueue(MockResponse.Builder().body("""{"id":999}""").build())
            assertFailsWith<IllegalStateException> { client.repositoryUrl(pair, "secret-token") }
            server.enqueue(MockResponse.Builder().code(403).body("secret-token").build())
            val failure = assertFailsWith<IllegalStateException> { client.repositoryUrl(pair, "secret-token") }
            assertTrue(failure.message.orEmpty().contains("403"))
            assertFalse(failure.message.orEmpty().contains("secret-token"))
        }
    }

    @Test fun `reads current PR fields including original authorship branch identity and merge history`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); verifyPair(server)
            val merged = pr.replace("\"state\":\"open\"", "\"state\":\"closed\"")
                .replace("\"merged\":false", "\"merged\":true,\"merge_commit_sha\":\"${"3".repeat(40)}\",\"merged_at\":\"2026-10-02T09:00:00Z\"")
            server.enqueue(MockResponse.Builder().body(merged).build())
            val result = client(server).getPullRequest(pair, "secret-token", 7)
            assertEquals(456L, result.id); assertEquals(7, result.number); assertEquals("PR_456", result.nodeId)
            assertEquals("Description", result.body); assertEquals("closed", result.state)
            assertTrue(result.draft); assertTrue(result.merged)
            assertEquals("3".repeat(40), result.mergeSha)
            assertEquals(OffsetDateTime.parse("2026-10-02T09:00:00Z"), result.mergedAt)
            assertEquals(OffsetDateTime.parse("2026-10-02T10:00:00Z"), result.modified)
            assertEquals(8L, result.user.id); assertEquals("author", result.user.login)
            assertEquals("feature/one", result.head.ref); assertEquals(123L, result.head.repo?.id)
            assertEquals("1".repeat(40), result.head.sha); assertEquals("main", result.base.ref)
            assertEquals("/repos/owner/repo", server.takeRequest().url.encodedPath)
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/repos/owner/repo/pulls/7", request.url.encodedPath)
            assertEquals("Bearer secret-token", request.headers["Authorization"])
        }
    }

    @Test fun `lists open and closed PRs using page numbers and an encoded head filter`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); verifyPair(server)
            server.enqueue(MockResponse.Builder().body("[$pr]").build())
            val client = client(server)
            assertEquals(7, client.listPullRequests(pair, "token", page = 2, head = "feature/one").single().number)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("all", request.url.queryParameter("state")); assertEquals("100", request.url.queryParameter("per_page"))
            assertEquals("2", request.url.queryParameter("page")); assertEquals("owner:feature/one", request.url.queryParameter("head"))
            verifyPair(server); server.enqueue(MockResponse.Builder().body("[]").build())
            assertTrue(client.listPullRequests(pair, "token", page = 3).isEmpty())
            server.takeRequest(); assertNull(server.takeRequest().url.queryParameter("head"))
        }
    }

    @Test fun `creates draft and ready counterparts without claiming the original author's API identity`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (draft in listOf(false, true)) {
                verifyPair(server); server.enqueue(MockResponse.Builder().code(201).body(pr).build())
                assertEquals(7, client(server).createPullRequest(pair, "token", GitHubCreatePullRequestInput(
                    "Title", "Original author attribution", "feature/one", "main", draft,
                )).number)
                server.takeRequest()
                val request = server.takeRequest()
                assertEquals("POST", request.method); assertEquals("/repos/owner/repo/pulls", request.url.encodedPath)
                val body = Json.parseToJsonElement(assertNotNull(request.body).utf8()).jsonObject
                assertEquals("Title", body["title"]?.jsonPrimitive?.content)
                assertEquals("Original author attribution", body["body"]?.jsonPrimitive?.content)
                assertEquals("feature/one", body["head"]?.jsonPrimitive?.content)
                assertEquals("main", body["base"]?.jsonPrimitive?.content)
                assertEquals(draft.toString(), body["draft"]?.jsonPrimitive?.content)
                assertFalse("user" in body)
            }
        }
    }

    @Test fun `updates metadata clears the body and closes or reopens without invoking a merge`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (state in listOf("open", "closed")) {
                verifyPair(server); server.enqueue(MockResponse.Builder().body(pr).build())
                client(server).updatePullRequest(pair, "token", 7, GitHubUpdatePullRequestInput("New title", JsonNull, "release", state))
                server.takeRequest()
                val request = server.takeRequest()
                assertEquals("PATCH", request.method); assertEquals("/repos/owner/repo/pulls/7", request.url.encodedPath)
                assertEquals(Json.parseToJsonElement("""{"title":"New title","body":null,"base":"release","state":"$state"}"""),
                    Json.parseToJsonElement(assertNotNull(request.body).utf8()))
            }
        }
    }

    @Test fun `patches only supplied fields including explicit null and empty descriptions`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val changes = listOf(
                GitHubUpdatePullRequestInput(title = "Title") to """{"title":"Title"}""",
                GitHubUpdatePullRequestInput(body = JsonNull) to """{"body":null}""",
                GitHubUpdatePullRequestInput(body = JsonPrimitive("")) to """{"body":""}""",
                GitHubUpdatePullRequestInput(body = JsonPrimitive("Description")) to """{"body":"Description"}""",
                GitHubUpdatePullRequestInput(base = "release") to """{"base":"release"}""",
                GitHubUpdatePullRequestInput(state = "closed") to """{"state":"closed"}""",
            )
            for ((input, expected) in changes) {
                verifyPair(server); server.enqueue(MockResponse.Builder().body(pr).build())
                client(server).updatePullRequest(pair, "token", 7, input)
                server.takeRequest()
                assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(assertNotNull(server.takeRequest().body).utf8()))
            }
        }
    }

    @Test fun `empty and non-string patches fail before making an HTTP request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            assertFailsWith<IllegalArgumentException> { client(server).updatePullRequest(pair, "token", 7, GitHubUpdatePullRequestInput()) }
            assertFailsWith<IllegalArgumentException> { client(server).updatePullRequest(pair, "token", 7, GitHubUpdatePullRequestInput(body = JsonPrimitive(42))) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `changes draft stage using the correct GraphQL input and verifies the returned PR`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (draft in listOf(true, false)) {
                verifyPair(server)
                server.enqueue(MockResponse.Builder().body("""{"data":{"changeStage":{"pullRequest":{"id":"PR_456","isDraft":$draft}}}}""").build())
                client(server).setPullRequestDraft(pair, "token", "PR_456", draft)
                server.takeRequest()
                val request = server.takeRequest()
                assertEquals("POST", request.method); assertEquals("/graphql", request.url.encodedPath)
                val body = Json.parseToJsonElement(assertNotNull(request.body).utf8()).jsonObject
                val query = body.getValue("query").jsonPrimitive.content
                assertTrue(query.contains(if (draft) "convertPullRequestToDraft" else "markPullRequestReadyForReview"))
                assertTrue(query.contains(if (draft) "ConvertPullRequestToDraftInput" else "MarkPullRequestReadyForReviewInput"))
                assertEquals("PR_456", body.getValue("variables").jsonObject.getValue("input").jsonObject
                    .getValue("pullRequestId").jsonPrimitive.content)
            }
        }
    }

    @Test fun `rejects GraphQL errors absent results and mismatched returned stages without disclosing response content`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (body in listOf(
                """{"errors":[{"message":"secret-token"}]}""",
                """{"data":null}""",
                """{"data":{"changeStage":null}}""",
                """{"data":{"changeStage":{"pullRequest":{"id":"PR_other","isDraft":true}}}}""",
                """{"data":{"changeStage":{"pullRequest":{"id":"PR_456","isDraft":false}}}}""",
            )) {
                verifyPair(server); server.enqueue(MockResponse.Builder().body(body).build())
                val failure = assertFailsWith<IllegalStateException> { client(server).setPullRequestDraft(pair, "token", "PR_456", true) }
                assertFalse(failure.message.orEmpty().contains("secret-token"))
            }
        }
    }

    @Test fun `validates identifiers and lifecycle state before sending requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = client(server)
            assertFailsWith<IllegalArgumentException> { client.getPullRequest(pair, "token", 0) }
            assertFailsWith<IllegalArgumentException> { client.listPullRequests(pair, "token", page = 0) }
            assertFailsWith<IllegalArgumentException> { client.updatePullRequest(pair, "token", 0, GitHubUpdatePullRequestInput("T", null, "main", "open")) }
            assertFailsWith<IllegalArgumentException> { client.updatePullRequest(pair, "token", 7, GitHubUpdatePullRequestInput("T", null, "main", "merged")) }
            assertFailsWith<IllegalArgumentException> { client.setPullRequestDraft(pair, "token", "", true) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `every PR operation verifies immutable repository identity before accessing or mutating a counterpart`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = client(server)
            val operations: List<suspend () -> Unit> = listOf(
                { client.getPullRequest(pair, "token", 7) },
                { client.listPullRequests(pair, "token") },
                { client.createPullRequest(pair, "token", GitHubCreatePullRequestInput("T", null, "feature", "main", false)) },
                { client.updatePullRequest(pair, "token", 7, GitHubUpdatePullRequestInput("T", null, "main", "closed")) },
                { client.setPullRequestDraft(pair, "token", "PR_456", true) },
            )
            for (operation in operations) {
                server.enqueue(MockResponse.Builder().body("""{"id":999}""").build())
                assertFailsWith<IllegalStateException> { operation() }
            }
            assertEquals(operations.size, server.requestCount)
            repeat(operations.size) { assertEquals("/repos/owner/repo", server.takeRequest().url.encodedPath) }
        }
    }

    @Test fun `PR endpoint failures remain loud without including provider response content`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = client(server)
            val operations: List<suspend () -> Unit> = listOf(
                { client.getPullRequest(pair, "token", 7) },
                { client.listPullRequests(pair, "token") },
                { client.createPullRequest(pair, "token", GitHubCreatePullRequestInput("T", null, "feature", "main", false)) },
                { client.updatePullRequest(pair, "token", 7, GitHubUpdatePullRequestInput("T", null, "main", "closed")) },
                { client.setPullRequestDraft(pair, "token", "PR_456", true) },
            )
            for (operation in operations) {
                verifyPair(server); server.enqueue(MockResponse.Builder().code(422).body("secret-token").build())
                val failure = assertFailsWith<GitHubRequestRejectedException> { operation() }
                assertTrue(failure.message.orEmpty().contains("422")); assertFalse(failure.message.orEmpty().contains("secret-token"))
            }
        }
    }
}
