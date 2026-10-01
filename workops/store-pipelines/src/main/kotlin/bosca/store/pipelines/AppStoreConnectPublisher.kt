package bosca.store.pipelines

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * the real App Store Connect API implementation of [AppStorePublisher]. Its REST
 * and JWT paths are exercised against a mock server; only a live-credential verification remains
 * external. Auth is a per-call ES256 JWT from the API key
 * `{issuerId, keyId, privateKey}` (or legacy `p8`) in the pipeline secret's JSON.
 *
 * NOTE: the `.aab`-equivalent (`.ipa`) is uploaded out-of-band by the build via Transporter/altool; this
 * class attaches the already-uploaded build by number and drives the submission/phased-release REST
 * flow. The App Store Connect flow is several linked JSON:API calls — implemented here in representative
 * form and pending live-credential verification.
 */
class AppStoreConnectPublisher(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.appstoreconnect.apple.com",
) : AppStorePublisher {

    override suspend fun submitForReview(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        phasedRelease: Boolean,
    ): AppStoreSubmitResult = submitForReviewInternal(
        credentialJson, bundleId, appVersion, buildNumber, phasedRelease, emptyMap(),
    )

    override suspend fun submitForReviewWithReleaseNotes(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        phasedRelease: Boolean,
        whatsNew: Map<String, String>,
    ): AppStoreSubmitResult {
        requireLocalizedNotes("App Store What's New", whatsNew)
        return submitForReviewInternal(
            credentialJson, bundleId, appVersion, buildNumber, phasedRelease, whatsNew,
        )
    }

    private suspend fun submitForReviewInternal(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        phasedRelease: Boolean,
        whatsNew: Map<String, String>,
    ): AppStoreSubmitResult = withContext(Dispatchers.IO) {
        val token = bearer(credentialJson)
        val appId = appId(token, bundleId)
        // Resolve the editable version for this marketing version and the uploaded build.
        val versionId = editableVersionId(token, appId, appVersion)
        val buildId = buildId(token, appId, appVersion, buildNumber)
        if (whatsNew.isNotEmpty()) upsertAppStoreVersionLocalizations(token, versionId, whatsNew)
        attachBuild(token, versionId, buildId)
        submit(token, versionId)
        if (phasedRelease) enablePhasedRelease(token, versionId)
        AppStoreSubmitResult(appVersion, buildNumber, versionState(token, versionId))
    }

    override suspend fun assignBetaGroups(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
    ): AppStoreSubmitResult = assignBetaGroupsInternal(
        credentialJson, bundleId, appVersion, buildNumber, groupNames, emptyMap(),
    )

    override suspend fun assignBetaGroupsWithReleaseNotes(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
        whatToTest: Map<String, String>,
    ): AppStoreSubmitResult {
        requireLocalizedNotes("TestFlight What to Test", whatToTest)
        return assignBetaGroupsInternal(
            credentialJson, bundleId, appVersion, buildNumber, groupNames, whatToTest,
        )
    }

    private suspend fun assignBetaGroupsInternal(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
        whatToTest: Map<String, String>,
    ): AppStoreSubmitResult = withContext(Dispatchers.IO) {
        require(groupNames.isNotEmpty()) { "App Store beta assignment requires at least one TestFlight group" }
        val token = bearer(credentialJson)
        val appId = appId(token, bundleId)
        val buildId = buildId(token, appId, appVersion, buildNumber)
        if (whatToTest.isNotEmpty()) upsertBetaBuildLocalizations(token, buildId, whatToTest)
        groupNames.distinct().forEach { name ->
            val groupId = betaGroupId(token, appId, name)
            post(token, "/v1/betaGroups/$groupId/relationships/builds", buildRelationshipBody(buildId))
        }
        AppStoreSubmitResult(appVersion, buildNumber, "ASSIGNED")
    }

    override suspend fun removeBetaGroups(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
    ): AppStoreSubmitResult = withContext(Dispatchers.IO) {
        require(groupNames.isNotEmpty()) { "App Store beta rollback requires at least one TestFlight group" }
        val token = bearer(credentialJson)
        val appId = appId(token, bundleId)
        val buildId = buildId(token, appId, appVersion, buildNumber)
        groupNames.distinct().forEach { name ->
            val groupId = betaGroupId(token, appId, name)
            delete(token, "/v1/betaGroups/$groupId/relationships/builds", buildRelationshipBody(buildId))
        }
        AppStoreSubmitResult(appVersion, buildNumber, "REMOVED")
    }

    override suspend fun haltPhasedRelease(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
    ): AppStoreSubmitResult =
        withContext(Dispatchers.IO) {
            val token = bearer(credentialJson)
            val appId = appId(token, bundleId)
            val versionId = editableVersionId(token, appId, appVersion)
            val relationship = get(
                token,
                "/v1/appStoreVersions/$versionId/relationships/appStoreVersionPhasedRelease",
            )
            val phasedReleaseId = objectId(relationship["data"])
                ?: error("App Store: version $appVersion has no phased release to pause")
            patch(
                token,
                "/v1/appStoreVersionPhasedReleases/$phasedReleaseId",
                phasedReleaseUpdateBody(phasedReleaseId, "PAUSED"),
            )
            AppStoreSubmitResult(appVersion, "", versionState(token, versionId))
        }

    override suspend fun reviewState(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        mode: AppStoreReviewMode,
    ): String =
        withContext(Dispatchers.IO) {
            val token = bearer(credentialJson)
            val appId = appId(token, bundleId)
            when (mode) {
                AppStoreReviewMode.BETA -> {
                    val buildId = buildId(token, appId, appVersion, buildNumber)
                    val submission = get(token, "/v1/builds/$buildId/betaAppReviewSubmission")
                    attributes(submission)?.stringValue("betaReviewState") ?: "UNKNOWN"
                }
                AppStoreReviewMode.APP_STORE -> versionState(token, editableVersionId(token, appId, appVersion))
            }
        }

    override suspend fun state(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String?,
    ): AppStoreStateResult = withContext(Dispatchers.IO) {
        val token = bearer(credentialJson)
        val appId = appId(token, bundleId)
        val versionId = editableVersionId(token, appId, appVersion)
        val build = buildResource(token, appId, appVersion, buildNumber)
        val selectedBuildId = build?.stringValue("id")
        val buildAttributes = build?.get("attributes") as? JsonObject
        val selectedBuildNumber = buildAttributes?.stringValue("version")
        val betaReviewState = selectedBuildId?.let { id ->
            val submission = getOptional(token, "/v1/builds/$id/betaAppReviewSubmission") ?: return@let null
            attributes(submission)?.stringValue("betaReviewState")
        }
        val groups = selectedBuildId?.let { id ->
            val response = get(token, "/v1/builds/$id/betaGroups", "limit" to "200")
            (response["data"] as? JsonArray).orEmpty().mapNotNull { resource ->
                ((resource as? JsonObject)?.get("attributes") as? JsonObject)?.stringValue("name")
            }
        }.orEmpty()
        val crashFeedback = selectedBuildId?.let { id ->
            val response = get(
                token,
                "/v1/apps/$appId/betaFeedbackCrashSubmissions",
                "filter[build]" to id,
                "limit" to "50",
            )
            (response["data"] as? JsonArray).orEmpty().mapNotNull { resource ->
                val feedback = resource as? JsonObject ?: return@mapNotNull null
                val attrs = feedback["attributes"] as? JsonObject ?: return@mapNotNull null
                AppStoreCrashFeedbackResult(
                    id = feedback.stringValue("id") ?: return@mapNotNull null,
                    comment = attrs.stringValue("comment"),
                    email = attrs.stringValue("email"),
                    deviceModel = attrs.stringValue("deviceModel"),
                    osVersion = attrs.stringValue("osVersion"),
                    createdAt = attrs.stringValue("createdDate"),
                )
            }
        }.orEmpty()
        val phasedRelationship = getOptional(
            token,
            "/v1/appStoreVersions/$versionId/relationships/appStoreVersionPhasedRelease",
        )
        val phasedReleaseState = objectId(phasedRelationship?.get("data"))?.let { id ->
            attributes(get(token, "/v1/appStoreVersionPhasedReleases/$id"))
                ?.stringValue("phasedReleaseState")
        }
        AppStoreStateResult(
            appVersion = appVersion,
            buildNumber = selectedBuildNumber,
            reviewState = versionState(token, versionId),
            betaReviewState = betaReviewState,
            buildProcessingState = buildAttributes?.stringValue("processingState"),
            phasedReleaseState = phasedReleaseState,
            testFlightGroups = groups.distinct(),
            testFlightCrashFeedback = crashFeedback,
        )
    }

    override suspend fun reviews(
        credentialJson: String,
        bundleId: String,
        maxResults: Int,
    ): List<AppStoreReviewResult> = withContext(Dispatchers.IO) {
        require(maxResults in 1..200) { "App Store reviews maxResults must be between 1 and 200" }
        val token = bearer(credentialJson)
        val appId = appId(token, bundleId)
        val response = get(
            token,
            "/v1/apps/$appId/customerReviews",
            "limit" to maxResults.toString(),
            "sort" to "-createdDate",
        )
        (response["data"] as? JsonArray).orEmpty().mapNotNull { resource ->
            val review = resource as? JsonObject ?: return@mapNotNull null
            val attrs = review["attributes"] as? JsonObject ?: return@mapNotNull null
            AppStoreReviewResult(
                id = review.stringValue("id") ?: return@mapNotNull null,
                rating = (attrs["rating"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                title = attrs.stringValue("title"),
                body = attrs.stringValue("body").orEmpty(),
                reviewer = attrs.stringValue("reviewerNickname"),
                territory = attrs.stringValue("territory"),
                createdAt = attrs.stringValue("createdDate"),
            )
        }
    }

    // ── App Store Connect REST helpers ─────────────────────────────────

    private fun appId(token: String, bundleId: String): String {
        val body = get(token, "/v1/apps", "filter[bundleId]" to bundleId, "limit" to "1")
        return firstId(body) ?: error("App Store: app '$bundleId' not found")
    }

    private fun editableVersionId(token: String, appId: String, appVersion: String): String {
        val body = get(
            token,
            "/v1/apps/$appId/appStoreVersions",
            "filter[versionString]" to appVersion,
            "filter[platform]" to "IOS",
            "limit" to "1",
        )
        return firstId(body)
            ?: error("App Store: no editable version $appVersion for app $appId")
    }

    private fun buildId(token: String, appId: String, appVersion: String, buildNumber: String): String {
        val resource = buildResource(token, appId, appVersion, buildNumber)
        return resource?.stringValue("id")
            ?: error("App Store: uploaded build $buildNumber not found for app $appId")
    }

    private fun buildResource(
        token: String,
        appId: String,
        appVersion: String,
        buildNumber: String?,
    ): JsonObject? {
        val query = buildList {
            add("filter[app]" to appId)
            add("filter[preReleaseVersion.version]" to appVersion)
            buildNumber?.takeIf { it.isNotBlank() }?.let { add("filter[version]" to it) }
            add("limit" to "1")
            add("sort" to "-uploadedDate")
        }
        val body = get(token, "/v1/builds", *query.toTypedArray())
        return (body["data"] as? JsonArray)?.firstOrNull() as? JsonObject
    }

    private fun betaGroupId(token: String, appId: String, groupName: String): String =
        firstId(
            get(
                token, "/v1/betaGroups",
                "filter[app]" to appId,
                "filter[name]" to groupName,
                "limit" to "1",
            ),
        )
            ?: error("App Store: TestFlight group '$groupName' not found for app $appId")

    private fun firstId(body: JsonElement): String? {
        val resources = body["data"] as? JsonArray ?: return null
        return objectId(resources.firstOrNull())
    }

    private fun objectId(element: JsonElement?): String? =
        (element as? JsonObject)?.stringValue("id")

    private fun attributes(body: JsonElement): JsonObject? =
        (body["data"] as? JsonObject)?.get("attributes") as? JsonObject

    private fun JsonObject.stringValue(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun attachBuild(token: String, versionId: String, buildId: String) {
        patch(
            token, "/v1/appStoreVersions/$versionId/relationships/build",
            buildJsonObject { putJsonObject("data") { put("type", "builds"); put("id", buildId) } },
        )
    }

    private fun upsertAppStoreVersionLocalizations(
        token: String,
        versionId: String,
        notes: Map<String, String>,
    ) {
        val existing = localizedResources(
            get(token, "/v1/appStoreVersions/$versionId/appStoreVersionLocalizations", "limit" to "200"),
        )
        notes.forEach { (locale, text) ->
            val id = existing[locale]
            if (id == null) {
                post(token, "/v1/appStoreVersionLocalizations", appStoreLocalizationCreateBody(versionId, locale, text))
            } else {
                patch(token, "/v1/appStoreVersionLocalizations/$id", localizationUpdateBody("appStoreVersionLocalizations", id, text))
            }
        }
    }

    private fun upsertBetaBuildLocalizations(token: String, buildId: String, notes: Map<String, String>) {
        val existing = localizedResources(
            get(token, "/v1/builds/$buildId/betaBuildLocalizations", "limit" to "200"),
        )
        notes.forEach { (locale, text) ->
            val id = existing[locale]
            if (id == null) {
                post(token, "/v1/betaBuildLocalizations", betaLocalizationCreateBody(buildId, locale, text))
            } else {
                patch(token, "/v1/betaBuildLocalizations/$id", localizationUpdateBody("betaBuildLocalizations", id, text))
            }
        }
    }

    private fun localizedResources(body: JsonElement): Map<String, String> =
        (body["data"] as? JsonArray).orEmpty().mapNotNull { element ->
            val resource = element as? JsonObject ?: return@mapNotNull null
            val id = resource.stringValue("id") ?: return@mapNotNull null
            val locale = (resource["attributes"] as? JsonObject)?.stringValue("locale") ?: return@mapNotNull null
            locale to id
        }.toMap()

    private fun appStoreLocalizationCreateBody(versionId: String, locale: String, text: String): JsonElement =
        buildJsonObject {
            putJsonObject("data") {
                put("type", "appStoreVersionLocalizations")
                putJsonObject("attributes") { put("locale", locale); put("whatsNew", text) }
                putJsonObject("relationships") {
                    putJsonObject("appStoreVersion") {
                        putJsonObject("data") { put("type", "appStoreVersions"); put("id", versionId) }
                    }
                }
            }
        }

    private fun betaLocalizationCreateBody(buildId: String, locale: String, text: String): JsonElement =
        buildJsonObject {
            putJsonObject("data") {
                put("type", "betaBuildLocalizations")
                putJsonObject("attributes") { put("locale", locale); put("whatsNew", text) }
                putJsonObject("relationships") {
                    putJsonObject("build") {
                        putJsonObject("data") { put("type", "builds"); put("id", buildId) }
                    }
                }
            }
        }

    private fun localizationUpdateBody(type: String, id: String, text: String): JsonElement = buildJsonObject {
        putJsonObject("data") {
            put("type", type)
            put("id", id)
            putJsonObject("attributes") { put("whatsNew", text) }
        }
    }

    private fun requireLocalizedNotes(field: String, notes: Map<String, String>) {
        require(notes.isNotEmpty()) { "$field requires at least one locale" }
        notes.forEach { (locale, text) ->
            require(locale.isNotBlank()) { "$field contains a blank locale" }
            require(text.isNotBlank()) { "$field locale '$locale' is blank" }
        }
    }

    private fun buildRelationshipBody(buildId: String): JsonElement = buildJsonObject {
        put("data", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject { put("type", "builds"); put("id", buildId) })
        })
    }

    private fun submit(token: String, versionId: String) {
        post(
            token, "/v1/appStoreVersionSubmissions",
            buildJsonObject {
                putJsonObject("data") {
                    put("type", "appStoreVersionSubmissions")
                    putJsonObject("relationships") {
                        putJsonObject("appStoreVersion") {
                            putJsonObject("data") { put("type", "appStoreVersions"); put("id", versionId) }
                        }
                    }
                }
            },
        )
    }

    private fun enablePhasedRelease(token: String, versionId: String) {
        post(token, "/v1/appStoreVersionPhasedReleases", phasedReleaseBody(versionId, "ACTIVE"))
    }

    private fun phasedReleaseBody(versionId: String, state: String): JsonElement = buildJsonObject {
        putJsonObject("data") {
            put("type", "appStoreVersionPhasedReleases")
            putJsonObject("attributes") { put("phasedReleaseState", state) }
            putJsonObject("relationships") {
                putJsonObject("appStoreVersion") {
                    putJsonObject("data") { put("type", "appStoreVersions"); put("id", versionId) }
                }
            }
        }
    }

    private fun phasedReleaseUpdateBody(id: String, state: String): JsonElement = buildJsonObject {
        putJsonObject("data") {
            put("type", "appStoreVersionPhasedReleases")
            put("id", id)
            putJsonObject("attributes") { put("phasedReleaseState", state) }
        }
    }

    private fun versionState(token: String, versionId: String): String {
        val body = get(token, "/v1/appStoreVersions/$versionId")
        val attributes = attributes(body)
        return (attributes?.stringValue("appVersionState") ?: attributes?.stringValue("appStoreState"))
            ?: "UNKNOWN"
    }

    private fun get(token: String, path: String, vararg query: Pair<String, String>): JsonElement {
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return execute(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())
    }
    private fun getOptional(token: String, path: String, vararg query: Pair<String, String>): JsonElement? {
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.code == 404) return@use null
            if (!response.isSuccessful) {
                error("App Store Connect ${request.method} ${request.url} failed: ${response.code} $text")
            }
            if (text.isBlank()) json.parseToJsonElement("{}") else json.parseToJsonElement(text)
        }
    }
    private fun post(token: String, path: String, body: JsonElement): JsonElement =
        execute(request(token, path).post(body.toString().toRequestBody(JSON_MEDIA)).build())
    private fun patch(token: String, path: String, body: JsonElement): JsonElement =
        execute(request(token, path).patch(body.toString().toRequestBody(JSON_MEDIA)).build())
    private fun delete(token: String, path: String, body: JsonElement): JsonElement =
        execute(request(token, path).delete(body.toString().toRequestBody(JSON_MEDIA)).build())

    private fun request(token: String, path: String): Request.Builder =
        Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $token")

    private fun execute(request: Request): JsonElement = http.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) error("App Store Connect ${request.method} ${request.url} failed: ${response.code} $text")
        if (text.isBlank()) json.parseToJsonElement("{}") else json.parseToJsonElement(text)
    }

    private fun bearer(credentialJson: String): String {
        val cred = credential(credentialJson)
        val now = Instant.now()
        return JWT.create()
            .withKeyId(cred.keyId)
            .withIssuer(cred.issuerId)
            .withAudience("appstoreconnect-v1")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(1200))) // Apple caps token lifetime at 20 minutes.
            .sign(Algorithm.ECDSA256(null, ecPrivateKey(cred.keyPem())))
    }

    private fun ecPrivateKey(pem: String): ECPrivateKey {
        val der = Base64.getDecoder().decode(
            pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), ""),
        )
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
    }

    private fun credential(credentialJson: String): AppStoreCredential {
        val body = json.parseToJsonElement(credentialJson) as? JsonObject
            ?: error("App Store credential must be a JSON object")
        return AppStoreCredential(
            issuerId = body.stringValue("issuerId") ?: error("App Store credential requires issuerId"),
            keyId = body.stringValue("keyId") ?: error("App Store credential requires keyId"),
            privateKey = body.stringValue("privateKey"),
            p8 = body.stringValue("p8"),
        )
    }

    private data class AppStoreCredential(
        val issuerId: String,
        val keyId: String,
        val privateKey: String? = null,
        val p8: String? = null,
    ) {
        fun keyPem(): String = privateKey ?: p8
            ?: error("App Store credential requires privateKey (or legacy p8)")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()
        private operator fun JsonElement.get(key: String): JsonElement? = jsonObject[key]
    }
}
