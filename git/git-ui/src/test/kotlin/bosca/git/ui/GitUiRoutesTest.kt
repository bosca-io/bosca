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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        requestProbe = null
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
        assertTrue("Loading statistics" in response.body(), response.body())
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
    fun `repository directory listings put folders first and sort names ignoring case`() {
        for (path in listOf("", "beta")) {
            val response = get("/repositories/ordering?ref=main&path=$path")
            assertEquals(200, response.statusCode(), response.body())
            val tree =
                Regex("<ul[^>]*class=\"tree-rows\"[^>]*>(.*?)</ul>", RegexOption.DOT_MATCHES_ALL)
                    .find(response.body())
                    ?.groupValues
                    ?.get(1)
            assertNotNull(tree, response.body())
            val names =
                Regex("<a\\b[^>]*class=\"tree-name\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(tree)
                    .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
                    .toList()
            assertEquals(listOf("beta", "Gamma", "apple.txt", "Zebra.txt"), names, "Path: $path")
        }
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
    fun `tag creation is available only on editable nonempty active repositories`() {
        assertTrue("New Tag" in get("/repositories/repo?tab=tags").body())
        assertFalse("New Tag" in get("/repositories/repo?tab=branches").body())
        assertFalse("New Tag" in get("/repositories/repo?tab=tags", token = "reader-token").body())
        assertFalse("New Tag" in get("/repositories/archived?tab=tags").body())
        assertFalse("New Tag" in get("/repositories/empty?tab=tags").body())
    }

    @Test
    fun `tag creation forwards the draft and profile identity then returns to tags`() {
        val response = action(
            "/repositories/repo?tab=tags&ref=feature%2Fone&return=%2Frepositories%3Fowner%3Dall",
            "git-tag-create.model", "createTag", listOf(" release/1.0 ", " feature/one ", "Release notes\nSecond line"),
            token = "bootstrap-token",
        )
        val state = actionState(response)
        assertEquals(false, state["failed"]?.jsonPrimitive?.boolean, response.body())
        val request = requests.last { it.operation == "GitUiCreateTag" }
        assertEquals("Bearer bootstrap-token", request.authorization)
        assertEquals("repo", request.variables["id"]?.jsonPrimitive?.content)
        assertEquals("release/1.0", request.variables["name"]?.jsonPrimitive?.content)
        assertEquals("feature/one", request.variables["source"]?.jsonPrimitive?.content)
        assertEquals("Release notes\nSecond line", request.variables["message"]?.jsonPrimitive?.content)
        assertEquals("admin", request.variables["taggerName"]?.jsonPrimitive?.content)
        assertEquals("studio@bosca.io", request.variables["taggerEmail"]?.jsonPrimitive?.content)
        assertEquals(repositoryHref("repo", "release/1.0", listHref = "/repositories?owner=all", tab = "tags"), state["nextHref"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tag creation failures retain the draft and blank inputs do not write`() {
        val path = "/repositories/repo?tab=tags"
        val shell = get(path).body()
        assertTrue("value=\"main\"" in shell, shell)
        for ((name, source) in listOf("v1" to "main", "1.0" to "missing")) {
            val failed = action(path, "git-tag-create.model", "createTag", listOf(name, source, "Keep annotation"), sourceHtml = shell)
            val state = actionState(failed)
            assertEquals(true, state["failed"]?.jsonPrimitive?.boolean, failed.body())
            assertEquals(name, state["name"]?.jsonPrimitive?.content)
            assertEquals(source, state["source"]?.jsonPrimitive?.content)
            assertEquals("Keep annotation", state["tagMessage"]?.jsonPrimitive?.content)
            assertEquals("", state["nextHref"]?.jsonPrimitive?.content)
            assertTrue("Keep annotation" in failed.body(), failed.body())
        }
        val before = requests.count { it.operation == "GitUiCreateTag" }
        for ((name, source) in listOf(" " to "main", "1.0" to " ")) {
            val failed = action(path, "git-tag-create.model", "createTag", listOf(name, source, ""), sourceHtml = shell)
            assertEquals(true, actionState(failed)["failed"]?.jsonPrimitive?.boolean, failed.body())
        }
        assertEquals(before, requests.count { it.operation == "GitUiCreateTag" })
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
        assertTrue("README.md" in paths.body() && "1.5 KB" in paths.body(), paths.body())
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
                "statuses" to "Recent Commits",
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
                listOf("Title", "Body", "feature/one", "main", false, false),
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
                listOf("repo", "", "Keep title", "Keep description", "feature/one", "main", true, false),
            )
        assertEquals(200, selected.statusCode(), selected.body())
        val state = actionState(selected)
        assertEquals(false, state["failed"]?.jsonPrimitive?.boolean, selected.body())
        assertEquals("Keep title", state["title"]?.jsonPrimitive?.content)
        assertEquals("Keep description", state["description"]?.jsonPrimitive?.content)
        assertEquals("repo", state["page"]?.jsonObject?.get("repository")?.jsonPrimitive?.content)
        assertEquals("feature/one", state["source"]?.jsonPrimitive?.content)
        assertEquals(true, state["draft"]?.jsonPrimitive?.boolean)
        assertEquals(true, state["deleteBranchOnMerge"]?.jsonPrimitive?.boolean)
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
                    false,
                ),
            )
        assertEquals(200, sourceChanged.statusCode(), sourceChanged.body())
        assertEquals(
            "Keep description",
            actionState(sourceChanged)["description"]?.jsonPrimitive?.content,
        )
        assertEquals("", actionState(sourceChanged)["source"]?.jsonPrimitive?.content)
        assertEquals(false, actionState(sourceChanged)["deleteBranchOnMerge"]?.jsonPrimitive?.boolean)
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
        val triggerPage = state.getValue("page").jsonObject
        assertEquals(0, triggerPage.getValue("runs").jsonArray.size)
        assertEquals(0, triggerPage.getValue("repositories").jsonArray.size)
        assertTrue(triggerPage.getValue("pipelines").jsonArray.all { it.jsonObject.getValue("runs").jsonArray.isEmpty() })
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
        assertEquals(404, get("/pulls/missing").statusCode())
    }

    @Test
    fun `pull detail resolves its path ID without repository and number parameters`() {
        for (suffix in listOf("", "?view=files", "?view=reviews", "?repo=other&number=99")) {
            val response = get("/pulls/pull$suffix")
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("Alice · @alice" in response.body(), response.body())
            val lookup = requests.last { it.operation == "GitUiPull" }
            assertEquals(buildJsonObject { put("id", "pull") }, lookup.variables)
        }
        assertEquals(404, get("/pulls/missing?repo=repo&number=1").statusCode())
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
    fun `pull editing loads branch choices on demand and retains the selected view after saving`() {
        val path = "/pulls/pull?repo=repo&number=1&view=files"
        val html = get(path).body()
        assertTrue("Edit pull request" in html, html)
        val opened = action(path, "git-pull-edit.model", "open", emptyList())
        assertEquals(200, opened.statusCode(), opened.body())
        assertTrue("feature/one" in opened.body() && "main" in opened.body(), opened.body())
        val saved = action(path, "git-pull-edit.model", "save", listOf("Updated title", "", "main", "feature/one", false))
        assertEquals(200, saved.statusCode(), saved.body())
        val state = actionState(saved)
        assertEquals(false, state["failed"]?.jsonPrimitive?.boolean, saved.body())
        assertTrue(state["nextHref"]?.jsonPrimitive?.content?.endsWith("&view=files") == true, saved.body())
        val input = requests.last { it.operation == "GitUiUpdatePull" }.variables.getValue("input").jsonObject
        assertEquals("Updated title", input["title"]?.jsonPrimitive?.content)
        assertEquals("", input["description"]?.jsonPrimitive?.content)
        assertEquals("main", input["sourceBranch"]?.jsonPrimitive?.content)
        assertEquals("feature/one", input["targetBranch"]?.jsonPrimitive?.content)
        assertEquals(0L, input["expectedVersion"]?.jsonPrimitive?.long)
        assertEquals(false, input["deleteBranchOnMerge"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `a rejected edit retains every field and exposes its API error`() {
        val response = action("/pulls/pull?repo=repo&number=1", "git-pull-edit.model", "save", listOf("Reject edit", "Draft description", "main", "feature/one", false))
        val state = actionState(response)
        assertEquals(true, state["failed"]?.jsonPrimitive?.boolean, response.body())
        assertEquals("Draft description", state["description"]?.jsonPrimitive?.content)
        assertEquals(false, state["deleteBranchOnMerge"]?.jsonPrimitive?.boolean)
        assertEquals("main", state["source"]?.jsonPrimitive?.content)
        assertEquals("feature/one", state["target"]?.jsonPrimitive?.content)
        assertTrue("Rejected pull request edit" in response.body(), response.body())
    }

    @Test
    fun `blank titles are rejected by the editor without sending an update`() {
        val count = requests.count { it.operation == "GitUiUpdatePull" }
        val response = action("/pulls/pull?repo=repo&number=1", "git-pull-edit.model", "save", listOf("   ", "Draft description", "feature/one", "main", false))
        assertTrue(actionState(response)["failed"]?.jsonPrimitive?.boolean == true, response.body())
        assertTrue("Enter a pull request title" in response.body(), response.body())
        assertEquals(count, requests.count { it.operation == "GitUiUpdatePull" })
    }

    @Test
    fun `pull files show only changes and reveal unchanged lines on request`() {
        val path = "/pulls/pull-context?repo=repo&number=12&view=files"
        val page = get(path)
        assertEquals(200, page.statusCode(), page.body())
        val body = page.body()
        assertTrue("1 hidden line" in body && "@@ -2,7 +2,7 @@" in body, body)
        assertTrue("Show 20 more lines below" in body && "Show 20 more lines above" in body, body)
        assertTrue(">line 20<" !in body && ">line 30<" !in body, "Unchanged lines between hunks start hidden")
        assertTrue(">line 40<" in body && "Context discussion" in body, "A discussion on a hidden line opens beside its context")
        assertFalse("Discussions outside this diff" in body, body)
        assertTrue("title=\"$CONTEXT_FILE\"" in body, "The full path is available on the file header")
        assertTrue("Copy file path" in body && "Show all lines" in body, body)

        val down = action(path, "git-pull-review.model", "expandContext", listOf(CONTEXT_FILE, 1, "down"), sourceHtml = body)
        val state = actionState(down)
        assertEquals("", state.getValue("contextMessage").jsonPrimitive.content, down.body())
        val hunks = state.getValue("diff").jsonArray.single().jsonObject.getValue("hunks").jsonArray.map { it.jsonObject }
        assertEquals((9..28).toList(), hunks.first().getValue("after").jsonArray.map { it.jsonObject.getValue("newLineNumber").jsonPrimitive.int })
        assertTrue(">line 20<" in down.body() && "Hide unchanged lines" in down.body(), down.body())
        assertEquals(CONTEXT_FILE, requests.last { it.operation == "GitUiBlob" }.variables["path"]?.jsonPrimitive?.content)

        val all = action(path, "git-pull-review.model", "expandFile", listOf(CONTEXT_FILE), sourceHtml = body)
        assertTrue((1..100).all { ">line $it<" in all.body() || it == 5 }, "Show all lines reveals the whole file")
        assertFalse("hidden line" in all.body(), all.body())

        // Expanded lines are ordinary diff lines, so reviewers can comment on them.
        val key = Regex("data-bml-state-key=\"(git-pull-review\\.model[^\"]*)\"").find(body)?.groupValues?.get(1) ?: error(body)
        val expandedHtml = "<script type=\"application/json\" data-bml-state-key=\"$key\">$state</script>"
        val comment = action(path, "git-pull-review.model", "select", listOf(CONTEXT_FILE, 20, 20), sourceHtml = expandedHtml)
        assertFalse(actionState(comment)["failed"]?.jsonPrimitive?.boolean == true, comment.body())
        assertEquals(CONTEXT_FILE, actionState(comment).getValue("commentFile").jsonPrimitive.content, comment.body())
    }

    @Test
    fun `revealing unchanged lines reports unavailable source beside the file`() {
        val path = "/pulls/pull-context?repo=repo&number=12&view=files"
        val response = action(path, "git-pull-review.model", "expandContext", listOf(CONTEXT_FILE, 0, "down"))
        val state = actionState(response)
        assertEquals(CONTEXT_FILE, state.getValue("contextFile").jsonPrimitive.content)
        assertTrue("Nothing to show below this change" in state.getValue("contextMessage").jsonPrimitive.content, response.body())
        assertTrue("Nothing to show below this change" in response.body(), response.body())
    }

    @Test
    fun `compared files reveal unchanged lines from the head ref`() {
        val compare = get("/compare?repo=repo&base=main&head=feature%2Fone")
        assertEquals(200, compare.statusCode(), compare.body())
        assertTrue("Changed files" in compare.body() && "diff-file-header" in compare.body(), compare.body())
        val response = action("/compare?repo=repo&base=main&head=feature%2Fone", "git-compare-files.model", "collapseContext", listOf("a.txt"))
        assertEquals("feature/one", actionState(response).getValue("ref").jsonPrimitive.content)
    }

    @Test
    fun `diff pagination rejects a retargeted PR instead of mixing comparisons`() {
        val response = action("/pulls/pull?repo=repo&number=1&view=files", "git-pull-review.model", "expand", emptyList())
        val state = actionState(response)
        assertTrue(state["failed"]?.jsonPrimitive?.boolean == true, response.body())
        assertTrue("Pull request changed" in response.body(), response.body())
        assertEquals(2, state.getValue("diff").jsonArray.single().jsonObject.getValue("hunks").jsonArray.single().jsonObject.getValue("lines").jsonArray.size)
    }

    @Test
    fun `a PR with a deleted source remains editable without requesting an invalid diff`() {
        val path = "/pulls/pull-missing-branch?repo=repo&number=8&view=files"
        val count = requests.count { it.operation == "GitUiPullDiffPage" }
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("Comparison unavailable" in response.body() && "no longer available" in response.body(), response.body())
        assertTrue("Edit pull request" in response.body(), response.body())
        assertFalse("Review changes" in response.body(), response.body())
        assertEquals(count, requests.count { it.operation == "GitUiPullDiffPage" })
        val opened = action(path, "git-pull-edit.model", "open", emptyList())
        assertTrue("missing (unavailable)" in opened.body(), opened.body())
        assertTrue("feature/one" in opened.body(), opened.body())
    }

    @Test
    fun `readers have no PR edit control and closed or merged requests retain only metadata editing`() {
        val reader = get("/pulls/pull?repo=repo&number=1", token = "reader-token")
        assertEquals(200, reader.statusCode(), reader.body())
        assertFalse("data-bml-component=\"git-pull-edit\"" in reader.body(), reader.body())
        for ((id, number) in listOf("pull-merged" to 3, "pull-closed" to 4)) {
            val html = get("/pulls/$id?repo=repo&number=$number").body()
            assertTrue("Edit pull request" in html, html)
            assertTrue("type=\"hidden\" name=\"source\"" in html, html)
        }
    }

    @Test
    fun `pull lifecycle states show the appropriate actions and review controls`() {
        val draft = get("/pulls/pull-draft?repo=repo&number=2").body()
        assertTrue("Mark Ready for Review" in draft && "Ready for review" in draft, draft)
        assertFalse("Review changes" in draft, draft)
        val draftFiles = get("/pulls/pull-draft?repo=repo&number=2&view=files").body()
        assertFalse("Comment on line" in draftFiles, draftFiles)
        assertTrue("Mark this draft ready" in draftFiles, draftFiles)

        val merged = get("/pulls/pull-merged?repo=repo&number=3").body()
        assertTrue(">merged<" in merged.replace(Regex(">\\s+"), ">").replace(Regex("\\s+<"), "<") && "--tone: #a78bff" in merged, merged)
        assertFalse("Close pull request" in merged || "Review changes" in merged, merged)
        val closed = get("/pulls/pull-closed?repo=repo&number=4").body()
        assertTrue("--tone: #ff5d6c" in closed && "Reopen pull request" in closed, closed)
        assertFalse("Review changes" in closed, closed)
    }

    @Test
    fun `merged pull files survive deleted branches and paginate without live ref validation`() {
        val path = "/pulls/pull-merged?repo=repo&number=3&view=files"
        val before = requests.size
        val html = get(path).body()
        assertTrue("Existing line comment" in html && "Second diff line" in html, html)
        assertTrue("Earlier revision" in html && "Discussions outside this diff" in html, html)
        val displayedDiff = Regex("<section[^>]*class=\"diff-file\"[^>]*>.*?</section>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).joinToString("\n") { it.value }
        assertTrue("Second diff line" in displayedDiff, html)
        assertFalse("Existing line comment" in displayedDiff, displayedDiff)
        assertFalse("Comparison unavailable" in html || "No files changed" in html, html)
        assertTrue(requests.drop(before).none { it.operation == "GitUiBranches" })
        val expanded = action(path, "git-pull-review.model", "expand", emptyList())
        assertEquals(false, actionState(expanded)["failed"]?.jsonPrimitive?.boolean, expanded.body())
        assertEquals(4000, actionState(expanded)["offset"]?.jsonPrimitive?.int)
        assertEquals("merged-abc", actionState(expanded).getValue("page").jsonObject.getValue("headSha").jsonPrimitive.content)
        assertTrue(requests.drop(before).none { it.operation == "GitUiBranches" || it.operation == "GitUiCommit" })
        val unavailable = get("/pulls/pull-merged-no-base?repo=repo&number=11&view=files").body()
        assertTrue("The original target commit for this merge is not recorded." in unavailable, unavailable)
        assertFalse("No files changed" in unavailable, unavailable)
    }

    @Test
    fun `conflicts block merging and metadata actions have descriptive icon labels`() {
        val page = get("/pulls/pull-conflicts?repo=repo&number=5").body()
        assertTrue("Merge Conflicts" in page && "This branch has conflicts that must be resolved" in page && "docs/guide.md" in page, page)
        assertTrue(Regex("<button[^>]*disabled[^>]*>.*?</button>", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).any { "Merge Pull Request" in it.value }, page)
        assertTrue("Remove assignee Alice" in page, page)
        assertTrue("Remove dependency #2" in page, page)
        assertTrue("Dependencies" in page, page)
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
    fun `compact line comments use the latest active owned review and show rejected drafts beside the field`() {
        val path = "/pulls/pull?repo=repo&number=1&view=files"
        val selected = action(path, "git-pull-review.model", "select", listOf("a.txt", 0, 1))
        val html = Json.parseToJsonElement(selected.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("ui-comment-editor" in html && "Comment on a.txt:1" in html, html)
        assertFalse("Your review" in html, html)
        val original = actionState(selected)
        val review = original.getValue("reviews").jsonArray.first().jsonObject
        fun reviewWith(id: String, reviewer: String = "owner", dismissed: Boolean = false): JsonObject =
            JsonObject(review + mapOf("id" to JsonPrimitive(id), "reviewerId" to JsonPrimitive(reviewer),
                "dismissedAt" to if (dismissed) JsonPrimitive("2026-10-12T00:00:00Z") else JsonNull))
        val state = JsonObject(original + ("reviews" to JsonArray(listOf(
            review, reviewWith("latest-owned"), reviewWith("dismissed", dismissed = true), reviewWith("other", "other"),
        ))))
        val saved = continueAction(path, "git-pull-review.model:pull", "comment", state, listOf("Saved comment"))
        assertEquals(false, actionState(saved)["failed"]?.jsonPrimitive?.boolean)
        assertEquals("latest-owned", requests.last { it.operation == "GitUiReviewComment" }.variables
            .getValue("input").jsonObject.getValue("reviewId").jsonPrimitive.content)
        val failed = continueAction(path, "git-pull-review.model:pull", "comment", state, listOf("Reject comment"))
        assertEquals(true, actionState(failed)["commentFailed"]?.jsonPrimitive?.boolean)
        assertEquals("Reject comment", actionState(failed)["commentBody"]?.jsonPrimitive?.content)
        val failureHtml = Json.parseToJsonElement(failed.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("aria-invalid=\"true\"" in failureHtml && "Comment rejected" in failureHtml, failureHtml)
        val switched = continueAction(path, "git-pull-review.model:pull", "select", actionState(failed), listOf("a.txt", 0, 2))
        assertEquals("", actionState(switched)["commentBody"]?.jsonPrimitive?.content)
        assertEquals(false, actionState(switched)["commentFailed"]?.jsonPrimitive?.boolean)
        assertEquals(2, actionState(switched)["commentOpenSequence"]?.jsonPrimitive?.int)
        val first = action("/pulls/pull-first-comment?repo=repo&number=9&view=files", "git-pull-review.model", "select", listOf("a.txt", 0, 1))
        assertTrue("Submit a review to start commenting." in first.body(), first.body())

        val file = original.getValue("diff").jsonArray.single().jsonObject
        val spacedFile = JsonObject(file + mapOf("oldPath" to JsonPrimitive("notes with spaces.md"),
            "newPath" to JsonPrimitive("notes with spaces.md")))
        val spacedState = JsonObject(original + ("diff" to JsonArray(listOf(spacedFile))))
        val spaced = continueAction(path, "git-pull-review.model:pull", "select", spacedState, listOf("notes with spaces.md", 0, 1))
        val spacedFailure = continueAction(path, "git-pull-review.model:pull", "comment", actionState(spaced), listOf("Reject comment"))
        val spacedHtml = Json.parseToJsonElement(spacedFailure.body()).jsonObject.getValue("html").jsonPrimitive.content
        val errorId = "pull-comment-pull-${encode("notes with spaces.md:0:1")}-error"
        assertTrue("id=\"$errorId\"" in spacedHtml && " $errorId\"" in spacedHtml, spacedHtml)
    }

    @Test
    fun `replies work without an owned review and retain original and outdated discussion context`() {
        post("/_fixture/replies/reset", "{}")
        val files = "/pulls/pull-replies?repo=repo&number=10&view=files"
        val selected = action(files, "git-pull-review.model", "reply", listOf("comment"))
        assertEquals("comment", actionState(selected)["replyToCommentId"]?.jsonPrimitive?.content)
        assertTrue("Write a reply" in selected.body() && "Submit a review to start" !in selected.body(), selected.body())
        val blank = continueAction(files, "git-pull-review.model:pull-replies", "comment", actionState(selected), listOf(" \n"))
        assertEquals(true, actionState(blank)["commentFailed"]?.jsonPrimitive?.boolean)
        assertTrue("Enter a reply" in blank.body(), blank.body())
        val rejected = continueAction(files, "git-pull-review.model:pull-replies", "comment", actionState(selected), listOf("Reject reply"))
        assertEquals("Reject reply", actionState(rejected)["commentBody"]?.jsonPrimitive?.content)
        assertEquals("comment", actionState(rejected)["replyToCommentId"]?.jsonPrimitive?.content)
        val saved = continueAction(files, "git-pull-review.model:pull-replies", "comment", actionState(rejected), listOf("Reply through the UI"))
        assertEquals(false, actionState(saved)["failed"]?.jsonPrimitive?.boolean)
        assertEquals("Reply added.", actionState(saved)["message"]?.jsonPrimitive?.content)
        assertEquals("", actionState(saved)["replyToCommentId"]?.jsonPrimitive?.content)
        val request = requests.last { it.operation == "GitUiReplyComment" }
        assertEquals("comment", request.variables["comment"]?.jsonPrimitive?.content)
        assertEquals(0, request.variables["version"]?.jsonPrimitive?.int)
        val savedHtml = Json.parseToJsonElement(saved.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("Existing line comment" in savedHtml && "Reply through the UI" in savedHtml, savedHtml)
        assertTrue("Discussions outside this diff" in savedHtml && "Outdated discussion" in savedHtml, savedHtml)
        val reviews = files.replace("view=files", "view=reviews")
        val old = action(reviews, "git-pull-review.model", "reply", listOf("old-comment"))
        val response = continueAction(reviews, "git-pull-review.model:pull-replies", "comment", actionState(old), listOf("Reply to old context"))
        assertEquals(false, actionState(response)["failed"]?.jsonPrimitive?.boolean)
        val oldHtml = Json.parseToJsonElement(response.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("Reply to old context" in oldHtml && "Outdated" in oldHtml && "ref=old-sha" in oldHtml, oldHtml)
        val currentHtml = get(files).body().replace(Regex("<script\\b[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue("Reply to old context" in currentHtml && "ref=old-sha" in currentHtml)
        assertTrue("id=\"discussion-old-comment\"" in currentHtml)
        post("/_fixture/replies/reset", "{}")
    }

    @Test
    fun `review locations link to unique reachable discussions with original source context`() {
        post("/_fixture/replies/reset", "{}")
        val reviews = get("/pulls/pull-replies?repo=repo&number=10&view=reviews").body()
        for (id in listOf("comment", "old-comment", "file-comment")) {
            assertTrue("view=files#discussion-$id" in reviews, reviews)
            assertTrue("id=\"discussion-$id\"" in reviews, reviews)
        }
        assertTrue("View discussion on a.txt:1 in Files Changed" in reviews, reviews)
        assertTrue("View old name.kt:3 at the original commit" in reviews && "ref=old-sha" in reviews, reviews)
        val files = get("/pulls/pull-replies?repo=repo&number=10&view=files").body()
        for (id in listOf("comment", "old-comment", "file-comment")) {
            assertEquals(1, Regex("id=\"discussion-$id\"").findAll(files).count(), files)
        }
        val inline = files.substringAfter("data-thread=\"comment\"").substringBefore("</article>")
        assertTrue("Existing line comment" in inline && "Outdated discussion" !in inline, inline)
        assertFalse("File-level discussion" in inline, inline)
        val fileDiscussion = files.substringAfter("data-thread=\"file-comment\"").substringBefore("</article>")
        assertFalse("Resolve thread" in fileDiscussion, fileDiscussion)
        assertTrue("Discussions outside this diff" in files, files)
        val reply = action("/pulls/pull-replies?repo=repo&number=10&view=files", "git-pull-review.model", "reply", listOf("file-comment"))
        val replyHtml = Json.parseToJsonElement(reply.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("aria-label=\"Reply to comment on a.txt\"" in replyHtml, replyHtml)
    }

    @Test
    fun `discussions outside displayed lines stay reachable for removed sides and truncated diffs`() {
        val response = action("/pulls/pull?repo=repo&number=1&view=files", "git-pull-review.model", "select", listOf("a.txt", 0, 1))
        val model = Json.decodeFromJsonElement(PullReviewModel.serializer(), actionState(response))
        val original = model.reviews.first().comments.first()
        val deleted = original.copy(id = "deleted", newLineNumber = null, oldLineNumber = 4)
        val hidden = original.copy(id = "hidden", newLineNumber = 4000)
        val old = original.copy(id = "outdated", outdated = true)
        val fileComment = original.copy(id = "file-comment", oldLineNumber = null, newLineNumber = null)
        model.reviews = listOf(model.reviews.first().copy(comments = listOf(original, deleted, hidden, old, fileComment)))
        model.diff = listOf(DiffView("a.txt", "a.txt", "MODIFY", listOf(
            DiffHunkView(1, 4, 1, 4000, 4004, listOf(
                DiffLineView("ADD", null, 1, "Current line"),
                DiffLineView("DELETE", 4, null, "Deleted line"),
            ))
        )))
        assertEquals(listOf(original.id), model.lineThreads("a.txt", null, 1).map { it.id })
        assertEquals(listOf("deleted"), model.lineThreads("a.txt", 4, null).map { it.id })
        assertEquals(listOf("hidden", "outdated", "file-comment"), model.detachedThreads.map { it.id })
        assertEquals("a.txt", model.detachedThreads.last().location)
        model.diff = emptyList()
        assertEquals(5, model.detachedThreads.size)
    }

    @Test
    fun `review notifications advance for successes and keep failures inline`() {
        val path = "/pulls/pull?repo=repo&number=1&view=files"
        val resolved = action(path, "git-pull-review.model", "resolve", listOf("a.txt", 1))
        val first = actionState(resolved)
        assertEquals(false, first["failed"]?.jsonPrimitive?.boolean)
        assertEquals("Thread resolved.", first["message"]?.jsonPrimitive?.content)
        assertEquals(1, first["noticeSequence"]?.jsonPrimitive?.int)
        val html = Json.parseToJsonElement(resolved.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("data-message=\"Thread resolved.\"" in html, html)
        assertTrue("data-bml-preserve=\"pull-review-notice-pull\"" in html, html)
        assertFalse("role=\"alert\"" in html, "Success must not insert an inline alert: $html")

        val repeated = continueAction(path, "git-pull-review.model:pull", "resolve", first, listOf("a.txt", 1))
        assertEquals(2, actionState(repeated)["noticeSequence"]?.jsonPrimitive?.int)
        val rejected = continueAction(path, "git-pull-review.model:pull", "resolve", actionState(repeated), listOf("fail", 1))
        assertEquals(true, actionState(rejected)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(2, actionState(rejected)["noticeSequence"]?.jsonPrimitive?.int)
        val failureHtml = Json.parseToJsonElement(rejected.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("role=\"alert\"" in failureHtml && "Thread resolution rejected" in failureHtml, failureHtml)

        val selected = continueAction(path, "git-pull-review.model:pull", "select", actionState(rejected), listOf("a.txt", 0, 1))
        val commented = continueAction(path, "git-pull-review.model:pull", "comment", actionState(selected), listOf("Saved comment"))
        assertEquals("Comment added.", actionState(commented)["message"]?.jsonPrimitive?.content)
        assertEquals(3, actionState(commented)["noticeSequence"]?.jsonPrimitive?.int)
        val reviewed = continueAction(path, "git-pull-review.model:pull", "review", actionState(commented), listOf("COMMENT_ONLY", "Review accepted"))
        assertEquals("Review submitted.", actionState(reviewed)["message"]?.jsonPrimitive?.content)
        assertEquals(4, actionState(reviewed)["noticeSequence"]?.jsonPrimitive?.int)
        assertEquals(true, actionState(reviewed)["reviewSubmitted"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `pull creation and reviews retain failed drafts and merge needs a plan`() {
        val create =
            action(
                "/pulls?repo=repo&new=true",
                "git-pull-create.model",
                "create",
                listOf("Rejected", "keep description", "feature/one", "main", true, false),
            )
        assertEquals(true, actionState(create)["failed"]?.jsonPrimitive?.boolean, create.body())
        assertEquals("keep description", actionState(create)["description"]?.jsonPrimitive?.content)
        assertEquals(false, actionState(create)["deleteBranchOnMerge"]?.jsonPrimitive?.boolean)
        assertEquals(false, requests.last { it.operation == "GitUiCreatePull" }.variables["input"]?.jsonObject?.get("deleteBranchOnMerge")?.jsonPrimitive?.boolean)
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
                listOf(""),
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
        assertEquals("SQUASH", actionState(plan)["strategy"]?.jsonPrimitive?.content)
        assertTrue("data-open-request" in plan.body(), plan.body())
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
                "This job is queued until its requirements are available." in html,
            html,
        )
        assertTrue("/ci/artifacts/repo/run/build%2Freport%20%231.zip" in html, html)
        assertTrue("Build · Run #3" in html, html)
        assertTrue("/repositories/repo?tab=pipelines" in html, html)
        assertTrue("io.bosca / core:1.2.3 (maven)" in html, html)
        assertTrue("platform / Publish @ refs/heads/main" in html, html)
        assertTrue("Pipeline ID" in html && "Repository ID" in html && "Created" in html, html)
        assertFalse("data-log-step=" in html, html)
        val expanded = action("/pipelines/run", "git-pipeline-run.model", "toggleStepLogs", listOf("step"))
        val expandedHtml = Json.parseToJsonElement(expanded.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertEquals("step", actionState(expanded)["expandedStepId"]?.jsonPrimitive?.content)
        assertTrue("&lt;script&gt;log&lt;/script&gt;" in expandedHtml, expandedHtml)
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
        val logs = action("/pipelines/run", "git-pipeline-logs.model", "earlier", emptyList(), sourceHtml = expandedHtml)
        assertEquals(false, actionState(logs)["hasEarlier"]?.jsonPrimitive?.boolean, logs.body())
        assertEquals(
            4,
            requests.last { it.operation == "GitUiLogs" }.variables["before"]?.jsonPrimitive?.int,
        )
    }

    @Test
    fun `failed run shows diagnostics durations and only the first failed steps logs`() {
        val html = get("/pipelines/failed-run").body()
        assertTrue("Build · Run #3" in html, html)
        assertTrue("1h 2m" in html && "1m 5s" in html && "1m 3s" in html, html)
        assertTrue("Cannot publish &lt;artifact&gt;" in html, html)
        assertTrue("Approved &lt;release&gt;" in html, html)
        assertTrue("Build failed before publishing" in html, html)
        assertTrue("release/main" in html && "Concurrency Group" in html, html)
        assertTrue("Rerun job" in html && "Delete run" in html, html)
        assertEquals(listOf("failed-step"), Regex("data-log-step=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList())
        val collapsed = action("/pipelines/failed-run", "git-pipeline-run.model", "toggleStepLogs", listOf("failed-step"))
        val refreshed = continueAction("/pipelines/failed-run", "git-pipeline-run.model:failed-run", "refresh", actionState(collapsed), emptyList())
        val refreshedHtml = Json.parseToJsonElement(refreshed.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertFalse("data-log-step=" in refreshedHtml, refreshedHtml)
        assertTrue("Cannot publish &lt;artifact&gt;" in refreshedHtml, refreshedHtml)
    }

    @Test
    fun `step logs load on demand without reloading history during run polling`() {
        val before = requests.count { it.operation == "GitUiLogs" }
        val html = get("/pipelines/run").body()
        assertFalse("data-log-step=" in html, html)
        assertEquals(before, requests.count { it.operation == "GitUiLogs" })
        val expanded = action("/pipelines/run", "git-pipeline-run.model", "toggleStepLogs", listOf("step"))
        assertEquals(before + 1, requests.count { it.operation == "GitUiLogs" })
        val refreshed = continueAction("/pipelines/run", "git-pipeline-run.model:run", "refresh", actionState(expanded), emptyList())
        assertEquals("step", actionState(refreshed)["expandedStepId"]?.jsonPrimitive?.content)
        assertEquals(before + 1, requests.count { it.operation == "GitUiLogs" })
        val polled = continueAction("/pipelines/run", "git-pipeline-run.model:run", "refresh", actionState(refreshed), emptyList())
        assertEquals("step", actionState(polled)["expandedStepId"]?.jsonPrimitive?.content)
        assertEquals(before + 1, requests.count { it.operation == "GitUiLogs" })
        val polledHtml = Json.parseToJsonElement(polled.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("data-bml-preserve=\"logs-step\"" in polledHtml, polledHtml)
        val expandedHtml = Json.parseToJsonElement(expanded.body()).jsonObject.getValue("html").jsonPrimitive.content
        val logs = action("/pipelines/run", "git-pipeline-logs.model", "refresh", emptyList(), sourceHtml = expandedHtml)
        assertEquals(1, actionState(logs).getValue("lines").jsonArray.size)
        assertEquals(before + 2, requests.count { it.operation == "GitUiLogs" })
        val collapsed = continueAction("/pipelines/run", "git-pipeline-run.model:run", "toggleStepLogs", actionState(polled), listOf("step"))
        val collapsedHtml = Json.parseToJsonElement(collapsed.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertFalse("data-log-step=" in collapsedHtml, collapsedHtml)
        assertTrue("aria-expanded=\"false\"" in collapsedHtml, collapsedHtml)
        assertEquals(before + 2, requests.count { it.operation == "GitUiLogs" })
        val reopened = continueAction("/pipelines/run", "git-pipeline-run.model:run", "toggleStepLogs", actionState(collapsed), listOf("step"))
        assertEquals("step", actionState(reopened)["expandedStepId"]?.jsonPrimitive?.content)
        assertEquals(before + 3, requests.count { it.operation == "GitUiLogs" })
    }

    @Test
    fun `run refresh loads history when it first expands a failed step`() {
        val run = Json.decodeFromString(
            bosca.git.ui.graphql.GitUiRunData.Git.PipelineRun.serializer(),
            requireNotNull(javaClass.getResource("/fixtures/run-failed.json")).readText(),
        ).copy(status = bosca.git.ui.graphql.GitPipelineRunStatus.RUNNING)
        val state = Json.encodeToJsonElement(
            PipelineRunModel.serializer(), PipelineRunModel(run, true, true, true),
        ).jsonObject
        val before = requests.count { it.operation == "GitUiLogs" }
        val refreshed = continueAction("/pipelines/failed-run", "git-pipeline-run.model:failed-run", "refresh", state, emptyList())
        assertEquals("failed-step", actionState(refreshed)["expandedStepId"]?.jsonPrimitive?.content)
        assertEquals(before + 1, requests.count { it.operation == "GitUiLogs" })
        val html = Json.parseToJsonElement(refreshed.body()).jsonObject.getValue("html").jsonPrimitive.content
        assertTrue("&lt;script&gt;log&lt;/script&gt;" in html, html)
        val polled = continueAction("/pipelines/failed-run", "git-pipeline-run.model:failed-run", "refresh", actionState(refreshed), emptyList())
        assertEquals("failed-step", actionState(polled)["expandedStepId"]?.jsonPrimitive?.content)
        assertEquals(before + 1, requests.count { it.operation == "GitUiLogs" })
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
                "git-secret-create.model",
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
        assertTrue("Current Command" in detail.body() && "echo build" in detail.body(), detail.body())
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
    fun `markdown viewer uses one action bar with labelled icon links and view tabs`() {
        val response = get("/repositories/repo?file=README.md")
        assertEquals(200, response.statusCode(), response.body())
        val html = response.body()
        assertTrue("file=README.md&amp;edit=true" in html || "file=README.md&edit=true" in html, html)
        assertTrue(Regex(">\\s*Rename\\s*<").containsMatchIn(html) && Regex(">\\s*Edit\\s*<").containsMatchIn(html), html)
        assertTrue("aria-label=\"Download file\"" in html, html)
        assertTrue("role=\"tablist\"" in html && "aria-label=\"Markdown view\"" in html, html)
        assertEquals(1, Regex("aria-label=\"File actions\"").findAll(html).count())
        assertFalse("Browse ref" in html, html)
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

    @Test
    fun `independent page reads overlap over HTTP`() {
        for ((path, operations) in listOf(
            "/repositories/repo?file=README.md&edit=true" to setOf("GitUiBranches", "GitUiTags", "GitUiProfiles"),
            "/repositories/repo?tab=pipelines" to setOf("GitUiRepository", "GitUiRefs", "GitUiPipelines"),
            "/repositories/repo?tab=commits" to setOf("GitUiBranches", "GitUiTags", "GitUiCommits"),
            "/repositories?owner=owner" to setOf("GitUiProfile", "GitUiRepositories"),
            "/repositories/repo/github" to setOf("GitUiRepository", "GitUiAccess"),
            "/repositories/repo/github" to setOf("GitUiGitHubPair", "GitUiGitHubRefs"),
            "/settings/github" to setOf("GitUiGitHubUsers", "GitUiPrincipals"),
            "/repositories/repo?tab=settings&panel=permissions" to setOf("GitUiPermissions", "GitUiGroups"),
            "/repositories/repo?tab=statuses&sha=abc" to setOf("GitUiStatuses", "GitUiCommits"),
            "/pulls/pull" to setOf("GitUiRepository", "GitUiBranches", "GitUiProfiles", "GitUiPullList", "GitUiRepositories"),
        )) {
            val arrived = CountDownLatch(operations.size)
            val timeouts = AtomicInteger()
            requestProbe = { operation ->
                if (operation in operations) {
                    arrived.countDown()
                    if (!arrived.await(3, TimeUnit.SECONDS)) timeouts.incrementAndGet()
                }
            }
            try {
                val response = get(path)
                assertEquals(200, response.statusCode(), response.body())
                assertEquals(0L, arrived.count, path)
                assertEquals(0, timeouts.get(), "Independent reads were serialized: $path")
            } finally { requestProbe = null }
        }
    }

    @Test
    fun `repository tabs reuse metadata and omit unrelated code and stats reads`() {
        for ((tab, expected) in mapOf(
            "pipelines" to setOf("GitUiRepository", "GitUiRefs", "GitUiPipelines"),
            "pulls" to setOf("GitUiRepository", "GitUiRepositories", "GitUiRepositoryPullList", "GitUiBranches"),
            "settings" to setOf("GitUiRepository"),
            "secrets" to setOf("GitUiRepository", "GitUiSecrets"),
            "statuses" to setOf("GitUiRepository", "GitUiBranches", "GitUiTags", "GitUiCommits"),
        )) {
            val before = requests.size
            val response = get("/repositories/repo?tab=$tab")
            assertEquals(200, response.statusCode(), response.body())
            val operations = requests.drop(before).map { it.operation }
            assertEquals(expected, operations.toSet(), tab)
            assertEquals(expected.size, operations.size, "Duplicate query in $tab: $operations")
        }
    }

    @Test
    fun `repository statistics load privately after the shell`() {
        val before = requests.size
        val shell = get("/repositories/repo")
        assertEquals(200, shell.statusCode(), shell.body())
        assertTrue("component:git-repository-stats:statistics" in shell.body(), shell.body())
        assertTrue(requests.drop(before).none { it.operation == "GitUiStats" })
        val payload = buildJsonObject {
            put("page", "/repositories/{id}")
            put("path", "/repositories/repo")
            put("query", buildJsonObject {})
            put("props", buildJsonObject { put("id", "repo") })
        }.toString()
        val endpoint = "/_bml/deferred/component%3Agit-repository-stats%3Astatistics"
        val denied = post(endpoint, payload, authenticated = false)
        assertEquals(401, denied.statusCode(), denied.body())
        assertTrue(requests.drop(before).none { it.operation == "GitUiStats" })
        val response = post(endpoint, payload)
        assertEquals(200, response.statusCode(), response.body())
        val tiles =
            Regex("<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>(.*?)</dd>", RegexOption.DOT_MATCHES_ALL)
                .findAll(response.body())
                .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
        assertEquals(
            mapOf("Commits" to "2", "Branches" to "2", "Tags" to "1", "Contributors" to "1", "Size" to "1.0 KB"),
            tiles,
            response.body(),
        )
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("Bearer ui-test-token", requests.last { it.operation == "GitUiStats" }.authorization)
    }

    @Test
    fun `global pipeline catalog avoids a metadata request per repository`() {
        val before = requests.size
        val response = get("/pipelines")
        assertEquals(200, response.statusCode(), response.body())
        val operations = requests.drop(before).map { it.operation }
        assertEquals(2, operations.count { it == "GitUiPipelines" })
        assertEquals(0, operations.count { it == "GitUiRepository" || it == "GitUiProfile" })
    }

    @Test
    fun `missing repository stays a not found response when parallel reads fail`() {
        for (suffix in listOf("", "?tab=pipelines", "?tab=commits")) {
            assertEquals(404, get("/repositories/missing$suffix").statusCode(), suffix)
        }
    }

    @Test
    fun `all data page routes avoid duplicate GraphQL reads`() {
        for (path in graphqlPageAuditPaths) {
            val before = requests.size
            val response = get(path)
            assertEquals(200, response.statusCode(), path + response.body())
            val reads = requests.drop(before)
            assertTrue(reads.isNotEmpty(), path)
            val duplicates = reads.groupBy { it.operation to it.variables }.filterValues { it.size > 1 }
            assertTrue(duplicates.isEmpty(), "Repeated GraphQL read in $path: ${duplicates.keys}")
        }
    }

    @Test
    fun `markdown preview reuses ref lookup including an unborn default branch`() {
        for ((id, expected) in listOf("repo" to setOf("GitUiRepository", "GitUiRefs", "GitUiCommit"), "empty" to setOf("GitUiRepository", "GitUiRefs"))) {
            val before = requests.size
            val args = JsonArray(listOf(id, "main", "README.md", "# Draft").map(::JsonPrimitive)).toString()
            val response = post("/_bml/contract/GitMarkdownPreview/render", args)
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("Draft" in response.body(), response.body())
            val operations = requests.drop(before).map { it.operation }
            assertEquals(expected, operations.toSet())
            assertEquals(expected.size, operations.size)
        }
    }

    @Test
    fun `all owners listing skips profile reads`() {
        val before = requests.size
        assertEquals(200, get("/repositories?owner=all").statusCode())
        assertEquals(listOf("GitUiRepositories"), requests.drop(before).map { it.operation })
    }

    @Test
    fun `read only browsing does not fetch commit author profiles`() {
        val before = requests.size
        assertEquals(200, get("/repositories/repo?file=README.md").statusCode())
        assertTrue(requests.drop(before).none { it.operation == "GitUiProfiles" })
    }

    @Test
    fun `search typing reuses choices and repository changes refresh them`() {
        val path = "/search?repo=repo&q=snippet"
        val shell = get(path)
        var before = requests.size
        val typed = action(path, "git-search.model:search", "search", listOf("repo", "new query", "content", "main"), sourceHtml = shell.body())
        assertEquals(false, actionState(typed)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(listOf("GitUiSearchContent"), requests.drop(before).map { it.operation })
        before = requests.size
        val changed = action(path, "git-search.model:search", "search", listOf("empty", "new query", "content", "main"), sourceHtml = shell.body())
        assertEquals(false, actionState(changed)["failed"]?.jsonPrimitive?.boolean)
        assertEquals(listOf("GitUiRepositories", "GitUiRefs"), requests.drop(before).map { it.operation })
    }

    @Test
    fun `pipeline lists request only the visible recent runs`() {
        for ((path, limit, details) in listOf(Triple("/pipelines", 1, false), Triple("/repositories/repo?tab=pipelines", 5, true))) {
            val before = requests.size
            assertEquals(200, get(path).statusCode())
            val queries = requests.drop(before).filter { it.operation == "GitUiPipelines" }
            assertTrue(queries.isNotEmpty())
            for (request in queries) {
                assertEquals(limit, request.variables["runLimit"]?.jsonPrimitive?.int)
                assertEquals(details, request.variables["jobDetails"]?.jsonPrimitive?.boolean)
            }
        }
    }

    @Test
    fun `selected repository pipeline pages skip the unused owner repository listing`() {
        for (owner in listOf("", "&owner=owner", "&owner=all")) {
            val before = requests.size
            val response = get("/pipelines?repo=repo$owner")
            assertEquals(200, response.statusCode(), response.body())
            assertTrue(requests.drop(before).none { it.operation == "GitUiRepositories" })
        }
    }

    @Test
    fun `pipeline history and older runs load only on demand`() {
        val path = "/repositories/repo?tab=pipelines"
        val before = requests.size
        val shell = get(path)
        assertTrue(requests.drop(before).none { it.operation == "GitUiPipelineRuns" })
        assertTrue("Run History" in shell.body())
        var start = requests.size
        val history = action(path, "git-pipeline-history.model:repo", "load", listOf(0), sourceHtml = shell.body())
        assertEquals(true, actionState(history)["loaded"]?.jsonPrimitive?.boolean, history.body())
        assertEquals(listOf("GitUiPipelineRuns"), requests.drop(start).map { it.operation })
        start = requests.size
        val next = continueAction(path, "git-pipeline-history.model:repo", "load", actionState(history), listOf(50))
        assertEquals(50L, actionState(next)["offset"]?.jsonPrimitive?.long, next.body())
        assertEquals(listOf("GitUiPipelineRuns"), requests.drop(start).map { it.operation })
        val failed = continueAction(path, "git-pipeline-history.model:repo", "load", actionState(history), listOf(100))
        assertTrue(actionState(failed)["message"]?.jsonPrimitive?.content.orEmpty().contains("History unavailable"), failed.body())
        assertEquals(0L, actionState(failed)["offset"]?.jsonPrimitive?.long)
        assertEquals(50, actionState(failed)["runs"]?.jsonArray?.size)
        val retried = continueAction(path, "git-pipeline-history.model:repo", "load", actionState(failed), listOf(50))
        assertEquals("", actionState(retried)["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `agent detail and refresh select only the requested agent`() {
        val path = "/ci-agents/agent"
        var before = requests.size
        val shell = get(path)
        assertEquals("agent", requests.drop(before).single { it.operation == "GitUiAgents" }.variables["id"]?.jsonPrimitive?.content)
        before = requests.size
        val response = action(path, "git-agent-status.model:agent", "refresh", emptyList(), sourceHtml = shell.body())
        assertEquals("", actionState(response)["message"]?.jsonPrimitive?.content, response.body())
        assertEquals("agent", requests.drop(before).single().variables["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `agent lists omit job details and detail views retain them`() {
        for ((path, expected) in listOf("/ci-agents" to false, "/ci-agents/agent" to true)) {
            val before = requests.size
            assertEquals(200, get(path).statusCode())
            assertEquals(expected, requests.drop(before).single { it.operation == "GitUiAgents" }.variables["details"]?.jsonPrimitive?.boolean)
        }
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
        sourceHtml: String? = null,
    ): HttpResponse<String> {
        val html = sourceHtml ?: get(sourcePath, token = token).body()
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
        private val upstreamExecutor = Executors.newVirtualThreadPerTaskExecutor()
        @Volatile private var requestProbe: ((String) -> Unit)? = null
        private lateinit var engine: NettyServerEngine
        private lateinit var subscriptionEngine: NettyServerEngine

        @JvmStatic fun browserFixturePort(): Int = port
        @JvmStatic fun browserFixtureApiPort(): Int = upstream.address.port

        @JvmStatic
        @BeforeClass
        fun start() {
            upstream = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            upstream.executor = upstreamExecutor
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
                requestProbe?.invoke(operation)
                requests.add(
                    ApiRequest(
                        operation,
                        variables,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("Origin"),
                    )
                )
                val id = variables["id"]?.jsonPrimitive?.contentOrNull
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
                        operation == "GitUiPull" && id == "pull-edit" ->
                            """{"data":{"git":{"pullRequest":$editedPull}}}"""
                        operation == "GitUiPull" && id == "pull-replies" ->
                            """{"data":{"git":{"pullRequest":$replyPull}}}"""
                        operation == "GitUiPullDiffPage" -> {
                            val number = variables.getValue("number").jsonPrimitive.int
                            val current = when (number) {
                                7 -> editedPull
                                10 -> replyPull
                                else -> Json.parseToJsonElement(pullStateFixture(pull, number)).jsonObject
                            }
                            val result = if (variables["offset"]?.jsonPrimitive?.int == 0 || number == 3) current
                                else JsonObject(current + ("version" to JsonPrimitive(current.getValue("version").jsonPrimitive.long + 1)))
                            """{"data":{"git":{"pullRequest":$result}}}"""
                        }
                        operation == "GitUiPull" -> {
                            val result = (1..12).asSequence().map { pullStateFixture(pull, it) }
                                .firstOrNull { Json.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content == id }
                            """{"data":{"git":{"pullRequest":$result}}}"""
                        }
                        operation == "GitUiUpdatePull" && variables["input"]?.jsonObject?.get("title")?.jsonPrimitive?.content == "Reject edit" ->
                            """{"errors":[{"message":"Rejected pull request edit"}]}"""
                        operation == "GitUiUpdatePull" -> {
                            if (id == "pull-edit") {
                                val changes = variables.getValue("input").jsonObject
                                val current = editedPull
                                val version = current.getValue("version").jsonPrimitive.long
                                if (changes["expectedVersion"]?.jsonPrimitive?.long != version) {
                                    """{"errors":[{"message":"Pull request changed. Reload it before continuing."}]}"""
                                } else {
                                    editedPull = JsonObject(current + changes.filterKeys { it != "expectedVersion" } + ("version" to JsonPrimitive(version + 1)))
                                    """{"data":{"git":{"updatePullRequest":{"id":"pull-edit","version":${version + 1}}}}}"""
                                }
                            } else """{"data":{"git":{"updatePullRequest":{"id":"pull","version":1}}}}"""
                        }
                        operation == "GitUiCreatePull" ->
                            """{"errors":[{"message":"Rejected pull request"}]}"""
                        operation == "GitUiReview" && variables["input"]?.jsonObject?.get("body")?.jsonPrimitive?.content == "Review accepted" ->
                            """{"data":{"git":{"submitReview":{"id":"review"}}}}"""
                        operation == "GitUiReview" ->
                            """{"errors":[{"message":"Review rejected"}]}"""
                        operation == "GitUiReviewComment" && variables["input"]?.jsonObject?.get("content")?.jsonPrimitive?.content == "Reject comment" ->
                            """{"errors":[{"message":"Comment rejected"}]}"""
                        operation == "GitUiReviewComment" ->
                            """{"data":{"git":{"addReviewComment":{"id":"comment"}}}}"""
                        operation == "GitUiReplyComment" && variables["content"]?.jsonPrimitive?.content == "Reject reply" ->
                            """{"errors":[{"message":"Reply rejected"}]}"""
                        operation == "GitUiReplyComment" -> {
                            if (id == "pull-replies") {
                                val current = replyPull
                                val review = current.getValue("reviews").jsonArray.single().jsonObject
                                val comments = review.getValue("comments").jsonArray
                                val original = comments.firstOrNull { it.jsonObject.getValue("id") == variables["comment"] }?.jsonObject
                                if (original == null || variables["version"] != current["version"]) {
                                    """{"errors":[{"message":"Pull request changed. Reload it before continuing."}]}"""
                                } else {
                                    val now = java.time.OffsetDateTime.now().toString()
                                    val reply = JsonObject(original + mapOf(
                                        "id" to JsonPrimitive(java.util.UUID.randomUUID().toString()),
                                        "authorId" to JsonPrimitive("owner"),
                                        "content" to variables.getValue("content"),
                                        "resolved" to JsonPrimitive(false),
                                        "created" to JsonPrimitive(now), "updated" to JsonPrimitive(now),
                                    ))
                                    val updated = JsonObject(review + ("comments" to JsonArray(comments + reply)))
                                    replyPull = JsonObject(current + ("reviews" to JsonArray(listOf(updated))))
                                    """{"data":{"git":{"replyReviewComment":{"id":${reply.getValue("id")}}}}}"""
                                }
                            } else """{"data":{"git":{"replyReviewComment":{"id":"response"}}}}"""
                        }
                        operation == "GitUiResolveThread" && variables["file"]?.jsonPrimitive?.content == "fail" ->
                            """{"errors":[{"message":"Thread resolution rejected"}]}"""
                        operation == "GitUiResolveThread" && id == "pull-replies" -> {
                            val review = replyPull.getValue("reviews").jsonArray.single().jsonObject
                            val comments = review.getValue("comments").jsonArray.map { value ->
                                val comment = value.jsonObject
                                if (comment["filePath"] == variables["file"] &&
                                    (comment["newLineNumber"] == variables["line"] || comment["oldLineNumber"] == variables["line"]))
                                    JsonObject(comment + ("resolved" to JsonPrimitive(true))) else comment
                            }
                            val updated = JsonObject(review + ("comments" to JsonArray(comments)))
                            replyPull = JsonObject(replyPull + ("reviews" to JsonArray(listOf(updated))))
                            """{"data":{"git":{"resolveReviewThread":true}}}"""
                        }
                        operation == "GitUiResolveThread" ->
                            """{"data":{"git":{"resolveReviewThread":true}}}"""
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
                        operation == "GitUiPipelineNames" ->
                            """{"data":{"git":{"pipelines":[{"id":"pipeline","name":"Build"}]}}}"""
                        operation == "GitUiPipelineTriggers" ->
                            """{"data":{"git":{"pipelines":[{"id":"pipeline","triggerTypes":["MANUAL"]}]}}}"""
                        operation == "GitUiPipelines" ->
                            """{"data":{"git":{"pipelines":[{"id":"pipeline","repositoryId":"repo","filePath":".bosca/pipelines/build.yml","name":"Build","updated":"2026-10-09T00:00:00Z","triggerTypes":["MANUAL"],"runs":[$run]}]}}}"""
                        operation == "GitUiPipelineRuns" && variables["offset"]?.jsonPrimitive?.long == 100L ->
                            """{"errors":[{"message":"History unavailable"}]}"""
                        operation == "GitUiPipelineRuns" -> {
                            val runs = if (variables["offset"]?.jsonPrimitive?.long == 0L) List(50) { run }.joinToString(",") else ""
                            """{"data":{"git":{"pipelineRuns":[$runs]}}}"""
                        }
                        operation == "GitUiPipelineInputs" ->
                            """{"data":{"git":{"pipelineInputs":[{"name":"version","type":"STRING","defaultValue":null,"description":"Version","options":[],"required":true}]}}}"""
                        operation == "GitUiRun" && id == "failed-run" ->
                            """{"data":{"git":{"pipelineRun":${fixture("run-failed")}}}}"""
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
                        operation == "GitUiAgents" -> {
                            val selected = if (id == null) agents else JsonArray(Json.parseToJsonElement(agents).jsonArray.filter { it.jsonObject["id"]?.jsonPrimitive?.content == id }).toString()
                            """{"data":{"git":{"pipelineAgents":$selected}}}"""
                        }
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
                        operation in setOf("GitUiProfiles", "GitUiOwnerProfiles") && token == "bootstrap-token" ->
                            """{"data":{"profiles":{"current":[{"id":"admin-profile","name":"admin","slug":null,"isPrimary":true,"attributes":[]}]}}}"""
                        operation in setOf("GitUiProfiles", "GitUiOwnerProfiles") ->
                            """{"data":{"profiles":{"current":[$profile]}}}"""
                        operation == "GitUiProfile" ->
                            """{"data":{"profiles":{"profile":${if (id == "other") profile.replace("\"id\":\"owner\"", "\"id\":\"other\"").replace("Alice", "Bob").replace("alice", "bob") else profile}}}}"""
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
                        operation == "GitUiRefs" && id == "missing" ->
                            """{"errors":[{"message":"Repository not found"}]}"""
                        operation == "GitUiRefs" && id == "empty" ->
                            """{"data":{"git":{"branches":[],"tags":[]}}}"""
                        operation == "GitUiRefs" ->
                            """{"data":{"git":{"branches":[{"name":"main","sha":"abc"},{"name":"feature/one","sha":"abc"}],"tags":[{"name":"v1","sha":"abc","targetSha":null,"isAnnotated":false,"message":null}]}}}"""
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
                        operation == "GitUiTree" && id == "ordering" -> {
                            val prefix = path?.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
                            """{"data":{"git":{"tree":[{"name":"Zebra.txt","path":"${prefix}Zebra.txt","type":"BLOB","sha":"abc","size":12},{"name":"Gamma","path":"${prefix}Gamma","type":"TREE","sha":"abc","size":0},{"name":"apple.txt","path":"${prefix}apple.txt","type":"BLOB","sha":"abc","size":12},{"name":"beta","path":"${prefix}beta","type":"TREE","sha":"abc","size":0}]}}}"""
                        }
                        operation == "GitUiTree" && path == "docs" ->
                            """{"data":{"git":{"tree":[{"name":"a # b.txt","path":"docs/a # b.txt","type":"BLOB","sha":"abc","size":12},{"name":"guide.md","path":"docs/guide.md","type":"BLOB","sha":"abc","size":12}]}}}"""
                        operation == "GitUiTree" ->
                            """{"data":{"git":{"tree":[{"name":"docs","path":"docs","type":"TREE","sha":"abc","size":0},{"name":"README.md","path":"README.md","type":"BLOB","sha":"abc","size":34},{"name":"a # b.txt","path":"docs/a # b.txt","type":"BLOB","sha":"abc","size":12}]}}}"""
                        operation == "GitUiBlob" && path == CONTEXT_FILE ->
                            buildJsonObject {
                                put("data", buildJsonObject {
                                    put("git", buildJsonObject {
                                        put("blob", buildJsonObject {
                                            val content = contextFileLines.joinToString("\n", postfix = "\n")
                                            put("content", content)
                                            put("size", content.length)
                                            put("sha", "context")
                                            put("isBinary", false)
                                        })
                                    })
                                })
                            }.toString()
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
                        operation == "GitUiCreateTag" && variables["name"]?.jsonPrimitive?.content == "v1" ->
                            """{"errors":[{"message":"Tag 'v1' already exists"}]}"""
                        operation == "GitUiCreateTag" && variables["source"]?.jsonPrimitive?.content == "missing" ->
                            """{"errors":[{"message":"Target ref 'missing' not found"}]}"""
                        operation == "GitUiCreateTag" ->
                            """{"data":{"git":{"createTag":{"name":${variables.getValue("name")},"sha":"tag-sha","targetSha":"abc"}}}}"""
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
            app.router.post("/_fixture/replies/reset") {
                replyPull = Json.parseToJsonElement(pullStateFixture(pull, 10)).jsonObject
                call.respond("reset")
            }
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
            upstreamExecutor.close()
        }

        private fun fixture(name: String): String =
            requireNotNull(GitUiRoutesTest::class.java.getResourceAsStream("/fixtures/$name.json"))
                .use { it.readAllBytes().decodeToString() }

        private val agents = fixture("agents")
        private val pair = fixture("pair")
        private val refs = fixture("refs")
        private val orchestrator = fixture("orchestrator")
        private val pull = fixture("pull")
        @Volatile private var editedPull = Json.parseToJsonElement(pullStateFixture(pull, 7)).jsonObject
        @Volatile private var replyPull = Json.parseToJsonElement(pullStateFixture(pull, 10)).jsonObject
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
            """{"id":"repo","slug":"repo","ownerId":"owner","contentType":"GENERAL","diskSizeBytes":1024,"updated":"2026-10-09T00:00:00Z","configuration":{"deleteBranchOnMerge":true},"canEdit":true,"canManage":true,"canExecute":true,"name":"Active project","description":"<script>untrusted</script>","defaultBranch":"main","archived":false,"deleted":false,"visibility":"PRIVATE","cloneUrl":"https://git.example.test/owner/repo.git"}"""
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
