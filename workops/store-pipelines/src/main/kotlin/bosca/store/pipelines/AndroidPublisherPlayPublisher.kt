package bosca.store.pipelines

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.api.services.androidpublisher.model.Track
import com.google.api.services.androidpublisher.model.TrackRelease
import com.google.api.services.androidpublisher.model.LocalizedText
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * the real Google Play Developer API (Android Publisher) implementation of
 * [PlayPublisher]. Generated Android Publisher request flows are tested through an injected client,
 * and reporting calls through a mock HTTP server; only live-credential verification remains external.
 * Each publishing call opens an edit, applies changes, and commits.
 */
class AndroidPublisherPlayPublisher(
    private val applicationName: String = "Bosca Release",
    private val http: OkHttpClient = OkHttpClient(),
    private val reportingBaseUrl: String = "https://playdeveloperreporting.googleapis.com",
    /** Test seam for the reporting-only OAuth token; production still mints it from [credentialJson]. */
    private val reportingAccessToken: ((credentialJson: String) -> String)? = null,
    /** Test seam around the generated Android Publisher client; production constructs it from credentials. */
    private val publisherFactory: ((credentialJson: String) -> AndroidPublisher)? = null,
    /** Test seam for credential refresh; production parses and scopes the service-account JSON. */
    private val credentialsFactory: ((credentialJson: String, scopes: List<String>) -> GoogleCredentials)? = null,
) : PlayPublisher {

    override suspend fun deployBundle(
        credentialJson: String,
        packageName: String,
        bundlePath: String,
        track: String,
        userFraction: Double,
        expectedVersionCode: Long?,
    ): PlayDeployResult = deployBundleInternal(
        credentialJson, packageName, bundlePath, track, userFraction, expectedVersionCode, emptyMap(),
    )

    override suspend fun deployBundleWithReleaseNotes(
        credentialJson: String,
        packageName: String,
        bundlePath: String,
        track: String,
        userFraction: Double,
        expectedVersionCode: Long?,
        releaseNotes: Map<String, String>,
    ): PlayDeployResult {
        require(releaseNotes.isNotEmpty()) { "Play submission requires at least one localized release note" }
        releaseNotes.forEach { (locale, text) ->
            require(locale.isNotBlank()) { "Play release notes contain a blank locale" }
            require(text.isNotBlank()) { "Play release notes locale '$locale' is blank" }
        }
        return deployBundleInternal(
            credentialJson, packageName, bundlePath, track, userFraction, expectedVersionCode, releaseNotes,
        )
    }

    private suspend fun deployBundleInternal(
        credentialJson: String,
        packageName: String,
        bundlePath: String,
        track: String,
        userFraction: Double,
        expectedVersionCode: Long?,
        releaseNotes: Map<String, String>,
    ): PlayDeployResult = withContext(Dispatchers.IO) {
        requireUserFraction(userFraction)
        val publisher = client(credentialJson)
        val editId = publisher.edits().insert(packageName, null).execute().id
        val existing = if (expectedVersionCode == null) {
            null
        } else {
            val bundles = publisher.edits().bundles().list(packageName, editId).execute().bundles
            expectedVersionCode.takeIf { expected ->
                bundles?.any { bundle -> bundle.versionCode?.toLong() == expected } == true
            }
        }
        val versionCode = existing ?: publisher.edits().bundles()
            .upload(packageName, editId, ByteArrayContent("application/octet-stream", File(bundlePath).readBytes()))
            .execute().versionCode?.toLong() ?: error("Play bundle upload returned no versionCode")
        if (expectedVersionCode != null && versionCode != expectedVersionCode) {
            error("Play uploaded versionCode $versionCode, but the selected artifact declares $expectedVersionCode")
        }
        applyRollout(publisher, packageName, editId, track, versionCode, userFraction, releaseNotes)
        publisher.edits().commit(packageName, editId).execute()
        PlayDeployResult(versionCode, track, userFraction)
    }

    override suspend fun setRollout(
        credentialJson: String,
        packageName: String,
        track: String,
        userFraction: Double,
        versionCode: Long?,
    ): PlayDeployResult = withContext(Dispatchers.IO) {
        requireUserFraction(userFraction)
        val publisher = client(credentialJson)
        val editId = publisher.edits().insert(packageName, null).execute().id
        // Reuse the current release's version code; change only the fraction/status (no re-upload).
        val current = publisher.edits().tracks().get(packageName, editId, track).execute()
        val selectedVersionCode = selectVersionCode(versionCode, current.releases, track, "roll out")
        matchingRelease(current.releases, selectedVersionCode, track).apply {
            status = rolloutStatus(userFraction)
            this.userFraction = rolloutFraction(userFraction)
        }
        publisher.edits().tracks().update(packageName, editId, track, current).execute()
        publisher.edits().commit(packageName, editId).execute()
        PlayDeployResult(selectedVersionCode, track, userFraction)
    }

    override suspend fun halt(
        credentialJson: String,
        packageName: String,
        track: String,
        versionCode: Long?,
    ): PlayDeployResult =
        withContext(Dispatchers.IO) {
            val publisher = client(credentialJson)
            val editId = publisher.edits().insert(packageName, null).execute().id
            val current = publisher.edits().tracks().get(packageName, editId, track).execute()
            val selectedVersionCode = selectVersionCode(versionCode, current.releases, track, "halt")
            val release = matchingRelease(current.releases, selectedVersionCode, track)
            release.status = "halted"
            publisher.edits().tracks().update(packageName, editId, track, current).execute()
            publisher.edits().commit(packageName, editId).execute()
            PlayDeployResult(selectedVersionCode, track, 0.0)
        }

    override suspend fun trackState(
        credentialJson: String,
        packageName: String,
        track: String,
        versionCode: Long?,
    ): PlayTrackStateResult = withContext(Dispatchers.IO) {
        val publisher = client(credentialJson)
        val editId = publisher.edits().insert(packageName, null).execute().id
        try {
            val releases = publisher.edits().tracks().get(packageName, editId, track).execute().releases.orEmpty()
            val release = when (versionCode) {
                null -> releases.firstOrNull()
                else -> releases.singleOrNull { it.versionCodes?.contains(versionCode) == true }
            } ?: error(
                if (versionCode == null) "Play track '$track' has no current release"
                else "Play track '$track' has no release containing versionCode $versionCode",
            )
            val versions = release.versionCodes.orEmpty()
            check(versions.isNotEmpty()) { "Play track '$track' current release has no version codes" }
            PlayTrackStateResult(
                track = track,
                versionCodes = versions,
                status = release.status ?: "UNKNOWN",
                userFraction = when (release.status) {
                    "completed" -> 1.0
                    "halted" -> 0.0
                    else -> release.userFraction ?: 0.0
                },
            )
        } finally {
            publisher.edits().delete(packageName, editId).execute()
        }
    }

    override suspend fun vitals(
        credentialJson: String,
        packageName: String,
        versionCode: Long?,
        window: Duration,
    ): PlayVitalsResult = withContext(Dispatchers.IO) {
        require(!window.isZero && !window.isNegative) { "store health window must be positive" }
        val token = reportingAccessToken?.invoke(credentialJson)
            ?: credentials(credentialJson, listOf(REPORTING_SCOPE)).refreshAccessToken().tokenValue
        val metricPath = "/v1beta1/apps/$packageName/crashRateMetricSet"
        val metadata = execute(
            Request.Builder().url("$reportingBaseUrl$metricPath")
                .header("Authorization", "Bearer $token").get().build(),
        )
        val freshness = hourlyFreshness(metadata)
            ?: error("Play vitals returned no HOURLY freshness for $packageName")
        val end = freshness["latestEndTime"] as? JsonObject
            ?: error("Play vitals returned no latestEndTime for $packageName")
        val zone = ZoneId.of((end["timeZone"] as? JsonObject)?.stringValue("id") ?: "UTC")
        val endTime = end.toLocalDateTime().atZone(zone)
        val start = endTime.minus(window)
        val body = buildJsonObject {
            put("timelineSpec", buildJsonObject {
                put("aggregationPeriod", "HOURLY")
                put("startTime", dateTime(start.toLocalDateTime(), zone.id))
                put("endTime", end)
            })
            put("dimensions", buildJsonArray { if (versionCode != null) add("versionCode") })
            put("metrics", buildJsonArray { add("crashRate"); add("anrRate") })
            if (versionCode != null) put("filter", "versionCode = $versionCode")
            put("pageSize", 100000)
        }
        val crashRates = mutableListOf<Double>()
        val anrRates = mutableListOf<Double>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val pageBody = buildJsonObject {
                body.forEach { (key, value) -> put(key, value) }
                pageToken?.let { put("pageToken", it) }
            }
            val response = execute(
                Request.Builder().url("$reportingBaseUrl$metricPath:query")
                    .header("Authorization", "Bearer $token")
                    .post(pageBody.toString().toRequestBody(JSON_MEDIA)).build(),
            ) as? JsonObject ?: error("Play vitals returned an invalid query response for $packageName")
            crashRates += metricRates(response, "crashRate")
            anrRates += metricRates(response, "anrRate")
            pageToken = (response["nextPageToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            check(pageToken == null || seenPageTokens.add(pageToken)) {
                "Play vitals repeated page token '$pageToken' for $packageName"
            }
        } while (pageToken != null)
        check(crashRates.isNotEmpty()) {
            "Play vitals returned no crash-rate samples for $packageName" +
                (versionCode?.let { " versionCode $it" } ?: "") + " in ${window.toHours()}h"
        }
        PlayVitalsResult(
            crashRate = crashRates.max(),
            window = window,
            versionCode = versionCode,
            anrRate = anrRates.maxOrNull() ?: 0.0,
        )
    }

    override suspend fun reviews(
        credentialJson: String,
        packageName: String,
        maxResults: Int,
    ): List<PlayReviewResult> = withContext(Dispatchers.IO) {
        require(maxResults in 1..100) { "Play reviews maxResults must be between 1 and 100" }
        val response = client(credentialJson).reviews().list(packageName)
            .setMaxResults(maxResults.toLong())
            .execute()
        response.reviews.orEmpty().mapNotNull { review ->
            val body = json.parseToJsonElement(GsonFactory.getDefaultInstance().toString(review)) as? JsonObject
                ?: return@mapNotNull null
            val comments = body["comments"] as? JsonArray ?: return@mapNotNull null
            val user = comments.asReversed().firstNotNullOfOrNull { comment ->
                (comment as? JsonObject)?.get("userComment") as? JsonObject
            } ?: return@mapNotNull null
            val rating = (user["starRating"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val lastModified = user["lastModified"] as? JsonObject
            PlayReviewResult(
                id = body.stringValue("reviewId") ?: return@mapNotNull null,
                rating = rating,
                text = user.stringValue("text").orEmpty(),
                author = body.stringValue("authorName"),
                locale = user.stringValue("reviewerLanguage"),
                createdEpochSeconds = (lastModified?.get("seconds") as? JsonPrimitive)?.longOrNull,
            )
        }
    }

    /** Sets the track's single release to [versionCode] at [userFraction] (`completed` at 1.0, else `inProgress`). */
    private fun applyRollout(
        publisher: AndroidPublisher,
        packageName: String,
        editId: String,
        track: String,
        versionCode: Long,
        userFraction: Double,
        releaseNotes: Map<String, String> = emptyMap(),
    ) {
        val release = TrackRelease().setVersionCodes(listOf(versionCode)).setStatus(rolloutStatus(userFraction))
        release.userFraction = rolloutFraction(userFraction)
        if (releaseNotes.isNotEmpty()) {
            release.releaseNotes = releaseNotes.map { (locale, text) ->
                LocalizedText().setLanguage(locale).setText(text)
            }
        }
        publisher.edits().tracks()
            .update(packageName, editId, track, Track().setTrack(track).setReleases(listOf(release)))
            .execute()
    }

    private fun client(credentialJson: String): AndroidPublisher {
        publisherFactory?.let { return it(credentialJson) }
        val credentials = credentials(credentialJson, listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
        return AndroidPublisher.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials),
        ).setApplicationName(applicationName).build()
    }

    private fun credentials(credentialJson: String, scopes: List<String>): GoogleCredentials =
        credentialsFactory?.invoke(credentialJson, scopes)
            ?: GoogleCredentials.fromStream(credentialJson.byteInputStream()).createScoped(scopes)

    private fun execute(request: Request): JsonElement = http.newCall(request).execute().use { response ->
        val body = response.body.string()
        check(response.isSuccessful) {
            "Google Play ${request.method} ${request.url} failed (${response.code}): ${body.take(2000)}"
        }
        json.parseToJsonElement(body)
    }

    private fun hourlyFreshness(metadata: JsonElement): JsonObject? {
        val root = metadata as? JsonObject ?: return null
        val freshnessInfo = root["freshnessInfo"] as? JsonObject ?: return null
        val freshnesses = freshnessInfo["freshnesses"] as? JsonArray ?: return null
        for (element in freshnesses) {
            val freshness = element as? JsonObject ?: continue
            if (freshness["aggregationPeriod"] == JsonPrimitive("HOURLY")) return freshness
        }
        return null
    }

    private fun metricRates(response: JsonObject, metricName: String): List<Double> {
        val rows = response["rows"] as? JsonArray ?: return emptyList()
        val rates = mutableListOf<Double>()
        for (rowElement in rows) {
            val row = rowElement as? JsonObject ?: continue
            val metrics = row["metrics"] as? JsonArray ?: continue
            for (metricElement in metrics) {
                val metric = metricElement as? JsonObject ?: continue
                if (metric["metric"] != JsonPrimitive(metricName)) continue
                val decimal = metric["decimalValue"] as? JsonObject ?: continue
                val value = decimal["value"] as? JsonPrimitive ?: continue
                value.doubleOrNull?.let(rates::add)
            }
        }
        return rates
    }

    private fun selectVersionCode(
        requested: Long?,
        releases: List<TrackRelease>?,
        track: String,
        action: String,
    ): Long {
        if (requested != null) return requested
        val firstRelease = releases?.firstOrNull()
            ?: error("Play track '$track' has no release to $action")
        return firstRelease.versionCodes?.firstOrNull()
            ?: error("Play track '$track' has no release to $action")
    }

    private fun matchingRelease(
        releases: List<TrackRelease>?,
        versionCode: Long,
        track: String,
    ): TrackRelease {
        val matching = mutableListOf<TrackRelease>()
        releases?.forEach { release ->
            if (release.versionCodes?.contains(versionCode) == true) matching += release
        }
        check(matching.size == 1) {
            "Play track '$track' expected exactly one release containing versionCode $versionCode, found ${matching.size}"
        }
        return matching.single()
    }

    private fun JsonObject.stringValue(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredInt(name: String): Int =
        ((get(name) as? JsonPrimitive)?.longOrNull ?: error("Play vitals $name is invalid")).toInt()

    private fun JsonObject.optionalInt(name: String): Int =
        (get(name) as? JsonPrimitive)?.longOrNull?.toInt() ?: 0

    private fun JsonObject.toLocalDateTime(): LocalDateTime = LocalDateTime.of(
        requiredInt("year"),
        requiredInt("month"),
        requiredInt("day"),
        optionalInt("hours"),
        optionalInt("minutes"),
        optionalInt("seconds"),
    )

    private fun dateTime(value: LocalDateTime, zoneId: String) = buildJsonObject {
        put("year", value.year)
        put("month", value.monthValue)
        put("day", value.dayOfMonth)
        put("hours", value.hour)
        put("minutes", value.minute)
        put("seconds", value.second)
        put("timeZone", buildJsonObject { put("id", zoneId) })
    }

    private fun requireUserFraction(value: Double) {
        require(value.isFinite() && value in 0.0..1.0) {
            "Play userFraction must be between 0.0 and 1.0, got $value"
        }
    }

    private fun rolloutStatus(value: Double): String = when {
        value <= 0.0 -> "halted"
        value >= 1.0 -> "completed"
        else -> "inProgress"
    }

    private fun rolloutFraction(value: Double): Double? = value.takeIf { it > 0.0 && it < 1.0 }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA = "application/json".toMediaType()
        const val REPORTING_SCOPE = "https://www.googleapis.com/auth/playdeveloperreporting"
    }
}
