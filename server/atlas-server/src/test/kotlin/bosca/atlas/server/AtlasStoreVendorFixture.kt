package bosca.atlas.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Isolates vendor REST and analytics-history responses while WorkOps reads Git configuration and secrets normally. */
internal class AtlasStoreVendorFixture : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val calls = ConcurrentLinkedQueue<JsonObject>()
    private val vendorFailure = AtomicBoolean(false)
    private val historyFailure = AtomicBoolean(false)
    private val emptyHistory = AtomicBoolean(false)
    private val revision = AtomicInteger(0)
    private val current = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val origin = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { item ->
                fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
                decode(item.substringBefore('=')) to decode(item.substringAfter('=', ""))
            }
            if (!path.startsWith("/fixture/") || path == "/fixture/observations") calls.add(buildJsonObject {
                put("path", path); put("method", exchange.requestMethod); put("authenticated", exchange.requestHeaders.getFirst("Authorization")?.startsWith("Bearer ") == true)
                put("query", JsonObject(query.mapValues { JsonPrimitive(it.value) }))
            })
            var status = 200
            val response = try {
                when {
                    path == "/fixture/state" -> buildJsonObject {
                        put("calls", JsonArray(calls.toList())); put("current", current.toString()); put("previous", current.minusSeconds(3600).toString())
                    }
                    path == "/fixture/mode" && exchange.requestMethod == "POST" -> {
                        val mode = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                        mode["vendorFailure"]?.jsonPrimitive?.boolean?.let(vendorFailure::set)
                        mode["historyFailure"]?.jsonPrimitive?.boolean?.let(historyFailure::set)
                        mode["emptyHistory"]?.jsonPrimitive?.boolean?.let(emptyHistory::set)
                        mode["revision"]?.jsonPrimitive?.int?.let(revision::set)
                        buildJsonObject { put("updated", true) }
                    }
                    path == "/fixture/observations" -> {
                        check(!historyFailure.get()) { "Isolated analytics history unavailable" }
                        if (emptyHistory.get()) JsonArray(emptyList()) else observations(query.getValue("application"), query.getValue("version"))
                    }
                    vendorFailure.get() -> { status = 503; buildJsonObject { put("error", "Isolated store vendor unavailable") } }
                    path.startsWith("/androidpublisher/v3/applications/io.atlas.play/edits") -> {
                        when {
                            path.endsWith("/tracks/production") -> json("""{"track":"production","releases":[{"versionCodes":["42"],"status":"${if (revision.get() == 0) "inProgress" else "completed"}","userFraction":0.25}]}""")
                            exchange.requestMethod == "POST" -> json("""{"id":"play-edit"}""")
                            exchange.requestMethod == "DELETE" -> json("{}")
                            else -> error("Unexpected Play request: $path")
                        }
                    }
                    path == "/v1/apps" -> {
                        check(query["filter[bundleId]"] == "io.atlas.ios")
                        json("""{"data":[{"id":"ios-app"}]}""")
                    }
                    path == "/v1/apps/ios-app/appStoreVersions" -> {
                        check(query["filter[versionString]"] in listOf("1.0.0", "2.0.0"))
                        json("""{"data":[{"id":"ios-version"}]}""")
                    }
                    path == "/v1/builds" -> json("""{"data":[{"id":"ios-build","attributes":{"version":"${if (query["filter[preReleaseVersion.version]"] == "2.0.0") "84" else "41"}","processingState":"VALID"}}]}""")
                    path == "/v1/builds/ios-build/betaAppReviewSubmission" -> json("""{"data":{"attributes":{"betaReviewState":"APPROVED"}}}""")
                    path == "/v1/builds/ios-build/betaGroups" -> json("""{"data":[{"attributes":{"name":"Internal"}},{"attributes":{"name":"External"}}]}""")
                    path == "/v1/apps/ios-app/betaFeedbackCrashSubmissions" -> buildJsonObject { putJsonArray("data") {
                        repeat(4) { index -> add(buildJsonObject {
                            put("id", "crash-$index"); putJsonObject("attributes") {
                                put("comment", if (index == 0) "<img src=x onerror=alert(1)>" else if (index == 1) "" else "Tester feedback $index")
                                put("deviceModel", "Fixture phone"); put("osVersion", "Fixture OS"); put("createdDate", current.toString())
                            }
                        }) }
                    } }
                    path == "/v1/appStoreVersions/ios-version/relationships/appStoreVersionPhasedRelease" -> json("""{"data":{"id":"phased"}}""")
                    path == "/v1/appStoreVersionPhasedReleases/phased" -> json("""{"data":{"attributes":{"phasedReleaseState":"ACTIVE"}}}""")
                    path == "/v1/appStoreVersions/ios-version" -> json("""{"data":{"attributes":{"appVersionState":"${if (revision.get() == 0) "WAITING_FOR_REVIEW" else "READY_FOR_SALE"}"}}}""")
                    else -> { status = 404; buildJsonObject { put("error", "Unexpected store fixture path: $path") } }
                }
            } catch (failure: Exception) {
                status = 503; buildJsonObject { put("error", failure.message.orEmpty()) }
            }
            val bytes = response.toString().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
    }

    private fun json(source: String) = Json.parseToJsonElement(source)

    private fun observations(application: String, version: String): JsonArray {
        require(application in listOf("io.atlas.play", "io.atlas.ios") && version in listOf("1.0.0", "2.0.0"))
        val play = application == "io.atlas.play"
        val store = if (play) "GOOGLE_PLAY" else "APP_STORE"
        fun record(type: String, time: Instant, payload: JsonObject, appVersion: String = version) = buildJsonObject {
            put("telemetry_type", type); put("created", time.toString())
            put("payload", buildJsonObject {
                put("store", store); put("applicationId", application); put("appVersion", appVersion)
                payload.forEach { (key, value) -> put(key, value) }
            }.toString())
        }
        return JsonArray(listOf(
            record("store.vitals", current.minusSeconds(3600), buildJsonObject { put("crashRate", 0.5); put("anrRate", 0.2) }),
            record("store.vitals", current, buildJsonObject { put("crashRate", if (version == "2.0.0") 0.75 else if (play) 0.25 else 0.0); if (play) put("anrRate", 0.1) }),
            record("store.reviews", current.minusSeconds(3600), json("""{"reviews":[{"id":"old","rating":3,"body":"Earlier review"}]}""").jsonObject),
            record("store.reviews", current, buildJsonObject { putJsonArray("reviews") {
                add(buildJsonObject { put("id", "latest"); put("rating", if (version == "2.0.0") 2 else if (play) 4 else 3); put("body", "<review> Release feedback") })
                if (play && version == "1.0.0") add(buildJsonObject { put("id", "second"); put("rating", 5); put("body", "Useful update") })
            } }),
            record("store.reviews", current.plusSeconds(1), json("""{"reviews":[{"id":"wrong","rating":1,"body":"Wrong version review"}]}""").jsonObject, "unrelated-version"),
        ))
    }

    override fun close() = server.stop(0)
}
