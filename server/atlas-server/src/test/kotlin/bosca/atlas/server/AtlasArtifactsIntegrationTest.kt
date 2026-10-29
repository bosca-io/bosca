package bosca.atlas.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Exercises artifact BML actions and streaming files against the real Atlas composition. */
class AtlasArtifactsIntegrationTest {
    @Test
    fun `UI file traffic supports a separate registry while GraphQL stays on Atlas`() {
        AtlasProcessFixture().use { fixture ->
            val origin = fixture.newOrigin()
            val registry = fixture.start("registry", mapOf("ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_ENABLED" to "false", "APP_URL" to origin, "APP_ALLOWED_ORIGIN" to origin)).first
            val api = fixture.start("api", mapOf("ATLAS_ARTIFACTS_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_ENABLED" to "false", "APP_URL" to origin, "APP_ALLOWED_ORIGIN" to origin, "GIT_UI_URL" to origin)).first
            val ui = fixture.startUi(api, origin, registry, registry)
            fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { v, key -> v.jsonObject.getValue(key) }
            val token = fixture.graphql(ui, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val namespace = fixture.graphql(ui, """mutation { artifactsAdmin { createNamespace(name: "split-files", public: false) { id } } }""", token).at("artifactsAdmin", "createNamespace", "id").jsonPrimitive.content
            val repository = fixture.graphql(ui, """mutation { artifactsAdmin { createRepository(namespaceId: "$namespace", name: "tool", type: raw) { id } } }""", token).at("artifactsAdmin", "createRepository", "id").jsonPrimitive.content
            val upload = fixture.upload(ui, "/_artifacts/raw/split-files/tool/1.0/tool.zip", "split bytes", token)
            assertEquals(201, upload.statusCode(), upload.body())
            assertEquals("split bytes", fixture.request(ui, "/_artifacts/raw/split-files/tool/1.0/tool.zip", token = token).body())
            val encodedPath = "split-files/tool/1.0%2Bbuild/tool%2B%E2%98%83%20file.zip"
            assertEquals(201, fixture.upload(ui, "/_artifacts/raw/$encodedPath", "encoded bytes", token).statusCode())
            assertEquals("encoded bytes", fixture.request(ui, "/_artifacts/raw/$encodedPath", token = token).body())
            val resolved = fixture.request(ui, "/_bml/contract/ArtifactUploadTarget/resolve", JsonArray(listOf(JsonPrimitive("new"), JsonPrimitive(namespace), JsonPrimitive("new-tool"))).toString(), token)
            assertEquals(200, resolved.statusCode(), resolved.body())
            val target = Json.parseToJsonElement(resolved.body()).jsonObject
            assertEquals("new-tool", target.getValue("name").jsonPrimitive.content)
            assertEquals("split-files", target.getValue("namespace").jsonPrimitive.content)
            assertEquals(404, fixture.request(api, "/raw/split-files/tool/1.0/tool.zip", token = token).statusCode())
            assertEquals(404, fixture.request(registry, "/graphql").statusCode())
            val page = fixture.request(ui, "/artifacts/repositories/$repository", token = token)
            assertEquals(200, page.statusCode(), page.body())
            assertTrue("tool.zip" in page.body())
            assertTrue("value=\"$registry/raw/split-files/tool/1.0/tool.zip\"" in page.body())
            val mavenFile = "/maven/split-files/com/example/tool.core/1.0/tool.core-1.0.jar"
            val mavenBytes = "maven artifact bytes"
            assertEquals(201, fixture.upload(registry, mavenFile, mavenBytes, token).statusCode())
            val mavenRepository = fixture.graphql(ui, "{ artifactsAdmin { repositories(type: maven) { id } } }", token)
                .at("artifactsAdmin", "repositories").jsonArray.single().at("id").jsonPrimitive.content
            val mavenPage = fixture.request(ui, "/artifacts/repositories/$mavenRepository", token = token)
            assertEquals(200, mavenPage.statusCode(), mavenPage.body())
            val downloadPaths = Regex("value=\"([^\"]*/maven/[^\"]*)\"").findAll(mavenPage.body())
                .map { java.net.URI(it.groupValues[1]).rawPath }.toSet()
            val downloadFile = "/maven/split-files/com/example/tool/core/1.0/tool.core-1.0.jar"
            assertEquals(setOf(downloadFile, "$downloadFile.sha1", "$downloadFile.md5"), downloadPaths)
            assertTrue("value=\"$registry$downloadFile\"" in mavenPage.body())
            assertEquals(mavenBytes, fixture.request(ui, "/_artifacts$downloadFile", token = token).body())
            for ((extension, algorithm) in listOf("sha1" to "SHA-1", "md5" to "MD5")) {
                val checksum = java.security.MessageDigest.getInstance(algorithm).digest(mavenBytes.encodeToByteArray())
                    .joinToString("") { "%02x".format(it) }
                val response = fixture.request(ui, "/_artifacts$downloadFile.$extension", token = token)
                assertEquals(200, response.statusCode(), response.body())
                assertEquals(checksum, response.body())
            }
        }
    }

    @Test
    fun `artifact pages manage namespaces repositories files permissions and destinations through Atlas`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("artifacts")
            fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { v, key -> v.jsonObject.getValue(key) }
            val token = fixture.graphql(ui, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            fun page(path: String) = fixture.request(ui, path, token = token).also { assertEquals(200, it.statusCode(), it.body()) }.body()
            fun action(path: String, route: String, prefix: String, method: String, args: List<JsonElement>, html: String = page(path), callerToken: String = token, expectFailure: Boolean = false): JsonObject {
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"($prefix[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: error("Missing $prefix state: $html")
                val response = fixture.request(ui, "/_bml/action", buildJsonObject {
                    put("page", route); put("path", path.substringBefore('?')); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), callerToken)
                assertEquals(200, response.statusCode(), response.body())
                val result = Json.parseToJsonElement(response.body()).jsonObject
                val state = Json.parseToJsonElement(result.getValue("state").jsonPrimitive.content).jsonObject
                assertEquals(expectFailure, state.getValue("feedback").jsonObject["failed"]?.jsonPrimitive?.boolean ?: false, response.body())
                return result
            }
            assertTrue("Bosca Atlas" in page("/"))
            val anonymous = fixture.request(ui, "/artifacts/repositories")
            assertEquals(302, anonymous.statusCode())
            assertTrue(anonymous.headers().firstValue("Location").orElse("").contains("login"))
            action("/artifacts/namespaces", "/artifacts/namespaces", "artifacts-create.model", "create", listOf(JsonPrimitive("atlas-ui-files"), JsonPrimitive(""), JsonPrimitive("raw"), JsonPrimitive(false)))
            val namespaces = fixture.graphql(ui, "{ artifactsAdmin { namespaces { id name } } }", token).at("artifactsAdmin", "namespaces").jsonArray
            val namespaceId = namespaces.first { it.at("name").jsonPrimitive.content == "atlas-ui-files" }.at("id").jsonPrimitive.content
            val namespacePath = "/artifacts/namespaces/$namespaceId"
            action(namespacePath, "/artifacts/namespaces/{id}", "artifacts-create.model", "create", listOf(JsonPrimitive("tool"), JsonPrimitive(namespaceId), JsonPrimitive("raw"), JsonPrimitive(false)))
            val repositories = fixture.graphql(ui, """{ artifactsAdmin { repositories(namespaceId: "$namespaceId") { id name } } }""", token).at("artifactsAdmin", "repositories").jsonArray
            val repositoryId = repositories.single().at("id").jsonPrimitive.content
            assertTrue("atlas-ui-files/tool" in page("/artifacts/repositories"))
            assertTrue("atlas-ui-files" in page(namespacePath))
            fixture.graphql(ui, """mutation { security { signup { passwordV2(identifier: "reader@example.test", password: "Reader-test-password", profile: {name: "Artifact Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = fixture.graphql(ui, """mutation { security { login { password(identifier: "reader@example.test", password: "Reader-test-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val readerGroup = fixture.graphql(ui, "{ security { principals { current { groups { id type } } } } }", reader).at("security", "principals", "current", "groups").jsonArray.first { it.at("type").jsonPrimitive.content == "PRINCIPAL" }.at("id").jsonPrimitive.content
            for (grant in listOf("VIEW", "LIST")) fixture.graphql(ui, """mutation { artifactsAdmin { addPermission(permission: {entityId: "$namespaceId", groupId: "$readerGroup", action: $grant}) } }""", token)
            assertEquals(200, fixture.request(ui, namespacePath, token = reader).statusCode())
            action(namespacePath, "/artifacts/namespaces/{id}", "artifacts-create.model", "create", listOf(JsonPrimitive("forged-repository"), JsonPrimitive(namespaceId), JsonPrimitive("raw"), JsonPrimitive(false)), callerToken = reader, expectFailure = true)
            assertTrue("Upload files" in page("/artifacts/repositories/upload?repositoryId=$repositoryId"))
            val upload = fixture.upload(ui, "/_artifacts/raw/atlas-ui-files/tool/1.0/tool.zip", "artifact bytes", token)
            assertEquals(201, upload.statusCode(), upload.body())
            assertEquals("artifact bytes", fixture.request(ui, "/_artifacts/raw/atlas-ui-files/tool/1.0/tool.zip", token = token).body())
            assertEquals(200, fixture.request(ui, "/_artifacts/raw/atlas-ui-files/tool/1.0/tool.zip", token = reader).statusCode())
            assertTrue(fixture.upload(ui, "/_artifacts/raw/atlas-ui-files/tool/1.0/forbidden.zip", "forbidden", reader).statusCode() in 400..499)
            val deniedSettings = fixture.request(ui, "/artifacts/settings/repositories/$repositoryId", token = reader)
            assertEquals(200, deniedSettings.statusCode())
            assertTrue("Administrator access is required" in deniedSettings.body())
            assertFalse("Token secret" in deniedSettings.body())
            val repositoryPath = "/artifacts/repositories/$repositoryId"
            val html = page(repositoryPath)
            assertTrue("tool.zip" in html, html)
            assertTrue("1.0" in html, html)
            val permissionsPath = "/artifacts/settings/permissions?namespaceId=$namespaceId"
            assertTrue("Grant permission" in page(permissionsPath))
            val groupId = fixture.graphql(ui, "{ security { principals { current { groups { id name } } } } }", token).at("security", "principals", "current", "groups").jsonArray.first { it.at("name").jsonPrimitive.content == "administrators" }.at("id").jsonPrimitive.content
            action(permissionsPath, "/artifacts/settings/permissions", "artifacts-permissions.model", "grant", listOf(JsonPrimitive(groupId), JsonPrimitive("VIEW")))
            action(permissionsPath, "/artifacts/settings/permissions", "artifacts-permissions.model", "revoke", listOf(JsonPrimitive(groupId), JsonPrimitive("VIEW"), JsonPrimitive(true)))
            val settingsPath = "/artifacts/settings/repositories/$repositoryId"
            val settings = page(settingsPath)
            assertTrue("GitHub release sync" in settings && "tool.zip" in settings, settings)
            val commit = "a".repeat(40)
            action(settingsPath, "/artifacts/settings/repositories/{id}", "artifacts-publication.model", "complete", listOf(JsonPrimitive(commit), JsonPrimitive(true)))
            val version = fixture.graphql(ui, """{ artifactsAdmin { repository(id: "$repositoryId") { versions { id completed completedCommitSha } } } }""", token).at("artifactsAdmin", "repository", "versions").jsonArray.single()
            assertTrue(version.at("completed").jsonPrimitive.boolean)
            assertEquals(commit, version.at("completedCommitSha").jsonPrimitive.content)
            val dockerId = fixture.graphql(ui, """mutation { artifactsAdmin { createRepository(namespaceId: "$namespaceId", name: "image", type: docker) { id } } }""", token).at("artifactsAdmin", "createRepository", "id").jsonPrimitive.content
            val syncPath = "/artifacts/settings/repositories/$dockerId"
            assertTrue("GitHub Container Registry sync" in page(syncPath))
            action(syncPath, "/artifacts/settings/repositories/{id}", "artifacts-sync.model", "saveSecret", listOf(JsonPrimitive("atlas-ui-registry"), JsonPrimitive("isolated-test-token")))
            action(syncPath, "/artifacts/settings/repositories/{id}", "artifacts-sync.model", "save", listOf(JsonPrimitive("images"), JsonPrimitive("bosca-io/atlas-test"), JsonPrimitive("builder"), JsonPrimitive("atlas-ui-registry"), JsonPrimitive(false)))
            assertTrue("images" in page(syncPath))
            val graphs = fixture.graphql(ui, "{ pipelines { all { name } } }", token).at("pipelines", "all").jsonArray
            assertTrue(graphs.any { it.at("name").jsonPrimitive.content == "Sync Docker Image to GHCR" })
            assertTrue(graphs.any { it.at("name").jsonPrimitive.content == "Publish Raw Artifact to GitHub" })
            val rawOwner = fixture.graphql(ui, """mutation { profiles { edit(profile: {slug: "raw", name: "Raw routing fixture", visibility: PUBLIC, attributes: []}) { id } } }""", token)
                .at("profiles", "edit", "id").jsonPrimitive.content
            fixture.graphql(ui, """mutation { git { createRepository(input: {name: "Git routing fixture", slug: "atlas-ui-files", ownerId: "$rawOwner", initializeWithReadme: true}) { id } } }""", token)
            val gitFilePath = "/raw/atlas-ui-files/raw/main/README.md"
            val gitFile = fixture.request(ui, gitFilePath, token = token)
            assertEquals(200, gitFile.statusCode(), gitFile.body())
            val rawRepository = fixture.graphql(ui, """mutation { artifactsAdmin { createRepository(namespaceId: "$namespaceId", name: "raw", type: raw) { id } } }""", token)
                .at("artifactsAdmin", "createRepository", "id").jsonPrimitive.content
            val artifactBytes = "Artifact routing fixture"
            assertEquals(201, fixture.upload(ui, "/_artifacts/raw/atlas-ui-files/raw/main/README.md", artifactBytes, token).statusCode())
            val rawPage = page("/artifacts/repositories/$rawRepository")
            val rawDownload = Regex("value=\"([^\"]*/raw/atlas-ui-files/raw/main/README.md)\"").find(rawPage)
                ?.groupValues?.get(1) ?: error("Missing raw artifact download URL")
            val artifactFile = fixture.request(ui, java.net.URI(rawDownload).rawPath, token = token)
            assertEquals(200, artifactFile.statusCode(), artifactFile.body())
            assertEquals(artifactBytes, artifactFile.body())
            assertEquals(gitFile.body(), fixture.request(ui, gitFilePath, token = token).body())
            val rawNamespace = fixture.graphql(ui, """mutation { artifactsAdmin { createNamespace(name: "raw", public: false) { id } } }""", token)
                .at("artifactsAdmin", "createNamespace", "id").jsonPrimitive.content
            fixture.graphql(ui, """mutation { artifactsAdmin { createRepository(namespaceId: "$rawNamespace", name: "tool", type: raw) { id } } }""", token)
            val rawNamespaceFile = "/_artifacts/raw/raw/tool/main/README.md"
            assertEquals(201, fixture.upload(ui, rawNamespaceFile, artifactBytes, token).statusCode())
            assertEquals(artifactBytes, fixture.request(ui, rawNamespaceFile, token = token).body())
            val equivalentOriginUi = fixture.startUi(api, fixture.newOrigin(), "HTTP://LOCALHOST:${java.net.URI(api).port}/")
            assertEquals(artifactBytes, fixture.request(equivalentOriginUi, java.net.URI(rawDownload).rawPath, token = token).body())
            assertEquals(gitFile.body(), fixture.request(equivalentOriginUi, gitFilePath, token = token).body())
            val refreshRepository = fixture.graphql(ui, """mutation { artifactsAdmin { createRepository(namespaceId: "$namespaceId", name: "publication-refresh", type: raw) { id } } }""", token)
                .at("artifactsAdmin", "createRepository", "id").jsonPrimitive.content
            assertEquals(201, fixture.upload(ui, "/_artifacts/raw/atlas-ui-files/publication-refresh/initial/file.zip", "initial bytes", token).statusCode())
            val refreshPath = "/artifacts/settings/repositories/$refreshRepository"
            val refreshPage = page(refreshPath)
            for (index in 1..16) {
                assertEquals(201, fixture.upload(ui, "/_artifacts/raw/atlas-ui-files/publication-refresh/newer-$index/file.zip", "new bytes $index", token).statusCode())
            }
            val refreshed = action(refreshPath, "/artifacts/settings/repositories/{id}", "artifacts-publication.model", "refresh", emptyList(), html = refreshPage)
            val refreshedState = Json.parseToJsonElement(refreshed.getValue("state").jsonPrimitive.content).jsonObject
            val selectedVersion = refreshedState.getValue("selectedVersionId").jsonPrimitive.content
            assertEquals("initial", refreshedState.at("reviewedVersion", "version").jsonPrimitive.content)
            assertFalse(refreshedState.getValue("versions").jsonArray.any { it.at("id").jsonPrimitive.content == selectedVersion })
            val selectedOption = Regex("<option\\b[^>]*>").findAll(refreshed.getValue("html").jsonPrimitive.content)
                .firstOrNull { "value=\"$selectedVersion\"" in it.value }
            assertTrue(selectedOption?.value?.contains(" selected") == true, "The version selector must retain the version whose files are being reviewed")
            val assets = Regex("(?:src|href)=\"(/_bml/[^\"]+)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.distinct().toList()
            for (asset in assets) assertEquals(200, fixture.request(ui, asset).statusCode(), asset)
            val favicon = fixture.request(ui, "/favicon.svg")
            assertEquals(200, favicon.statusCode())
            assertTrue("M256 262L491 138L256 21L21 138L256 262Z" in favicon.body())
            if (System.getProperty("atlas.artifacts.browser") == "true") {
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "artifacts/artifacts-ui")
                val browserLog = File(fixture.directory, "browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/artifacts.mjs").directory(project).apply {
                    environment().putAll(mapOf("ATLAS_ARTIFACTS_TEST_URL" to ui, "ATLAS_ARTIFACTS_TEST_TOKEN" to token,
                        "ATLAS_ARTIFACTS_TEST_REPOSITORY" to repositoryId, "ATLAS_ARTIFACTS_TEST_DOCKER" to dockerId,
                        "ATLAS_ARTIFACTS_TEST_NAMESPACE" to namespaceId,
                        "ATLAS_ARTIFACTS_TEST_PASSWORD" to "atlas-admin-password"))
                    redirectErrorStream(true)
                    redirectOutput(browserLog)
                }.start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Browser check timed out: ${browserLog.readText()}")
                    assertEquals(0, browser.exitValue(), browserLog.readText())
                } finally {
                    if (browser.isAlive) browser.destroyForcibly()
                }
            }
            assertTrue(fixture.request(api, "/artifacts/repositories", token = token).statusCode() == 404)
            action(repositoryPath, "/artifacts/repositories/{id}", "artifacts-repository.model", "deleteVersion", listOf(version.at("id"), JsonPrimitive(true)))
            val remaining = fixture.graphql(ui, """{ artifactsAdmin { repository(id: "$repositoryId") { versions { id version } } } }""", token).at("artifactsAdmin", "repository", "versions").jsonArray
            assertTrue(remaining.none { it.at("id") == version.at("id") }, "Only the confirmed version should be removed")
            if (System.getProperty("atlas.artifacts.browser") == "true") assertTrue(remaining.any { it.at("version").jsonPrimitive.content == "browser-1" })
            else assertTrue("No versions published" in page(repositoryPath))
        }
    }
}
