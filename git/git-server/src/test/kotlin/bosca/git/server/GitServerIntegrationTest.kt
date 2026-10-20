package bosca.git.server

import bosca.bml.graphql.GraphQLClientFactory
import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Exercises both production listeners in one process with isolated PostgreSQL and NATS. */
class GitServerIntegrationTest {
    private fun JsonElement.at(vararg fields: String): JsonElement =
        fields.fold(this) { value, field -> value.jsonObject.getValue(field) }

    private fun verifySharedShell(apiOrigin: String, token: String, cookiePrefix: String) {
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        val shared = object : BmlPageRenderer {
            override val route = "/shared-review"
            override val sharedCacheMaxAgeSeconds = 60L
            override suspend fun render(ctx: RenderContext) {
                assertEquals(null, ctx.token)
                assertTrue(ctx.cookies.isEmpty())
                val repositories = ctx.gql.execute("{ git { repositories { id } } }")
                    .at("git", "repositories").jsonArray
                ctx.writer.markup("<html><body>visible=${repositories.size}</body></html>")
            }
        }
        BmlServer(CompiledProject("git-http-isolation", "1"), listOf(shared),
            graphqlEndpoint = "$apiOrigin/graphql", dev = false).install(application)
        val port = ServerSocket(0).use { it.localPort }
        val engine = NettyServerEngine(application, port)
        thread(isDaemon = true) { engine.start() }
        try {
            val http = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/shared-review"))
                .header("Authorization", "Bearer $token").header("Cookie", "$cookiePrefix=$token")
                .timeout(Duration.ofSeconds(10)).build()
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var response: HttpResponse<String>? = null
            while (response == null && System.nanoTime() < deadline) {
                response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                if (response == null) Thread.sleep(25)
            }
            val rendered = requireNotNull(response) { "Shared BML probe did not start" }
            assertEquals(200, rendered.statusCode(), rendered.body())
            assertTrue("visible=0" in rendered.body(), rendered.body())
            assertEquals("public, max-age=60, stale-while-revalidate=60",
                rendered.headers().firstValue("Cloudflare-CDN-Cache-Control").orElse(""))
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP API authenticates browser SSR actions file delivery and subscription relay`() = verify("_bat")

    @Test
    fun `custom cookie prefix works through UI and API listeners`() = verify("_git_review")

    private fun verify(cookiePrefix: String) {
        val postgres = SharedPostgreSQLContainer().withDatabaseName("git_http_ui")
        val nats = SharedNatsContainer()
        val directory = kotlin.io.path.createTempDirectory("git-http-ui").toFile()
        val storageDirectory = File(directory, "storage")
        var process: Process? = null
        var apiPort: Int? = null
        var uiListenerPort: Int? = null
        try {
            postgres.start()
            nats.start()
            val port = ServerSocket(0).use { it.localPort }
            apiPort = port
            val apiOrigin = "http://localhost:$port"
            var uiPort = ServerSocket(0).use { it.localPort }
            while (uiPort == port) uiPort = ServerSocket(0).use { it.localPort }
            uiListenerPort = uiPort
            val origin = "http://localhost:$uiPort"
            val config = File(directory, "application.yaml")
            val template =
                File(
                        requireNotNull(System.getProperty("workspaceRoot")),
                        "git/git-server/src/main/resources/application.yaml",
                    )
                    .readText()
            val storageType = "    type: \"\$STORAGE_TYPE:s3\""
            require(storageType in template) { "Missing content storage configuration in Git server template." }
            val isolatedTemplate = template.replace(
                storageType,
                "    type: fs\n    basePath: ${JsonPrimitive(storageDirectory.absolutePath)}",
            )
            val cookieConfig = if (cookiePrefix == "_bat") isolatedTemplate else isolatedTemplate.replace(
                "cookie-prefixes: []", "cookie-prefixes:\n    - prefix: $cookiePrefix\n      domains: [localhost]"
            )
            config.writeText(
                cookieConfig +
                    "\ninitialization:\n  password:\n    admin: local-admin-password\n    sa: local-sa-password\n"
            )
            val log = File(directory, "server.log")
            val executable = System.getenv("GIT_SERVER_TEST_BINARY")?.takeIf(String::isNotBlank)
            val command =
                if (executable == null) {
                    listOf(
                        File(System.getProperty("java.home"), "bin/java").absolutePath,
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        requireNotNull(System.getProperty("apiServerClasspath")),
                        "bosca.git.server.ApplicationKt",
                        "-config=${config.absolutePath}",
                    )
                } else {
                    listOf(executable, "-config=${config.absolutePath}")
                }
            process =
                ProcessBuilder(command)
                    .apply {
                        environment()
                            .putAll(
                                mapOf(
                                    "GIT_HTTP_PORT" to port.toString(),
                                    "GIT_UI_HTTP_PORT" to uiPort.toString(),
                                    "BML_AUTH_COOKIE_PREFIX" to cookiePrefix,
                                    "BML_GRAPHQL_ENDPOINT" to "http://127.0.0.1:1/graphql",
                                    "GIT_DEVELOPMENT" to "true",
                                    "DATABASE_URL" to postgres.jdbcUrl,
                                    "DATABASE_USER" to postgres.username,
                                    "DATABASE_PASSWORD" to postgres.password,
                                    "NATS_HOST" to nats.url.removePrefix("nats://"),
                                    "NATS_USERNAME" to nats.username,
                                    "NATS_PASSWORD" to nats.password,
                                    "NATS_TOKEN" to "",
                                    "APP_URL" to origin,
                                    "GIT_URL" to apiOrigin,
                                    "GIT_UI_URL" to origin,
                                    "GRAPHQL_INTROSPECTION_ENABLED" to "true",
                                    "SECURITY_AUTO_VERIFY" to "true",
                                    "APP_ALLOWED_ORIGIN" to origin,
                                    "WELCOME_EMAIL_URL" to "$origin/repositories",
                                    "OAUTH2_ALLOWED_REDIRECT" to origin,
                                )
                            )
                        redirectErrorStream(true)
                        redirectOutput(log)
                    }
                    .start()
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            fun request(
                path: String,
                body: String? = null,
                token: String? = null,
                cookie: String? = null,
                base: String = origin,
            ): HttpResponse<String> {
                val builder =
                    HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Origin", origin)
                token?.let { builder.header("Authorization", "Bearer $it") }
                cookie?.let { builder.header("Cookie", it) }
                body?.let {
                    builder
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(it))
                }
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
            var ready = false
            while (process.isAlive && System.nanoTime() < deadline) {
                ready =
                    runCatching { request("/api/v1/ready", base = apiOrigin).statusCode() == 200 }.getOrDefault(false)
                if (ready) break
                Thread.sleep(250)
            }
            assertTrue(
                ready,
                "Git server failed to start. Log: ${log.absolutePath}\n${log.readText().takeLast(12_000)}",
            )
            val uiDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            var uiReady = false
            while (process.isAlive && System.nanoTime() < uiDeadline) {
                uiReady = runCatching { request("/api/v1/ready").statusCode() == 200 }.getOrDefault(false)
                if (uiReady) break
                Thread.sleep(100)
            }
            assertTrue(uiReady, "UI server failed to start. ${log.readText().takeLast(12_000)}")
            assertEquals(404, request("/login", base = apiOrigin).statusCode())
            assertEquals(404, request("/_bml/app.css", base = apiOrigin).statusCode())
            fun graphql(
                query: String,
                variables: JsonObject = JsonObject(emptyMap()),
                token: String? = null,
            ): JsonObject {
                val response =
                    request(
                        "/graphql",
                        buildJsonObject {
                            put("query", query)
                            put("variables", variables)
                        }
                            .toString(),
                        token,
                    )
                assertEquals(200, response.statusCode(), response.body())
                val result = Json.parseToJsonElement(response.body()).jsonObject
                assertFalse(result.containsKey("errors"), result.toString())
                return result.getValue("data").jsonObject
            }
            val loginPage = request("/login")
            assertEquals(200, loginPage.statusCode())
            val designStyles = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(loginPage.body())
                .map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it.startsWith("/_bml/libraries/") && it.substringBefore('?').endsWith(".css") }
                .map { request(it).also { response -> assertEquals(200, response.statusCode()) }.body() }
                .joinToString("\n")
            assertFalse("/_git/" in designStyles)
            val designFonts =
                Regex("""url\(["']?(/_bml/libraries/[^)"']+\.woff2)["']?\)""")
                    .findAll(designStyles)
                    .map { it.groupValues[1] }
                    .toList()
            assertEquals(11, designFonts.size)
            assertEquals(designFonts.distinct(), designFonts)
            designFonts.forEach { path ->
                val font = request(path)
                assertEquals(200, font.statusCode(), path)
                assertEquals("font/woff2", font.headers().firstValue("Content-Type").orElse(""))
                assertTrue(font.body().startsWith("wOF2"), path)
            }
            val signup =
                graphql(
                    """
                    mutation {
                      security {
                        signup {
                          passwordV2(
                            identifier: "local@example.test"
                            password: "local-test-password"
                            profile: { name: "Local User", visibility: PUBLIC, attributes: [] }
                          ) {
                            principal {
                              id
                            }
                          }
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val principalId =
                signup
                    .at("security", "signup", "passwordV2", "principal", "id")
                    .jsonPrimitive
                    .content
            val login =
                graphql(
                    """
                    mutation {
                      security {
                        login {
                          password(
                            identifier: "local@example.test"
                            password: "local-test-password"
                          ) {
                            token {
                              token
                            }
                          }
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val token =
                login.at("security", "login", "password", "token", "token").jsonPrimitive.content
            val identity =
                graphql(
                    "{ security { principals { current { id groups { id type } } } } profiles { current { id name slug attributes { typeId attributes } } } }",
                    token = token,
                )
            assertEquals(
                principalId,
                identity.at("security", "principals", "current", "id").jsonPrimitive.content,
            )
            val ownerId =
                identity
                    .at("profiles", "current")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content
            val adminLogin =
                graphql(
                    """
                    mutation {
                      security {
                        login {
                          password(identifier: "admin", password: "local-admin-password") {
                            token {
                              token
                            }
                          }
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val adminToken =
                adminLogin
                    .at("security", "login", "password", "token", "token")
                    .jsonPrimitive
                    .content
            val created =
                graphql(
                    """
                    mutation Create(${'$'}owner: UUID!) {
                      git {
                        createRepository(
                          input: {
                            name: "Local Repository"
                            slug: "local-repository"
                            ownerId: ${'$'}owner
                            initializeWithReadme: true
                          }
                        ) {
                          id
                        }
                      }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject { put("owner", ownerId) },
                    adminToken,
                )
            val repositoryId = created.at("git", "createRepository", "id").jsonPrimitive.content
            assertTrue(
                storageDirectory.walkTopDown().any { it.isFile && it.length() > 0 },
                "Repository initialization must persist objects in this test's isolated storage",
            )
            val apiToken = graphql(
                "mutation { security { apiTokens { create(input: { name: \"Read-only review\", scopes: [\"git:read\"] }) { rawToken } } } }",
                token = adminToken,
            ).at("security", "apiTokens", "create", "rawToken").jsonPrimitive.content
            runBlocking {
                val client = GraphQLClientFactory("$apiOrigin/graphql").forToken(adminToken)
                val query = "{ git { repositoryById(id: \"$repositoryId\") { canEdit canManage } } }"
                assertTrue(client.execute(query).at("git", "repositoryById", "canEdit").jsonPrimitive.boolean)
                val restricted = client.execute(query, token = apiToken).at("git", "repositoryById")
                assertFalse(restricted.at("canEdit").jsonPrimitive.boolean)
                assertFalse(restricted.at("canManage").jsonPrimitive.boolean)
                assertTrue(client.execute(query).at("git", "repositoryById", "canEdit").jsonPrimitive.boolean)
            }
            verifySharedShell(apiOrigin, adminToken, cookiePrefix)
            val ownerGroup =
                identity
                    .at("security", "principals", "current", "groups")
                    .jsonArray
                    .first { it.jsonObject.getValue("type").jsonPrimitive.content == "PRINCIPAL" }
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content
            graphql(
                """
                mutation GrantView(${'$'}repository: String!, ${'$'}group: String!) {
                                    git { addPermission(permission: {entityId: ${'$'}repository, groupId: ${'$'}group, action: VIEW}) }
                                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("repository", repositoryId)
                    put("group", ownerGroup)
                },
                adminToken,
            )
            val repository = graphql(
                "query Read(${'$'}id: UUID!) { git { repositoryById(id: ${'$'}id) { cloneUrl } } }",
                buildJsonObject { put("id", repositoryId) }, adminToken,
            ).at("git", "repositoryById")
            val clonePath = URI(repository.at("cloneUrl").jsonPrimitive.content).rawPath
            val rawPath = clonePath.removeSuffix(".git") + "/raw/main/README.md"
            val raw = request(rawPath, cookie = "$cookiePrefix=$token")
            assertEquals(200, raw.statusCode(), raw.body())
            assertTrue("Local Repository" in raw.body(), raw.body())
            assertEquals("private, no-store", raw.headers().firstValue("Cache-Control").orElse(""))
            val denied = request(rawPath)
            val upstreamDenied = request(rawPath, base = apiOrigin)
            assertEquals(400, upstreamDenied.statusCode(), upstreamDenied.body())
            assertEquals(upstreamDenied.statusCode(), denied.statusCode(), denied.body())
            assertEquals(upstreamDenied.body(), denied.body())
            assertFalse("Local Repository" in denied.body(), denied.body())
            assertEquals(401, request(rawPath, token = "invalid-token", cookie = "$cookiePrefix=$token").statusCode())
            val cloneBrowser = request(clonePath, base = apiOrigin)
            assertEquals(302, cloneBrowser.statusCode(), cloneBrowser.body())
            assertEquals("$origin/repositories/$repositoryId", cloneBrowser.headers().firstValue("Location").orElse(""))
            val page = request("/repositories/$repositoryId", cookie = "$cookiePrefix=$token")
            assertEquals(200, page.statusCode(), page.body())
            assertTrue("Local Repository" in page.body(), page.body())
            assertTrue("tree-list" in page.body() && "file=README.md" in page.body(), page.body())
            val filePage =
                request("/repositories/$repositoryId?file=README.md", cookie = "$cookiePrefix=$token")
            assertEquals(200, filePage.statusCode(), filePage.body())
            assertTrue("markdown-file" in filePage.body(), filePage.body())
            val list = request("/repositories", cookie = "$cookiePrefix=$token")
            assertEquals(200, list.statusCode(), list.body())
            val marker =
                Regex(
                        "<script type=\"application/json\" data-bml-state-key=\"(git-repository-row\\.model:[^\"]*)\"[^>]*>(.*?)</script>",
                        RegexOption.DOT_MATCHES_ALL,
                    )
                    .find(list.body()) ?: error("Missing repository action state: ${list.body()}")
            val archived =
                request(
                    "/_bml/action",
                    buildJsonObject {
                        put("page", "/repositories")
                        put("path", "/repositories")
                        put("stateKey", marker.groupValues[1])
                        put("state", marker.groupValues[2])
                        put("method", "lifecycle")
                        put(
                            "args",
                            buildJsonArray {
                                add("archive")
                                add(true)
                            },
                        )
                    }
                        .toString(),
                    cookie = "$cookiePrefix=$token",
                )
            assertEquals(200, archived.statusCode(), archived.body())
            assertFalse(
                Json.parseToJsonElement(archived.body())
                    .jsonObject
                    .getValue("state")
                    .jsonPrimitive
                    .content
                    .let {
                        Json.parseToJsonElement(it)
                            .jsonObject
                            .getValue("failed")
                            .jsonPrimitive
                            .boolean
                    },
                archived.body(),
            )
            val archivedRepository =
                graphql(
                    """
                    query Read(${'$'}id: UUID!) {
                      git {
                        repositoryById(id: ${'$'}id) {
                          archived
                        }
                      }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject { put("id", repositoryId) },
                    token,
                )
            assertTrue(
                archivedRepository.at("git", "repositoryById", "archived").jsonPrimitive.boolean
            )
            val emptyCreated =
                graphql(
                    """
                    mutation CreateEmpty(${'$'}owner: UUID!) {
                      git {
                        createRepository(input: {
                          name: "Empty Repository"
                          slug: "empty-repository"
                          ownerId: ${'$'}owner
                          defaultBranch: "trunk"
                          initializeWithReadme: false
                        }) { id }
                      }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject { put("owner", ownerId) },
                    adminToken,
                )
            val emptyId = emptyCreated.at("git", "createRepository", "id").jsonPrimitive.content
            graphql(
                """
                mutation GrantEmptyView(${'$'}repository: String!, ${'$'}group: String!) {
                  git { addPermission(permission: {entityId: ${'$'}repository, groupId: ${'$'}group, action: VIEW}) }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("repository", emptyId)
                    put("group", ownerGroup)
                },
                adminToken,
            )
            val emptyPage = request("/repositories/$emptyId", cookie = "$cookiePrefix=$token")
            assertEquals(200, emptyPage.statusCode(), emptyPage.body())
            assertTrue("Empty Repository" in emptyPage.body(), emptyPage.body())
            assertTrue("Quick setup" in emptyPage.body(), emptyPage.body())
            // Empty repositories show quick setup instead of statistics; the private endpoint still serves them.
            val statsProps = buildJsonObject { put("id", emptyId) }.toString()
            val statsRequest = buildJsonObject {
                put("page", "/repositories/{id}")
                put("path", "/repositories/$emptyId")
                put("query", buildJsonObject {})
                put("props", Json.parseToJsonElement(statsProps))
            }.toString()
            val statsEndpoint = "/_bml/deferred/component%3Agit-repository-stats%3Astatistics"
            val deniedStats = request(statsEndpoint, statsRequest)
            assertEquals(401, deniedStats.statusCode(), deniedStats.body())
            val emptyStats = request(statsEndpoint, statsRequest, cookie = "$cookiePrefix=$token")
            assertEquals(200, emptyStats.statusCode(), emptyStats.body())
            assertEquals("private, no-store", emptyStats.headers().firstValue("Cache-Control").orElse(""))
            val emptyTiles =
                Regex("<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>(.*?)</dd>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(emptyStats.body())
                    .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
            assertEquals(
                listOf("0", "0", "0", "0"),
                listOf("Commits", "Branches", "Tags", "Contributors").map { emptyTiles[it] },
                emptyStats.body(),
            )
            val emptyCommits = request("/repositories/$emptyId?tab=commits", cookie = "$cookiePrefix=$token")
            assertEquals(200, emptyCommits.statusCode(), emptyCommits.body())
            assertTrue("No commits found." in emptyCommits.body(), emptyCommits.body())

            val adminProfiles =
                graphql(
                        "{ profiles { current { name attributes { typeId } } } }",
                        token = adminToken,
                    )
                    .at("profiles", "current")
                    .jsonArray
            assertTrue(
                adminProfiles.any { it.jsonObject["name"]?.jsonPrimitive?.content == "admin" }
            )
            assertTrue(
                adminProfiles.all { profile ->
                    profile.jsonObject.getValue("attributes").jsonArray.none {
                        it.jsonObject["typeId"]?.jsonPrimitive?.content == "bosca.profiles.email"
                    }
                },
                adminProfiles.toString(),
            )
            val editor = request("/repositories/$emptyId?new=true", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, editor.statusCode(), editor.body())
            val editorState =
                Regex(
                        "<script type=\"application/json\" data-bml-state-key=\"(git-file-editor\\.model:[^\"]*)\"[^>]*>(.*?)</script>",
                        RegexOption.DOT_MATCHES_ALL,
                    )
                    .find(editor.body()) ?: error("Missing editor state: ${editor.body()}")
            val authorPreview =
                Json.parseToJsonElement(editorState.groupValues[2])
                    .jsonObject
                    .getValue("page")
                    .jsonObject
            assertEquals("admin", authorPreview["authorName"]?.jsonPrimitive?.content)
            assertEquals("studio@bosca.io", authorPreview["authorEmail"]?.jsonPrimitive?.content)
            assertEquals("trunk", authorPreview["ref"]?.jsonPrimitive?.content)
            val readmeContent = "# Empty Repository\n\nCreated through the Git UI.\n"
            val committed =
                request(
                    "/_bml/action",
                    buildJsonObject {
                        put("page", "/repositories/{id}")
                        put("path", "/repositories/$emptyId")
                        put("stateKey", editorState.groupValues[1])
                        put("state", editorState.groupValues[2])
                        put("method", "save")
                        put(
                            "args",
                            buildJsonArray {
                                add("README.md")
                                add(readmeContent)
                                add("Add README through Git UI")
                            },
                        )
                    }
                        .toString(),
                    cookie = "$cookiePrefix=$adminToken",
                )
            assertEquals(200, committed.statusCode(), committed.body())
            val saved =
                Json.parseToJsonElement(
                        Json.parseToJsonElement(committed.body())
                            .jsonObject
                            .getValue("state")
                            .jsonPrimitive
                            .content
                    )
                    .jsonObject
            assertEquals(false, saved["failed"]?.jsonPrimitive?.boolean, committed.body())
            assertTrue(
                saved["nextHref"]?.jsonPrimitive?.content.orEmpty().contains("file=README.md"),
                committed.body(),
            )
            val readback =
                graphql(
                    """
                    query ReadBack(${'$'}id: UUID!) {
                      git {
                        blob(repositoryId: ${'$'}id, ref: "trunk", path: "README.md") { content }
                        commits(repositoryId: ${'$'}id, ref: "trunk") { message authorName authorEmail }
                      }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject { put("id", emptyId) },
                    adminToken,
                )
            assertEquals(readmeContent, readback.at("git", "blob", "content").jsonPrimitive.content)
            for ((tab, heading) in
                listOf(
                    "pulls" to "No open pull requests",
                    "pipelines" to "No pipelines",
                    "statuses" to "Recent Commits",
                    "secrets" to "No pipeline secrets configured",
                    "settings" to "Save repository",
                )) {
                val panel = request("/repositories/$emptyId?tab=$tab", cookie = "$cookiePrefix=$adminToken")
                assertEquals(200, panel.statusCode(), panel.body())
                assertTrue(heading in panel.body(), panel.body())
            }
            val initialCommit = readback.at("git", "commits").jsonArray.single().jsonObject
            assertEquals(
                "Add README through Git UI",
                initialCommit["message"]?.jsonPrimitive?.content,
            )
            assertEquals("admin", initialCommit["authorName"]?.jsonPrimitive?.content)
            assertEquals("studio@bosca.io", initialCommit["authorEmail"]?.jsonPrimitive?.content)
            val tagPage = request("/repositories/$emptyId?tab=tags", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, tagPage.statusCode(), tagPage.body())
            assertTrue("New Tag" in tagPage.body(), tagPage.body())
            val tagState = Regex(
                "<script type=\"application/json\" data-bml-state-key=\"(git-tag-create.model[^\"]*)\"[^>]*>(.*?)</script>",
                RegexOption.DOT_MATCHES_ALL,
            ).find(tagPage.body()) ?: error("Tag creation state missing: ${tagPage.body()}")
            fun createTag(name: String, target: String = "trunk", actorToken: String = adminToken): JsonObject {
                val response = request("/_bml/action", buildJsonObject {
                    put("page", "/repositories/{id}")
                    put("path", "/repositories/$emptyId")
                    put("stateKey", tagState.groupValues[1])
                    put("state", tagState.groupValues[2])
                    put("method", "createTag")
                    put("args", buildJsonArray { add(name); add(target); add("First release\nRelease notes") })
                }.toString(), cookie = "$cookiePrefix=$actorToken")
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            val createdTag = createTag("release/1.0")
            assertEquals(false, createdTag["failed"]?.jsonPrimitive?.boolean, createdTag.toString())
            assertTrue(createdTag["nextHref"]?.jsonPrimitive?.content.orEmpty().contains("tab=tags"))
            val tags = graphql(
                """
                query Tags(${'$'}id: UUID!) {
                  git {
                    tags(repositoryId: ${'$'}id) { name sha targetSha isAnnotated taggerName taggerEmail message }
                    blob(repositoryId: ${'$'}id, ref: "refs/tags/release/1.0", path: "README.md") { content }
                  }
                }
                """.trimIndent(), buildJsonObject { put("id", emptyId) }, adminToken,
            )
            val tag = tags.at("git", "tags").jsonArray.single()
            assertEquals("release/1.0", tag.at("name").jsonPrimitive.content)
            assertEquals(true, tag.at("isAnnotated").jsonPrimitive.boolean)
            assertEquals("admin", tag.at("taggerName").jsonPrimitive.content)
            assertEquals("studio@bosca.io", tag.at("taggerEmail").jsonPrimitive.content)
            assertEquals("First release\nRelease notes", tag.at("message").jsonPrimitive.content)
            assertTrue(tag.at("sha") != tag.at("targetSha"))
            assertEquals(readmeContent, tags.at("git", "blob", "content").jsonPrimitive.content)
            val duplicateTag = createTag("release/1.0")
            assertEquals(true, duplicateTag["failed"]?.jsonPrimitive?.boolean, duplicateTag.toString())
            assertTrue("already exists" in duplicateTag["message"]?.jsonPrimitive?.content.orEmpty())
            for (failedTag in listOf(createTag("invalid tag"), createTag("missing-target", "missing"), createTag("denied", actorToken = apiToken))) {
                assertEquals(true, failedTag["failed"]?.jsonPrimitive?.boolean, failedTag.toString())
            }
            val tagsPage = request("/repositories/$emptyId?tab=tags", cookie = "$cookiePrefix=$adminToken")
            assertTrue("release/1.0" in tagsPage.body(), tagsPage.body())
            assertTrue("First release" in tagsPage.body(), tagsPage.body())
            val readmePage =
                request("/repositories/$emptyId?file=README.md", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, readmePage.statusCode(), readmePage.body())
            assertTrue("Created through the Git UI." in readmePage.body(), readmePage.body())

            val searchPage =
                request("/search?repo=$emptyId&mode=paths&ref=trunk", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, searchPage.statusCode(), searchPage.body())
            val searchState =
                Regex(
                        "<script type=\"application/json\" data-bml-state-key=\"(git-search.model[^\"]*)\"[^>]*>(.*?)</script>",
                        RegexOption.DOT_MATCHES_ALL,
                    )
                    .find(searchPage.body()) ?: error("Missing search action state")
            val searchAction =
                request(
                    "/_bml/action",
                    body = buildJsonObject {
                            put("page", "/search")
                            put("path", "/search")
                            put("stateKey", searchState.groupValues[1])
                            put("method", "search")
                            put("state", searchState.groupValues[2])
                            put(
                                "args",
                                JsonArray(
                                    listOf(emptyId, "README", "paths", "trunk").map(::JsonPrimitive)
                                ),
                            )
                        }
                            .toString(),
                    cookie = "$cookiePrefix=$adminToken",
                )
            assertEquals(200, searchAction.statusCode(), searchAction.body())
            val searchResult =
                Json.parseToJsonElement(
                        Json.parseToJsonElement(searchAction.body())
                            .jsonObject
                            .getValue("state")
                            .jsonPrimitive
                            .content
                    )
                    .jsonObject
            assertEquals(false, searchResult["failed"]?.jsonPrimitive?.boolean, searchAction.body())
            val searchHit = searchResult.getValue("hits").jsonArray.single().jsonObject
            assertEquals("README.md", searchHit["path"]?.jsonPrimitive?.content)
            assertTrue(
                searchHit["href"]?.jsonPrimitive?.content.orEmpty().contains("ref="),
                searchAction.body(),
            )

            // Exercise compiled editing against the production API and actual Git/SQL state.
            for (branch in listOf("feature", "feature-two", "release")) {
                graphql(
                    "mutation Branch(\$repo: UUID!, \$name: String!) { git { createBranch(repositoryId: \$repo, branchName: \$name, sourceRef: \"trunk\") { name } } }",
                    buildJsonObject { put("repo", emptyId); put("name", branch) }, adminToken,
                )
            }
            for ((branch, content) in listOf("feature" to "Before retarget\n", "feature-two" to "After retarget\n")) {
                graphql(
                    "mutation File(\$repo: UUID!, \$branch: String!, \$content: String!) { git { commitFile(input: { repositoryId: \$repo, branch: \$branch, path: \"review.txt\", content: \$content, message: \"Prepare review\", authorName: \"Reviewer\", authorEmail: \"reviewer@example.test\" }) { commitSha } } }",
                    buildJsonObject { put("repo", emptyId); put("branch", branch); put("content", content) }, adminToken,
                )
            }
            for (counts in listOf(null, "true", "null", "false")) {
                val argument = counts?.let { ", includeAheadBehind: $it" }.orEmpty()
                val branches = graphql(
                    "query Branches(\$repo: UUID!) { git { branches(repositoryId: \$repo$argument) { name sha ahead behind } } }",
                    buildJsonObject { put("repo", emptyId) }, adminToken,
                ).at("git", "branches").jsonArray
                assertEquals(setOf("trunk", "feature", "feature-two", "release"), branches.map { it.at("name").jsonPrimitive.content }.toSet())
                val feature = branches.single { it.at("name").jsonPrimitive.content == "feature" }
                assertEquals(40, feature.at("sha").jsonPrimitive.content.length)
                assertEquals(if (counts == "false") 0 else 1, feature.at("ahead").jsonPrimitive.int)
                assertEquals(0, feature.at("behind").jsonPrimitive.int)
            }
            val branchPage = request("/repositories/$emptyId?tab=branches", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, branchPage.statusCode(), branchPage.body())
            assertTrue("Commits compared with trunk: 1 ahead, 0 behind" in branchPage.body(), branchPage.body())
            graphql(
                "mutation Second(\$repo: UUID!) { git { commitFile(input: { repositoryId: \$repo, branch: \"feature\", path: \"second-review.txt\", content: \"Second change\", message: \"Second review commit\", authorName: \"Reviewer\", authorEmail: \"reviewer@example.test\" }) { commitSha } } }",
                buildJsonObject { put("repo", emptyId) }, adminToken,
            )
            val editable = graphql(
                "mutation Pull(\$repo: UUID!) { git { createPullRequest(input: { repositoryId: \$repo, title: \"Original PR\", description: \"Original description\", sourceBranch: \"feature\", targetBranch: \"trunk\" }) { id number version } } }",
                buildJsonObject { put("repo", emptyId) }, adminToken,
            ).at("git", "createPullRequest").jsonObject
            val pullId = editable.getValue("id").jsonPrimitive.content
            val pullNumber = editable.getValue("number").jsonPrimitive.int
            val reviewId = graphql(
                "mutation Review(\$id: UUID!) { git { submitReview(input: { pullRequestId: \$id, status: APPROVED, expectedVersion: 0 }) { id } } }",
                buildJsonObject { put("id", pullId) }, adminToken,
            ).at("git", "submitReview", "id").jsonPrimitive.content
            val sourceSha = graphql(
                "query Head(\$repo: UUID!) { git { branches(repositoryId: \$repo) { name sha } } }",
                buildJsonObject { put("repo", emptyId) }, adminToken,
            ).at("git", "branches").jsonArray.single { it.at("name").jsonPrimitive.content == "feature" }.at("sha").jsonPrimitive.content
            val commitPage = graphql(
                "query Commits(\$id: UUID!) { git { pullRequestById(id: \$id) { commits(offset: 0, limit: 1) { sha message parentShas } } } }",
                buildJsonObject { put("id", pullId) }, adminToken,
            ).at("git", "pullRequestById", "commits").jsonArray.single()
            assertEquals(sourceSha, commitPage.at("sha").jsonPrimitive.content)
            assertEquals("Second review commit", commitPage.at("message").jsonPrimitive.content)
            val olderCommit = graphql(
                "query Commits(\$id: UUID!) { git { pullRequestById(id: \$id) { commits(offset: 1, limit: 1) { sha message } } } }",
                buildJsonObject { put("id", pullId) }, adminToken,
            ).at("git", "pullRequestById", "commits").jsonArray.single()
            assertEquals("Prepare review", olderCommit.at("message").jsonPrimitive.content)
            val selectedDiff = graphql(
                "query Selected(\$id: UUID!, \$sha: String!) { git { pullRequestById(id: \$id) { diff(commitSha: \$sha) { newPath } } } }",
                buildJsonObject { put("id", pullId); put("sha", sourceSha) }, adminToken,
            ).at("git", "pullRequestById", "diff").jsonArray
            assertEquals(listOf("second-review.txt"), selectedDiff.map { it.at("newPath").jsonPrimitive.content })
            val commitsHtml = request("/pulls/$pullId?view=commits", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, commitsHtml.statusCode(), commitsHtml.body())
            assertTrue("Second review commit" in commitsHtml.body() && "Prepare review" in commitsHtml.body(), commitsHtml.body())
            val commitHtml = request("/pulls/$pullId?view=files&commit=$sourceSha", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, commitHtml.statusCode(), commitHtml.body())
            assertTrue("second-review.txt" in commitHtml.body(), commitHtml.body())
            assertFalse("data-path=\"review.txt\"" in commitHtml.body(), commitHtml.body())
            val originalCommentId = graphql(
                "mutation Comment(\$id: UUID!, \$review: UUID!, \$sha: String!) { git { addReviewComment(input: { pullRequestId: \$id, reviewId: \$review, filePath: \"review.txt\", newLineNumber: 1, commitSha: \$sha, content: \"Original line comment\", expectedVersion: 0 }) { id } } }",
                buildJsonObject { put("id", pullId); put("review", reviewId); put("sha", sourceSha) }, adminToken,
            ).at("git", "addReviewComment", "id").jsonPrimitive.content
            val pullById = graphql(
                "query PullById(\$id: UUID!) { git { pullRequestById(id: \$id) { id repositoryId number } } }",
                buildJsonObject { put("id", pullId) }, adminToken,
            ).at("git", "pullRequestById")
            assertEquals(emptyId, pullById.at("repositoryId").jsonPrimitive.content)
            assertEquals(pullNumber, pullById.at("number").jsonPrimitive.int)
            val directPull = request("/pulls/$pullId", cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, directPull.statusCode(), directPull.body())
            assertTrue("Original PR" in directPull.body(), directPull.body())
            assertEquals(404, request("/pulls/${java.util.UUID.randomUUID()}", cookie = "$cookiePrefix=$adminToken").statusCode())
            val pullPath = "/pulls/$pullId?view=files"
            val pullHtml = request(pullPath, cookie = "$cookiePrefix=$adminToken").body()
            assertTrue("Before retarget" in pullHtml, pullHtml)
            val editState = Regex("<script type=\"application/json\" data-bml-state-key=\"(git-pull-edit.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(pullHtml) ?: error("Missing PR edit state: $pullHtml")
            val edited = request(
                "/_bml/action",
                buildJsonObject {
                    put("page", "/pulls/{id}"); put("path", "/pulls/$pullId")
                    put("stateKey", editState.groupValues[1]); put("state", editState.groupValues[2]); put("method", "save")
                    put("args", buildJsonArray { add("Edited PR"); add("## Updated description\n\nEdited through BML."); add("feature-two"); add("release"); add(false) })
                }.toString(), cookie = "$cookiePrefix=$adminToken",
            )
            assertEquals(200, edited.statusCode(), edited.body())
            val editedModel = Json.parseToJsonElement(Json.parseToJsonElement(edited.body()).at("state").jsonPrimitive.content).jsonObject
            assertEquals(false, editedModel["failed"]?.jsonPrimitive?.boolean, edited.body())
            assertTrue(editedModel["nextHref"]?.jsonPrimitive?.content?.endsWith("&view=files") == true, edited.body())
            val currentPull = graphql(
                "query Current(\$repo: UUID!, \$number: Int!) { git { pullRequest(repositoryId: \$repo, number: \$number) { id number version title description sourceBranch targetBranch mergeable reviews { dismissedAt dismissReason comments { outdated content } } diff { hunks { lines { content } } } } } }",
                buildJsonObject { put("repo", emptyId); put("number", pullNumber) }, adminToken,
            ).at("git", "pullRequest")
            assertEquals(pullId, currentPull.at("id").jsonPrimitive.content)
            assertEquals(pullNumber, currentPull.at("number").jsonPrimitive.int)
            assertEquals(1L, currentPull.at("version").jsonPrimitive.long)
            assertEquals("Edited PR", currentPull.at("title").jsonPrimitive.content)
            assertEquals("feature-two", currentPull.at("sourceBranch").jsonPrimitive.content)
            assertEquals("release", currentPull.at("targetBranch").jsonPrimitive.content)
            assertTrue(currentPull.at("mergeable").jsonPrimitive.boolean)
            assertTrue("After retarget" in currentPull.at("diff").toString())
            assertFalse("Before retarget" in currentPull.at("diff").toString())
            assertEquals("branches_updated", currentPull.at("reviews").jsonArray.single().at("dismissReason").jsonPrimitive.content)
            assertTrue(currentPull.at("reviews").jsonArray.single().at("comments").jsonArray.single().at("outdated").jsonPrimitive.boolean)
            val refreshed = request(pullPath, cookie = "$cookiePrefix=$adminToken").body()
            assertTrue("After retarget" in refreshed && "Edited PR" in refreshed, refreshed)
            val overview = request(pullPath.replace("view=files", "view=overview"), cookie = "$cookiePrefix=$adminToken").body()
            assertTrue("Updated description" in overview && "Edited through BML." in overview, overview)
            graphql(
                "mutation Resolve(\$id: UUID!) { git { resolveReviewThread(pullRequestId: \$id, filePath: \"review.txt\", lineNumber: 1) } }",
                buildJsonObject { put("id", pullId) }, adminToken,
            )
            val historyPath = pullPath.replace("view=files", "view=reviews")
            val history = request(historyPath, cookie = "$cookiePrefix=$adminToken").body()
            assertTrue("Outdated" in history && "Resolved" in history && "Reply" in history, history)
            val reviewState = Regex("<script type=\"application/json\" data-bml-state-key=\"(git-pull-review.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(history) ?: error("Missing review state")
            fun reviewAction(method: String, state: String, argument: String): HttpResponse<String> = request(
                "/_bml/action", buildJsonObject {
                    put("page", "/pulls/{id}"); put("path", "/pulls/$pullId")
                    put("stateKey", reviewState.groupValues[1]); put("state", state); put("method", method)
                    put("args", buildJsonArray { add(argument) })
                }.toString(), cookie = "$cookiePrefix=$adminToken",
            )
            val openedReply = reviewAction("reply", reviewState.groupValues[2], originalCommentId)
            assertEquals(200, openedReply.statusCode(), openedReply.body())
            assertTrue("Write a reply" in openedReply.body() && "Submit a review to start" !in openedReply.body(), openedReply.body())
            val savedReply = reviewAction("comment", Json.parseToJsonElement(openedReply.body()).at("state").jsonPrimitive.content, "Response to the original discussion")
            assertEquals(200, savedReply.statusCode(), savedReply.body())
            val replyModel = Json.parseToJsonElement(Json.parseToJsonElement(savedReply.body()).at("state").jsonPrimitive.content)
            assertFalse(replyModel.at("failed").jsonPrimitive.boolean, savedReply.body())
            assertEquals("Reply added.", replyModel.at("message").jsonPrimitive.content)
            val savedComments = graphql(
                "query Discussion(\$repo: UUID!, \$number: Int!) { git { pullRequest(repositoryId: \$repo, number: \$number) { reviews { comments { id reviewId commitSha filePath newLineNumber outdated resolved content } } } } }",
                buildJsonObject { put("repo", emptyId); put("number", pullNumber) }, adminToken,
            ).at("git", "pullRequest", "reviews").jsonArray.single().at("comments").jsonArray
            val savedResponse = savedComments.single { it.at("content").jsonPrimitive.content == "Response to the original discussion" }
            assertEquals(reviewId, savedResponse.at("reviewId").jsonPrimitive.content)
            assertEquals(sourceSha, savedResponse.at("commitSha").jsonPrimitive.content)
            assertEquals("review.txt", savedResponse.at("filePath").jsonPrimitive.content)
            assertEquals(1, savedResponse.at("newLineNumber").jsonPrimitive.int)
            assertTrue(savedResponse.at("outdated").jsonPrimitive.boolean)
            assertFalse(savedResponse.at("resolved").jsonPrimitive.boolean)
            assertTrue(savedComments.single { it.at("id").jsonPrimitive.content == originalCommentId }.at("resolved").jsonPrimitive.boolean)
            val deniedReply = request("/graphql", buildJsonObject {
                put("query", "mutation DeniedReply(\$id: UUID!, \$comment: UUID!) { git { replyReviewComment(pullRequestId: \$id, commentId: \$comment, content: \"Denied reply\", expectedVersion: 1) { id } } }")
                put("variables", buildJsonObject { put("id", pullId); put("comment", originalCommentId) })
            }.toString(), apiToken)
            assertTrue(Json.parseToJsonElement(deniedReply.body()).jsonObject.containsKey("errors"), deniedReply.body())
            val currentDiffHtml = request(pullPath, cookie = "$cookiePrefix=$adminToken").body()
                .replace(Regex("<script\\b[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            val displayedDiff = Regex("<section[^>]*class=\"diff-file\"[^>]*>.*?</section>", RegexOption.DOT_MATCHES_ALL)
                .findAll(currentDiffHtml).joinToString("\n") { it.value }
            assertTrue("After retarget" in displayedDiff, currentDiffHtml)
            assertFalse("Response to the original discussion" in displayedDiff)
            assertTrue("Response to the original discussion" in currentDiffHtml && "Discussions outside this diff" in currentDiffHtml)
            val deniedApiEdit = request("/graphql", buildJsonObject {
                put("query", "mutation Denied(\$id: UUID!) { git { updatePullRequest(id: \$id, input: { title: \"Denied edit\", expectedVersion: 1 }) { id } } }")
                put("variables", buildJsonObject { put("id", pullId) })
            }.toString(), apiToken)
            assertTrue(Json.parseToJsonElement(deniedApiEdit.body()).jsonObject.containsKey("errors"), deniedApiEdit.body())
            for ((actor, message) in listOf(
                adminToken to "Pull request changed",
                apiToken to "edit access",
            )) {
                val rejected = request("/_bml/action", buildJsonObject {
                    put("page", "/pulls/{id}"); put("path", "/pulls/$pullId")
                    put("stateKey", editState.groupValues[1]); put("state", editState.groupValues[2]); put("method", "save")
                    put("args", buildJsonArray { add("Stale edit"); add(""); add("feature"); add("trunk"); add(false) })
                }.toString(), cookie = "$cookiePrefix=$actor")
                assertTrue(message in rejected.body(), rejected.body())
            }
            for (mutation in listOf(
                "mutation StaleReview(\$id: UUID!) { git { submitReview(input: { pullRequestId: \$id, status: APPROVED, expectedVersion: 0 }) { id } } }",
                "mutation StaleComment(\$id: UUID!, \$review: UUID!, \$sha: String!) { git { addReviewComment(input: { pullRequestId: \$id, reviewId: \$review, filePath: \"review.txt\", newLineNumber: 1, commitSha: \$sha, content: \"Stale line comment\", expectedVersion: 0 }) { id } } }",
                "mutation StaleReply(\$id: UUID!, \$comment: UUID!) { git { replyReviewComment(pullRequestId: \$id, commentId: \$comment, content: \"Stale reply\", expectedVersion: 0) { id } } }",
                "mutation InvalidBranch(\$id: UUID!) { git { updatePullRequest(id: \$id, input: { targetBranch: \"missing\", expectedVersion: 1 }) { id } } }",
                "mutation StaleMerge(\$id: UUID!) { git { mergePullRequest(id: \$id, strategy: MERGE_COMMIT, expectedVersion: 0) { id } } }",
                "mutation StalePlan(\$id: UUID!) { git { mergePullRequestWithDependencies(id: \$id, strategy: MERGE_COMMIT, expectedVersion: 0) { id } } }",
            )) {
                val rejected = request("/graphql", buildJsonObject {
                    put("query", mutation)
                    put("variables", buildJsonObject { put("id", pullId); put("review", reviewId); put("sha", sourceSha); put("comment", originalCommentId) })
                }.toString(), adminToken)
                val result = Json.parseToJsonElement(rejected.body()).jsonObject
                assertTrue(result.containsKey("errors"), rejected.body())
                assertTrue("Pull request changed" in rejected.body() || "no longer available" in rejected.body(), rejected.body())
            }
            graphql(
                "mutation DeleteSource(\$repo: UUID!) { git { deleteBranch(repositoryId: \$repo, branchName: \"feature-two\") } }",
                buildJsonObject { put("repo", emptyId) }, adminToken,
            )
            for ((source, title) in listOf("feature-two" to "Metadata with deleted source", "feature" to "Repaired PR")) {
                val pageWithMissingSource = request(pullPath, cookie = "$cookiePrefix=$adminToken")
                assertEquals(200, pageWithMissingSource.statusCode(), pageWithMissingSource.body())
                assertTrue("Comparison unavailable" in pageWithMissingSource.body(), pageWithMissingSource.body())
                val state = Regex("<script type=\"application/json\" data-bml-state-key=\"(git-pull-edit.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                    .find(pageWithMissingSource.body()) ?: error("Missing recovery edit state")
                val repair = request("/_bml/action", buildJsonObject {
                    put("page", "/pulls/{id}"); put("path", "/pulls/$pullId")
                    put("stateKey", state.groupValues[1]); put("state", state.groupValues[2]); put("method", "save")
                    put("args", buildJsonArray { add(title); add("Edited with a deleted source branch."); add(source); add("release"); add(false) })
                }.toString(), cookie = "$cookiePrefix=$adminToken")
                val stateAfter = Json.parseToJsonElement(Json.parseToJsonElement(repair.body()).at("state").jsonPrimitive.content)
                assertEquals(false, stateAfter.at("failed").jsonPrimitive.boolean, repair.body())
            }
            val recovered = request(pullPath, cookie = "$cookiePrefix=$adminToken")
            assertEquals(200, recovered.statusCode(), recovered.body())
            assertTrue("Repaired PR" in recovered.body() && "Before retarget" in recovered.body(), recovered.body())
            assertFalse("Comparison unavailable" in recovered.body(), recovered.body())
            for (strategy in listOf("MERGE_COMMIT", "SQUASH", "REBASE", "FAST_FORWARD")) {
                val source = "merged-${strategy.lowercase()}"
                val target = "target-${strategy.lowercase()}"
                for (branch in listOf(source, target)) {
                    graphql(
                        "mutation Branch(\$repo: UUID!, \$name: String!) { git { createBranch(repositoryId: \$repo, branchName: \$name, sourceRef: \"trunk\") { name } } }",
                        buildJsonObject { put("repo", emptyId); put("name", branch) }, adminToken,
                    )
                }
                val before = graphql(
                    "query Branches(\$repo: UUID!) { git { branches(repositoryId: \$repo) { name sha } } }",
                    buildJsonObject { put("repo", emptyId) }, adminToken,
                ).at("git", "branches").jsonArray.first { it.at("name").jsonPrimitive.content == target }
                    .at("sha").jsonPrimitive.content
                var sourceHead = ""
                for (path in listOf("first-change.txt", "second-change.txt")) {
                    sourceHead = graphql(
                        "mutation File(\$repo: UUID!, \$branch: String!, \$path: String!) { git { commitFile(input: { repositoryId: \$repo, branch: \$branch, path: \$path, content: \"Retained merged change\\n\", message: \"Prepare merged diff\", authorName: \"Reviewer\", authorEmail: \"reviewer@example.test\" }) { commitSha } } }",
                        buildJsonObject { put("repo", emptyId); put("branch", source); put("path", path) }, adminToken,
                    ).at("git", "commitFile", "commitSha").jsonPrimitive.content
                }
                var createdPull = graphql(
                    "mutation Pull(\$repo: UUID!, \$source: String!, \$target: String!, \$cleanup: Boolean) { git { createPullRequest(input: { repositoryId: \$repo, title: \"Retained merged diff\", sourceBranch: \$source, targetBranch: \$target, deleteBranchOnMerge: \$cleanup }) { id number version deleteBranchOnMerge } } }",
                    buildJsonObject {
                        put("repo", emptyId); put("source", source); put("target", target)
                        if (strategy == "FAST_FORWARD") put("cleanup", false)
                    }, adminToken,
                ).at("git", "createPullRequest")
                assertEquals(strategy != "FAST_FORWARD", createdPull.at("deleteBranchOnMerge").jsonPrimitive.boolean)
                if (strategy == "SQUASH") {
                    createdPull = graphql(
                        "mutation Cleanup(\$id: UUID!, \$version: Long!) { git { updatePullRequest(id: \$id, input: { deleteBranchOnMerge: false, expectedVersion: \$version }) { id number version deleteBranchOnMerge } } }",
                        buildJsonObject { put("id", createdPull.at("id").jsonPrimitive.content); put("version", createdPull.at("version").jsonPrimitive.long) }, adminToken,
                    ).at("git", "updatePullRequest")
                    assertFalse(createdPull.at("deleteBranchOnMerge").jsonPrimitive.boolean)
                }
                val mergedReview = graphql(
                    "mutation Review(\$id: UUID!) { git { submitReview(input: { pullRequestId: \$id, status: COMMENT_ONLY }) { id } } }",
                    buildJsonObject { put("id", createdPull.at("id").jsonPrimitive.content) }, adminToken,
                ).at("git", "submitReview", "id").jsonPrimitive.content
                graphql(
                    "mutation Comment(\$id: UUID!, \$review: UUID!, \$sha: String!) { git { addReviewComment(input: { pullRequestId: \$id, reviewId: \$review, filePath: \"first-change.txt\", newLineNumber: 1, commitSha: \$sha, content: \"Retained source discussion\" }) { id } } }",
                    buildJsonObject { put("id", createdPull.at("id").jsonPrimitive.content); put("review", mergedReview); put("sha", sourceHead) }, adminToken,
                )
                val merged = graphql(
                    "mutation Merge(\$id: UUID!, \$strategy: GitMergeStrategy!, \$version: Long!) { git { mergePullRequest(id: \$id, strategy: \$strategy, expectedVersion: \$version) { mergeSha mergeBaseSha } } }",
                    buildJsonObject {
                        put("id", createdPull.at("id").jsonPrimitive.content); put("strategy", strategy)
                        put("version", createdPull.at("version").jsonPrimitive.long)
                    }, adminToken,
                ).at("git", "mergePullRequest")
                val mergedSha = merged.at("mergeSha").jsonPrimitive.content
                assertEquals(before, merged.at("mergeBaseSha").jsonPrimitive.content, strategy)
                val remainingBranches = graphql(
                    "query Branches(\$repo: UUID!) { git { branches(repositoryId: \$repo) { name sha } } }",
                    buildJsonObject { put("repo", emptyId) }, adminToken,
                ).at("git", "branches").jsonArray
                val keepSource = !createdPull.at("deleteBranchOnMerge").jsonPrimitive.boolean
                assertEquals(keepSource, remainingBranches.any { it.at("name").jsonPrimitive.content == source }, strategy)
                if (keepSource) graphql(
                    "mutation Delete(\$repo: UUID!, \$branch: String!) { git { deleteBranch(repositoryId: \$repo, branchName: \$branch) } }",
                    buildJsonObject { put("repo", emptyId); put("branch", source) }, adminToken,
                )
                assertEquals(mergedSha, remainingBranches.single { it.at("name").jsonPrimitive.content == target }
                    .at("sha").jsonPrimitive.content, strategy)
                graphql(
                    "mutation Later(\$repo: UUID!, \$branch: String!) { git { commitFile(input: { repositoryId: \$repo, branch: \$branch, path: \"unrelated-after-merge.txt\", content: \"Later target change\", message: \"Advance target\", authorName: \"Reviewer\", authorEmail: \"reviewer@example.test\" }) { commitSha } } }",
                    buildJsonObject { put("repo", emptyId); put("branch", target) }, adminToken,
                )
                val historical = graphql(
                    "query Merged(\$repo: UUID!, \$number: Int!) { git { pullRequest(repositoryId: \$repo, number: \$number) { mergeSha mergeBaseSha commits { sha message } diff { newPath } } } }",
                    buildJsonObject { put("repo", emptyId); put("number", createdPull.at("number").jsonPrimitive.int) }, adminToken,
                ).at("git", "pullRequest")
                assertEquals(before, historical.at("mergeBaseSha").jsonPrimitive.content, strategy)
                assertEquals(mergedSha, historical.at("mergeSha").jsonPrimitive.content, strategy)
                assertEquals(setOf("first-change.txt", "second-change.txt"),
                    historical.at("diff").jsonArray.map { it.at("newPath").jsonPrimitive.content }.toSet(), strategy)
                graphql(
                    "mutation Delete(\$repo: UUID!, \$branch: String!) { git { deleteBranch(repositoryId: \$repo, branchName: \$branch) } }",
                    buildJsonObject { put("repo", emptyId); put("branch", target) }, adminToken,
                )
                val filesPath = "/pulls/${createdPull.at("id").jsonPrimitive.content}?repo=$emptyId&number=${createdPull.at("number").jsonPrimitive.int}&view=files"
                val files = request(filesPath, cookie = "$cookiePrefix=$adminToken")
                assertEquals(200, files.statusCode(), files.body())
                val historicalCommits = historical.at("commits").jsonArray
                assertEquals(if (strategy == "SQUASH") 1 else if (strategy == "MERGE_COMMIT") 3 else 2, historicalCommits.size, strategy)
                val selectedPath = filesPath + "&commit=" + historicalCommits.first().at("sha").jsonPrimitive.content
                val selectedFiles = request(selectedPath, cookie = "$cookiePrefix=$adminToken")
                assertEquals(200, selectedFiles.statusCode(), selectedFiles.body())
                assertTrue("second-change.txt" in selectedFiles.body(), selectedFiles.body())
                if (strategy in listOf("REBASE", "FAST_FORWARD"))
                    assertFalse("data-path=\"first-change.txt\"" in selectedFiles.body(), selectedFiles.body())
                assertTrue("first-change.txt" in files.body() && "second-change.txt" in files.body() && "Retained merged change" in files.body(), files.body())
                assertFalse("Comparison unavailable" in files.body() || "No files changed" in files.body(), files.body())
                val mergedFiles = Regex("<section[^>]*class=\"diff-file\"[^>]*>.*?</section>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(files.body()).joinToString("\n") { it.value }
                assertTrue("first-change.txt" in mergedFiles && "second-change.txt" in mergedFiles && "Retained merged change" in mergedFiles, files.body())
                if (strategy == "FAST_FORWARD") {
                    assertTrue("Retained source discussion" in mergedFiles, files.body())
                } else {
                    assertFalse("Retained source discussion" in mergedFiles, files.body())
                    assertTrue("Retained source discussion" in files.body() && "Earlier revision" in files.body(), files.body())
                }
            }
            fun socket(authToken: String?): Pair<WebSocket, LinkedBlockingQueue<JsonObject>> {
                val messages = LinkedBlockingQueue<JsonObject>()
                val listener =
                    object : WebSocket.Listener {
                        private val text = StringBuilder()

                        override fun onOpen(webSocket: WebSocket) {
                            webSocket.request(1)
                        }

                        override fun onText(
                            webSocket: WebSocket,
                            data: CharSequence,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            text.append(data)
                            if (last) {
                                messages.add(Json.parseToJsonElement(text.toString()).jsonObject)
                                text.setLength(0)
                            }
                            webSocket.request(1)
                            return null
                        }

                        override fun onClose(
                            webSocket: WebSocket,
                            statusCode: Int,
                            reason: String,
                        ): CompletionStage<*>? {
                            messages.add(
                                buildJsonObject {
                                    put("type", "closed")
                                    put("code", statusCode)
                                }
                            )
                            return null
                        }
                    }
                val socket =
                    client
                        .newWebSocketBuilder()
                        .subprotocols("graphql-transport-ws")
                        .buildAsync(URI.create("ws://localhost:$uiPort/_git/subscriptions"), listener)
                        .get(10, TimeUnit.SECONDS)
                socket
                    .sendText(
                        buildJsonObject {
                            put("type", "connection_init")
                            put(
                                "payload",
                                buildJsonObject { authToken?.let { put("authToken", it) } },
                            )
                        }
                            .toString(),
                        true,
                    )
                    .join()
                return socket to messages
            }
            val (live, messages) = socket(token)
            try {
                assertEquals(
                    "connection_ack",
                    messages.poll(10, TimeUnit.SECONDS)?.get("type")?.jsonPrimitive?.content,
                )
                live
                    .sendText(
                        buildJsonObject {
                            put("id", "events")
                            put("type", "subscribe")
                            put(
                                "payload",
                                buildJsonObject {
                                    put(
                                        "query",
                                        "subscription { gitPipelineEvents(repositoryId: \"$repositoryId\") { repositoryId status } }",
                                    )
                                },
                            )
                        }
                            .toString(),
                        true,
                    )
                    .join()
                val event = buildJsonObject {
                    put("repositoryId", repositoryId)
                    put("pipelineRunId", java.util.UUID.randomUUID().toString())
                    put("pipelineId", java.util.UUID.randomUUID().toString())
                    put("status", "RUNNING")
                }
                    .toString()
                    .toByteArray()
                val publisher = nats.newConnection()
                var update: JsonObject? = null
                repeat(50) {
                    if (update == null) {
                        publisher.publish("bosca.git.pipeline", event)
                        publisher.flush(Duration.ofSeconds(2))
                        update = messages.poll(100, TimeUnit.MILLISECONDS)
                    }
                }
                val result = requireNotNull(update) { "No local subscription event arrived" }
                assertEquals(
                    "next",
                    result.getValue("type").jsonPrimitive.content,
                    result.toString(),
                )
                assertEquals(
                    repositoryId,
                    result
                        .at("payload", "data", "gitPipelineEvents", "repositoryId")
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "RUNNING",
                    result
                        .at("payload", "data", "gitPipelineEvents", "status")
                        .jsonPrimitive
                        .content,
                )
            } finally {
                live.abort()
            }
            val (rejected, rejection) = socket("invalid-token")
            try {
                assertEquals(
                    "connection_error",
                    rejection.poll(10, TimeUnit.SECONDS)?.get("type")?.jsonPrimitive?.content,
                )
                assertEquals(
                    4401,
                    rejection.poll(10, TimeUnit.SECONDS)?.get("code")?.jsonPrimitive?.int,
                )
            } finally {
                rejected.abort()
            }
            assertEquals(
                401,
                request(
                        "/graphql",
                        "{\"query\":\"{ git { repositories { id } } }\"}",
                        "invalid-token",
                    )
                    .statusCode(),
            )
            val signOut =
                request(
                    "/graphql",
                    "{\"query\":\"mutation { security { login { signOut } } }\"}",
                    cookie = "$cookiePrefix=$token",
                )
            assertEquals(200, signOut.statusCode(), signOut.body())
            assertTrue(
                signOut.headers().allValues("Set-Cookie").any {
                    it.startsWith("$cookiePrefix=") && "Max-Age=0" in it
                },
                signOut.headers().toString(),
            )
            assertFalse("graphql proxy upstream unreachable" in log.readText(), log.readText())
        } finally {
            process?.destroy()
            val stopped = process?.waitFor(45, TimeUnit.SECONDS) ?: true
            if (!stopped) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            try {
                assertTrue(stopped, "Git host did not shut down both listeners gracefully")
                apiPort?.let { ServerSocket(it).use { socket -> assertTrue(socket.isBound) } }
                uiListenerPort?.let { ServerSocket(it).use { socket -> assertTrue(socket.isBound) } }
            } finally {
                nats.stop()
                postgres.stop()
            }
        }
    }
}
