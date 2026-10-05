package bosca.store.pipelines

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppStoreConnectPublisherTest {

    private val server = MockWebServer().apply { start() }
    private val publisher = AppStoreConnectPublisher(
        http = okhttp3.OkHttpClient(),
        baseUrl = server.url("").toString().trimEnd('/'),
    )
    private val privateKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().private.encoded
        .let(Base64.getEncoder()::encodeToString)
        .let { "-----BEGIN PRIVATE KEY-----\n$it\n-----END PRIVATE KEY-----" }
    private val credential = kotlinx.serialization.json.buildJsonObject {
            put("issuerId", "69a6de00-0000-0000-0000-000000000000")
            put("keyId", "ABC123DEFG")
            put("privateKey", privateKey)
        }.toString()

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `assign and remove beta groups resolve the build once and use JSON API relationships`() = runTest {
        enqueueList("app-1")
        enqueueList("build-1")
        enqueueList("group-1")
        enqueueEmpty()
        enqueueList("group-2")
        enqueueEmpty()

        val assigned = publisher.assignBetaGroups(
            credential, "io.example.app", "1.4.0", "42", listOf("Beta", "Employees", "Beta"),
        )

        assertEquals("ASSIGNED", assigned.state)
        val requests = List(6) { server.takeRequest() }
        assertEquals("io.example.app", requests[0].url.queryParameter("filter[bundleId]"))
        assertEquals("42", requests[1].url.queryParameter("filter[version]"))
        assertEquals("POST", requests[3].method)
        assertEquals("/v1/betaGroups/group-1/relationships/builds", requests[3].target)
        val relationship = Json.parseToJsonElement(requests[3].body?.utf8().orEmpty()).jsonObject
            .getValue("data").jsonArray.single().jsonObject
        assertEquals("build-1", relationship.getValue("id").jsonPrimitive.content)
        assertEquals("Bearer", requests[0].headers["Authorization"]?.substringBefore(' '))

        enqueueList("app-1")
        enqueueList("build-1")
        enqueueList("group-1")
        enqueueEmpty()
        val removed = publisher.removeBetaGroups(
            credential, "io.example.app", "1.4.0", "42", listOf("Beta"),
        )
        assertEquals("REMOVED", removed.state)
        repeat(3) { server.takeRequest() }
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/betaGroups/group-1/relationships/builds", delete.target)
        assertTrue("build-1" in delete.body?.utf8().orEmpty())
    }

    @Test
    fun `full review attaches build submits and enables phased release`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        enqueueList("build-1")
        enqueueEmpty()
        enqueueEmpty()
        enqueueEmpty()
        enqueueVersion("PENDING_DEVELOPER_RELEASE")

        val result = publisher.submitForReview(credential, "io.example.app", "1.4.0", "42", true)

        assertEquals("PENDING_DEVELOPER_RELEASE", result.state)
        val requests = List(7) { server.takeRequest() }
        assertEquals("IOS", requests[1].url.queryParameter("filter[platform]"))
        assertEquals("PATCH", requests[3].method)
        assertEquals("/v1/appStoreVersions/version-1/relationships/build", requests[3].target)
        assertTrue("build-1" in requests[3].body?.utf8().orEmpty())
        assertEquals("POST", requests[4].method)
        assertEquals("/v1/appStoreVersionSubmissions", requests[4].target)
        assertEquals("POST", requests[5].method)
        assertEquals("/v1/appStoreVersionPhasedReleases", requests[5].target)
        assertTrue("ACTIVE" in requests[5].body?.utf8().orEmpty())
    }

    @Test
    fun `full review creates and updates localized What's New before submission`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse(
            """{"data":[null,[],{"attributes":{"locale":"es-ES"}},{"id":"bad-attributes","attributes":[]},{"id":"blank-id","attributes":{"locale":"es-ES"}},{"id":"loc-en","attributes":{"locale":"en-US"}},{"id":"ignored","attributes":{}}]}""",
        ))
        enqueueEmpty()
        enqueueEmpty()
        enqueueEmpty()
        enqueueEmpty()
        enqueueVersion("PENDING_DEVELOPER_RELEASE")

        val result = publisher.submitForReviewWithReleaseNotes(
            credential,
            "io.example.app",
            "1.4.0",
            "42",
            false,
            linkedMapOf("en-US" to "Faster startup", "fr-FR" to "Démarrage plus rapide"),
        )

        assertEquals("PENDING_DEVELOPER_RELEASE", result.state)
        val requests = List(9) { server.takeRequest() }
        assertEquals("/v1/appStoreVersions/version-1/appStoreVersionLocalizations?limit=200", requests[3].target)
        assertEquals("PATCH", requests[4].method)
        assertEquals("/v1/appStoreVersionLocalizations/loc-en", requests[4].target)
        assertTrue("Faster startup" in requests[4].body?.utf8().orEmpty())
        assertEquals("POST", requests[5].method)
        assertEquals("/v1/appStoreVersionLocalizations", requests[5].target)
        assertTrue("fr-FR" in requests[5].body?.utf8().orEmpty())
        assertEquals("/v1/appStoreVersions/version-1/relationships/build", requests[6].target)
        assertEquals("/v1/appStoreVersionSubmissions", requests[7].target)
    }

    @Test
    fun `localized submission treats a malformed localization collection as empty`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse("""{"data":{}}"""))
        enqueueEmpty()
        enqueueEmpty()
        enqueueEmpty()
        enqueueVersion("PENDING_DEVELOPER_RELEASE")

        publisher.submitForReviewWithReleaseNotes(
            credential, "io.example.app", "1.4.0", "42", false, mapOf("en-US" to "Faster startup"),
        )

        repeat(4) { server.takeRequest() }
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/v1/appStoreVersionLocalizations", create.target)
    }

    @Test
    fun `TestFlight assignment creates and updates localized What to Test before group assignment`() = runTest {
        enqueueList("app-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse(
            """{"data":[{"id":"beta-en","attributes":{"locale":"en-US"}}]}""",
        ))
        enqueueEmpty()
        enqueueEmpty()
        enqueueList("group-1")
        enqueueEmpty()

        val result = publisher.assignBetaGroupsWithReleaseNotes(
            credential,
            "io.example.app",
            "1.4.0",
            "42",
            listOf("Beta"),
            linkedMapOf("en-US" to "Try login", "de-DE" to "Anmeldung testen"),
        )

        assertEquals("ASSIGNED", result.state)
        val requests = List(7) { server.takeRequest() }
        assertEquals("/v1/builds/build-1/betaBuildLocalizations?limit=200", requests[2].target)
        assertEquals("PATCH", requests[3].method)
        assertEquals("/v1/betaBuildLocalizations/beta-en", requests[3].target)
        assertTrue("Try login" in requests[3].body?.utf8().orEmpty())
        assertEquals("POST", requests[4].method)
        assertEquals("/v1/betaBuildLocalizations", requests[4].target)
        assertTrue("de-DE" in requests[4].body?.utf8().orEmpty())
        assertEquals("/v1/betaGroups/group-1/relationships/builds", requests[6].target)
    }

    @Test
    fun `localized store notes fail loudly for missing locale and field values`() = runTest {
        assertTrue(
            "at least one locale" in assertFailsWith<IllegalArgumentException> {
                publisher.submitForReviewWithReleaseNotes(
                    credential, "io.example.app", "1.4.0", "42", false, emptyMap(),
                )
            }.message.orEmpty(),
        )
        assertTrue(
            "en-US" in assertFailsWith<IllegalArgumentException> {
                publisher.assignBetaGroupsWithReleaseNotes(
                    credential, "io.example.app", "1.4.0", "42", listOf("Beta"), mapOf("en-US" to " "),
                )
            }.message.orEmpty(),
        )
        assertTrue(
            "blank locale" in assertFailsWith<IllegalArgumentException> {
                publisher.assignBetaGroupsWithReleaseNotes(
                    credential, "io.example.app", "1.4.0", "42", listOf("Beta"), mapOf(" " to "Test this"),
                )
            }.message.orEmpty(),
        )
    }

    @Test
    fun `phased rollback patches the relationship resource id rather than the version id`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":{"type":"appStoreVersionPhasedReleases","id":"phase-9"}}"""))
        enqueueEmpty()
        enqueueVersion("READY_FOR_SALE")

        val result = publisher.haltPhasedRelease(credential, "io.example.app", "1.4.0")

        assertEquals("READY_FOR_SALE", result.state)
        repeat(3) { server.takeRequest() }
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v1/appStoreVersionPhasedReleases/phase-9", patch.target)
        val body = patch.body?.utf8().orEmpty()
        assertTrue("PAUSED" in body && "phase-9" in body)
    }

    @Test
    fun `review state uses the build beta relationship or full version state according to the enum`() = runTest {
        enqueueList("app-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse("""{"data":{"attributes":{"betaReviewState":"IN_REVIEW"}}}"""))
        assertEquals(
            "IN_REVIEW",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.BETA),
        )
        repeat(2) { server.takeRequest() }
        assertEquals("/v1/builds/build-1/betaAppReviewSubmission", server.takeRequest().target)

        enqueueList("app-1")
        enqueueList("version-1")
        enqueueVersion("WAITING_FOR_REVIEW")
        assertEquals(
            "WAITING_FOR_REVIEW",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.APP_STORE),
        )
        repeat(2) { server.takeRequest() }
        assertEquals("/v1/appStoreVersions/version-1", server.takeRequest().target)
    }

    @Test
    fun `state reads review processing phased release and TestFlight groups`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse(
            """{"data":[{"type":"builds","id":"build-1","attributes":{"version":"42","processingState":"VALID"}}]}""",
        ))
        server.enqueue(jsonResponse("""{"data":{"attributes":{"betaReviewState":"APPROVED"}}}"""))
        server.enqueue(jsonResponse(
            """{"data":[null,{"id":"bad-attributes","attributes":[]},{"id":"missing-name","attributes":{}},{"id":"group-1","attributes":{"name":"Internal"}},{"id":"group-2","attributes":{"name":"External"}}]}""",
        ))
        server.enqueue(jsonResponse(
            """{"data":[null,{"id":"bad-attributes","attributes":[]},{"attributes":{}},{"id":"feedback-1","attributes":{"comment":"Crashes at launch","email":"tester@example.com","deviceModel":"iPhone17,1","osVersion":"19.0","createdDate":"2026-07-22T00:00:00Z"}},{"id":"feedback-2","attributes":{}}]}""",
        ))
        server.enqueue(jsonResponse("""{"data":{"type":"appStoreVersionPhasedReleases","id":"phase-1"}}"""))
        server.enqueue(jsonResponse("""{"data":{"attributes":{"phasedReleaseState":"ACTIVE"}}}"""))
        enqueueVersion("WAITING_FOR_REVIEW")

        val state = publisher.state(credential, "io.example.app", "1.4.0", "42")

        assertEquals("42", state.buildNumber)
        assertEquals("VALID", state.buildProcessingState)
        assertEquals("APPROVED", state.betaReviewState)
        assertEquals("ACTIVE", state.phasedReleaseState)
        assertEquals(listOf("Internal", "External"), state.testFlightGroups)
        assertEquals(
            listOf(
                AppStoreCrashFeedbackResult(
                    "feedback-1", "Crashes at launch", "tester@example.com", "iPhone17,1", "19.0",
                    "2026-07-22T00:00:00Z",
                ),
                AppStoreCrashFeedbackResult("feedback-2", null, null, null, null, null),
            ),
            state.testFlightCrashFeedback,
        )
        assertEquals("WAITING_FOR_REVIEW", state.reviewState)
        val requests = List(9) { server.takeRequest() }
        assertEquals("42", requests[2].url.queryParameter("filter[version]"))
        assertEquals("-uploadedDate", requests[2].url.queryParameter("sort"))
        assertEquals("build-1", requests[5].url.queryParameter("filter[build]"))
    }

    @Test
    fun `state treats absent optional review and phased resources as empty`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse(
            """{"data":[{"type":"builds","id":"build-1","attributes":{"version":"43"}}]}""",
        ))
        server.enqueue(MockResponse.Builder().code(404).body("missing").build())
        server.enqueue(jsonResponse("""{"data":[]}"""))
        server.enqueue(jsonResponse("""{"data":[]}"""))
        server.enqueue(MockResponse.Builder().code(404).body("missing").build())
        enqueueVersion("READY_FOR_SALE")

        val state = publisher.state(credential, "io.example.app", "1.4.0")

        assertEquals(null, state.betaReviewState)
        assertEquals(null, state.phasedReleaseState)
        assertEquals(emptyList(), state.testFlightGroups)
        assertEquals(emptyList(), state.testFlightCrashFeedback)
        assertEquals("READY_FOR_SALE", state.reviewState)
    }

    @Test
    fun `state remains useful when no build is available for the version`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":[]}"""))
        server.enqueue(jsonResponse("""{"data":null}"""))
        enqueueVersion("READY_FOR_SALE")

        val state = publisher.state(credential, "io.example.app", "1.4.0")

        assertEquals(null, state.buildNumber)
        assertEquals(null, state.betaReviewState)
        assertEquals(emptyList(), state.testFlightGroups)
        assertEquals(emptyList(), state.testFlightCrashFeedback)
        assertEquals(null, state.phasedReleaseState)
        assertEquals("READY_FOR_SALE", state.reviewState)
        val requests = List(5) { server.takeRequest() }
        assertTrue(requests.none { "/betaGroups" in it.target || "/betaFeedbackCrashSubmissions" in it.target })

        listOf("""{"data":{}}""", """{"data":[null]}""").forEach { buildResponse ->
            enqueueList("app-1")
            enqueueList("version-1")
            server.enqueue(jsonResponse(buildResponse))
            server.enqueue(jsonResponse("""{"data":null}"""))
            enqueueVersion("READY_FOR_SALE")
            assertEquals(null, publisher.state(credential, "io.example.app", "1.4.0").buildNumber)
            repeat(5) { server.takeRequest() }
        }
    }

    @Test
    fun `state tolerates blank and malformed optional resources but exposes vendor failures`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":[{"id":"build-1","attributes":[]}]}"""))
        server.enqueue(MockResponse.Builder().code(200).body("").build())
        server.enqueue(jsonResponse("""{"data":{}}"""))
        server.enqueue(jsonResponse("""{"data":{}}"""))
        server.enqueue(jsonResponse("""{"data":{"id":"phase-1"}}"""))
        server.enqueue(jsonResponse("""{"data":{"attributes":[]}}"""))
        server.enqueue(jsonResponse("""{"data":{"attributes":[]}}"""))

        val state = publisher.state(credential, "io.example.app", "1.4.0", " ")

        assertEquals(null, state.buildNumber)
        assertEquals(null, state.betaReviewState)
        assertEquals(emptyList(), state.testFlightGroups)
        assertEquals(emptyList(), state.testFlightCrashFeedback)
        assertEquals(null, state.phasedReleaseState)
        assertEquals("UNKNOWN", state.reviewState)
        val requests = List(9) { server.takeRequest() }
        assertEquals(null, requests[2].url.queryParameter("filter[version]"))

        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":[{"id":"build-1","attributes":{}}]}"""))
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        assertTrue(
            "503" in assertFailsWith<IllegalStateException> {
                publisher.state(credential, "io.example.app", "1.4.0", "42")
            }.message.orEmpty(),
        )
    }

    @Test
    fun `customer reviews map normalized fields and validate limits`() = runTest {
        enqueueList("app-1")
        server.enqueue(jsonResponse(
            """{"data":[
              null,
              {"id":"bad-attributes","attributes":[]},
              {"attributes":{"rating":4}},
              {"id":"r1","attributes":{"rating":5,"title":"Great","body":"Love it","reviewerNickname":"Sam","territory":"USA","createdDate":"2026-07-22T00:00:00Z"}},
              {"id":"r2","attributes":{"rating":3}},
              {"id":"bad-rating","attributes":{"rating":"bad"}},
              {"id":"missing-rating","attributes":{"body":"ignored"}}
            ]}""",
        ))

        val reviews = publisher.reviews(credential, "io.example.app", 25)

        assertEquals(
            listOf(
                AppStoreReviewResult("r1", 5, "Great", "Love it", "Sam", "USA", "2026-07-22T00:00:00Z"),
                AppStoreReviewResult("r2", 3, null, "", null, null, null),
            ),
            reviews,
        )
        val requests = List(2) { server.takeRequest() }
        assertEquals("25", requests[1].url.queryParameter("limit"))
        assertEquals("-createdDate", requests[1].url.queryParameter("sort"))
        assertFailsWith<IllegalArgumentException> {
            publisher.reviews(credential, "io.example.app", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.reviews(credential, "io.example.app", 201)
        }
    }

    @Test
    fun `full review without phased release supports the legacy p8 key and deprecated state fallback`() = runTest {
        enqueueList("app-1")
        enqueueList("version-1")
        enqueueList("build-1")
        enqueueEmpty()
        enqueueEmpty()
        server.enqueue(jsonResponse("""{"data":{"attributes":{"appStoreState":"ACCEPTED"}}}"""))

        val result = publisher.submitForReview(
            credential.replace("\"privateKey\"", "\"p8\""), "io.example.app", "1.4.0", "42", false,
        )

        assertEquals("ACCEPTED", result.state)
        val requests = List(6) { server.takeRequest() }
        assertTrue(requests.none { it.target.startsWith("/v1/appStoreVersionPhasedReleases") })
    }

    @Test
    fun `query parameters are encoded without changing their values`() = runTest {
        enqueueList("app-1")
        enqueueList("build-1")
        enqueueList("group-1")
        enqueueEmpty()

        publisher.assignBetaGroups(
            credential, "io.example.app+beta", "1.4 release", "42+7", listOf("Staff & Family"),
        )

        val requests = List(4) { server.takeRequest() }
        assertEquals("io.example.app+beta", requests[0].url.queryParameter("filter[bundleId]"))
        assertEquals("1.4 release", requests[1].url.queryParameter("filter[preReleaseVersion.version]"))
        assertEquals("42+7", requests[1].url.queryParameter("filter[version]"))
        assertEquals("Staff & Family", requests[2].url.queryParameter("filter[name]"))
    }

    @Test
    fun `publisher fails closed for empty groups missing resources and HTTP failures`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            publisher.assignBetaGroups(credential, "io.example.app", "1.4.0", "42", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.removeBetaGroups(credential, "io.example.app", "1.4.0", "42", emptyList())
        }
        val missingKey = """{"issuerId":"issuer","keyId":"key"}"""
        assertTrue(
            "requires privateKey" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(missingKey, "io.example.app", "1.4.0", "42", listOf("Beta"))
            }.message.orEmpty(),
        )

        val missingIssuer = kotlinx.serialization.json.buildJsonObject {
            put("keyId", "key")
            put("privateKey", privateKey)
        }.toString()
        val blankIssuer = kotlinx.serialization.json.buildJsonObject {
            put("issuerId", " ")
            put("keyId", "key")
            put("privateKey", privateKey)
        }.toString()
        val missingKeyId = kotlinx.serialization.json.buildJsonObject {
            put("issuerId", "issuer")
            put("privateKey", privateKey)
        }.toString()
        listOf(
            "[]" to "must be a JSON object",
            missingIssuer to "requires issuerId",
            blankIssuer to "requires issuerId",
            """{"issuerId":null,"keyId":"key","privateKey":"unused"}""" to "requires issuerId",
            missingKeyId to "requires keyId",
            """{"issuerId":"issuer","keyId":{},"privateKey":"unused"}""" to "requires keyId",
        ).forEach { (invalidCredential, expectedMessage) ->
            assertTrue(
                expectedMessage in assertFailsWith<IllegalStateException> {
                    publisher.assignBetaGroups(
                        invalidCredential, "io.example.app", "1.4.0", "42", listOf("Beta"),
                    )
                }.message.orEmpty(),
            )
        }

        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertTrue(
            "not found" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(credential, "io.missing", "1.4.0", "42", listOf("Beta"))
            }.message.orEmpty(),
        )

        server.enqueue(jsonResponse("""{"data":{}}"""))
        assertTrue(
            "not found" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(credential, "io.malformed", "1.4.0", "42", listOf("Beta"))
            }.message.orEmpty(),
        )

        server.enqueue(jsonResponse("""{"data":[{"id":" "}]}"""))
        assertTrue(
            "not found" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(credential, "io.blank", "1.4.0", "42", listOf("Beta"))
            }.message.orEmpty(),
        )

        enqueueList("app-1")
        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertTrue(
            "uploaded build" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(credential, "io.example.app", "1.4.0", "99", listOf("Beta"))
            }.message.orEmpty(),
        )

        enqueueList("app-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertTrue(
            "TestFlight group" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(credential, "io.example.app", "1.4.0", "42", listOf("Missing"))
            }.message.orEmpty(),
        )

        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        assertTrue(
            "503" in assertFailsWith<IllegalStateException> {
                publisher.assignBetaGroups(credential, "io.example.app", "1.4.0", "42", listOf("Beta"))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `publisher fails closed when a version or phased release relationship is missing`() = runTest {
        enqueueList("app-1")
        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertTrue(
            "no editable version" in assertFailsWith<IllegalStateException> {
                publisher.submitForReview(credential, "io.example.app", "9.9.9", "42", false)
            }.message.orEmpty(),
        )

        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":null}"""))
        assertTrue(
            "no phased release" in assertFailsWith<IllegalStateException> {
                publisher.haltPhasedRelease(credential, "io.example.app", "1.4.0")
            }.message.orEmpty(),
        )

        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":{}}"""))
        assertTrue(
            "no phased release" in assertFailsWith<IllegalStateException> {
                publisher.haltPhasedRelease(credential, "io.example.app", "1.4.0")
            }.message.orEmpty(),
        )
    }

    @Test
    fun `review reports UNKNOWN when Apple omits a state`() = runTest {
        enqueueList("app-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse("""{"data":{"attributes":{}}}"""))
        assertEquals(
            "UNKNOWN",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.BETA),
        )

        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":{"attributes":{}}}"""))
        assertEquals(
            "UNKNOWN",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.APP_STORE),
        )

        enqueueList("app-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertEquals(
            "UNKNOWN",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.BETA),
        )

        enqueueList("app-1")
        enqueueList("build-1")
        server.enqueue(jsonResponse("""{"data":{"attributes":[]}}"""))
        assertEquals(
            "UNKNOWN",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.BETA),
        )

        enqueueList("app-1")
        enqueueList("version-1")
        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertEquals(
            "UNKNOWN",
            publisher.reviewState(credential, "io.example.app", "1.4.0", "42", AppStoreReviewMode.APP_STORE),
        )
    }

    private fun enqueueList(id: String) = server.enqueue(
        jsonResponse("""{"data":[{"type":"objects","id":"$id"}]}"""),
    )

    private fun enqueueVersion(state: String) = server.enqueue(
        jsonResponse("""{"data":{"id":"version-1","attributes":{"appVersionState":"$state"}}}"""),
    )

    private fun enqueueEmpty() = server.enqueue(jsonResponse(""))

    private fun jsonResponse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
