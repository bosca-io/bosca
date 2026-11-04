package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production-host coverage of account lifecycle, persona wiring and one-time token disclosure. */
class AtlasSecurityIntegrationTest {
    @Test
    fun `System security manages principals personas and tokens under the caller identity`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("security", mapOf("ATLAS_RUNNER_ENABLED" to "false"))
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { v, key -> v.jsonObject.getValue(key) }
            val admin = fixture.graphql(ui, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val signup = fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "security-reader@example.test", password: "Reader-test-password", profile: {name: "Security Reader", visibility: PUBLIC, attributes: []}) { principal { id profiles { id } } } } } }""")
            val readerId = signup.at("security", "signup", "passwordV2", "principal", "id").jsonPrimitive.content
            val reader = fixture.graphql(api, """mutation { security { login { password(identifier: "security-reader@example.test", password: "Reader-test-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val profileId = fixture.graphql(api, "{ profiles { current { id } } }", reader).at("profiles", "current").jsonArray.first().at("id").jsonPrimitive.content
            assertFalse(profileId == "00000000-0000-0000-0000-000000000000")
            val pagingIds = (0 until 26).map { index ->
                fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "principal-page-$index@example.test", password: "Paging-test-password", profile: {name: "Paging $index", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""").at("security", "signup", "passwordV2", "principal", "id").jsonPrimitive.content
            }
            fixture.graphql(api, """mutation { security { admin { markPrincipalDeleted(id: "${pagingIds.first()}") { id } } } }""", admin)
            for (origin in listOf(ui, standalone)) {
                fun matching(query: String, offset: Long = 0, includeDeleted: Boolean = true) =
                    fixture.graphql(origin, """{ security { principals { all(limit:25,offset:$offset,includeDeleted:$includeDeleted,query:${JsonPrimitive(query)}) { id } } } }""", admin).at("security", "principals", "all").jsonArray
                val first = matching("PRINCIPAL-PAGE-")
                val second = matching("principal-page-", offset = 25)
                assertEquals(25, first.size)
                assertEquals(1, second.size)
                assertEquals(pagingIds.toSet(), (first + second).map { it.at("id").jsonPrimitive.content }.toSet())
                assertEquals(25, matching("principal-page-", includeDeleted = false).size)
                assertTrue(matching("principal-page-", offset = 25, includeDeleted = false).isEmpty())
                assertEquals(pagingIds.first(), matching(pagingIds.first().uppercase()).single().at("id").jsonPrimitive.content)
                assertTrue(matching("%_").isEmpty(), "Search treats SQL wildcard characters literally")
                val denied = fixture.request(origin, "/graphql", """{"query":"{ security { principals { all(limit:25,offset:0,query:\"principal-page-\") { id } } } }"}""", reader)
                val errors = Json.parseToJsonElement(denied.body()).jsonObject.getValue("errors").jsonArray
                assertTrue(errors.any { it.at("message").jsonPrimitive.content.contains("Unauthorized access") }, denied.body())
            }
            fixture.start("security-index-worker", mapOf("ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_QUEUES" to "index", "ATLAS_RUNNER_LISTENERS_ENABLED" to "false", "ATLAS_SCHEDULER_ENABLED" to "false"))
            fixture.graphql(api, """mutation { storageSystems { reindex(storageName:"Admin Search Index",deleteFirst:false,metadata:false,collections:false,profiles:true) } }""", admin)
            fixture.await("token owner profile is indexed") {
                fixture.graphql(api, """{ search { search(query:{query:"Security Reader",filter:["_type = \"profile\""],storageSystemName:"Admin Search Index",limit:20,offset:0}) { documents { profile { id } } } } }""", admin)
                    .at("search", "search", "documents").jsonArray.any { it.at("profile").takeUnless { value -> value is JsonNull }?.at("id")?.jsonPrimitive?.content == profileId }
            }
            fun page(path: String, caller: String = admin, origin: String = ui): String {
                val response = fixture.request(origin, path, token = caller)
                assertEquals(200, response.statusCode(), response.body() + "\n" + fixture.logs())
                return response.body()
            }
            fun action(path: String, route: String, prefix: String, method: String, args: List<JsonElement>, caller: String = admin, origin: String = ui): JsonObject {
                val html = page(path, origin = origin)
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"($prefix[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: error("Missing $prefix: $html")
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", route); put("path", path.substringBefore('?')); put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun ok(state: JsonObject) = assertFalse(state.at("feedback").jsonObject["failed"]?.jsonPrimitive?.boolean ?: false, state.toString() + "\n" + fixture.directory.resolve("security.log").readText().takeLast(50_000))
            fun args(vararg values: String): List<JsonElement> = values.map(::JsonPrimitive)
            val principalsPath = "/system/security/principals"
            for (origin in listOf(ui, standalone)) {
                val owners = fixture.request(origin, "/_bml/contract/SystemTokenOwnerSearch/search", "[\"Security Reader\"]", admin)
                assertEquals(200, owners.statusCode(), owners.body() + fixture.logs())
                val ownerChoice = Json.parseToJsonElement(owners.body()).jsonArray.single()
                assertEquals(readerId, ownerChoice.at("id").jsonPrimitive.content)
                assertEquals("Security Reader", ownerChoice.at("label").jsonPrimitive.content)
                val deniedSearch = fixture.request(origin, "/_bml/contract/SystemTokenOwnerSearch/search", "[\"Security Reader\"]", reader)
                assertEquals(500, deniedSearch.statusCode(), deniedSearch.body())
                for (path in listOf(principalsPath, "$principalsPath/$readerId", "/system/security/personas", "/system/security/tokens")) page(path, origin = origin)
                assertTrue("Administrator access is required" in page(principalsPath, reader, origin))
                assertTrue("API tokens" in page("/system/security/tokens", reader, origin))
            }
            val personaPath = "/system/security/personas"
            val personaArgs = args("Security fixture", "Access fixture", "[\"git\",\"system\"]") + JsonPrimitive(true)
            val denied = action(personaPath, personaPath, "system-personas.model", "save", personaArgs, reader)
            assertTrue(denied.at("feedback", "failed").jsonPrimitive.boolean)
            val created = action(personaPath, personaPath, "system-personas.model", "save", personaArgs)
            ok(created)
            val detail = created.at("feedback", "nextHref").jsonPrimitive.content
            val personaId = detail.substringAfterLast('/')
            assertTrue("Security fixture" in page(detail, origin = standalone))
            ok(action(detail, "$personaPath/{id}", "system-personas.model", "save", args("Renamed persona", "Edited", "[\"git\",\"system\",\"cms\"]") + JsonPrimitive(true)))
            ok(action(detail, "$personaPath/{id}", "system-personas.model", "assign", args(profileId)))
            val assigned = fixture.graphql(api, """{ profiles { studioPersonas { byProfile(profileId: "$profileId") { id subsystemIds enabled } } } }""", admin).at("profiles", "studioPersonas", "byProfile").jsonArray.single()
            assertEquals(personaId, assigned.at("id").jsonPrimitive.content)
            assertEquals(listOf("git", "system", "cms"), assigned.at("subsystemIds").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(assigned.at("enabled").jsonPrimitive.boolean)
            val tokenPath = "/system/security/tokens"
            for (origin in listOf(ui, standalone)) {
                val fresh = action(tokenPath, tokenPath, "system-tokens.model", "openCreate", emptyList(), origin = origin)
                val expires = java.time.LocalDateTime.parse(fresh.at("draft", "expires").jsonPrimitive.content)
                val remaining = java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), expires)
                assertTrue(remaining.toDays() in 89..90, fresh.toString())
                val scopeArgs = args("Scope draft", "Retained description", "[\"content:view\"]", "__self__", "2030-01-02T03:04")
                val opened = action(tokenPath, tokenPath, "system-tokens.model", "openScopes", scopeArgs, origin = origin)
                assertTrue(opened.at("scopeEditorOpen").jsonPrimitive.boolean)
                assertEquals("Scope draft", opened.at("draft", "name").jsonPrimitive.content)
                val applied = action(tokenPath, tokenPath, "system-tokens.model", "applyScopes", scopeArgs, origin = origin)
                assertFalse(applied.at("scopeEditorOpen").jsonPrimitive.boolean)
                assertTrue(applied.at("scopeEditorCompleted").jsonPrimitive.boolean)
                assertEquals("Retained description", applied.at("draft", "description").jsonPrimitive.content)
                assertEquals("2030-01-02T03:04", applied.at("draft", "expires").jsonPrimitive.content)
                assertEquals(listOf("content:view"), applied.at("draft", "scopes").jsonArray.map { it.jsonPrimitive.content })
            }
            val tokenState = action(tokenPath, tokenPath, "system-tokens.model", "save", args("System test token", "Created in isolated fixture", "[\"content:view\"]", "__self__", ""))
            ok(tokenState)
            val raw = tokenState.at("rawToken").jsonPrimitive.content
            assertTrue(raw.isNotBlank())
            assertFalse(raw in page(tokenPath))
            val token = fixture.graphql(api, "{ security { apiTokens { my(limit: 25, offset: 0) { id name scopes } } } }", admin).at("security", "apiTokens", "my").jsonArray.single()
            val tokenId = token.at("id").jsonPrimitive.long
            val tokenDetail = "$tokenPath/$tokenId"
            ok(action(tokenDetail, "$tokenPath/{id}", "system-tokens.model", "save", args("Renamed token", "Edited", "[\"content:view\",\"git:read\"]", "__self__", "")))
            ok(action(tokenDetail, "$tokenPath/{id}", "system-tokens.model", "save", args("Renamed token", "", "[]", "__self__", "")))
            val cleared = fixture.graphql(api, """mutation { security { apiTokens { edit(id: $tokenId, name: "Legacy edit") { id scopes description } } } }""", admin).at("security", "apiTokens", "edit")
            assertEquals(JsonNull, cleared.at("scopes"))
            assertEquals("", cleared.at("description").jsonPrimitive.content)
            ok(action(tokenDetail, "$tokenPath/{id}", "system-tokens.model", "change", listOf(JsonPrimitive(tokenId), JsonPrimitive("revoke"), JsonPrimitive(true))))
            assertEquals("Revoked", fixture.graphql(api, """{ security { apiTokens { token(id: $tokenId) { revokedAt } } } }""", admin).at("security", "apiTokens", "token", "revokedAt").let { if (it != JsonNull) "Revoked" else "Active" })
            ok(action(tokenDetail, "$tokenPath/{id}", "system-tokens.model", "change", listOf(JsonPrimitive(tokenId), JsonPrimitive("delete"), JsonPrimitive(true))))
            val crossOwner = action(tokenPath, tokenPath, "system-tokens.model", "save", args("Reader token", "Created by administrator", "[]", readerId, ""))
            ok(crossOwner)
            assertTrue("Reader token" in page("$tokenPath?owner=$readerId", origin = standalone))
            for (origin in listOf(ui, standalone)) {
                val redirect = fixture.request(origin, "/system/artifacts", token = admin)
                assertEquals(301, redirect.statusCode())
                assertEquals("/artifacts/namespaces", redirect.headers().firstValue("Location").orElse(""))
            }
            if (System.getProperty("atlas.security.browser") == "true") {
                val scopeNamespace = fixture.graphql(api, """mutation { artifactsAdmin { createNamespace(name:"token-scope",public:false) { id } } }""", admin).at("artifactsAdmin", "createNamespace", "id").jsonPrimitive.content
                fixture.graphql(api, """mutation { artifactsAdmin { createRepository(namespaceId:"$scopeNamespace",name:"tool",type:raw) { id } } }""", admin)
                fixture.graphql(api, """mutation { artifactsAdmin { createRepository(namespaceId:"$scopeNamespace",name:"package",type:npm) { id } } }""", admin)
                assertEquals(201, fixture.upload(ui, "/_artifacts/raw/token-scope/tool/1.0/tool.txt", "Token scope fixture", admin).statusCode())
                val browserMetadataId = fixture.seedSearchMetadata()
                val browserModelId = fixture.graphql(api, """mutation { models { add(model: {key: "browser-storage-embedding", type: "test", name: "Browser storage model", description: "Isolated browser fixture", configuration: {}}) { id } } }""", admin).at("models", "add", "id").jsonPrimitive.content
                val project = File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")
                val log = File(fixture.directory, "security-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/security.mjs").directory(project).apply {
                    environment()["ATLAS_SYSTEM_TEST_URL"] = ui
                    environment()["ATLAS_SYSTEM_STANDALONE_URL"] = standalone
                    environment()["ATLAS_SYSTEM_TEST_PASSWORD"] = "atlas-admin-password"
                    environment()["ATLAS_SYSTEM_TEST_PRINCIPAL"] = readerId
                    environment()["ATLAS_SYSTEM_TEST_MODEL_ID"] = browserModelId
                    environment()["ATLAS_SYSTEM_TEST_METADATA_ID"] = browserMetadataId
                    redirectErrorStream(true)
                    redirectOutput(log)
                }.start()
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Browser check timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText())
                } finally {
                    if (browser.isAlive) browser.destroyForcibly()
                }
            }
            val lifecyclePath = "$principalsPath/$readerId"
            ok(action(lifecyclePath, "$principalsPath/{id}", "system-principals.model", "change", listOf(JsonPrimitive("mark"), JsonPrimitive(true))))
            assertTrue("Restore principal" in page(lifecyclePath))
            ok(action(lifecyclePath, "$principalsPath/{id}", "system-principals.model", "change", listOf(JsonPrimitive("restore"), JsonPrimitive(true))))
            ok(action(lifecyclePath, "$principalsPath/{id}", "system-principals.model", "change", listOf(JsonPrimitive("mark"), JsonPrimitive(true))))
            ok(action(lifecyclePath, "$principalsPath/{id}", "system-principals.model", "change", listOf(JsonPrimitive("delete"), JsonPrimitive(true))))
            ok(action(detail, "$personaPath/{id}", "system-personas.model", "delete", args(personaId) + JsonPrimitive(true)))
        }
    }
}
