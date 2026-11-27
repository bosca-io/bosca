package bosca.atlas.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Real configured task forms and API-script execution using the platform's JVM engine. */
class AtlasWorkOpsFormsIntegrationTest {
    @Test fun `embedded Atlas resolves configured task form adapters`() = verify(false)
    @Test fun `standalone Atlas UI resolves configured task form adapters`() = verify(true)

    private fun verify(standalone: Boolean) {
        // The engine is supplied by a separate JVM worker in native deployments.
        assumeTrue(System.getenv("ATLAS_SERVER_TEST_BINARY").isNullOrBlank())
        AtlasProcessFixture().use { fixture ->
            val (api, embedded) = fixture.start("task-forms", mainClass = "bosca.atlas.server.AtlasWorkOpsFormsFixtureKt")
            val ui = if (standalone) fixture.startUi(api, fixture.newOrigin()) else embedded
            fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            val token = fixture.graphql(ui, """mutation { security { login { password(identifier:"admin",password:"atlas-admin-password") { token { token } } } } }""")
                .at("security", "login", "password", "token", "token").jsonPrimitive.content
            fun query(source: String) = fixture.graphql(ui, source, token)
            val profile = query("{ profiles { current { id } } }").at("profiles", "current").jsonArray.first().at("id").jsonPrimitive.content
            val portfolio = query("""mutation { workOps { portfolios { create(input:{key:"FORM",name:"Forms portfolio",ownerProfileId:"$profile"}) { id } } } }""").at("workOps", "portfolios", "create", "id").jsonPrimitive.content
            val program = query("""mutation { workOps { programs { create(input:{portfolioId:"$portfolio",key:"FORM",name:"Forms program",ownerProfileId:"$profile"}) { id } } } }""").at("workOps", "programs", "create", "id").jsonPrimitive.content
            val project = query("""mutation { workOps { projects { create(input:{programId:"$program",key:"FORM",name:"Forms project",ownerProfileId:"$profile"}) { id } } } }""").at("workOps", "projects", "create", "id").jsonPrimitive.content
            val source = """main {
                val input = (context as BoscaScriptContext).input.jsonObject
                fun text(key: String): String? {
                    val value = input[key]
                    return (if (value is JsonArray) value.firstOrNull() else value)?.jsonPrimitive?.contentOrNull
                }
                val enabled = input["enabled"]
                buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("id", text("choice") ?: "result")
                put("label", (text("prefix") ?: "Choice") + " " + (text("q") ?: "initial"))
                put("caller", authentication.principal()?.id.toString())
                put("enabledIsArray", enabled is JsonArray)
                put("enabledIsString", (if (enabled is JsonArray) enabled.firstOrNull() else enabled)?.jsonPrimitive?.isString ?: false)
            }) }) } }"""
            val script = query("""mutation { scripts { addScript(script:{key:"form-choices",name:"Form choices",type:API,source:${JsonPrimitive(source)}}) { id enabled } } }""").at("scripts", "addScript")
            val scriptId = script.at("id").jsonPrimitive.content
            assertTrue(script.at("enabled").jsonPrimitive.boolean)
            val get = fixture.request(api, "/api/v1/s/form-choices?prefix=GET&q=search&enabled=false", token = token)
            assertEquals(200, get.statusCode(), get.body() + "\n" + fixture.logs())
            val getChoice = Json.parseToJsonElement(get.body()).at("choices").jsonArray.single()
            assertEquals("GET search", getChoice.at("label").jsonPrimitive.content)
            assertTrue(getChoice.at("enabledIsString").jsonPrimitive.boolean)
            assertTrue(getChoice.at("enabledIsArray").jsonPrimitive.boolean)
            val post = fixture.request(api, "/api/v1/s/form-choices", """{"prefix":"POST","q":"search","enabled":false}""", token)
            assertEquals(200, post.statusCode(), post.body() + "\n" + fixture.logs())
            val postChoice = Json.parseToJsonElement(post.body()).at("choices").jsonArray.single()
            assertFalse(postChoice.at("enabledIsString").jsonPrimitive.boolean)
            assertFalse(postChoice.at("enabledIsArray").jsonPrimitive.boolean)
            assertEquals(getChoice.at("caller"), postChoice.at("caller"))
            assertFalse(getChoice.at("caller").jsonPrimitive.content == "null")
            assertTrue(fixture.request(api, "/api/v1/s/form-choices").statusCode() >= 400)
            if (System.getProperty("atlas.workops.browser") == "true") {
                val log = File(fixture.directory, "task-form-browser.log")
                val browser = ProcessBuilder("node", File(System.getProperty("workspaceRoot"), "workops/workops-ui/src/test/browser/task-forms.mjs").absolutePath)
                    .directory(File(System.getProperty("workspaceRoot"), "workops/workops-ui")).apply {
                        environment()["ATLAS_WORKOPS_TEST_URL"] = ui
                        environment()["ATLAS_WORKOPS_TEST_PROJECT"] = project
                        environment()["ATLAS_WORKOPS_TEST_PROGRAM"] = program
                        environment()["ATLAS_WORKOPS_TEST_PROFILE"] = profile
                        environment()["ATLAS_WORKOPS_TEST_SCRIPT"] = scriptId
                        environment()["ATLAS_WORKOPS_TEST_PASSWORD"] = "atlas-admin-password"
                        redirectErrorStream(true); redirectOutput(log)
                    }.start()
                try {
                    assertTrue(browser.waitFor(120, TimeUnit.SECONDS), "Form browser timed out: ${log.readText()}")
                    assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                } finally { if (browser.isAlive) browser.destroyForcibly() }
            }
            query("""mutation { scripts { disableScript(id:"$scriptId") } }""")
            assertTrue(fixture.request(api, "/api/v1/s/form-choices", token = token).statusCode() >= 400)
        }
    }
}
