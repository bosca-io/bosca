package bosca.atlas.server

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real configuration, search, context, pipeline, worker and authorization wiring in both hosts. */
class AtlasHubSpotIntegrationTest {
    @Test fun `HubSpot settings context and synchronization use owning services in both hosts`() {
        AtlasProcessFixture().use { fixture ->
            val (api, ui) = fixture.start("hubspot", mapOf("ATLAS_RUNNER_ENABLED" to "false"), "bosca.atlas.server.AtlasHubSpotFixtureKt")
            val standalone = fixture.startUi(api, fixture.newOrigin())
            fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier: "$identifier", password: "$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
            val admin = login("admin", "atlas-admin-password")
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "hubspot-reader@example.test", password: "Reader-test-password", profile: {name: "HubSpot Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val reader = login("hubspot-reader@example.test", "Reader-test-password")
            val person = fixture.graphql(api, "{ profiles { current { id } } }", reader).at("profiles", "current").jsonArray.first().at("id").jsonPrimitive.content
            val company = fixture.seedHubSpotOrganization()
            val initial = """{token:"fixture-stored-token", contactToCompanyAssociationTypeId:279, contactToCompanyAssociationCategory:"HUBSPOT_DEFINED", listIds:[], subscriptionIds:[], expressions:{generic:"{\"firstname\": profile.name}", organization:"{\"name\": organization.name}"}, extra:true}"""
            fixture.graphql(api, """mutation { configurations { setConfiguration(configuration:{key:"hubspot", description:"HubSpot fixture", public:false, value:$initial}) { id } } }""", admin)
            fun read() = fixture.graphql(api, "{ configurations { configuration(key:\"hubspot\") { id value public permissions { action group { id } } } } }", admin).at("configurations", "configuration")
            fun page(origin: String, caller: String = admin) = fixture.request(origin, "/system/integrations/hubspot", token = caller).also { assertEquals(200, it.statusCode(), it.body() + fixture.logs()) }.body()
            fun invoke(origin: String, component: String, method: String, args: List<JsonElement>, caller: String = admin, previous: String? = null): JsonObject {
                val html = page(origin)
                val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-hubspot-$component.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: error("Missing $component state")
                val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                    put("page", "/system/integrations/hubspot"); put("path", "/system/integrations/hubspot")
                    put("stateKey", marker.groupValues[1]); put("state", previous ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                }.toString(), caller)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
            }
            fun args(vararg values: Any) = values.map { if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
            fun ok(state: JsonObject) = assertFalse(state.at("feedback", "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
            for (origin in listOf(ui, standalone)) {
                assertEquals(302, fixture.request(origin, "/system/integrations/hubspot").statusCode())
                assertFalse("fixture-stored-token" in page(origin))
                val fields = args("", false, "123", "USER_DEFINED", "[\"one\",\"two\"]", "[\"three\"]", "{\"firstname\": profile.name}", "{\"name\": organization.name}")
                assertTrue(invoke(origin, "settings", "save", fields, reader).at("feedback", "failed").jsonPrimitive.boolean)
                ok(invoke(origin, "settings", "save", fields))
                assertEquals("fixture-stored-token", read().at("value", "token").jsonPrimitive.content)
                assertTrue(read().at("value", "extra").jsonPrimitive.boolean)
                for (id in listOf(person, company)) {
                    val context = invoke(origin, "preview", "select", args(id))
                    ok(context); assertEquals(id, context.at("context", "profile", "id").jsonPrimitive.content)
                    assertTrue(invoke(origin, "preview", "select", args(id), reader).at("feedback", "failed").jsonPrimitive.boolean)
                    // Avoid lists/subscription calls until browser settings exercise them separately.
                    ok(invoke(origin, "settings", "save", args("", false, "279", "HUBSPOT_DEFINED", "[]", "[]", "{\"firstname\": profile.name}", "{\"name\": organization.name}")))
                    ok(invoke(origin, "preview", "sync", args(false), previous = context.toString()))
                    val attributes = fixture.graphql(api, """{ profiles { profile(id:"$id") { attributes { typeId attributes } } } }""", admin).at("profiles", "profile", "attributes").jsonArray
                    assertTrue(attributes.any { it.at("typeId").jsonPrimitive.content == "bosca.profiles.hubspot.id" })
                }
                assertTrue(invoke(origin, "preview", "sync", args(true), reader).at("feedback", "failed").jsonPrimitive.boolean)
                ok(invoke(origin, "preview", "sync", args(true)))
            }
            fixture.start("hubspot-worker", mapOf("ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_QUEUES" to "profile,index", "ATLAS_RUNNER_LISTENERS_ENABLED" to "false", "ATLAS_SCHEDULER_ENABLED" to "false"), "bosca.atlas.server.AtlasHubSpotFixtureKt")
            fixture.graphql(api, """mutation { storageSystems { reindex(storageName:"Admin Search Index", deleteFirst:false, metadata:false, collections:false, profiles:true) } }""", admin)
            fixture.await("HubSpot profile search is indexed") {
                fixture.graphql(api, """{ search { search(query:{query:"HubSpot",filter:["_type = \"profile\""],storageSystemName:"Admin Search Index",limit:20,offset:0}) { documents { profile { id name } } } } }""", admin)
                    .at("search", "search", "documents").jsonArray.any { it.at("profile").takeUnless { value -> value is JsonNull }?.at("id")?.jsonPrimitive?.content == person }
            }
            assertTrue(fixture.directory.resolve("hubspot.log").readText().contains("HubSpot fixture: POST /crm/v3/objects/contacts"))
            assertTrue(fixture.directory.resolve("hubspot.log").readText().contains("HubSpot fixture: POST /crm/v3/objects/companies"))
            fixture.await("profile worker finishes HubSpot backfill") {
                fixture.graphql(api, "{ scheduler { history(limit:100,offset:0) { id status name } } }", admin).at("scheduler", "history").jsonArray.count { it.at("name").jsonPrimitive.content.contains("hubspot", ignoreCase = true) && it.at("status").jsonPrimitive.content == "COMPLETED" } >= 2
            }
            val configId = read().at("id").jsonPrimitive.content
            val group = fixture.graphql(api, """mutation { security { groups { addGroup(name:"HubSpot editors",description:"Delegated settings",groupType:PRINCIPAL) { id } } } }""", admin).at("security", "groups", "addGroup", "id").jsonPrimitive.content
            val principal = fixture.graphql(api, "{ security { principals { current { id } } } }", reader).at("security", "principals", "current", "id").jsonPrimitive.content
            fixture.graphql(api, """mutation { configurations { setConfiguration(configuration:{key:"hubspot",description:"Delegated HubSpot",public:false,value:$initial,permissions:[{action:VIEW,entityId:"$configId",groupId:"$group"},{action:EDIT,entityId:"$configId",groupId:"$group"}]}) { id } } }""", admin)
            fixture.graphql(api, """mutation { security { addPrincipalGroup(groupId:"$group",principalId:"$principal") } }""", admin)
            val delegated = login("hubspot-reader@example.test", "Reader-test-password")
            for (origin in listOf(ui, standalone)) {
                assertTrue("Stored token" in page(origin, delegated))
                ok(invoke(origin, "settings", "save", args("", true, "279", "HUBSPOT_DEFINED", "[]", "[]", "{\"firstname\": profile.name}", "{\"name\": organization.name}"), delegated))
                assertEquals("", read().at("value", "token").jsonPrimitive.content)
                ok(invoke(origin, "settings", "save", args("delegated-replacement", false, "279", "HUBSPOT_DEFINED", "[]", "[]", "{\"firstname\": profile.name}", "{\"name\": organization.name}"), delegated))
                assertEquals("delegated-replacement", read().at("value", "token").jsonPrimitive.content)
                assertEquals(2, read().at("permissions").jsonArray.count { it.at("group", "id").jsonPrimitive.content == group })
                assertTrue(invoke(origin, "preview", "sync", args(true), delegated).at("feedback", "failed").jsonPrimitive.boolean)
            }
            fixture.start("hubspot-configured", mapOf("ATLAS_RUNNER_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false"))
            assertFalse("HubSpot configuration could not be loaded" in fixture.directory.resolve("hubspot-configured.log").readText())
            if (System.getProperty("atlas.hubspot.browser") == "true") {
                val log = File(fixture.directory, "hubspot-browser.log")
                val browser = ProcessBuilder("node", "src/test/browser/hubspot.mjs").directory(File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")).apply {
                    environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_HUBSPOT_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password", "ATLAS_HUBSPOT_PERSON_ID" to person, "ATLAS_HUBSPOT_COMPANY_ID" to company))
                }.redirectErrorStream(true).redirectOutput(log).start()
                try { assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "HubSpot browser timed out"); assertEquals(0, browser.exitValue(), log.readText() + fixture.logs()) }
                finally { if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() } }
            }
        }
    }
}
