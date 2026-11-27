package bosca.atlas.server

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.sun.net.httpserver.HttpServer
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.internet.InternetAddress
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Atlas wiring, installed WorkOps notification pipelines, PostgreSQL, NATS and SMTP delivery. */
class AtlasNotificationDeliveryIntegrationTest {
    @Test
    fun `combined Atlas delivers an installed WorkOps email pipeline through the shared message service`() = verify(false)

    @Test
    fun `separate Atlas worker delivers an installed WorkOps email pipeline through the shared message service`() = verify(true)

    private fun verify(separateWorker: Boolean) {
        val smtpHost = JsonPrimitive(System.getenv("ATLAS_FIXTURE_EXTERNAL_HOST")?.takeIf(String::isNotBlank) ?: "127.0.0.1")
        val mail = GreenMail(ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
        mail.start()
        val recipient = "workops-${if (separateWorker) "worker" else "combined"}@example.test"
        mail.setUser(recipient, "recipient", "fixture-password")
        val renders = CopyOnWriteArrayList<JsonObject>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        // The unchanged BML rendering service is an HTTP contract boundary here; delivery is real SMTP.
        val renderer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange -> exchange.use {
                check(exchange.requestMethod == "POST") { "Unexpected renderer request: ${exchange.requestMethod} ${exchange.requestURI}" }
                check(exchange.requestHeaders.getFirst("Authorization").orEmpty().startsWith("Bearer "))
                val input = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                val template = exchange.requestURI.path.substringAfterLast('/')
                renders += buildJsonObject { put("template", template); put("request", input) }
                val payload = input["payload"] ?: JsonNull
                val response = buildJsonObject {
                    put("project", "bosca-messages"); put("templateKey", template); put("version", "fixture-render-1")
                    putJsonObject("email") {
                        put("subject", "$template notification")
                        put("html", "<p>${payload.jsonObject["body"]?.jsonPrimitive?.content.orEmpty()}</p>")
                        put("text", payload.toString()); putJsonArray("images") {}
                    }
                }.toString().encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response)
            } }
            start()
        }
        try {
            AtlasProcessFixture(imageRenditions = false).use { fixture ->
                val publicOrigin = fixture.newOrigin()
                val flags = mapOf("ATLAS_UI_ENABLED" to "false", "APP_URL" to publicOrigin,
                    "MAILER_TYPE" to "smtp", "MAILER_FROM_NAME" to "Atlas Notifications", "MAILER_FROM_EMAIL" to "atlas@example.test",
                    "BML_MESSAGE_SERVER_URL" to "http://127.0.0.1:${renderer.address.port}")
                val freshAtlas = if (separateWorker) null else fixture.start("atlas-mail", flags).first
                val freshDeviceMigrations = freshAtlas?.let {
                    fixture.databaseConnection().use { connection ->
                        connection.prepareStatement("select version from devices.flyway_schema_history where success and version is not null order by installed_rank").use { statement ->
                            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
                        }
                    }.also { assertEquals(listOf("1", "2", "3"), it) }
                }
                val platform = fixture.start("platform-mail", flags + mapOf("GIT_SYNC_LISTENERS" to "false",
                    "SCHEDULER_ENABLED" to "false"), mainClass = "bosca.server.ApplicationKt").first
                val api = freshAtlas ?: fixture.start("atlas-mail", flags + mapOf("ATLAS_RUNNER_ENABLED" to "false")).first
                val token = fixture.graphql(api, """mutation {security{login{password(identifier:"admin",password:"atlas-admin-password"){token{token}}}}}""")
                    .at("security", "login", "password", "token", "token").jsonPrimitive.content
                fun query(source: String) = fixture.graphql(api, source, token)
                val profile = query("{profiles{current{id}}}").at("profiles", "current").jsonArray.first().at("id").jsonPrimitive.content
                fixture.graphql(platform, """mutation {profiles{addAttributes(id:"$profile",attributes:[{
                    typeId:"bosca.profiles.email",attributes:{email:"$recipient"},confidence:100,priority:1,source:"user",visibility:USER
                }])}}""", token)
                query("""mutation {configurations{setConfiguration(configuration:{key:"smtp",description:"Isolated SMTP",public:false,
                    value:{host:$smtpHost,port:${mail.smtp.port},security:"NONE",username:"",password:""}}){id}}}""")
                query("""mutation {workOps{notifications{updatePreferences(input:{eventChannels:{TASK_CREATED:["IN_APP","EMAIL"]},
                    watchAuthored:true,watchCommented:true,dailyDigest:false,mutedTaskIds:[],mutedProjectIds:[]}){version}}}}""")
                val portfolio = query("""mutation {workOps{portfolios{create(input:{key:"MAIL",name:"Mail portfolio",ownerProfileId:"$profile"}){id}}}}""")
                    .at("workOps", "portfolios", "create", "id").jsonPrimitive.content
                val program = query("""mutation {workOps{programs{create(input:{portfolioId:"$portfolio",key:"MAIL",name:"Mail program",ownerProfileId:"$profile"}){id}}}}""")
                    .at("workOps", "programs", "create", "id").jsonPrimitive.content
                val project = query("""mutation {workOps{projects{create(input:{programId:"$program",key:"MAIL",name:"Mail project",ownerProfileId:"$profile"}){id}}}}""")
                    .at("workOps", "projects", "create", "id").jsonPrimitive.content
                val task = query("""mutation {workOps{tasks{create(input:{projectId:"$project",summary:"Atlas SMTP qualification"}){id key}}}}""")
                    .at("workOps", "tasks", "create")
                val taskId = task.at("id").jsonPrimitive.content
                if (separateWorker) fixture.start("mail-worker", flags + mapOf(
                    "ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false",
                    "ATLAS_SCHEDULER_ENABLED" to "false"))
                var runs: JsonArray = JsonArray(emptyList())
                fixture.await("installed WorkOps pipeline and actual SMTP delivery") {
                    runs = query("{pipelines{allRuns(offset:0,limit:100){pipelineName eventName outcome errorMessage runId}}}")
                        .at("pipelines", "allRuns").jsonArray.filter {
                            it.at("eventName").jsonPrimitive.content.endsWith("WorkOpsEmailRequested")
                        }.let(::JsonArray)
                    assertTrue(runs.none { it.at("outcome").jsonPrimitive.content == "FAILED" }, runs.toString())
                    runs.any { it.at("outcome").jsonPrimitive.content == "OK" } &&
                        mail.receivedMessages.any { it.subject == "workops-notification notification" }
                }
                val messages = mail.receivedMessages.filter { it.subject == "workops-notification notification" }
                assertEquals(1, messages.size)
                val received = messages.single()
                assertEquals(recipient, (received.getRecipients(Message.RecipientType.TO).single() as InternetAddress).address)
                val from = received.from.single() as InternetAddress
                assertEquals("atlas@example.test", from.address); assertEquals("Atlas Notifications", from.personal)
                assertTrue("Atlas SMTP qualification" in mimeText(received.content))
                val render = renders.single { it.at("template").jsonPrimitive.content == "workops-notification" }
                val request = render.at("request")
                assertEquals(recipient, request.at("recipientEmail").jsonPrimitive.content)
                assertEquals(profile, request.at("recipientId").jsonPrimitive.content)
                assertEquals(task.at("key"), request.at("payload", "entityKey"))
                assertEquals("$publicOrigin/workops/tasks/$taskId", request.at("payload", "actionUrl").jsonPrimitive.content)
                fixture.await("in-app notification remains available alongside email") {
                    query("{workOps{notifications{mine(offset:0,limit:100){taskId}}}}")
                        .at("workOps", "notifications", "mine").jsonArray.any { it.at("taskId").jsonPrimitive.contentOrNull == taskId }
                }
                val reports = File(requireNotNull(System.getProperty("workspaceRoot")),
                    "server/atlas-server/build/reports/notification-delivery/${fixture.directory.name}").apply { mkdirs() }
                File(reports, "evidence.json").writeText(buildJsonObject {
                    put("separateWorker", separateWorker); put("task", task); put("pipelineRuns", runs)
                    freshDeviceMigrations?.let { versions -> putJsonArray("freshDeviceMigrations") { versions.forEach { add(it) } } }
                    put("render", render); put("smtpRecipient", recipient); put("smtpSubject", received.subject)
                    put("smtpBody", mimeText(received.content))
                }.toString())
            }
        } finally { renderer.stop(0); executor.close(); mail.stop() }
    }

    private fun mimeText(value: Any): String = when (value) {
        is Multipart -> (0 until value.count).joinToString("\n") { mimeText(value.getBodyPart(it).content) }
        else -> value.toString()
    }

    private fun JsonElement.at(vararg names: String): JsonElement = names.fold(this) { value, name -> value.jsonObject.getValue(name) }
}
