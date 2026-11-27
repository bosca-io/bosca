package bosca.atlas.server

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual Content uploads, persisted dimensions and durable image jobs through the WorkOps UI proxy. */
class AtlasWorkOpsMediaIntegrationTest {
    @Test
    fun `combined Atlas completes image metadata jobs for WorkOps uploads`() = verify(false)

    @Test
    fun `separate Atlas worker completes image metadata jobs for WorkOps uploads`() = verify(true)

    private fun verify(separateWorker: Boolean) {
        AtlasProcessFixture(imageRenditions = false).use { fixture ->
            val (api, embeddedUi) = fixture.start("media", if (separateWorker) mapOf(
                "ATLAS_RUNNER_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false",
            ) else emptyMap())
            val ui = if (separateWorker) fixture.startUi(api, fixture.newOrigin()) else embeddedUi
            val token = fixture.graphql(ui, """mutation {security{login{password(identifier:"admin",password:"atlas-admin-password"){token{token}}}}}""")
                .at("security", "login", "password", "token", "token").jsonPrimitive.content
            fun query(source: String) = fixture.graphql(ui, source, token)
            val reports = File(requireNotNull(System.getProperty("workspaceRoot")),
                "server/atlas-server/build/reports/workops-media/${fixture.directory.name}").apply { mkdirs() }
            val evidence = mutableListOf<JsonObject>()
            var lastMetadataId: String? = null
            var lastAttributes: JsonElement = JsonNull
            var lastJobs = JsonArray(emptyList())
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            try {
                for (format in listOf("png", "jpeg", "gif", "svg")) {
                    // Only the JUnit driver generates raster fixtures. The child uses the packaged Atlas runtime.
                    val bytes = if (format == "svg") {
                        """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="24"><rect width="32" height="24"/></svg>""".encodeToByteArray()
                    } else {
                        ByteArrayOutputStream().use { output ->
                            assertTrue(ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), format, output))
                            output.toByteArray()
                        }
                    }
                    val contentType = if (format == "svg") "image/svg+xml" else "image/$format"
                    val id = query("""mutation {content{metadata{add(metadata:{name:"Atlas media $format",contentType:"$contentType",languageTag:"und",contentLength:${bytes.size}},setReady:false){id}}}}""")
                        .at("content", "metadata", "add", "id").jsonPrimitive.content
                    lastMetadataId = id
                    val boundary = "atlas-media-$id"
                    val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload.$format\"\r\nContent-Type: $contentType\r\n\r\n".encodeToByteArray() +
                        bytes + "\r\n--$boundary--\r\n".encodeToByteArray()
                    val uploaded = http.send(HttpRequest.newBuilder(URI("$ui/api/v1/content/metadata/upload?id=$id"))
                        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer $token")
                        .header("Content-Type", "multipart/form-data; boundary=$boundary")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(201, uploaded.statusCode(), uploaded.body())
                    query("""mutation {content{metadata{setMetadataReady(id:"$id")}}}""")
                    if (separateWorker && format == "png") {
                        fixture.await("uploaded image job is recorded while the API has no consumers") {
                            imageJobs(query("{scheduler{history(source:EVENT,offset:0,limit:200){name status definition errorMessage}}}"), id).isNotEmpty()
                        }
                        val state = query("""{content{metadata(id:"$id"){attributes}}}""").at("content", "metadata", "attributes") as? JsonObject
                        assertTrue(state?.get("size") == null)
                        fixture.start("media-worker", mapOf("ATLAS_API_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false",
                            "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false", "ATLAS_SCHEDULER_ENABLED" to "false",
                            "STORAGE_URL_PREFIX" to api, "STORAGE_URL_UPLOAD_PREFIX" to api))
                    }
                    var jobs = JsonArray(emptyList())
                    var attributes: JsonElement = JsonNull
                    fixture.await("$format dimensions and all uploaded-image jobs complete", observation = {
                        "metadata=$id attributes=$attributes jobs=$jobs"
                    }) {
                        attributes = query("""{content{metadata(id:"$id"){attributes}}}""").at("content", "metadata", "attributes")
                        jobs = imageJobs(query("{scheduler{history(source:EVENT,offset:0,limit:200){name status definition errorMessage}}}"), id)
                        lastAttributes = attributes
                        lastJobs = jobs
                        assertTrue(jobs.none { it.at("status").jsonPrimitive.content == "FAILED" }, jobs.toString())
                        val size = (attributes as? JsonObject)?.get("size")?.jsonPrimitive?.contentOrNull
                        jobs.isNotEmpty() && jobs.all { it.at("status").jsonPrimitive.content == "COMPLETED" } &&
                            (if (format == "svg") size == null else size == "32x24")
                    }
                    val downloaded = fixture.binaryRequest(ui, "/content/image/$id", "GET", ByteArray(0), token)
                        .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals(200, downloaded.statusCode(), downloaded.body().decodeToString())
                    assertContentEquals(bytes, downloaded.body(), "Original $format bytes remain available")
                    evidence += buildJsonObject {
                        put("format", format); put("metadataId", id); put("attributes", attributes); put("jobs", jobs)
                    }
                }
            } finally {
                val databaseAttributes = lastMetadataId?.let { id ->
                    fixture.databaseConnection().use { connection ->
                        connection.prepareStatement("select attributes from metadata where id = ?::uuid").use { statement ->
                            statement.setString(1, id)
                            statement.executeQuery().use { result ->
                                check(result.next())
                                result.getString(1)?.let(Json::parseToJsonElement) ?: JsonNull
                            }
                        }
                    }
                } ?: JsonNull
                File(reports, "evidence.json").writeText(buildJsonObject {
                    put("separateWorker", separateWorker); put("renditionsConfigured", false)
                    put("images", JsonArray(evidence))
                    put("lastMetadataId", lastMetadataId); put("lastApiAttributes", lastAttributes)
                    put("lastDatabaseAttributes", databaseAttributes); put("lastJobs", lastJobs)
                }.toString())
                fixture.directory.listFiles().orEmpty().filter { it.extension in setOf("log", "yaml") }
                    .forEach { it.copyTo(File(reports, it.name), overwrite = true) }
            }
        }
    }

    private fun imageJobs(data: JsonObject, id: String): JsonArray = JsonArray(
        data.at("scheduler", "history").jsonArray.filter {
            it.at("name").jsonPrimitive.contentOrNull == "metadata-process-content" &&
                (it.at("definition") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == id
        },
    )

    private fun JsonElement.at(vararg names: String): JsonElement = names.fold(this) { value, name -> value.jsonObject.getValue(name) }
}
