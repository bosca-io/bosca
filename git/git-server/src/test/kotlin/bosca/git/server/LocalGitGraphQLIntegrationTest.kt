package bosca.git.server

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/** Starts the production composition with isolated PostgreSQL/NATS and no upstream API server. */
class LocalGitGraphQLIntegrationTest {
    private fun JsonElement.at(vararg fields: String): JsonElement =
        fields.fold(this) { value, field -> value.jsonObject.getValue(field) }

    @Test
    fun `local API authenticates browser SSR actions and subscriptions without an upstream`() {
        val postgres = SharedPostgreSQLContainer().withDatabaseName("git_local_graphql")
        val nats = SharedNatsContainer()
        val directory = kotlin.io.path.createTempDirectory("git-local-graphql").toFile()
        var process: Process? = null
        try {
            postgres.start()
            nats.start()
            val port = ServerSocket(0).use { it.localPort }
            val origin = "http://localhost:$port"
            val config = File(directory, "application.yaml")
            val template =
                File(
                        requireNotNull(System.getProperty("workspaceRoot")),
                        "git/git-server/src/main/resources/application.yaml",
                    )
                    .readText()
            config.writeText(
                template +
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
                        requireNotNull(System.getProperty("applicationClasspath")),
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
                                    "GIT_DEVELOPMENT" to "true",
                                    "DATABASE_URL" to postgres.jdbcUrl,
                                    "DATABASE_USER" to postgres.username,
                                    "DATABASE_PASSWORD" to postgres.password,
                                    "NATS_HOST" to nats.url.removePrefix("nats://"),
                                    "NATS_USERNAME" to nats.username,
                                    "NATS_PASSWORD" to nats.password,
                                    "NATS_TOKEN" to "",
                                    "APP_URL" to origin,
                                    "GIT_URL" to origin,
                                    "GRAPHQL_INTROSPECTION_ENABLED" to "true",
                                    "SECURITY_AUTO_VERIFY" to "true",
                                    "APP_ALLOWED_ORIGIN" to origin,
                                    "WELCOME_EMAIL_URL" to "$origin/repositories",
                                    "OAUTH2_ALLOWED_REDIRECT" to origin,
                                    // Deliberately unreachable: local BML must never use this
                                    // legacy endpoint.
                                    "BML_GRAPHQL_ENDPOINT" to "http://127.0.0.1:1/graphql",
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
            ): HttpResponse<String> {
                val builder =
                    HttpRequest.newBuilder(URI.create(origin + path))
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
                    runCatching { request("/api/v1/ready").statusCode() == 200 }.getOrDefault(false)
                if (ready) break
                Thread.sleep(250)
            }
            assertTrue(
                ready,
                "Git server failed to start. Log: ${log.absolutePath}\n${log.readText().takeLast(12_000)}",
            )
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
            assertEquals(200, request("/login").statusCode())
            val designFonts =
                Regex("url\\((/_git/fonts/[^)]+)\\)")
                    .findAll(request("/_bml/app.css").body())
                    .map { it.groupValues[1] }
                    .toList()
            assertEquals(11, designFonts.size)
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
            val page = request("/repositories/$repositoryId", cookie = "_bat=$token")
            assertEquals(200, page.statusCode(), page.body())
            assertTrue("Local Repository" in page.body(), page.body())
            val treeProps =
                Regex(
                        "data-bml-deferred=\"component:git-repository-sidebar:repository-tree-loader\"\\s+data-bml-props=\"([^\"]+)\""
                    )
                    .find(page.body())
                    ?.groupValues
                    ?.get(1)
                    ?.replace("&quot;", "\"")
                    ?.replace("&amp;", "&")
                    ?: error("Missing deferred repository tree: ${page.body()}")
            val treeRequest = buildJsonObject {
                put("page", "/repositories/{id}")
                put("path", "/repositories/$repositoryId")
                put("query", buildJsonObject {})
                put("props", Json.parseToJsonElement(treeProps))
            }
                .toString()
            val tree =
                request(
                    "/_bml/deferred/component%3Agit-repository-sidebar%3Arepository-tree-loader",
                    treeRequest,
                    cookie = "_bat=$token",
                )
            assertEquals(200, tree.statusCode(), tree.body())
            assertEquals("private, no-store", tree.headers().firstValue("Cache-Control").orElse(""))
            assertTrue("README.md" in tree.body(), tree.body())
            val readmeRow =
                Regex("<div[^>]*data-path=\"README.md\"[^>]*>").find(tree.body())?.value
                    ?: error("Missing real README tree row")
            assertFalse("data-bml-method" in readmeRow, tree.body())
            assertTrue("file=README.md" in tree.body(), tree.body())
            val filePage =
                request("/repositories/$repositoryId?file=README.md", cookie = "_bat=$token")
            assertEquals(200, filePage.statusCode(), filePage.body())
            assertTrue("markdown-file" in filePage.body(), filePage.body())
            assertEquals(
                401,
                request(
                        "/_bml/deferred/component%3Agit-repository-sidebar%3Arepository-tree-loader",
                        treeRequest,
                    )
                    .statusCode(),
            )

            val list = request("/repositories", cookie = "_bat=$token")
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
                    cookie = "_bat=$token",
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
            val emptyPage = request("/repositories/$emptyId", cookie = "_bat=$token")
            assertEquals(200, emptyPage.statusCode(), emptyPage.body())
            assertTrue("Empty Repository" in emptyPage.body(), emptyPage.body())
            assertTrue("Start this repository" in emptyPage.body(), emptyPage.body())
            assertTrue(
                "0 commits · 0 branches · 0 tags · 0 contributors" in emptyPage.body(),
                emptyPage.body(),
            )
            val emptyCommits = request("/repositories/$emptyId?tab=commits", cookie = "_bat=$token")
            assertEquals(200, emptyCommits.statusCode(), emptyCommits.body())
            assertTrue("No commits at this ref" in emptyCommits.body(), emptyCommits.body())

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
            val editor = request("/repositories/$emptyId?new=true", cookie = "_bat=$adminToken")
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
                    cookie = "_bat=$adminToken",
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
                    "pulls" to "No pull requests",
                    "pipelines" to "No pipelines",
                    "statuses" to "Recent commits",
                    "secrets" to "Pipeline secrets",
                    "settings" to "Save repository",
                )) {
                val panel = request("/repositories/$emptyId?tab=$tab", cookie = "_bat=$adminToken")
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
            val readmePage =
                request("/repositories/$emptyId?file=README.md", cookie = "_bat=$adminToken")
            assertEquals(200, readmePage.statusCode(), readmePage.body())
            assertTrue("Created through the Git UI." in readmePage.body(), readmePage.body())

            val searchPage =
                request("/search?repo=$emptyId&mode=paths&ref=trunk", cookie = "_bat=$adminToken")
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
                    cookie = "_bat=$adminToken",
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
                        .buildAsync(URI.create("ws://localhost:$port/_git/subscriptions"), listener)
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
                    cookie = "_bat=$token",
                )
            assertEquals(200, signOut.statusCode(), signOut.body())
            assertTrue(
                signOut.headers().allValues("Set-Cookie").any {
                    it.startsWith("_bat=") && "Max-Age=0" in it
                },
                signOut.headers().toString(),
            )
            assertFalse("graphql proxy upstream unreachable" in log.readText(), log.readText())
        } finally {
            process?.destroy()
            process?.waitFor(10, TimeUnit.SECONDS)
            if (process?.isAlive == true) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            nats.stop()
            postgres.stop()
        }
    }
}
