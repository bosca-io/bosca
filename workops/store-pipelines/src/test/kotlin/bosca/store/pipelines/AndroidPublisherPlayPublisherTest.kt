package bosca.store.pipelines

import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.AppEdit
import com.google.api.services.androidpublisher.model.Bundle
import com.google.api.services.androidpublisher.model.BundlesListResponse
import com.google.api.services.androidpublisher.model.Track
import com.google.api.services.androidpublisher.model.TrackRelease
import com.google.api.services.androidpublisher.model.Comment
import com.google.api.services.androidpublisher.model.Review
import com.google.api.services.androidpublisher.model.ReviewsListResponse
import com.google.api.services.androidpublisher.model.Timestamp
import com.google.api.services.androidpublisher.model.UserComment
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.Base64
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidPublisherPlayPublisherTest {

    private val server = MockWebServer().apply { start() }
    private val publisher = AndroidPublisherPlayPublisher(
        http = okhttp3.OkHttpClient(),
        reportingBaseUrl = server.url("").toString().trimEnd('/'),
        reportingAccessToken = { credential ->
            assertEquals("credential-json", credential)
            "reporting-token"
        },
    )

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `crash rate queries the freshest hourly window for the durable version code`() = runTest {
        server.enqueue(jsonResponse(freshness()))
        server.enqueue(jsonResponse(
            """{"rows":[
                {"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.25"}},{"metric":"anrRate","decimalValue":{"value":"0.10"}}]},
                {"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.75"}},{"metric":"anrRate","decimalValue":{"value":"0.20"}}]}
            ],"nextPageToken":"next-page"}""",
        ))
        server.enqueue(jsonResponse(
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":"1.25"}}]}]}""",
        ))

        val result = publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(2))

        assertEquals(1.25, result.crashRate)
        assertEquals(0.20, result.anrRate)
        assertEquals(42, result.versionCode)
        assertEquals(Duration.ofHours(2), result.window)
        val metadata = server.takeRequest()
        assertEquals("GET", metadata.method)
        assertEquals("/v1beta1/apps/io.example.app/crashRateMetricSet", metadata.target)
        assertEquals("Bearer reporting-token", metadata.headers["Authorization"])
        val query = server.takeRequest()
        assertEquals("POST", query.method)
        assertEquals("/v1beta1/apps/io.example.app/crashRateMetricSet:query", query.target)
        val body = Json.parseToJsonElement(query.body?.utf8().orEmpty()).jsonObject
        assertEquals("versionCode = 42", body.getValue("filter").jsonPrimitive.content)
        assertEquals("versionCode", body.getValue("dimensions").jsonArray.single().jsonPrimitive.content)
        val timeline = body.getValue("timelineSpec").jsonObject
        assertEquals("HOURLY", timeline.getValue("aggregationPeriod").jsonPrimitive.content)
        assertEquals(10, timeline.getValue("startTime").jsonObject.getValue("hours").jsonPrimitive.content.toInt())
        assertEquals(12, timeline.getValue("endTime").jsonObject.getValue("hours").jsonPrimitive.content.toInt())
        val nextQueryBody = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()).jsonObject
        assertEquals("next-page", nextQueryBody.getValue("pageToken").jsonPrimitive.content)
    }

    @Test
    fun `crash rate supports an unscoped query and fails closed when samples are absent`() = runTest {
        server.enqueue(jsonResponse(freshness()))
        server.enqueue(jsonResponse("""{"rows":[]}"""))

        val failure = assertFailsWith<IllegalStateException> {
            publisher.crashRate("credential-json", "io.example.app", null, Duration.ofHours(24))
        }

        assertTrue("no crash-rate samples" in failure.message.orEmpty())
        server.takeRequest()
        val queryBody = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()).jsonObject
        assertTrue("filter" !in queryBody)
        assertTrue(queryBody.getValue("dimensions").jsonArray.isEmpty())

        server.enqueue(jsonResponse(freshness()))
        server.enqueue(jsonResponse("{}"))
        val scopedFailure = assertFailsWith<IllegalStateException> {
            publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(24))
        }
        assertTrue("versionCode 42" in scopedFailure.message.orEmpty())
    }

    @Test
    fun `crash rate accepts UTC freshness defaults and ignores malformed metric rows`() = runTest {
        server.enqueue(jsonResponse(
            """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":{"year":2026,"month":7,"day":21}}]}}""",
        ))
        server.enqueue(jsonResponse(
            """{"rows":[
                {},
                {"metrics":[]},
                {"metrics":[{"metric":"distinctUsers","decimalValue":{"value":"10"}}]},
                {"metrics":[{"metric":"crashRate"}]},
                {"metrics":[{"metric":"crashRate","decimalValue":{"value":"not-a-number"}}]},
                {"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.4"}}]}
            ],"nextPageToken":""}""",
        ))

        val result = publisher.crashRate("credential-json", "io.example.app", null, Duration.ofHours(1))

        assertEquals(0.4, result.crashRate)
        server.takeRequest()
        val query = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()).jsonObject
        val end = query.getValue("timelineSpec").jsonObject.getValue("endTime").jsonObject
        assertTrue("hours" !in end)
        assertEquals("UTC", query.getValue("timelineSpec").jsonObject
            .getValue("startTime").jsonObject.getValue("timeZone").jsonObject.getValue("id").jsonPrimitive.content)

        server.enqueue(jsonResponse(
            """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":{"year":2026,"month":7,"day":21,"timeZone":{"id":null}}}]}}""",
        ))
        server.enqueue(jsonResponse(
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.3"}}]}],"nextPageToken":null}""",
        ))
        assertEquals(
            0.3,
            publisher.crashRate("credential-json", "io.example.app", null, Duration.ofHours(1)).crashRate,
        )

        server.enqueue(jsonResponse(
            """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":{"year":2026,"month":7,"day":21,"timeZone":{"id":" "}}}]}}""",
        ))
        server.enqueue(jsonResponse(
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.2"}}]}]}""",
        ))
        assertEquals(
            0.2,
            publisher.crashRate("credential-json", "io.example.app", null, Duration.ofHours(1)).crashRate,
        )
    }

    @Test
    fun `crash rate refreshes reporting credentials when no token seam is installed`() = runTest {
        val credentials = mockk<GoogleCredentials>()
        every { credentials.refreshAccessToken() } returns AccessToken("refreshed-token", Date())
        val credentialPublisher = AndroidPublisherPlayPublisher(
            http = okhttp3.OkHttpClient(),
            reportingBaseUrl = server.url("").toString().trimEnd('/'),
            credentialsFactory = { json, scopes ->
                assertEquals("credential-json", json)
                assertEquals(listOf("https://www.googleapis.com/auth/playdeveloperreporting"), scopes)
                credentials
            },
        )
        server.enqueue(jsonResponse(freshness()))
        server.enqueue(jsonResponse(
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.2"}}]}]}""",
        ))

        assertEquals(
            0.2,
            credentialPublisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1)).crashRate,
        )
        server.takeRequest()
        assertEquals("Bearer refreshed-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `crash rate rejects non-positive windows and reporting failures`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            publisher.crashRate("credential-json", "io.example.app", 42, Duration.ZERO)
        }
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        val failure = assertFailsWith<IllegalStateException> {
            publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
        }
        assertTrue("503" in failure.message.orEmpty())
    }

    @Test
    fun `crash rate fails closed when reporting repeats a page token`() = runTest {
        server.enqueue(jsonResponse(freshness()))
        repeat(2) {
            server.enqueue(jsonResponse(
                """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.25"}}]}],"nextPageToken":"stuck"}""",
            ))
        }

        val failure = assertFailsWith<IllegalStateException> {
            publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
        }

        assertTrue("repeated page token" in failure.message.orEmpty())
    }

    @Test
    fun `crash rate rejects incomplete freshness metadata`() = runTest {
        server.enqueue(jsonResponse("{}"))
        assertTrue(
            "no HOURLY freshness" in assertFailsWith<IllegalStateException> {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }.message.orEmpty(),
        )

        server.enqueue(jsonResponse("""{"freshnessInfo":{}}"""))
        assertTrue(
            "no HOURLY freshness" in assertFailsWith<IllegalStateException> {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }.message.orEmpty(),
        )

        server.enqueue(jsonResponse("""{"freshnessInfo":{"freshnesses":[]}}"""))
        assertTrue(
            "no HOURLY freshness" in assertFailsWith<IllegalStateException> {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }.message.orEmpty(),
        )

        server.enqueue(jsonResponse("""{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY"}]}}"""))
        assertTrue(
            "no latestEndTime" in assertFailsWith<IllegalStateException> {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }.message.orEmpty(),
        )

        server.enqueue(jsonResponse(
            """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":[]}]}}""",
        ))
        assertTrue(
            "no latestEndTime" in assertFailsWith<IllegalStateException> {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `crash rate rejects invalid freshness date components`() = runTest {
        listOf("year", "month", "day").forEach { invalidField ->
            val fields = mutableMapOf("year" to "2026", "month" to "7", "day" to "21")
            fields[invalidField] = "\"bad\""
            server.enqueue(jsonResponse(
                """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":{"year":${fields.getValue("year")},"month":${fields.getValue("month")},"day":${fields.getValue("day")}}}]}}""",
            ))

            val failure = assertFailsWith<IllegalStateException> {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }

            assertTrue(invalidField in failure.message.orEmpty())
        }

        listOf(
            """{"month":7,"day":21}""",
            """{"year":{},"month":7,"day":21}""",
        ).forEach { invalidEndTime ->
            server.enqueue(jsonResponse(
                """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":$invalidEndTime}]}}""",
            ))
            assertTrue(
                "year" in assertFailsWith<IllegalStateException> {
                    publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
                }.message.orEmpty(),
            )
        }
    }

    @Test
    fun `invalid optional freshness times fall back to midnight`() = runTest {
        server.enqueue(jsonResponse(
            """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"HOURLY","latestEndTime":{"year":2026,"month":7,"day":21,"hours":"bad","minutes":"bad","seconds":"bad","timeZone":{}}}]}}""",
        ))
        server.enqueue(jsonResponse(
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":"0.1"}}]}]}""",
        ))

        assertEquals(
            0.1,
            publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1)).crashRate,
        )
    }

    @Test
    fun `crash rate fails closed for structurally invalid reporting documents`() = runTest {
        listOf(
            "[]",
            """{"freshnessInfo":[]}""",
            """{"freshnessInfo":{"freshnesses":{}}}""",
            """{"freshnessInfo":{"freshnesses":[1]}}""",
            """{"freshnessInfo":{"freshnesses":[{"other":"value"}]}}""",
            """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":{}}]}}""",
        ).forEach { malformedMetadata ->
            server.enqueue(jsonResponse(malformedMetadata))
            assertFails {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }
        }

        listOf(
            "[]",
            """{"rows":{}}""",
            """{"rows":[1]}""",
            """{"rows":[{"metrics":{}}]}""",
            """{"rows":[{"metrics":[1]}]}""",
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":[]}]}]}""",
            """{"rows":[{"metrics":[{"metric":"crashRate","decimalValue":{"value":{}}}]}]}""",
            """{"rows":[],"nextPageToken":{}}""",
        ).forEach { malformedQuery ->
            server.enqueue(jsonResponse(freshness()))
            server.enqueue(jsonResponse(malformedQuery))
            assertFails {
                publisher.crashRate("credential-json", "io.example.app", 42, Duration.ofHours(1))
            }
        }
    }

    @Test
    fun `production credentials construct the generated Android Publisher client`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
        val pem = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(key)
        val credential = """{
            "type":"service_account",
            "project_id":"bosca-test",
            "private_key_id":"key-id",
            "private_key":"-----BEGIN PRIVATE KEY-----\n$pem\n-----END PRIVATE KEY-----\n",
            "client_email":"store-test@bosca-test.iam.gserviceaccount.com",
            "client_id":"1234567890",
            "auth_uri":"https://accounts.google.com/o/oauth2/auth",
            "token_uri":"https://oauth2.googleapis.com/token"
        }"""
        val method = AndroidPublisherPlayPublisher::class.java.getDeclaredMethod("client", String::class.java)
            .apply { isAccessible = true }

        val client = method.invoke(AndroidPublisherPlayPublisher(), credential) as AndroidPublisher

        assertEquals("Bosca Release", client.applicationName)
    }

    @Test
    fun `deploy is idempotent for an existing durable version and maps terminal rollout states`() = runTest {
        val harness = PublisherHarness(existingBundles = listOf(42))
        val publisher = harness.publisher()

        val completed = publisher.deployBundle("credential", "io.example.app", "/unused.aab", "production", 1.0, 42)

        assertEquals(42, completed.versionCode)
        assertEquals("completed", harness.updatedTrack.releases.single().status)
        assertEquals(null, harness.updatedTrack.releases.single().userFraction)
        verify(exactly = 0) { harness.bundles.upload(any(), any(), any()) }

        harness.updatedTrack = Track()
        publisher.deployBundle("credential", "io.example.app", "/unused.aab", "production", 0.0, 42)
        assertEquals("halted", harness.updatedTrack.releases.single().status)
        assertEquals(null, harness.updatedTrack.releases.single().userFraction)
        verify(exactly = 2) { harness.commit.execute() }
    }

    @Test
    fun `deploy publishes localized Play release notes and validates every locale`() = runTest {
        val harness = PublisherHarness(existingBundles = listOf(42))

        val result = harness.publisher().deployBundleWithReleaseNotes(
            "credential",
            "io.example.app",
            "/unused.aab",
            "production",
            1.0,
            42,
            linkedMapOf("en-US" to "Faster startup", "fr-FR" to "Démarrage plus rapide"),
        )

        assertEquals(42, result.versionCode)
        assertEquals(
            listOf("en-US" to "Faster startup", "fr-FR" to "Démarrage plus rapide"),
            harness.updatedTrack.releases.single().releaseNotes.map { it.language to it.text },
        )
        assertFailsWith<IllegalArgumentException> {
            harness.publisher().deployBundleWithReleaseNotes(
                "credential", "io.example.app", "/unused.aab", "production", 1.0, 42, emptyMap(),
            )
        }
        assertTrue(
            "en-US" in assertFailsWith<IllegalArgumentException> {
                harness.publisher().deployBundleWithReleaseNotes(
                    "credential", "io.example.app", "/unused.aab", "production", 1.0, 42,
                    mapOf("en-US" to " "),
                )
            }.message.orEmpty(),
        )
        assertTrue(
            "blank locale" in assertFailsWith<IllegalArgumentException> {
                harness.publisher().deployBundleWithReleaseNotes(
                    "credential", "io.example.app", "/unused.aab", "production", 1.0, 42,
                    mapOf(" " to "Notes"),
                )
            }.message.orEmpty(),
        )
    }

    @Test
    fun `deploy uploads a missing bundle and rejects a version code that differs from the durable allocation`() = runTest {
        val bundle = kotlin.io.path.createTempFile(suffix = ".aab").toFile().apply { writeText("bundle") }
        try {
            val harness = PublisherHarness(uploadedVersionCode = 43)
            val publisher = harness.publisher()

            val result = publisher.deployBundle(
                "credential", "io.example.app", bundle.absolutePath, "internal", 0.25, 43,
            )

            assertEquals(43, result.versionCode)
            assertEquals("inProgress", harness.updatedTrack.releases.single().status)
            assertEquals(0.25, harness.updatedTrack.releases.single().userFraction)

            val mismatch = PublisherHarness(uploadedVersionCode = 43).publisher()
            val error = assertFailsWith<IllegalStateException> {
                mismatch.deployBundle("credential", "io.example.app", bundle.absolutePath, "internal", 0.5, 44)
            }
            assertTrue("declares 44" in error.message.orEmpty())
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun `deploy supports publisher-assigned versions and fails when upload omits one`() = runTest {
        val bundle = kotlin.io.path.createTempFile(suffix = ".aab").toFile().apply { writeText("bundle") }
        try {
            val assigned = PublisherHarness(uploadedVersionCode = 55).publisher().deployBundle(
                "credential", "io.example.app", bundle.absolutePath, "internal", 0.5, null,
            )
            assertEquals(55, assigned.versionCode)

            val nullExistingVersion = PublisherHarness(existingBundles = listOf(null), uploadedVersionCode = 56)
                .publisher().deployBundle(
                    "credential", "io.example.app", bundle.absolutePath, "internal", 0.5, 56,
                )
            assertEquals(56, nullExistingVersion.versionCode)

            val nonMatchingExisting = PublisherHarness(existingBundles = listOf(55), uploadedVersionCode = 56)
                .publisher().deployBundle(
                    "credential", "io.example.app", bundle.absolutePath, "internal", 0.5, 56,
                )
            assertEquals(56, nonMatchingExisting.versionCode)

            val missing = PublisherHarness(existingBundles = null, uploadedVersionCode = null).publisher()
            assertTrue(
                "no versionCode" in assertFailsWith<IllegalStateException> {
                    missing.deployBundle("credential", "io.example.app", bundle.absolutePath, "internal", 0.5, 55)
                }.message.orEmpty(),
            )
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun `set rollout mutates only the exact durable release without re-uploading`() = runTest {
        val selected = TrackRelease().setVersionCodes(listOf(42)).setStatus("inProgress").setUserFraction(0.1)
        val other = TrackRelease().setVersionCodes(listOf(99)).setStatus("completed")
        val harness = PublisherHarness(currentTrack = Track().setTrack("production").setReleases(listOf(selected, other)))

        val result = harness.publisher().setRollout("credential", "io.example.app", "production", 0.5, 42)

        assertEquals(42, result.versionCode)
        assertEquals(listOf(selected, other), harness.updatedTrack.releases)
        assertEquals("inProgress", selected.status)
        assertEquals(0.5, selected.userFraction)
        assertEquals("completed", other.status)
        verify(exactly = 0) { harness.bundles.upload(any(), any(), any()) }
    }

    @Test
    fun `track state reads the selected release and abandons the read edit`() = runTest {
        val selected = TrackRelease().setVersionCodes(listOf(42)).setStatus("inProgress").setUserFraction(0.25)
        val harness = PublisherHarness(
            currentTrack = Track().setTrack("production").setReleases(
                listOf(selected, TrackRelease().setVersionCodes(listOf(99)).setStatus("completed")),
            ),
        )

        val state = harness.publisher().trackState("credential", "io.example.app", "production", 42)

        assertEquals(listOf(42L), state.versionCodes)
        assertEquals("inProgress", state.status)
        assertEquals(0.25, state.userFraction)
        verify(exactly = 1) { harness.delete.execute() }

        val completed = PublisherHarness(
            currentTrack = Track().setReleases(listOf(TrackRelease().setVersionCodes(listOf(7)).setStatus("completed"))),
        ).publisher().trackState("credential", "io.example.app", "production")
        assertEquals(1.0, completed.userFraction)

        val halted = PublisherHarness(
            currentTrack = Track().setReleases(listOf(TrackRelease().setVersionCodes(listOf(8)).setStatus("halted"))),
        ).publisher().trackState("credential", "io.example.app", "production")
        assertEquals(0.0, halted.userFraction)
    }

    @Test
    fun `track state fails closed for missing malformed and unmatched releases`() = runTest {
        val empty = PublisherHarness(currentTrack = Track())
        assertTrue(
            "no current release" in assertFailsWith<IllegalStateException> {
                empty.publisher().trackState("credential", "io.example.app", "production")
            }.message.orEmpty(),
        )
        verify(exactly = 1) { empty.delete.execute() }

        val unmatched = PublisherHarness(
            currentTrack = Track().setReleases(listOf(TrackRelease().setVersionCodes(listOf(7)))),
        )
        assertTrue(
            "versionCode 42" in assertFailsWith<IllegalStateException> {
                unmatched.publisher().trackState("credential", "io.example.app", "production", 42)
            }.message.orEmpty(),
        )

        val noCodes = PublisherHarness(currentTrack = Track().setReleases(listOf(TrackRelease())))
        assertTrue(
            "no version codes" in assertFailsWith<IllegalStateException> {
                noCodes.publisher().trackState("credential", "io.example.app", "production")
            }.message.orEmpty(),
        )

        val unknown = PublisherHarness(
            currentTrack = Track().setReleases(listOf(TrackRelease().setVersionCodes(listOf(9)))),
        ).publisher().trackState("credential", "io.example.app", "production")
        assertEquals("UNKNOWN", unknown.status)
        assertEquals(0.0, unknown.userFraction)
    }

    @Test
    fun `reviews map the latest user comment and validate the page size`() = runTest {
        val android = mockk<AndroidPublisher>()
        val reviews = mockk<AndroidPublisher.Reviews>()
        val list = mockk<AndroidPublisher.Reviews.List>()
        every { android.reviews() } returns reviews
        every { reviews.list("io.example.app") } returns list
        every { list.setMaxResults(2L) } returns list
        every { list.execute() } returns ReviewsListResponse().setReviews(
            listOf(
                Review().setReviewId("r1").setAuthorName("Pat").setComments(
                    listOf(Comment().setUserComment(
                        UserComment().setStarRating(2).setText("Needs work").setReviewerLanguage("en-US")
                            .setLastModified(Timestamp().setSeconds(123)),
                    )),
                ),
                Review().setReviewId("developer-only").setComments(listOf(Comment())),
                Review().setReviewId("no-comments"),
                Review().setReviewId("missing-rating").setComments(
                    listOf(Comment().setUserComment(UserComment().setText("No rating"))),
                ),
                Review().setReviewId(null).setComments(
                    listOf(Comment().setUserComment(UserComment().setStarRating(4))),
                ),
                Review().setReviewId("minimal").setComments(
                    listOf(Comment().setUserComment(UserComment().setStarRating(3))),
                ),
            ),
        )
        val publisher = AndroidPublisherPlayPublisher(publisherFactory = { android })

        val result = publisher.reviews("credential", "io.example.app", 2)

        assertEquals(
            listOf(
                PlayReviewResult("r1", 2, "Needs work", "Pat", "en-US", 123),
                PlayReviewResult("minimal", 3, "", null, null, null),
            ),
            result,
        )
        assertFailsWith<IllegalArgumentException> {
            publisher.reviews("credential", "io.example.app", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.reviews("credential", "io.example.app", 101)
        }

        every { list.setMaxResults(1L) } returns list
        every { list.execute() } returns ReviewsListResponse()
        assertEquals(emptyList(), publisher.reviews("credential", "io.example.app", 1))
    }

    @Test
    fun `rollout and halt infer a version only when the current track is unambiguous`() = runTest {
        val release = TrackRelease().setVersionCodes(listOf(42)).setStatus("inProgress").setUserFraction(0.5)
        val completedHarness = PublisherHarness(currentTrack = Track().setReleases(listOf(release)))
        val completed = completedHarness.publisher().setRollout(
            "credential", "io.example.app", "production", 1.0, null,
        )
        assertEquals(42, completed.versionCode)
        assertEquals("completed", release.status)
        assertEquals(null, release.userFraction)

        val haltedRelease = TrackRelease().setVersionCodes(listOf(51)).setStatus("inProgress")
        val haltedHarness = PublisherHarness(currentTrack = Track().setReleases(listOf(haltedRelease)))
        val halted = haltedHarness.publisher().halt("credential", "io.example.app", "production", null)
        assertEquals(51, halted.versionCode)
        assertEquals("halted", haltedRelease.status)
        assertEquals(0.0, halted.userFraction)

        val emptyHarness = PublisherHarness(currentTrack = Track().setReleases(emptyList()))
        assertFailsWith<IllegalStateException> {
            emptyHarness.publisher().setRollout("credential", "io.example.app", "production", 0.5, null)
        }
        assertFailsWith<IllegalStateException> {
            emptyHarness.publisher().halt("credential", "io.example.app", "production", null)
        }

        val nullHarness = PublisherHarness(currentTrack = Track())
        assertFailsWith<IllegalStateException> {
            nullHarness.publisher().setRollout("credential", "io.example.app", "production", 0.5, null)
        }
        assertFailsWith<IllegalStateException> {
            nullHarness.publisher().halt("credential", "io.example.app", "production", null)
        }


        val nullCodes = TrackRelease().setStatus("inProgress")
        val selected = TrackRelease().setVersionCodes(listOf(42)).setStatus("inProgress")
        val nullCodesHarness = PublisherHarness(currentTrack = Track().setReleases(listOf(nullCodes, selected)))
        assertEquals(
            42,
            nullCodesHarness.publisher().setRollout(
                "credential", "io.example.app", "production", 0.5, 42,
            ).versionCode,
        )

        assertFailsWith<IllegalStateException> {
            PublisherHarness(currentTrack = Track().setReleases(listOf(nullCodes))).publisher().setRollout(
                "credential", "io.example.app", "production", 0.5, null,
            )
        }
        assertFailsWith<IllegalStateException> {
            PublisherHarness(currentTrack = Track().setReleases(listOf(TrackRelease().setVersionCodes(emptyList()))))
                .publisher().halt("credential", "io.example.app", "production", null)
        }
        assertFailsWith<IllegalStateException> {
            PublisherHarness(currentTrack = Track()).publisher().halt(
                "credential", "io.example.app", "production", 42,
            )
        }
    }

    @Test
    fun `rollout rejects invalid fractions and ambiguous exact releases`() = runTest {
        val factory = mockk<(String) -> AndroidPublisher>()
        val publisher = AndroidPublisherPlayPublisher(publisherFactory = factory)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                publisher.setRollout("credential", "io.example.app", "production", invalid, 42)
            }
        }
        verify(exactly = 0) { factory.invoke(any()) }

        val duplicate = TrackRelease().setVersionCodes(listOf(42)).setStatus("inProgress")
        val ambiguous = PublisherHarness(
            currentTrack = Track().setReleases(listOf(duplicate, TrackRelease().setVersionCodes(listOf(42)))),
        )
        assertFailsWith<IllegalStateException> {
            ambiguous.publisher().setRollout("credential", "io.example.app", "production", 0.5, 42)
        }
        assertFailsWith<IllegalStateException> {
            ambiguous.publisher().setRollout("credential", "io.example.app", "production", 0.5, 7)
        }
        assertFailsWith<IllegalStateException> {
            ambiguous.publisher().halt("credential", "io.example.app", "production", 7)
        }
        assertFailsWith<IllegalStateException> {
            ambiguous.publisher().halt("credential", "io.example.app", "production", 42)
        }
    }

    private fun freshness() =
        """{"freshnessInfo":{"freshnesses":[{"aggregationPeriod":"DAILY"},{"aggregationPeriod":"HOURLY","latestEndTime":{"year":2026,"month":7,"day":21,"hours":12,"minutes":0,"seconds":0,"timeZone":{"id":"UTC"}}}]}}"""

    private fun jsonResponse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private class PublisherHarness(
        existingBundles: List<Long?>? = emptyList(),
        uploadedVersionCode: Long? = 42,
        currentTrack: Track = Track().setTrack("production").setReleases(
            listOf(TrackRelease().setVersionCodes(listOf(42)).setStatus("inProgress")),
        ),
    ) {
        private val android = mockk<AndroidPublisher>()
        private val edits = mockk<AndroidPublisher.Edits>()
        private val insert = mockk<AndroidPublisher.Edits.Insert>()
        val bundles = mockk<AndroidPublisher.Edits.Bundles>()
        private val listBundles = mockk<AndroidPublisher.Edits.Bundles.List>()
        private val upload = mockk<AndroidPublisher.Edits.Bundles.Upload>()
        private val tracks = mockk<AndroidPublisher.Edits.Tracks>()
        private val getTrack = mockk<AndroidPublisher.Edits.Tracks.Get>()
        private val updateTrack = mockk<AndroidPublisher.Edits.Tracks.Update>()
        val commit = mockk<AndroidPublisher.Edits.Commit>()
        val delete = mockk<AndroidPublisher.Edits.Delete>()
        var updatedTrack: Track = Track()

        init {
            every { android.edits() } returns edits
            every { edits.insert(any(), any()) } returns insert
            every { insert.execute() } returns AppEdit().setId("edit-1")
            every { edits.bundles() } returns bundles
            every { bundles.list(any(), any()) } returns listBundles
            every { listBundles.execute() } returns BundlesListResponse().setBundles(
                existingBundles?.map { Bundle().setVersionCode(it?.toInt()) },
            )
            every { bundles.upload(any(), any(), any()) } returns upload
            every { upload.execute() } returns Bundle().setVersionCode(uploadedVersionCode?.toInt())
            every { edits.tracks() } returns tracks
            every { tracks.get(any(), any(), any()) } returns getTrack
            every { getTrack.execute() } returns currentTrack
            every { tracks.update(any(), any(), any(), any()) } returns updateTrack
            every { updateTrack.execute() } answers { updatedTrack }
            every { edits.commit(any(), any()) } returns commit
            every { commit.execute() } returns AppEdit().setId("edit-1")
            every { edits.delete(any(), any()) } returns delete
            every { delete.execute() } returns null
        }

        fun publisher(): AndroidPublisherPlayPublisher {
            every { tracks.update(any(), any(), any(), any()) } answers {
                updatedTrack = arg(3)
                updateTrack
            }
            return AndroidPublisherPlayPublisher(publisherFactory = { credential ->
                assertEquals("credential", credential)
                android
            })
        }

    }
}
