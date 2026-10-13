package bosca.git.ui

import bosca.graphql.GraphQLWebSocketProtocolHandler
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass

/** Exercises the compiled BML library, packaged assets and authentication proxy over real HTTP. */
class GitUiRoutesTest {
    @BeforeTest
    fun resetWrites() {
        storedFiles.clear()
        userMappings.clear()
        treeRetries.clear()
        commitSha = "abc"
    }

    @Test
    fun `private routes require a session and preserve the destination`() {
        val response = get("/repositories?state=archived", authenticated = false)
        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("Location").orElse("")
        assertTrue(location.startsWith("/login?redirect="), location)
        assertTrue("repositories" in location && "archived" in location, location)
        assertEquals(200, get("/login", authenticated = false).statusCode())
        assertEquals(302, get("/").statusCode())
        assertEquals("/repositories", get("/").headers().firstValue("Location").orElse(""))
    }

    @Test
    fun `bare index shows only active repositories and forwards custom cookie identity`() {
        val response = get("/repositories")
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("Active project" in response.body(), response.body())
        assertFalse("Archived project" in response.body(), response.body())
        assertFalse("Deleted project" in response.body(), response.body())
        assertTrue("&lt;script&gt;" in response.body(), response.body())
        assertFalse("<script>untrusted" in response.body(), response.body())
        val request = requests.last { it.operation == "GitUiRepositories" }
        assertEquals("false", request.variables["includeArchived"]?.jsonPrimitive?.content)
        assertEquals("Bearer ui-test-token", request.authorization)
        assertEquals("private, no-cache", response.headers().firstValue("Cache-Control").orElse(""))
    }

    @Test
    fun `archived and all are explicit and never show deleted repositories`() {
        val archived = get("/repositories?state=archived").body()
        assertTrue("Archived project" in archived, archived)
        assertFalse("Active project" in archived, archived)
        val all = get("/repositories?state=all").body()
        assertTrue("Active project" in all && "Archived project" in all, all)
        assertFalse("Deleted project" in all, all)
        assertEquals(
            "true",
            requests
                .last { it.operation == "GitUiRepositories" }
                .variables["includeArchived"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse("Archived project" in get("/repositories?state=unknown").body())
    }

    @Test
    fun `repository browsing preserves and encodes refs and file paths`() {
        val response = get("/repositories/repo?ref=feature%2Fone&path=docs")
        assertTrue("2 commits · 2 branches · 1 tag · 1 contributor" in response.body(), response.body())
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(
            "ref=feature%2Fone&amp;path=docs&amp;file=docs%2Fa%20%23%20b.txt" in response.body(),
            response.body(),
        )
        assertEquals(
            "abc",
            requests
                .last { it.operation == "GitUiCommit" }
                .variables["sha"]
                ?.jsonPrimitive
                ?.content,
        )
        val request = requests.last {
            it.operation == "GitUiTree" && it.variables["path"]?.jsonPrimitive?.content == "docs"
        }
        assertEquals("abc", request.variables["ref"]?.jsonPrimitive?.content)
        assertEquals("docs", request.variables["path"]?.jsonPrimitive?.content)
        val file = get("/repositories/repo?ref=feature%2Fone&path=docs&file=docs%2Fa%20%23%20b.txt")
        assertEquals(200, file.statusCode(), file.body())
        assertTrue("&lt;script&gt;source&lt;/script&gt;" in file.body(), file.body())
        assertFalse("<script>source</script>" in file.body(), file.body())
        assertEquals(
            "docs/a # b.txt",
            requests.last { it.operation == "GitUiBlob" }.variables["path"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `missing repository and unavailable files have distinct states`() {
        assertEquals(404, get("/repositories/missing").statusCode())
        assertTrue("File not found at this ref." in get("/repositories/repo?file=missing").body())
        assertTrue(
            "cannot be displayed as inline text" in get("/repositories/repo?file=binary").body()
        )
        assertEquals(500, get("/repositories/error").statusCode())
    }

    @Test
    fun `production page bundles are packaged and served with global auth configuration`() {
        val html = get("/login", authenticated = false).body()
        val paths =
            Regex("src=\"(/_bml/js/[^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(paths.isNotEmpty(), html)
        paths.forEach { path ->
            val response = get(path, authenticated = false)
            assertEquals(200, response.statusCode(), path)
            assertTrue(response.body().isNotBlank(), path)
        }
        val global = get("/_bml/app.js", authenticated = false)
        assertEquals(200, global.statusCode())
        assertTrue("\"authCookiePrefix\":\"_git_test\"" in global.body())
        assertEquals(404, get("/_bml/js/missing.page.js", authenticated = false).statusCode())
    }

    @Test
    fun `design fonts are packaged and publicly served with their binary content type`() {
        val stylesheet = get("/_bml/app.css", authenticated = false)
        assertEquals(200, stylesheet.statusCode())
        val paths =
            Regex("url\\((/_git/fonts/[^)]+)\\)")
                .findAll(stylesheet.body())
                .map { it.groupValues[1] }
                .toList()
        assertEquals(11, paths.size)
        paths.forEach { path ->
            val response =
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            assertEquals(200, response.statusCode(), path)
            assertEquals("font/woff2", response.headers().firstValue("Content-Type").orElse(""))
            assertEquals("wOF2", response.body().take(4).toByteArray().decodeToString())
            assertEquals(
                "public, max-age=31536000, immutable",
                response.headers().firstValue("Cache-Control").orElse(""),
            )
        }
        assertEquals(404, get("/_git/fonts/missing.woff2", authenticated = false).statusCode())
    }

    @Test
    fun `local controls preserve native form attributes and island refs`() {
        val html = get("/login", authenticated = false).body()
        val identifier =
            Regex("<input[^>]*data-bml-ref=\"identifier\"[^>]*>").find(html)?.value.orEmpty()
        assertTrue(
            "name=\"identifier\"" in identifier && "autocomplete=\"username\"" in identifier,
            html,
        )
        assertTrue(Regex("\\brequired(?:[ >])").containsMatchIn(identifier), identifier)
        val password =
            Regex("<input[^>]*data-bml-ref=\"password\"[^>]*>").find(html)?.value.orEmpty()
        assertTrue(
            "type=\"password\"" in password && "autocomplete=\"current-password\"" in password,
            html,
        )
        val submit = Regex("<button[^>]*data-bml-ref=\"submit\"[^>]*>").find(html)?.value.orEmpty()
        assertTrue("type=\"submit\"" in submit, html)
        assertFalse(Regex("\\bdisabled(?:[ >])").containsMatchIn(submit), submit)
        assertTrue("auth-primary" in submit, submit)
        assertTrue(
            Regex("<p[^>]*data-bml-ref=\"error\"[^>]*\\bhidden[ >]").containsMatchIn(html),
            html,
        )
    }

    @Test
    fun `authentication flows and emailed landing routes are public`() {
        val routes =
            mapOf(
                "/login" to "Welcome back.",
                "/auth/signup" to "Create your account.",
                "/auth/forgot" to "Forgot your password?",
                "/auth/reset-password?token=test" to "Choose a new password.",
                "/auth/verify?token=test" to "Check your email.",
                "/auth/link/confirm?proof=test" to "Confirm your account.",
            )
        routes.forEach { (path, heading) ->
            val response = get(path, authenticated = false)
            assertEquals(200, response.statusCode(), path)
            assertTrue(heading in response.body(), path)
            assertTrue("class=\"auth-document\"" in response.body(), path)
            assertTrue("name=\"referrer\" content=\"same-origin\"" in response.body(), path)
        }
        val signup = get("/auth/signup", authenticated = false).body()
        assertTrue(
            "given-name" in signup && "family-name" in signup && "new-password" in signup,
            signup,
        )
        assertTrue("Continue with Google" in signup, signup)
        assertFalse("Passkey" in signup, signup)
    }

    @Test
    fun `same origin graphql proxy forwards the resolved session token`() {
        val response =
            client.send(
                HttpRequest.newBuilder(URI("http://localhost:$port/graphql"))
                    .header("Cookie", "_git_test=ui-test-token")
                    .header("Origin", "http://localhost:$port")
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"operationName":"AuthProbe","query":"query AuthProbe { probe }","variables":{}}"""
                        )
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(
            "Bearer ui-test-token",
            requests.last { it.operation == "AuthProbe" }.authorization,
        )
        assertEquals("http://localhost:$port", requests.last { it.operation == "AuthProbe" }.origin)
    }

    @Test
    fun `ui routes take precedence over the clone url browser fallback`() {
        assertEquals("clone URL fallback", get("/owner/repository.git").body())
        assertEquals(200, get("/repositories/repo").statusCode())
        assertEquals(200, get("/_bml/app.css", authenticated = false).statusCode())
    }

    @Test
    fun `owners remain distinct and default to current profile`() {
        val html = get("/repositories").body()
        assertTrue("Alice · @alice" in html)
        assertEquals(
            "owner",
            requests
                .last { it.operation == "GitUiRepositories" }
                .variables["owner"]
                ?.jsonPrimitive
                ?.content,
        )
        val owners = post("/_bml/contract/GitOwnerSearch/search", "[\"Alice\"]").body()
        assertTrue("Alice · @alice-2" in owners, owners)
        assertTrue("Alice · @alice" in owners, owners)
        get("/repositories?owner=all")
        assertEquals(null, requests.last { it.operation == "GitUiRepositories" }.variables["owner"])
    }

    @Test
    fun `markdown uses commit pinned raw images and local viewer links`() {
        val html = get("/repositories/repo?file=README.md").body()
        assertTrue("src=\"/owner/repo/raw/abc/images/logo.png\"" in html, html)
        assertTrue("file=docs%2Fguide.md#install" in html, html)
        assertTrue("id=\"hello\"" in html, html)
        assertTrue("Preview" in html && "Source" in html)
        assertTrue("selected ref does not exist" in get("/repositories/repo?ref=invalid").body())
        assertTrue("directory does not exist" in get("/repositories/repo?path=absent").body())
    }

    @Test
    fun `compiled actions commit with current author and preserve protected drafts`() {
        val response =
            action(
                "/repositories/repo?file=docs%2Fa.txt&edit=true",
                "git-file-editor.model",
                "save",
                listOf("protected.txt", "draft\n<script>keep</script>", "My commit"),
            )
        assertEquals(200, response.statusCode(), response.body())
        val state = actionState(response)
        assertEquals("draft\n<script>keep</script>", state["draftContent"]?.jsonPrimitive?.content)
        assertEquals("protected.txt", state["draftPath"]?.jsonPrimitive?.content)
        assertEquals("My commit", state["commitMessage"]?.jsonPrimitive?.content)
        assertEquals(true, state["failed"]?.jsonPrimitive?.boolean)
        assertTrue("Branch is protected" in response.body(), response.body())
        val write =
            requests.last { it.operation == "GitUiCommitFile" }.variables["input"]?.jsonObject
        assertEquals("Alice", write?.get("authorName")?.jsonPrimitive?.content)
        assertEquals("alice@example.test", write?.get("authorEmail")?.jsonPrimitive?.content)
        val success =
            action(
                "/repositories/repo?new=true",
                "git-file-editor.model",
                "save",
                listOf("new.txt", "new content", "Create file"),
            )
        assertTrue(
            actionState(success)["nextHref"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
                .contains("file=new.txt"),
            success.body(),
        )
    }

    @Test
    fun `bootstrap profile without email can create a README folder and delete files`() {
        val token = "bootstrap-token"
        val created =
            action(
                "/repositories/empty?new=true",
                "git-file-editor.model",
                "save",
                listOf("README.md", "# My repository\n", "Add README"),
                token = token,
            )
        val state = actionState(created)
        assertEquals(false, state["failed"]?.jsonPrimitive?.boolean, created.body())
        assertTrue(state["nextHref"]?.jsonPrimitive?.content.orEmpty().contains("file=README.md"))
        val preview = state.getValue("page").jsonObject
        assertEquals("admin", preview["authorName"]?.jsonPrimitive?.content)
        assertEquals("studio@bosca.io", preview["authorEmail"]?.jsonPrimitive?.content)
        val write =
            requests
                .last { it.operation == "GitUiCommitFile" }
                .variables
                .getValue("input")
                .jsonObject
        assertEquals("admin", write["authorName"]?.jsonPrimitive?.content)
        assertEquals("studio@bosca.io", write["authorEmail"]?.jsonPrimitive?.content)
        assertEquals("# My repository\n", storedFiles["empty|README.md"])

        val folder =
            action(
                "/repositories/repo",
                "git-file-actions.model",
                "folder",
                listOf("docs/new"),
                token = token,
            )
        assertEquals(false, actionState(folder)["failed"]?.jsonPrimitive?.boolean, folder.body())
        val folderWrite =
            requests
                .last { it.operation == "GitUiCommitFile" }
                .variables
                .getValue("input")
                .jsonObject
        assertEquals("docs/new/.gitkeep", folderWrite["path"]?.jsonPrimitive?.content)
        assertEquals("studio@bosca.io", folderWrite["authorEmail"]?.jsonPrimitive?.content)

        val deleted =
            action(
                "/repositories/repo?file=README.md",
                "git-file-actions.model",
                "delete",
                listOf(true),
                token = token,
            )
        assertEquals(false, actionState(deleted)["failed"]?.jsonPrimitive?.boolean, deleted.body())
        val deleteWrite = requests.last { it.operation == "GitUiDeleteFile" }.variables
        assertEquals("admin", deleteWrite["name"]?.jsonPrimitive?.content)
        assertEquals("studio@bosca.io", deleteWrite["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unborn branch can be initialized and detached refs cannot be written`() {
        val created =
            action(
                "/repositories/empty?new=true",
                "git-file-editor.model",
                "save",
                listOf("new.txt", "initial", "Initial commit"),
            )
        assertEquals(false, actionState(created)["failed"]?.jsonPrimitive?.boolean, created.body())
        val before = requests.count { it.operation == "GitUiCommitFile" }
        val detached =
            action(
                "/repositories/repo?ref=v1",
                "git-file-actions.model",
                "delete",
                listOf(true),
                sourcePath = "/repositories/repo?file=docs%2Fa.txt",
            )
        assertEquals(true, actionState(detached)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(before, requests.count { it.operation == "GitUiCommitFile" })
    }

    @Test
    fun `create and import fail visibly with all posted fields retained`() {
        val failed =
            action(
                "/repositories",
                "git-repository-create.model",
                "create",
                listOf(
                    "Rejected",
                    "rejected",
                    "Keep description",
                    "owner",
                    "PRIVATE",
                    "GENERAL",
                    "feature/first",
                    true,
                    "Kotlin",
                    "MIT",
                ),
            )
        val state = actionState(failed)
        assertEquals(true, state["failed"]?.jsonPrimitive?.boolean, failed.body())
        assertEquals("Rejected", state["draft"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(
            "feature/first",
            state["draft"]?.jsonObject?.get("branch")?.jsonPrimitive?.content,
        )
        assertTrue("Keep description" in failed.body(), failed.body())
        val imported =
            action(
                "/repositories",
                "git-repository-import.model",
                "importRepository",
                listOf("Imported", "imported", "https://example.test/repo.git", "owner"),
            )
        assertTrue(
            actionState(imported)["nextHref"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
                .contains("repositories/imported"),
            imported.body(),
        )
    }

    @Test
    fun `lifecycle requires confirmation and live actions require auth`() {
        val before = requests.count { it.operation == "GitUiArchive" }
        val rejected =
            action(
                "/repositories",
                "git-repository-row.model",
                "lifecycle",
                listOf("archive", false),
            )
        assertEquals(true, actionState(rejected)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(before, requests.count { it.operation == "GitUiArchive" })
        val archived =
            action(
                "/repositories",
                "git-repository-row.model",
                "lifecycle",
                listOf("archive", true),
            )
        assertEquals(false, actionState(archived)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(
            "owner",
            requests
                .last { it.operation == "GitUiRepositories" }
                .variables["owner"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            401,
            post(
                    "/_bml/action",
                    "{\"page\":\"/repositories\",\"stateKey\":\"git-repository-create.model:owner\",\"method\":\"create\",\"state\":\"{}\"}",
                    false,
                )
                .statusCode(),
        )
    }

    @Test
    fun `exploration escapes snippets and links to selected refs and lines`() {
        val search = get("/search?repo=repo&q=image&ref=feature%2Fone").body()
        assertTrue("&lt;script&gt;snippet&lt;/script&gt;" in search, search)
        assertTrue("line=7" in search && "ref=feature%2Fone" in search, search)
        assertTrue("path=docs" in get("/search?repo=repo&q=docs&mode=paths").body())
        val compare = get("/compare?repo=repo&base=main&head=feature%2Fone")
        assertEquals(200, compare.statusCode(), compare.body())
        assertTrue("&lt;script&gt;diff&lt;/script&gt;" in compare.body(), compare.body())
        assertTrue("Create pull request" in compare.body(), compare.body())
        val statuses = get("/statuses?repo=repo&sha=abc").body()
        assertTrue("Checks passed" in statuses && "tests" in statuses, statuses)
        assertFalse("javascript:alert" in statuses, statuses)
    }

    @Test
    fun `search uses accessible repositories independently of remembered owners`() {
        val response = get("/search?repo=repo&owner=missing-owner&q=snippet")
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("<mark" in response.body(), response.body())
        assertTrue("&lt;script&gt;" in response.body(), response.body())
        assertFalse("git-owner-picker" in response.body(), response.body())
        assertEquals(
            JsonNull,
            requests.last { it.operation == "GitUiRepositories" }.variables["owner"] ?: JsonNull,
        )
        assertFalse("(archived)" in response.body(), response.body())
        val empty = get("/search?repo=empty&q=snippet").body()
        assertTrue("no commits yet" in empty, empty)
    }

    @Test
    fun `search actions retain criteria show failures and reset a changed repository ref`() {
        val path = "/search?repo=repo&q=snippet&ref=feature%2Fone"
        val failure =
            action(
                path,
                "git-search.model:search",
                "search",
                listOf("repo", "fail", "content", "feature/one"),
            )
        assertEquals(200, failure.statusCode(), failure.body())
        val failed = actionState(failure)
        assertTrue(failed["failed"]?.jsonPrimitive?.boolean == true)
        assertEquals("fail", failed["query"]?.jsonPrimitive?.content)
        assertEquals("feature/one", failed["ref"]?.jsonPrimitive?.content)
        assertTrue(failed["hits"]?.jsonArray.orEmpty().isEmpty())
        assertTrue("Search unavailable" in failure.body(), failure.body())
        val changed =
            action(
                path,
                "git-search.model:search",
                "search",
                listOf("empty", "retained text", "content", "feature/one"),
            )
        assertEquals("empty", actionState(changed)["id"]?.jsonPrimitive?.content)
        assertEquals("main", actionState(changed)["ref"]?.jsonPrimitive?.content)
        assertEquals("retained text", actionState(changed)["query"]?.jsonPrimitive?.content)
        assertTrue("no commits yet" in changed.body(), changed.body())
    }

    @Test
    fun `path search resolves tags preserves entry metadata and shows unavailable branch search`() {
        val paths = get("/search?repo=repo&q=paths&mode=paths&ref=refs%2Ftags%2Fv1")
        assertEquals(200, paths.statusCode(), paths.body())
        assertTrue("README.md" in paths.body() && "1.5 KiB" in paths.body(), paths.body())
        assertTrue("Symbolic link" in paths.body(), paths.body())
        assertTrue("file=README.md" in paths.body() && "ref=abc" in paths.body(), paths.body())
        assertEquals(
            "abc",
            requests
                .last { it.operation == "GitUiSearchPaths" }
                .variables["ref"]
                ?.jsonPrimitive
                ?.content,
        )
        val before = requests.count { it.operation == "GitUiSearchContent" }
        val content = get("/search?repo=repo&q=paths&ref=v1").body()
        assertTrue("Content search is available on branches" in content, content)
        assertEquals(before, requests.count { it.operation == "GitUiSearchContent" })
        assertTrue("No matches" in get("/search?repo=repo&q=nomatches").body())
    }

    @Test
    fun `markdown line navigation opens its numbered source view`() {
        val html = get("/repositories/repo?file=search-result.md&line=2").body()
        assertTrue("matching line" in html, html)
        assertTrue("data-tab=\"Source\" aria-selected=\"true\"" in html, html)
        assertFalse(Regex("<div[^>]+ref=\"sourceSurface\"[^>]+hidden").containsMatchIn(html), html)
    }

    @Test
    fun `pull lists default open and preserve explicit all and paging`() {
        val response = get("/pulls")
        assertEquals(200, response.statusCode())
        assertTrue("Active project" in response.body(), response.body())
        assertFalse("Deleted project" in response.body(), response.body())
        assertEquals(
            "OPEN",
            requests
                .last { it.operation == "GitUiPullList" }
                .variables["status"]
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue("Fix image" in get("/pulls?status=ALL&offset=50").body())
        assertEquals(null, requests.last { it.operation == "GitUiPullList" }.variables["status"])
        assertEquals(
            50,
            requests
                .last { it.operation == "GitUiPullList" }
                .variables["offset"]
                ?.jsonPrimitive
                ?.int,
        )
        assertTrue("feature/one" in get("/pulls?repo=repo&new=true&head=feature%2Fone").body())
    }

    @Test
    fun `repository tabs keep the repository context and load the selected panel`() {
        for ((tab, content) in
            listOf(
                "pulls" to "Fix image",
                "pipelines" to "Build",
                "statuses" to "Recent commits",
                "secrets" to "DEPLOY_TOKEN",
                "settings" to "Save repository",
            )) {
            val path = "/repositories/repo?tab=$tab&ref=feature%2Fone&owner=someone-else"
            val response = get(path)
            assertEquals(200, response.statusCode(), response.body())
            assertTrue(content in response.body(), response.body())
            assertTrue("tab=$tab" in response.body(), response.body())
            assertFalse("href=\"/pipelines?repo=" in response.body(), response.body())
            assertFalse("href=\"/pulls?repo=" in response.body(), response.body())
        }
        val settings =
            get("/repositories/repo?tab=settings&panel=protection&ref=feature%2Fone").body()
        assertTrue("Branch protection" in settings, settings)
        assertTrue("tab=settings&amp;panel=permissions" in settings, settings)
        val statuses = get("/repositories/repo?tab=statuses&sha=abc").body()
        assertTrue("Checks passed" in statuses, statuses)
        assertTrue("tab=statuses&amp;sha=" in statuses, statuses)
    }

    @Test
    fun `archived repository pull tab keeps its scope and disables creation`() {
        val html = get("/repositories/archived?tab=pulls").body()
        assertTrue("Fix image" in html, html)
        assertEquals(
            "archived",
            requests
                .last { it.operation == "GitUiRepositoryPullList" }
                .variables["repo"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse("Choose a target repository above" in html, html)
        val denied =
            action(
                "/repositories/archived?tab=pulls",
                "git-pull-create.model",
                "create",
                listOf("Title", "Body", "feature/one", "main", false),
            )
        assertEquals(true, actionState(denied)["failed"]?.jsonPrimitive?.boolean, denied.body())
        assertTrue("Repository edit access" in denied.body(), denied.body())
    }

    @Test
    fun `global pull creation selects repositories inside the dialog and retains the draft`() {
        val path = "/pulls?new=true"
        val html = get(path).body()
        assertTrue("Target repository" in html && "Source branch" in html, html)
        assertFalse("Choose a target repository above" in html, html)
        val selected =
            action(
                path,
                "git-pull-create.model",
                "selectRepositories",
                listOf("repo", "", "Keep title", "Keep description", "feature/one", "main", true),
            )
        assertEquals(200, selected.statusCode(), selected.body())
        val state = actionState(selected)
        assertEquals(false, state["failed"]?.jsonPrimitive?.boolean, selected.body())
        assertEquals("Keep title", state["title"]?.jsonPrimitive?.content)
        assertEquals("Keep description", state["description"]?.jsonPrimitive?.content)
        assertEquals("repo", state["page"]?.jsonObject?.get("repository")?.jsonPrimitive?.content)
        assertEquals("feature/one", state["source"]?.jsonPrimitive?.content)
        assertEquals(true, state["draft"]?.jsonPrimitive?.boolean)
        assertTrue("feature/one" in selected.body(), selected.body())
        val sourceChanged =
            continueAction(
                path,
                "git-pull-create.model:",
                "selectRepositories",
                state,
                listOf(
                    "repo",
                    "empty",
                    "Keep title",
                    "Keep description",
                    "feature/one",
                    "main",
                    true,
                ),
            )
        assertEquals(200, sourceChanged.statusCode(), sourceChanged.body())
        assertEquals(
            "Keep description",
            actionState(sourceChanged)["description"]?.jsonPrimitive?.content,
        )
        assertEquals("", actionState(sourceChanged)["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `pipeline ref changes load inputs through the island and preserve typed values`() {
        val path = "/repositories/repo?tab=pipelines&pipeline=pipeline&ref=main"
        val changed =
            action(
                path,
                "git-pipeline-trigger.model",
                "selectRef",
                listOf("refs/heads/feature/one", "[{\"name\":\"version\",\"value\":\"v2\"}]"),
            )
        assertEquals(200, changed.statusCode(), changed.body())
        val state = actionState(changed)
        assertEquals(false, state["failed"]?.jsonPrimitive?.boolean, changed.body())
        assertEquals(
            "refs/heads/feature/one",
            state["page"]?.jsonObject?.get("ref")?.jsonPrimitive?.content,
        )
        assertEquals(
            "v2",
            state["values"]?.jsonArray?.first()?.jsonObject?.get("value")?.jsonPrimitive?.content,
        )
        assertEquals(
            "refs/heads/feature/one",
            requests
                .last { it.operation == "GitUiPipelineInputs" }
                .variables["ref"]
                ?.jsonPrimitive
                ?.content,
        )
        val invalid =
            action(
                path,
                "git-pipeline-trigger.model",
                "selectRef",
                listOf("refs/heads/missing", "[]"),
            )
        assertEquals(true, actionState(invalid)["failed"]?.jsonPrimitive?.boolean, invalid.body())
    }

    @Test
    fun `pull detail renders diffs reviews checks and only matched identity`() {
        val page = get("/pulls/pull?repo=repo&number=1")
        assertEquals(200, page.statusCode(), page.body())
        assertTrue("Alice · @alice" in page.body(), page.body())
        assertTrue("Checks passed" in page.body(), page.body())
        assertTrue(
            "Overview" in page.body() && "Files Changed" in page.body() && "Reviews" in page.body()
        )
        assertFalse("diff-lines" in page.body(), "Overview must not render the full inline diff")
        val files = get("/pulls/pull?repo=repo&number=1&view=files")
        assertEquals(200, files.statusCode(), files.body())
        assertTrue("Existing line comment" in files.body(), files.body())
        assertTrue("&lt;script&gt;diff&lt;/script&gt;" in files.body(), files.body())
        val reviews = get("/pulls/pull?repo=repo&number=1&view=reviews")
        assertEquals(200, reviews.statusCode(), reviews.body())
        assertTrue("Existing line comment" in reviews.body(), reviews.body())
        assertFalse("diff-lines" in reviews.body(), "Reviews must not render the full inline diff")
        assertTrue("/owner/repo/raw/abc/images/logo.png" in page.body(), page.body())
        assertEquals(404, get("/pulls/other?repo=repo&number=1").statusCode())
        assertEquals(404, get("/pulls/pull").statusCode())
    }

    @Test
    fun `empty added files render a clear notice instead of an empty diff hunk`() {
        val response = get("/pulls/pull-empty?repo=repo&number=6&view=files")
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("Test/.gitkeep" in response.body(), response.body())
        assertTrue("Empty file added." in response.body(), response.body())
        assertTrue("diff-sidebar" in response.body(), response.body())
        assertFalse("diff-hunk-heading" in response.body(), response.body())
        assertFalse("Comment on line" in response.body(), response.body())
    }

    @Test
    fun `pull lifecycle states show the appropriate actions and review controls`() {
        val draft = get("/pulls/pull-draft?repo=repo&number=2").body()
        assertTrue("Draft pull request" in draft && "Ready for review" in draft, draft)
        assertFalse("Review changes" in draft, draft)
        val draftFiles = get("/pulls/pull-draft?repo=repo&number=2&view=files").body()
        assertFalse("Comment on line" in draftFiles, draftFiles)
        assertTrue("Mark this draft ready" in draftFiles, draftFiles)

        val merged = get("/pulls/pull-merged?repo=repo&number=3").body()
        assertTrue("Pull request merged" in merged && "data-tone=\"purple\"" in merged, merged)
        assertFalse("Close pull request" in merged || "Review changes" in merged, merged)
        val closed = get("/pulls/pull-closed?repo=repo&number=4").body()
        assertTrue("Pull request closed" in closed && "Reopen pull request" in closed, closed)
        assertFalse("Review changes" in closed, closed)
    }

    @Test
    fun `conflicts block merging and metadata actions have descriptive icon labels`() {
        val page = get("/pulls/pull-conflicts?repo=repo&number=5").body()
        assertTrue("Resolve merge conflicts" in page && "docs/guide.md" in page, page)
        assertTrue(Regex("<button[^>]*disabled[^>]*>.*?</button>", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).any { "Merge pull request" in it.value }, page)
        assertTrue("Remove assignee Alice" in page, page)
        assertTrue("Remove dependency #2" in page, page)
        assertTrue("Depends on" in page, page)
    }

    @Test
    fun `line comments open beside the selected line and cancel without losing the review`() {
        val path = "/pulls/pull?repo=repo&number=1&view=files"
        val selected = action(path, "git-pull-review.model", "select", listOf("a.txt", 0, 1))
        assertTrue("comment-form" in selected.body(), selected.body())
        val cancelled = continueAction(path, "git-pull-review.model:pull", "cancelComment",
            actionState(selected), emptyList())
        assertEquals("", actionState(cancelled)["commentFile"]?.jsonPrimitive?.content)
        assertTrue("Existing line comment" in cancelled.body(), cancelled.body())
    }

    @Test
    fun `pull creation and reviews retain failed drafts and merge needs a plan`() {
        val create =
            action(
                "/pulls?repo=repo&new=true",
                "git-pull-create.model",
                "create",
                listOf("Rejected", "keep description", "feature/one", "main", true),
            )
        assertEquals(true, actionState(create)["failed"]?.jsonPrimitive?.boolean, create.body())
        assertEquals("keep description", actionState(create)["description"]?.jsonPrimitive?.content)
        val source = "/pulls/pull?repo=repo&number=1"
        val review =
            action(source, "git-pull-review.model", "review", listOf("APPROVED", "Rejected"))
        assertEquals(
            "Rejected",
            actionState(review)["reviewBody"]?.jsonPrimitive?.content,
            review.body(),
        )
        assertEquals(true, actionState(review)["reviewFailed"]?.jsonPrimitive?.boolean)
        val comment =
            action(
                "$source&view=files",
                "git-pull-review.model",
                "comment",
                listOf("review", ""),
            )
        assertEquals(true, actionState(comment)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(false, actionState(comment)["reviewFailed"]?.jsonPrimitive?.boolean)
        assertFalse("data-force-open" in comment.body(), comment.body())
        val before = requests.count { it.operation == "GitUiMergePull" }
        val merge = action(source, "git-pull-actions.model", "merge", listOf(true))
        assertEquals(true, actionState(merge)["failed"]?.jsonPrimitive?.boolean, merge.body())
        assertEquals("merge", actionState(merge)["selectedAction"]?.jsonPrimitive?.content)
        assertEquals(before, requests.count { it.operation == "GitUiMergePull" })
        val rejectedClose =
            action(source, "git-pull-actions.model", "lifecycle", listOf("close", false))
        assertEquals(true, actionState(rejectedClose)["failed"]?.jsonPrimitive?.boolean)
        assertEquals("close", actionState(rejectedClose)["selectedAction"]?.jsonPrimitive?.content)
        assertTrue("data-force-open" in rejectedClose.body(), rejectedClose.body())
        val plan = action(source, "git-pull-actions.model", "prepare", listOf("SQUASH"))
        assertEquals(1, actionState(plan)["plan"]?.jsonArray?.size, plan.body())
        assertTrue("Merge plan" in plan.body(), plan.body())
        val reset =
            continueAction(
                source,
                "git-pull-actions.model:pull",
                "resetMerge",
                actionState(plan),
                emptyList(),
            )
        assertEquals(0, actionState(reset)["plan"]?.jsonArray?.size, reset.body())
        assertEquals("SQUASH", actionState(reset)["strategy"]?.jsonPrimitive?.content)
        assertTrue("data-force-open" in reset.body(), reset.body())
        val merged =
            continueAction(
                source,
                "git-pull-actions.model:pull",
                "merge",
                actionState(plan),
                listOf(true),
            )
        assertEquals(false, actionState(merged)["failed"]?.jsonPrimitive?.boolean, merged.body())
        assertEquals(
            "SQUASH",
            requests
                .last { it.operation == "GitUiMergePull" }
                .variables["strategy"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `pipeline inputs are typed ref specific and validated before execution`() {
        val path = "/pipelines?owner=owner&repo=repo&pipeline=pipeline&ref=refs%2Fheads%2Fmain"
        val html = get(path).body()
        assertTrue("Run pipeline" in html && "Version" in html && "CHOICE" !in html, html)
        assertTrue(
            "Alice · @alice" in html,
            "Opening a manual run must preserve the selected owner: $html",
        )
        assertEquals(
            "refs/heads/main",
            requests
                .last { it.operation == "GitUiPipelineInputs" }
                .variables["ref"]
                ?.jsonPrimitive
                ?.content,
        )
        val invalid =
            action(
                path,
                "git-pipeline-trigger.model",
                "start",
                listOf("[{\"name\":\"version\",\"value\":\"\"}]"),
            )
        assertEquals(true, actionState(invalid)["failed"]?.jsonPrimitive?.boolean, invalid.body())
        val valid =
            action(
                path,
                "git-pipeline-trigger.model",
                "start",
                listOf("[{\"name\":\"version\",\"value\":\"v1\"}]"),
            )
        assertEquals(
            "/pipelines/new-run",
            actionState(valid)["nextHref"]?.jsonPrimitive?.content,
            valid.body(),
        )
        assertEquals(
            "refs/heads/main",
            requests
                .last { it.operation == "GitUiTrigger" }
                .variables["ref"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `pipeline run actions logs paging and artifact names pass through compiled routes`() {
        val html = get("/pipelines/run").body()
        assertTrue(
            "Run #3" in html &&
                "Build anyway" in html &&
                "Waiting for external requirements" in html,
            html,
        )
        assertTrue("/ci/artifacts/repo/run/build%2Freport%20%231.zip" in html, html)
        assertTrue("&lt;script&gt;log&lt;/script&gt;" in html, html)
        val before = requests.count { it.operation == "GitUiCancelRun" }
        val denied =
            action(
                "/pipelines/run",
                "git-pipeline-run.model",
                "action",
                listOf("cancel", "", "", false),
            )
        assertEquals(true, actionState(denied)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(before, requests.count { it.operation == "GitUiCancelRun" })
        val rejectedBypass =
            action(
                "/pipelines/run",
                "git-pipeline-job-actions.model",
                "action",
                listOf("buildAnyway", "job", "Keep the reviewed reason", false),
            )
        assertEquals(true, actionState(rejectedBypass)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(
            "Keep the reviewed reason",
            actionState(rejectedBypass)["reason"]?.jsonPrimitive?.content,
        )
        val rejectedBypassHtml =
            Json.parseToJsonElement(rejectedBypass.body())
                .jsonObject
                .getValue("html")
                .jsonPrimitive
                .content
        assertTrue("data-force-open" in rejectedBypassHtml, rejectedBypassHtml)
        assertTrue("Keep the reviewed reason" in rejectedBypassHtml, rejectedBypassHtml)
        val bypass =
            action(
                "/pipelines/run",
                "git-pipeline-job-actions.model",
                "action",
                listOf("buildAnyway", "job", "Emergency release", true),
            )
        assertEquals(false, actionState(bypass)["failed"]?.jsonPrimitive?.boolean, bypass.body())
        assertEquals(
            "Emergency release",
            requests
                .last { it.operation == "GitUiBuildAnyway" }
                .variables["reason"]
                ?.jsonPrimitive
                ?.content,
        )
        val logs = action("/pipelines/run", "git-pipeline-logs.model", "earlier", emptyList())
        assertEquals(false, actionState(logs)["hasEarlier"]?.jsonPrimitive?.boolean, logs.body())
        assertEquals(
            4,
            requests.last { it.operation == "GitUiLogs" }.variables["before"]?.jsonPrimitive?.int,
        )
    }

    @Test
    fun `repository settings metadata maintenance and secret values use scoped APIs`() {
        val path = "/repositories/repo/settings"
        assertTrue("Rename slug" in get(path).body())
        val metadata =
            action(
                path,
                "git-repository-metadata.model",
                "save",
                listOf("Renamed", "Keep description", "PRIVATE", "GENERAL", "main"),
            )
        assertEquals(
            false,
            actionState(metadata)["failed"]?.jsonPrimitive?.boolean,
            metadata.body(),
        )
        assertEquals(
            "repo",
            requests.last { it.operation == "GitUiUpdate" }.variables["id"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "Renamed",
            requests
                .last { it.operation == "GitUiUpdate" }
                .variables["input"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content,
        )
        val utility =
            action(
                "$path?panel=utilities",
                "git-repository-utilities.model",
                "run",
                listOf("gc", true),
            )
        assertEquals(512, actionState(utility)["size"]?.jsonPrimitive?.int, utility.body())
        val secrets =
            action(
                "/repositories/repo/secrets",
                "git-repository-secrets.model",
                "save",
                listOf("TOKEN", "never-render-this-value", "production", true),
            )
        assertEquals(false, actionState(secrets)["failed"]?.jsonPrimitive?.boolean, secrets.body())
        assertFalse("never-render-this-value" in secrets.body(), secrets.body())
        assertEquals(
            "production",
            requests
                .last { it.operation == "GitUiSetSecret" }
                .variables["environment"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `webhook events protection drafts and group grants use compiled form contracts`() {
        val root = "/repositories/repo/settings"
        val webhook =
            action(
                "$root?panel=webhooks",
                "git-repository-webhooks.model",
                "create",
                listOf(
                    "https://example.test/hook",
                    "never-show-webhook-secret",
                    "[\"PUSH\",\"TAG_CREATED\"]",
                    true,
                ),
            )
        assertEquals(false, actionState(webhook)["failed"]?.jsonPrimitive?.boolean, webhook.body())
        assertEquals(
            2,
            requests
                .last { it.operation == "GitUiCreateWebhook" }
                .variables["input"]
                ?.jsonObject
                ?.get("events")
                ?.jsonArray
                ?.size,
        )
        assertFalse("never-show-webhook-secret" in webhook.body(), webhook.body())
        val rule =
            action(
                "$root?panel=protection",
                "git-repository-protection.model",
                "save",
                listOf("release/*", true, 2, true, false, true, false, false, "tests, lint"),
            )
        assertEquals(false, actionState(rule)["failed"]?.jsonPrimitive?.boolean, rule.body())
        assertEquals(
            2,
            requests
                .last { it.operation == "GitUiCreateProtection" }
                .variables["input"]
                ?.jsonObject
                ?.get("requiredApprovals")
                ?.jsonPrimitive
                ?.int,
        )
        val perms =
            action(
                "$root?panel=permissions",
                "git-repository-permissions.model",
                "change",
                listOf("group", "EXECUTE", false, true),
            )
        assertEquals(false, actionState(perms)["failed"]?.jsonPrimitive?.boolean, perms.body())
        assertEquals(
            "repo",
            requests
                .last { it.operation == "GitUiGrant" }
                .variables["input"]
                ?.jsonObject
                ?.get("entityId")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `agent registration token appears once and settings survive polling boundaries`() {
        val registration =
            action(
                "/ci-agents",
                "git-agent-register.model",
                "register",
                listOf("New agent", "linux, build", "RUNNER"),
            )
        assertTrue("one-time-agent-token" in registration.body(), registration.body())
        assertFalse(actionState(registration).containsKey("token"), registration.body())
        val detail = get("/ci-agents/agent")
        assertEquals(200, detail.statusCode(), detail.body())
        assertTrue("Current job" in detail.body() && "echo build" in detail.body(), detail.body())
        val settings =
            action(
                "/ci-agents/agent",
                "git-agent-settings.model",
                "save",
                listOf("Updated agent", "linux, build"),
            )
        assertEquals(
            false,
            actionState(settings)["failed"]?.jsonPrimitive?.boolean,
            settings.body(),
        )
        val orchestrator = get("/ci-agents/orchestrator")
        assertEquals(200, orchestrator.statusCode(), orchestrator.body())
        assertTrue("ui-json-editor" in orchestrator.body(), orchestrator.body())
        val invalid =
            action(
                "/ci-agents/orchestrator",
                "git-agent-settings.model",
                "configure",
                listOf("digitalocean", "", "nyc1", "small", "ubuntu", "[]", "[]", 5, 60, 120),
            )
        assertEquals(true, actionState(invalid)["failed"]?.jsonPrimitive?.boolean, invalid.body())
        assertEquals("[]", actionState(invalid)["profiles"]?.jsonPrimitive?.content)
    }

    @Test
    fun `administrator pages do not fetch configuration for a nonadministrator`() {
        val before = requests.count {
            it.operation == "GitUiAgents" ||
                it.operation == "GitUiGitHubPair" ||
                it.operation == "GitUiGitHubUsers"
        }
        for (path in listOf("/ci-agents", "/repositories/repo/github", "/settings/github")) {
            val request =
                HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                    .header("Cookie", "_git_test=reader-token")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("Administrator access is required" in response.body(), response.body())
        }
        assertEquals(
            before,
            requests.count {
                it.operation == "GitUiAgents" ||
                    it.operation == "GitUiGitHubPair" ||
                    it.operation == "GitUiGitHubUsers"
            },
        )
    }

    @Test
    fun `pairing keeps version patterns and missing secret errors retain draft`() {
        val path = "/repositories/repo/github"
        val html = get(path).body()
        assertTrue("9007199254740993" in html, html)
        val fail =
            action(
                path,
                "git-github-pair.model",
                "save",
                listOf(
                    "example",
                    "repository",
                    "missing",
                    "token",
                    true,
                    "main, release/*",
                    "private/**",
                    "",
                    "",
                ),
            )
        assertEquals(true, actionState(fail)["failed"]?.jsonPrimitive?.boolean, fail.body())
        assertEquals("main, release/*", actionState(fail)["pushIncludes"]?.jsonPrimitive?.content)
        val save =
            action(
                path,
                "git-github-pair.model",
                "save",
                listOf(
                    "example",
                    "repository",
                    "webhook",
                    "token",
                    true,
                    "main, release/*",
                    "private/**",
                    "",
                    "",
                ),
            )
        assertEquals(false, actionState(save)["failed"]?.jsonPrimitive?.boolean, save.body())
        val input = requests.last { it.operation == "GitUiSavePair" }.variables["input"]?.jsonObject
        assertEquals(3, input?.get("version")?.jsonPrimitive?.int)
        assertEquals(2, input?.get("pushBranchIncludes")?.jsonArray?.size)
    }

    @Test
    fun `conflict resolution requires reviewed host values and refreshes history`() {
        val path = "/repositories/repo/github"
        val reviewed = action(path, "git-github-history.model", "review", listOf("refs/heads/main"))
        assertEquals(
            "def",
            actionState(reviewed)["reviewed"]?.jsonObject?.get("githubSha")?.jsonPrimitive?.content,
        )
        val before = requests.count { it.operation == "GitUiResolveRef" }
        val denied =
            continueAction(
                path,
                "git-github-history.model:reporefs",
                "resolve",
                actionState(reviewed),
                listOf("BOSCA", false),
            )
        assertEquals(true, actionState(denied)["failed"]?.jsonPrimitive?.boolean, denied.body())
        assertEquals(before, requests.count { it.operation == "GitUiResolveRef" })
        val resolved =
            continueAction(
                path,
                "git-github-history.model:reporefs",
                "resolve",
                actionState(reviewed),
                listOf("GITHUB", true),
            )
        assertEquals(
            false,
            actionState(resolved)["failed"]?.jsonPrimitive?.boolean,
            resolved.body(),
        )
        val input =
            requests.last { it.operation == "GitUiResolveRef" }.variables["input"]?.jsonObject
        assertEquals("abc", input?.get("expectedBoscaSha")?.jsonPrimitive?.content)
        assertEquals("def", input?.get("expectedGitHubSha")?.jsonPrimitive?.content)
        assertTrue(
            requests.last { it.operation.startsWith("GitUiGitHub") }.operation == "GitUiGitHubRefs"
        )
    }

    @Test
    fun `merge stops when the reviewed source branch advances`() {
        val path = "/pulls/pull?repo=repo&number=1"
        val prepared = action(path, "git-pull-actions.model", "prepare", listOf("MERGE_COMMIT"))
        assertEquals(
            false,
            actionState(prepared)["failed"]?.jsonPrimitive?.boolean,
            prepared.body(),
        )
        val before = requests.count { it.operation == "GitUiMergePull" }
        commitSha = "def"
        val rejected =
            continueAction(
                path,
                "git-pull-actions.model:pull",
                "merge",
                actionState(prepared),
                listOf(true),
            )
        assertEquals(true, actionState(rejected)["failed"]?.jsonPrimitive?.boolean, rejected.body())
        assertTrue("source branch changed" in rejected.body(), rejected.body())
        assertEquals(before, requests.count { it.operation == "GitUiMergePull" })
    }

    @Test
    fun `read edit and execute grants expose only their permitted controls`() {
        fun read(path: String, token: String): String =
            client
                .send(
                    HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                        .header("Cookie", "_git_test=$token")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                .body()
        for (token in listOf("reader-token", "executor-token", "editor-token")) {
            assertFalse("Repository actions" in read("/repositories", token), token)
            assertFalse("Save repository" in read("/repositories/repo/settings", token), token)
            assertFalse("setPipelineSecret" in read("/repositories/repo/secrets", token), token)
        }
        val reader = read("/pipelines/run", "reader-token")
        assertFalse("Cancel run" in reader || "Build anyway" in reader, reader)
        val executor = read("/pipelines/run", "executor-token")
        assertTrue("Build anyway" in executor, executor)
        assertFalse("Cancel run" in executor || "Cancel job" in executor, executor)
        val editor = read("/pipelines/run", "editor-token")
        assertTrue("Cancel run" in editor && "Cancel job" in editor, editor)
        assertFalse("Build anyway" in editor, editor)
        assertTrue(
            "Repository edit access is required" in
                read("/pulls?repo=repo&new=true", "reader-token")
        )
        assertFalse(
            "data-bml-component=\"git-file-editor\"" in
                read("/repositories/repo?new=true", "reader-token")
        )
        assertTrue(
            "data-bml-component=\"git-file-editor\"" in
                read("/repositories/repo?new=true", "editor-token")
        )
    }

    @Test
    fun `lazy folder expansion uses pinned ref and collapse removes descendants`() {
        val path = "/repositories/repo?file=README.md"
        val expanded = action(path, "git-sidebar-tree.model", "expand", listOf("docs"))
        val entries = actionState(expanded)["entries"]?.jsonArray.orEmpty()
        assertEquals(
            1,
            entries
                .first { it.jsonObject["path"]?.jsonPrimitive?.content == "docs/guide.md" }
                .jsonObject["depth"]
                ?.jsonPrimitive
                ?.int,
        )
        assertEquals(
            "abc",
            requests
                .last {
                    it.operation == "GitUiTree" &&
                        it.variables["path"]?.jsonPrimitive?.content == "docs"
                }
                .variables["ref"]
                ?.jsonPrimitive
                ?.content,
        )
        val collapsed =
            continueAction(
                path,
                "git-sidebar-tree.model:repoabc",
                "expand",
                actionState(expanded),
                listOf("docs"),
            )
        assertFalse(
            actionState(collapsed)["entries"]?.jsonArray.orEmpty().any {
                it.jsonObject["path"]?.jsonPrimitive?.content?.startsWith("docs/") == true
            }
        )
    }

    @Test
    fun `deferred tree binds folder actions only to directory rows`() {
        val html = deferredSidebar("/repositories/repo?file=README.md").body()
        val file = Regex("<div[^>]*data-path=\"README.md\"[^>]*>").find(html)?.value
        val folder = Regex("<div[^>]*data-path=\"docs\"[^>]*>").find(html)?.value
        assertNotNull(file)
        assertNotNull(folder)
        assertFalse("data-bml-method" in file.orEmpty(), html)
        assertTrue("data-bml-method=\"expand\"" in folder.orEmpty(), html)
        assertTrue("href=\"/repositories/repo?ref=main&amp;file=README.md\"" in html, html)
    }

    @Test
    fun `clone panel composes a compact selectable field and inline copy control`() {
        val html = get("/repositories/repo").body()
        assertTrue("copy-field-row" in html, html)
        assertTrue(
            Regex("<input[^>]*aria-label=\"Clone URL\"[^>]*readonly").containsMatchIn(html),
            html,
        )
        assertTrue("value=\"https://git.example.test/owner/repo.git\"" in html, html)
        assertTrue("popover=\"manual\"" in html, html)
        assertFalse("clone-url" in html, html)
    }

    @Test
    fun `file sidebar loads privately after the shell without fetching tree data eagerly`() {
        val before = requests.count { it.operation == "GitUiTree" }
        val shell = get("/repositories/repo?file=README.md")
        assertEquals(200, shell.statusCode())
        assertTrue("data-bml-deferred=" in shell.body(), shell.body())
        assertTrue("Loading files" in shell.body(), shell.body())
        assertFalse("data-bml-state-key=\"git-sidebar-tree.model" in shell.body(), shell.body())
        assertEquals(before, requests.count { it.operation == "GitUiTree" })
        val tree = deferredSidebar("/repositories/repo?file=README.md")
        assertEquals(200, tree.statusCode(), tree.body())
        assertEquals("private, no-store", tree.headers().firstValue("Cache-Control").orElse(""))
        assertTrue("Repository files" in tree.body() && "README.md" in tree.body(), tree.body())
        assertEquals(before + 1, requests.count { it.operation == "GitUiTree" })
        assertEquals(
            "Bearer ui-test-token",
            requests.last { it.operation == "GitUiTree" }.authorization,
        )
        assertEquals(401, deferredSidebar("/repositories/repo?file=README.md", false).statusCode())
    }

    @Test
    fun `markdown viewer uses one action bar with labelled icon links and view tabs`() {
        val response = get("/repositories/repo?file=README.md")
        assertEquals(200, response.statusCode(), response.body())
        val html = response.body()
        assertTrue("aria-label=\"Edit or rename file\"" in html, html)
        assertTrue("aria-label=\"Download file\"" in html, html)
        assertTrue("aria-label=\"Open raw file\"" in html, html)
        assertTrue("role=\"tablist\"" in html && "aria-label=\"Markdown view\"" in html, html)
        assertEquals(1, Regex("aria-label=\"File actions\"").findAll(html).count())
        assertFalse("Browse ref" in html, html)
    }

    @Test
    fun `nested file tree expands its ancestors and marks the selected file`() {
        val response = deferredSidebar("/repositories/repo?file=docs%2Fguide.md")
        assertEquals(200, response.statusCode(), response.body())
        val html = response.body()
        assertTrue("data-path=\"docs/guide.md\"" in html, html)
        assertTrue(
            Regex("<div[^>]+aria-selected=\"true\"[^>]+data-path=\"docs/guide.md\"")
                .containsMatchIn(html),
            html,
        )
        assertEquals(1, Regex("aria-selected=\"true\"").findAll(html).count())
        assertFalse("<summary" in html, html)
        assertEquals(
            "abc",
            requests.last { it.operation == "GitUiTree" }.variables["ref"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `protection editing opens a dialog with all persisted policy settings`() {
        val response =
            action(
                "/repositories/repo/settings?panel=protection",
                "git-repository-protection.model",
                "edit",
                listOf("rule"),
            )
        val state = actionState(response)
        assertTrue(state["editing"]?.jsonPrimitive?.boolean == true)
        assertEquals("main", state["pattern"]?.jsonPrimitive?.content)
        assertEquals(2, state["approvals"]?.jsonPrimitive?.int)
        assertTrue("data-force-open" in response.body(), response.body())
    }

    @Test
    fun `user mapping changes preserve identity labels and require removal confirmation`() {
        val saved =
            action(
                "/settings/github",
                "git-github-users.model",
                "save",
                listOf("alice", "principal"),
            )
        assertEquals(false, actionState(saved)["failed"]?.jsonPrimitive?.boolean)
        assertTrue("@alice" in saved.body() && "alice@example.test" in saved.body(), saved.body())
        val rejected =
            action("/settings/github", "git-github-users.model", "remove", listOf(101L, false))
        assertEquals(true, actionState(rejected)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(1, userMappings.size)
        val removed =
            action("/settings/github", "git-github-users.model", "remove", listOf(101L, true))
        assertEquals(false, actionState(removed)["failed"]?.jsonPrimitive?.boolean)
        assertTrue(userMappings.isEmpty())
    }

    private fun deferredSidebar(path: String, authenticated: Boolean = true): HttpResponse<String> {
        val file =
            java.net.URLDecoder.decode(
                path.substringAfter("file=", "README.md").substringBefore('&'),
                java.nio.charset.StandardCharsets.UTF_8,
            )
        return post(
            "/_bml/deferred/component%3Agit-repository-sidebar%3Arepository-tree-loader",
            buildJsonObject {
                put("page", "/repositories/{id}")
                put("path", path.substringBefore('?'))
                put("query", buildJsonObject { put("file", file) })
                put(
                    "props",
                    buildJsonObject {
                        put("repoId", "repo")
                        put("refName", "main")
                        put("commit", "abc")
                        put("filePath", file)
                        put("directory", file.substringBeforeLast('/', ""))
                        put("returnHref", "/repositories")
                    },
                )
            }
                .toString(),
            authenticated,
        )
    }

    private fun route(path: String): String =
        when {
            path.startsWith("/settings/github") -> "/settings/github"
            path.endsWith("/settings") || path.contains("/settings?") ->
                "/repositories/{id}/settings"
            path.endsWith("/secrets") -> "/repositories/{id}/secrets"
            path.endsWith("/github") -> "/repositories/{id}/github"
            path.startsWith("/ci-agents/") -> "/ci-agents/{id}"
            path.startsWith("/repositories/") -> "/repositories/{id}"
            path.startsWith("/pipelines/") -> "/pipelines/{id}"
            path.startsWith("/pulls/") -> "/pulls/{id}"
            else -> path.substringBefore('?')
        }

    private fun continueAction(
        path: String,
        key: String,
        method: String,
        state: JsonObject,
        args: List<Any>,
    ): HttpResponse<String> =
        post(
            "/_bml/action",
            buildJsonObject {
                put("page", route(path))
                put("path", path.substringBefore('?'))
                put("stateKey", key)
                put("method", method)
                put("state", state.toString())
                put(
                    "args",
                    JsonArray(
                        args.map {
                            when (it) {
                                is Boolean -> JsonPrimitive(it)
                                is Int -> JsonPrimitive(it)
                                is Long -> JsonPrimitive(it)
                                else -> JsonPrimitive(it.toString())
                            }
                        }
                    ),
                )
            }
                .toString(),
        )

    private fun action(
        path: String,
        prefix: String,
        method: String,
        args: List<Any>,
        sourcePath: String = path,
        token: String = "ui-test-token",
    ): HttpResponse<String> {
        val html =
            if (prefix == "git-sidebar-tree.model") deferredSidebar(sourcePath).body()
            else get(sourcePath, token = token).body()
        val marker =
            Regex(
                    "<script type=\"application/json\" data-bml-state-key=\"($prefix[^\"]*)\"[^>]*>(.*?)</script>",
                    RegexOption.DOT_MATCHES_ALL,
                )
                .find(html) ?: error("Missing state $prefix: $html")
        val route = route(path)
        val payload = buildJsonObject {
            put("page", route)
            put("path", path.substringBefore('?'))
            put("stateKey", marker.groupValues[1])
            put("method", method)
            put("state", marker.groupValues[2])
            put(
                "args",
                JsonArray(
                    args.map {
                        when (it) {
                            is Boolean -> JsonPrimitive(it)
                            is Int -> JsonPrimitive(it)
                            is Long -> JsonPrimitive(it)
                            else -> JsonPrimitive(it.toString())
                        }
                    }
                ),
            )
        }
        // Client-posted identity/ref is rechecked by the action's API boundary.
        val state = Json.parseToJsonElement(marker.groupValues[2]).jsonObject.toMutableMap()
        if (path.contains("ref=v1")) state["ref"] = JsonPrimitive("v1")
        return post(
            "/_bml/action",
            JsonObject(
                    payload.toMutableMap().apply {
                        put("state", JsonPrimitive(JsonObject(state).toString()))
                    }
                )
                .toString(),
            token = token,
        )
    }

    private fun actionState(response: HttpResponse<String>): JsonObject {
        assertEquals(200, response.statusCode(), response.body())
        return Json.parseToJsonElement(
                Json.parseToJsonElement(response.body())
                    .jsonObject
                    .getValue("state")
                    .jsonPrimitive
                    .content
            )
            .jsonObject
    }

    private fun post(
        path: String,
        body: String,
        authenticated: Boolean = true,
        token: String = "ui-test-token",
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:$port")
        if (authenticated) request.header("Cookie", "_git_test=$token")
        return client.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun get(
        path: String,
        authenticated: Boolean = true,
        token: String = "ui-test-token",
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (authenticated) request.header("Cookie", "_git_test=$token")
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private data class ApiRequest(
            val operation: String,
            val variables: JsonObject,
            val authorization: String?,
            val origin: String?,
        )

        private val client = HttpClient.newHttpClient()
        private val requests = CopyOnWriteArrayList<ApiRequest>()
        private val storedFiles = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val treeRetries =
            java.util.concurrent.ConcurrentHashMap<
                String,
                java.util.concurrent.atomic.AtomicInteger,
            >()
        private val userMappings = CopyOnWriteArrayList<JsonObject>()
        @Volatile private var commitSha = "abc"
        private var port = 0
        private lateinit var upstream: HttpServer
        private lateinit var engine: NettyServerEngine
        private lateinit var subscriptionEngine: NettyServerEngine

        @JvmStatic fun browserFixturePort(): Int = port

        @JvmStatic
        @BeforeClass
        fun start() {
            upstream = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            upstream.createContext("/graphql") { exchange ->
                val input =
                    Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString())
                        .jsonObject
                val operation =
                    input["operationName"]?.jsonPrimitive?.content
                        ?: Regex("(?:query|mutation)\\s+(\\w+)")
                            .find(input["query"]?.jsonPrimitive?.content.orEmpty())
                            ?.groupValues
                            ?.get(1)
                            .orEmpty()
                val variables = input["variables"]?.jsonObject ?: JsonObject(emptyMap())
                requests.add(
                    ApiRequest(
                        operation,
                        variables,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("Origin"),
                    )
                )
                val id = variables["id"]?.jsonPrimitive?.content
                val path = variables["path"]?.jsonPrimitive?.content
                val token =
                    exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                fun capabilities(json: String): String =
                    when (token) {
                        "reader-token" ->
                            json
                                .replace("\"canEdit\":true", "\"canEdit\":false")
                                .replace("\"canManage\":true", "\"canManage\":false")
                                .replace("\"canExecute\":true", "\"canExecute\":false")
                        "executor-token" ->
                            json
                                .replace("\"canEdit\":true", "\"canEdit\":false")
                                .replace("\"canManage\":true", "\"canManage\":false")
                        "editor-token" ->
                            json
                                .replace("\"canManage\":true", "\"canManage\":false")
                                .replace("\"canExecute\":true", "\"canExecute\":false")
                        else -> json
                    }
                if (
                    operation == "GitUiSearchContent" &&
                        variables["query"]?.jsonPrimitive?.content == "slow"
                )
                    Thread.sleep(500)
                val result =
                    when {
                        id == "error" -> """{"errors":[{"message":"Upstream failure"}]}"""
                        operation == "GitUiRepositories" ->
                            """{"data":{"git":{"repositories":[${capabilities(active)},${capabilities(active.replace("\"id\":\"repo\"", "\"id\":\"empty\"").replace("Active project", "Empty project"))},${capabilities(archived)},${capabilities(deleted)}]}}}"""
                        operation == "GitUiSearchContent" &&
                            variables["query"]?.jsonPrimitive?.content == "fail" ->
                            """{"errors":[{"message":"Search unavailable"}]}"""
                        operation == "GitUiSearchContent" &&
                            variables["query"]?.jsonPrimitive?.content == "nomatches" ->
                            """{"data":{"git":{"searchContent":[]}}}"""
                        operation == "GitUiSearchContent" &&
                            variables["query"]?.jsonPrimitive?.content == "lines" ->
                            """{"data":{"git":{"searchContent":[{"filePath":"search-result.md","lineNumber":2,"snippet":"matching line"}]}}}"""
                        operation == "GitUiSearchContent" ->
                            """{"data":{"git":{"searchContent":[{"filePath":"docs/a.txt","lineNumber":7,"snippet":"<script>snippet</script>"}]}}}"""
                        operation == "GitUiSearchPaths" &&
                            variables["query"]?.jsonPrimitive?.content == "paths" ->
                            """{"data":{"git":{"searchPaths":[{"name":"docs","path":"docs","type":"TREE","sha":"abc","size":0},{"name":"README.md","path":"README.md","type":"BLOB","sha":"abc","size":1536},{"name":"current.txt","path":"docs/current.txt","type":"SYMLINK","sha":"abc","size":12}]}}}"""
                        operation == "GitUiSearchPaths" ->
                            """{"data":{"git":{"searchPaths":[{"name":"docs","path":"docs","type":"TREE","sha":"abc","size":0}]}}}"""
                        operation == "GitUiCompare" ->
                            """{"data":{"git":{"compare":$comparison}}}"""
                        operation == "GitUiStatuses" ->
                            """{"data":{"git":{"commitStatuses":[$status]}}}"""
                        operation == "GitUiPullList" &&
                            variables["status"]?.jsonPrimitive?.content == "CLOSED" ->
                            """{"data":{"git":{"allPullRequests":[]}}}"""
                        operation == "GitUiPullList" ->
                            """{"data":{"git":{"allPullRequests":[$pull]}}}"""
                        operation == "GitUiRepositoryPullList" ->
                            """{"data":{"git":{"pullRequests":[$pull]}}}"""
                        operation == "GitUiPull" ->
                            """{"data":{"git":{"pullRequest":${pullStateFixture(pull, variables["number"]?.jsonPrimitive?.int ?: 1)}}}}"""
                        operation == "GitUiCreatePull" ->
                            """{"errors":[{"message":"Rejected pull request"}]}"""
                        operation == "GitUiReview" ->
                            """{"errors":[{"message":"Review rejected"}]}"""
                        operation == "GitUiMergePlan" ->
                            """{"data":{"git":{"pullRequestMergePlan":[$pull]}}}"""
                        operation == "GitUiMergePull" ->
                            """{"data":{"git":{"mergePullRequest":{"id":"pull","status":"MERGED"}}}}"""
                        operation == "GitUiPipelines" &&
                            variables["repo"]?.jsonPrimitive?.content == "empty" ->
                            """{"data":{"git":{"pipelines":[]}}}"""
                        operation == "GitUiPipelineRuns" &&
                            variables["repo"]?.jsonPrimitive?.content == "empty" ->
                            """{"data":{"git":{"pipelineRuns":[]}}}"""
                        operation == "GitUiPipelines" ->
                            """{"data":{"git":{"pipelines":[{"id":"pipeline","repositoryId":"repo","filePath":".bosca/pipelines/build.yml","name":"Build","updated":"2026-10-09T00:00:00Z","triggerTypes":["MANUAL"],"runs":[$run]}]}}}"""
                        operation == "GitUiPipelineRuns" ->
                            """{"data":{"git":{"pipelineRuns":[$run]}}}"""
                        operation == "GitUiPipelineInputs" ->
                            """{"data":{"git":{"pipelineInputs":[{"name":"version","type":"STRING","defaultValue":null,"description":"Version","options":[],"required":true}]}}}"""
                        operation == "GitUiRun" -> """{"data":{"git":{"pipelineRun":$run}}}"""
                        operation == "GitUiLogs" ->
                            """{"data":{"git":{"pipelineLogs":${if (variables["before"] != null) "[]" else "[{\"lineNumber\":4,\"timestamp\":\"2026-10-09T00:00:00Z\",\"content\":\"<script>log</script>\",\"stream\":\"STDOUT\"}]"}}}}"""
                        operation == "GitUiTrigger" ->
                            """{"data":{"git":{"triggerPipeline":{"id":"new-run"}}}}"""
                        operation == "GitUiCancelRun" ->
                            """{"data":{"git":{"cancelPipelineRun":{"id":"run","status":"CANCELLED"}}}}"""
                        operation == "GitUiBuildAnyway" ->
                            """{"data":{"git":{"runPipelineJobAnyway":{"id":"job","status":"QUEUED"}}}}"""
                        operation == "GitUiAccess" ->
                            """{"data":{"security":{"principals":{"current":{"id":"principal","groups":${if (exchange.requestHeaders.getFirst("Authorization") == "Bearer reader-token") "[]" else "[{\"id\":\"admin\",\"name\":\"administrators\"}]"}}}}}}"""
                        operation == "GitUiPermissions" ->
                            """{"data":{"git":{"repositoryById":{"id":"repo","permissions":[{"groupId":"group","action":"EDIT","group":{"id":"group","name":"Editors"}}]}}}}"""
                        operation == "GitUiGroups" ->
                            """{"data":{"security":{"groups":{"all":[{"id":"group","name":"Editors","description":"Editors"}]}}}}"""
                        operation == "GitUiWebhooks" ->
                            """{"data":{"git":{"webhooks":${if (id == "empty") "[]" else "[{\"id\":\"hook\",\"url\":\"https://build.example.test/hooks/git\",\"active\":true,\"events\":[\"PUSH\",\"TAG_CREATED\"],\"created\":\"2026-10-09T00:00:00Z\"}]"}}}}"""
                        operation == "GitUiProtection" ->
                            """{"data":{"git":{"branchProtectionRules":${if (id == "empty") "[]" else "[{\"id\":\"rule\",\"pattern\":\"main\",\"requirePullRequest\":true,\"requiredApprovals\":2,\"dismissStaleReviews\":true,\"requireCodeOwnerReview\":true,\"requireLinearHistory\":true,\"allowForcePush\":false,\"allowDeletion\":false,\"requireStatusChecks\":[\"build\",\"test\"],\"restrictPushAccess\":null}]"}}}}"""
                        operation == "GitUiSecrets" ->
                            """{"data":{"git":{"pipelineSecrets":${if (id == "empty") "[]" else "[{\"id\":\"secret\",\"name\":\"DEPLOY_TOKEN\",\"environmentKey\":\"production\",\"created\":\"2026-10-09T00:00:00Z\",\"updated\":\"2026-10-09T00:00:00Z\"}]"}}}}"""
                        operation == "GitUiUpdate" ->
                            """{"data":{"git":{"updateRepository":{"id":"repo"}}}}"""
                        operation == "GitUiGc" ->
                            """{"data":{"git":{"runRepositoryGc":{"id":"repo","diskSizeBytes":512}}}}"""
                        operation == "GitUiSetSecret" ->
                            """{"data":{"git":{"setPipelineSecret":{"id":"secret"}}}}"""
                        operation == "GitUiCreateWebhook" ->
                            """{"data":{"git":{"createWebhook":{"id":"hook"}}}}"""
                        operation == "GitUiCreateProtection" ->
                            """{"data":{"git":{"createBranchProtectionRule":{"id":"rule"}}}}"""
                        operation == "GitUiDeleteWebhook" ->
                            """{"data":{"git":{"deleteWebhook":true}}}"""
                        operation == "GitUiDeleteProtection" ->
                            """{"data":{"git":{"deleteBranchProtectionRule":true}}}"""
                        operation == "GitUiUpdateProtection" ->
                            """{"data":{"git":{"updateBranchProtectionRule":{"id":"rule"}}}}"""
                        operation == "GitUiDeleteSecret" ->
                            """{"data":{"git":{"deletePipelineSecret":true}}}"""
                        operation == "GitUiRevoke" ->
                            """{"data":{"git":{"removePermission":true}}}"""
                        operation == "GitUiRepair" ->
                            """{"data":{"git":{"runRepositoryRepair":{"id":"repo","diskSizeBytes":512}}}}"""
                        operation == "GitUiGrant" -> """{"data":{"git":{"addPermission":true}}}"""
                        operation == "GitUiAgents" &&
                            variables["status"]?.jsonPrimitive?.content == "OFFLINE" ->
                            """{"data":{"git":{"pipelineAgents":[]}}}"""
                        operation == "GitUiAgents" ->
                            """{"data":{"git":{"pipelineAgents":$agents}}}"""
                        operation == "GitUiRegisterAgent" ->
                            """{"data":{"git":{"registerAgent":{"agent":{"id":"agent"},"token":"one-time-agent-token"}}}}"""
                        operation == "GitUiUpdateAgent" ->
                            """{"data":{"git":{"updateAgent":{"id":"agent"}}}}"""
                        operation == "GitUiOrchestrator" ->
                            """{"data":{"git":{"getOrchestratorConfig":$orchestrator}}}"""
                        operation == "GitUiGitHubPair" ->
                            """{"data":{"github":{"pair":$pair},"pipelines":{"secrets":[{"name":"token"},{"name":"webhook"}]}}}"""
                        operation == "GitUiGitHubRefs" ->
                            """{"data":{"github":{"refStates":$refs}}}"""
                        operation == "GitUiGitHubPullStates" ->
                            """{"data":{"github":{"pullRequestStates":[]}}}"""
                        operation == "GitUiGitHubDeliveries" ->
                            """{"data":{"github":{"deliveries":[]}}}"""
                        operation == "GitUiSavePair" ->
                            """{"data":{"github":{"savePair":{"version":4}}}}"""
                        operation == "GitUiResolveRef" ->
                            """{"data":{"github":{"resolveRef":{"ref":"refs/heads/main","conflict":false}}}}"""
                        operation == "GitUiGitHubUsers" ->
                            """{"data":{"github":{"users":${JsonArray(userMappings.toList())}}}}"""
                        operation == "GitUiMapUser" &&
                            variables["username"]?.jsonPrimitive?.content == "Rejected" ->
                            """{"errors":[{"message":"Mapping rejected"}]}"""
                        operation == "GitUiMapUser" -> {
                            userMappings.clear()
                            userMappings.add(
                                buildJsonObject {
                                    put("githubUserId", 101)
                                    put("githubUsername", variables.getValue("username"))
                                    put("principalId", variables.getValue("principal"))
                                    put("created", "2026-10-09T00:00:00Z")
                                    put("modified", "2026-10-09T00:00:00Z")
                                }
                            )
                            """{"data":{"github":{"mapUserByUsername":{"githubUserId":101}}}}"""
                        }
                        operation == "GitUiUnmapUser" -> {
                            userMappings.clear()
                            """{"data":{"github":{"unmapUser":true}}}"""
                        }
                        operation == "GitUiPrincipals" ->
                            """{"data":{"security":{"principals":{"all":[{"id":"principal","primaryProfileId":"owner","profiles":[{"id":"owner","name":"Alice","slug":"alice"}],"credentials":[{"identifier":"alice@example.test"}]}]}}}}"""
                        operation == "GitUiRepository" ->
                            """{"data":{"git":{"repositoryById":${if (id == "missing") "null" else if (id == "archived") capabilities(archived) else if (id == "empty") capabilities(active.replace("\"id\":\"repo\"", "\"id\":\"empty\"").replace("Active project", "Empty project")) else capabilities(active)}}}}"""
                        operation == "GitUiBranches" && id == "missing" ->
                            """{"errors":[{"message":"Repository not found"}]}"""
                        operation == "GitUiProfiles" && token == "bootstrap-token" ->
                            """{"data":{"profiles":{"current":[{"id":"admin-profile","name":"admin","slug":null,"isPrimary":true,"attributes":[]}]}}}"""
                        operation == "GitUiProfiles" ->
                            """{"data":{"profiles":{"current":[$profile]}}}"""
                        operation == "GitUiProfile" ->
                            """{"data":{"profiles":{"profile":$profile}}}"""
                        operation == "GitUiOwners" &&
                            variables["query"]?.jsonPrimitive?.content == "Fail" ->
                            """{"errors":[{"message":"Profile search unavailable"}]}"""
                        operation == "GitUiOwners" &&
                            variables["query"]?.jsonPrimitive?.content == "Nobody" ->
                            """{"data":{"search":{"search":{"documents":[]}}}}"""
                        operation == "GitUiOwners" ->
                            """{"data":{"search":{"search":{"documents":[{"profile":$profile},{"profile":$otherProfile}]}}}}"""
                        operation == "GitUiTags" && id == "empty" ->
                            """{"data":{"git":{"tags":[]}}}"""
                        operation == "GitUiTags" ->
                            """{"data":{"git":{"tags":[{"name":"v1","sha":"abc","targetSha":null,"isAnnotated":false,"message":null}]}}}"""
                        operation == "GitUiStats" && id == "empty" ->
                            """{"data":{"git":{"stats":{"commitCount":0,"branchCount":0,"tagCount":0,"contributorCount":0,"diskSizeBytes":0}}}}"""
                        operation == "GitUiStats" ->
                            """{"data":{"git":{"stats":{"commitCount":2,"branchCount":2,"tagCount":1,"contributorCount":1,"diskSizeBytes":1024}}}}"""
                        operation == "GitUiCommit" &&
                            variables["sha"]?.jsonPrimitive?.content in
                                setOf("main", "feature/one", "v1") ->
                            """{"errors":[{"message":"commit requires a SHA, not a ref name"}]}"""
                        operation == "GitUiCommit" ->
                            """{"data":{"git":{"commit":${if (variables["sha"]?.jsonPrimitive?.content == "invalid") "null" else "{\"sha\":\"$commitSha\",\"message\":\"Initial\"}"}}}}"""
                        operation == "GitUiCommits" ->
                            """{"data":{"git":{"commits":[{"sha":"abc","message":"Initial","authorName":"Alice","authorEmail":"alice@example.test","authorDate":"2026-10-09T00:00:00Z","parentShas":[]}]}}}"""
                        operation == "GitUiBranches" && id == "empty" ->
                            """{"data":{"git":{"branches":[]}}}"""
                        operation == "GitUiBranches" ->
                            """{"data":{"git":{"branches":[{"name":"main","sha":"abc"},{"name":"feature/one","sha":"abc"}]}}}"""
                        operation == "GitUiTree" &&
                            id?.startsWith("retry-") == true &&
                            treeRetries
                                .computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger() }
                                .getAndIncrement() == 0 ->
                            """{"errors":[{"message":"Tree temporarily unavailable"}]}"""
                        operation == "GitUiTree" && id == "empty" ->
                            """{"data":{"git":{"tree":[]}}}"""
                        operation == "GitUiTree" && path == "docs" ->
                            """{"data":{"git":{"tree":[{"name":"a # b.txt","path":"docs/a # b.txt","type":"BLOB","sha":"abc","size":12},{"name":"guide.md","path":"docs/guide.md","type":"BLOB","sha":"abc","size":12}]}}}"""
                        operation == "GitUiTree" ->
                            """{"data":{"git":{"tree":[{"name":"docs","path":"docs","type":"TREE","sha":"abc","size":0},{"name":"README.md","path":"README.md","type":"BLOB","sha":"abc","size":34},{"name":"a # b.txt","path":"docs/a # b.txt","type":"BLOB","sha":"abc","size":12}]}}}"""
                        operation == "GitUiBlob" && path == "search-result.md" ->
                            """{"data":{"git":{"blob":{"content":"# Title\nmatching line\nlast line","size":31,"sha":"abc","isBinary":false}}}}"""
                        operation == "GitUiBlob" && path in setOf("empty.txt", "long.txt") ->
                                buildJsonObject {
                                    put(
                                        "data",
                                        buildJsonObject {
                                            put(
                                                "git",
                                                buildJsonObject {
                                                    put(
                                                        "blob",
                                                        buildJsonObject {
                                                            val content =
                                                                if (path == "empty.txt") ""
                                                                else
                                                                    (1..500).joinToString("\n") {
                                                                        "Line $it " +
                                                                            "long source content "
                                                                                .repeat(30)
                                                                    }
                                                            put("content", content)
                                                            put("size", content.length)
                                                            put("sha", "abc")
                                                            put("isBinary", false)
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                                .toString()
                        operation == "GitUiBlob" && storedFiles.containsKey("$id|$path") ->
                                buildJsonObject {
                                    put(
                                        "data",
                                        buildJsonObject {
                                            put(
                                                "git",
                                                buildJsonObject {
                                                    put(
                                                        "blob",
                                                        buildJsonObject {
                                                            val content =
                                                                storedFiles.getValue("$id|$path")
                                                            put("content", content)
                                                            put("size", content.toByteArray().size)
                                                            put("sha", "def")
                                                            put("isBinary", false)
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                                .toString()
                        operation == "GitUiBlob" && id == "empty" ->
                            """{"data":{"git":{"blob":null}}}"""
                        operation == "GitUiBlob" && path?.startsWith("browser-") == true ->
                            """{"data":{"git":{"blob":null}}}"""
                        operation == "GitUiBlob" ->
                            """{"data":{"git":{"blob":${when (path) {
                        "missing", "new.txt", "protected.txt" -> "null"
                        "README.md" -> """{"content":"![Logo](images/logo.png)\n# Hello\n[Guide](docs/guide.md#install)","size":75,"sha":"abc","isBinary":false}"""
                        "binary" -> """{"content":null,"size":12,"sha":"abc","isBinary":true}"""
                        else -> """{"content":"<script>source</script>","size":23,"sha":"abc","isBinary":false}"""
                    }}}}}"""
                        operation == "GitUiCommitFile" &&
                            variables["input"]?.jsonObject?.get("path")?.jsonPrimitive?.content ==
                                "protected.txt" ->
                            """{"errors":[{"message":"Branch is protected"}]}"""
                        operation == "GitUiCommitFile" -> {
                            val input = variables.getValue("input").jsonObject
                            storedFiles[
                                "${input.getValue("repositoryId").jsonPrimitive.content}|${input.getValue("path").jsonPrimitive.content}"] =
                                input.getValue("content").jsonPrimitive.content
                            """{"data":{"git":{"commitFile":{"commitSha":"def","branch":"main","path":"new.txt"}}}}"""
                        }
                        operation == "GitUiDeleteFile" -> {
                            storedFiles.remove("$id|$path")
                            """{"data":{"git":{"deleteFile":{"commitSha":"ghi"}}}}"""
                        }
                        operation == "GitUiCreate" &&
                            variables["input"]?.jsonObject?.get("name")?.jsonPrimitive?.content ==
                                "Rejected" -> """{"errors":[{"message":"Slug unavailable"}]}"""
                        operation == "GitUiCreate" ->
                            """{"data":{"git":{"createRepository":{"id":"created"}}}}"""
                        operation == "GitUiImport" ->
                            """{"data":{"git":{"importRepository":{"id":"imported"}}}}"""
                        operation == "GitUiArchive" ->
                            """{"data":{"git":{"archiveRepository":{"id":"repo","archived":true}}}}"""
                        operation == "LoginPassword" &&
                            variables["password"]?.jsonPrimitive?.content == "invalid" ->
                            """{"errors":[{"message":"Invalid credentials","extensions":{"code":"INVALID_CREDENTIALS"}}]}"""
                        operation == "LoginPassword" &&
                            variables["identifier"]?.jsonPrimitive?.content ==
                                "pending@example.test" ->
                            """{"errors":[{"message":"Principal not verified","extensions":{"code":"PRINCIPAL_NOT_VERIFIED"}}]}"""
                        operation == "LoginPassword" ->
                            """{"data":{"security":{"login":{"password":$session}}}}"""
                        operation == "ExchangeToken" ->
                            """{"data":{"security":{"login":{"exchangeToken":$session}}}}"""
                        operation == "SignupPassword" -> {
                            val identifier = variables["identifier"]?.jsonPrimitive?.content
                            val signup =
                                if (identifier == "linked@example.test")
                                    """{"principal":null,"linkChallenge":{"token":"link-token","methods":["EMAIL"]}}"""
                                else
                                    """{"principal":{"id":"signup-principal","verified":${identifier == "verified@example.test"}},"linkChallenge":null}"""
                            """{"data":{"security":{"signup":{"password":$signup}}}}"""
                        }
                        operation == "VerifyEmail" ->
                            """{"data":{"security":{"signup":{"verify":{"linkChallenge":null}}}}}"""
                        operation == "ResendVerification" ->
                            """{"data":{"security":{"signup":{"passwordResendVerification":true}}}}"""
                        operation == "ForgotPassword" ->
                            """{"data":{"security":{"login":{"forgotPassword":true}}}}"""
                        operation == "ResetPassword" ->
                            """{"data":{"security":{"login":{"resetPassword":true}}}}"""
                        operation == "LinkRequestEmailProof" ->
                            """{"data":{"security":{"link":{"requestEmailProof":true}}}}"""
                        operation == "LinkConfirmPassword" ->
                            """{"data":{"security":{"link":{"confirmPassword":$session}}}}"""
                        operation == "LinkConfirmEmail" ->
                            """{"data":{"security":{"link":{"confirmEmail":$session}}}}"""
                        operation == "GetCurrentPrincipal" ->
                            """{"data":{"security":{"principals":{"current":{"id":"test-principal","verified":true,"primaryProfileId":null}}}}}"""
                        operation == "GetCurrentProfiles" ->
                            """{"data":{"profiles":{"current":[]}}}"""
                        operation == "SignOut" ->
                            """{"data":{"security":{"login":{"signOut":true}}}}"""
                        else -> """{"data":{"probe":true}}"""
                    }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, result.size.toLong())
                exchange.responseBody.use { it.write(result) }
                exchange.close()
            }
            upstream.start()
            val subscriptionPort = ServerSocket(0).use { it.localPort }
            val subscriptionApp =
                BoscaApplication(
                    ApplicationConfig.load(
                        "bosca:\n  server:\n    port: $subscriptionPort\n    drain-timeout-ms: 0\n    development: true\n"
                            .byteInputStream()
                    )
                )
            subscriptionApp.router.post("/graphql") {
                val request =
                    HttpRequest.newBuilder(URI("http://localhost:${upstream.address.port}/graphql"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(call.request.bodyText()))
                call.request.header("Authorization")?.let { request.header("Authorization", it) }
                call.request.header("Origin")?.let { request.header("Origin", it) }
                val response =
                    client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).await()
                call.respondBytes(response.body().toByteArray(), ContentType.Application.Json)
            }
            subscriptionApp.router.webSocket("/graphqlws", "graphql-transport-ws") {
                GraphQLWebSocketProtocolHandler.handle(
                    this,
                    Json,
                    subscribe = { _, _ ->
                        flow {
                            delay(500)
                            for (number in listOf(4, 9, 8, 9) + (10..130)) {
                                emit(
                                    buildJsonObject {
                                        put(
                                            "data",
                                            buildJsonObject {
                                                put(
                                                    "pipelineStepLogs",
                                                    buildJsonObject {
                                                        put("lineNumber", number)
                                                        put("timestamp", "2026-10-09T00:00:00Z")
                                                        put(
                                                            "stream",
                                                            if (number % 2 == 0) "STDOUT"
                                                            else "STDERR",
                                                        )
                                                        put(
                                                            "content",
                                                            "Live line $number <script>literal output</script>",
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    }
                                )
                                delay(20)
                            }
                            awaitCancellation()
                        }
                    },
                    onConnectionInit = { _, payload ->
                        payload?.jsonObject?.get("authToken")?.jsonPrimitive?.content ==
                            "ui-test-token"
                    },
                )
            }
            subscriptionApp.freezeMiddleware()
            subscriptionEngine = NettyServerEngine(subscriptionApp, subscriptionPort)
            thread(isDaemon = true, name = "git-ui-test-subscriptions") {
                subscriptionEngine.start()
            }
            repeat(100) {
                try {
                    java.net.Socket("localhost", subscriptionPort).close()
                    return@repeat
                } catch (_: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
            port = ServerSocket(0).use { it.localPort }
            val config =
                "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n"
            val app = BoscaApplication(ApplicationConfig.load(config.byteInputStream()))
            installGitUi(app, "http://localhost:$subscriptionPort/graphql", "_git_test")
            // Fixture asset delivery for browser rendering; production transport is tested in
            // EndToEndGitCliTest.
            app.router.get("/owner/repo/raw/{ref}/{path...}") {
                if (call.request.cookies["_git_test"] != "ui-test-token")
                    call.respond(bosca.server.HttpStatusCode.Forbidden)
                else if (call.pathParameters["path"] != "images/logo.png")
                    call.respond(bosca.server.HttpStatusCode.NotFound)
                else
                    call.respondBytes(
                        java.util.Base64.getDecoder()
                            .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII="
                            ),
                        ContentType.Image.Png,
                    )
            }
            app.router.get("/{owner}/{repo}") { call.respond("clone URL fallback") }
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "git-ui-test-server") { engine.start() }
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).close()
                    return
                } catch (_: java.io.IOException) {
                    Thread.sleep(50)
                }
            }
            error("Git UI test server did not start")
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
            if (::subscriptionEngine.isInitialized) subscriptionEngine.stopWithoutHalting()
            if (::upstream.isInitialized) upstream.stop(0)
        }

        private fun fixture(name: String): String =
            requireNotNull(GitUiRoutesTest::class.java.getResourceAsStream("/fixtures/$name.json"))
                .use { it.readAllBytes().decodeToString() }

        private val agents = fixture("agents")
        private val pair = fixture("pair")
        private val refs = fixture("refs")
        private val orchestrator = fixture("orchestrator")
        private val pull = fixture("pull")
        private val run = fixture("run")
        private val comparison = fixture("compare")
        private val status = fixture("status")
        private val profile =
            """{"id":"owner","name":"Alice","slug":"alice","isPrimary":true,"attributes":[{"typeId":"bosca.profiles.email","attributes":{"email":"alice@example.test"}}]}"""
        private val otherProfile =
            profile
                .replace("\"id\":\"owner\"", "\"id\":\"other\"")
                .replace("\"slug\":\"alice\"", "\"slug\":\"alice-2\"")
        private val session =
            """{"principal":{"id":"test-principal","verified":true,"primaryProfileId":null},"profile":[],"token":{"token":"ui-test-token","expiresAt":4102444800,"issuedAt":1},"refreshToken":null,"accountCreated":false,"originator":"git-ui"}"""
        private val active =
            """{"id":"repo","slug":"repo","ownerId":"owner","contentType":"GENERAL","diskSizeBytes":1024,"updated":"2026-10-09T00:00:00Z","canEdit":true,"canManage":true,"canExecute":true,"name":"Active project","description":"<script>untrusted</script>","defaultBranch":"main","archived":false,"deleted":false,"visibility":"PRIVATE","cloneUrl":"https://git.example.test/owner/repo.git"}"""
        private val archived =
            active
                .replace("\"id\":\"repo\"", "\"id\":\"archived\"")
                .replace("Active project", "Archived project")
                .replace("\"archived\":false", "\"archived\":true")
        private val deleted =
            active
                .replace("Active project", "Deleted project")
                .replace("\"deleted\":false", "\"deleted\":true")
    }
}
