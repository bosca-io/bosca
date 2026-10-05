package bosca.store.pipelines

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.pipelines.service.PipelineSecretService
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration

private const val STORE_GROUP = "WorkOps"
private const val STORE_SUBGROUP = "Store telemetry"

/** Common settings metadata shared textually by the store telemetry nodes. */
private fun requireSetting(value: String, field: String, label: String): String {
    require(value.isNotBlank()) { "$label requires $field" }
    return value
}

private suspend fun credential(context: PipelineContext, name: String, label: String): String {
    require(name.isNotBlank()) { "$label requires credentialSecretName" }
    return provide<PipelineSecretService>().resolveForExecution(name, context.dryRun)
}

/** Fetches a point-in-time Play crash/ANR observation for scheduled monitoring pipelines. */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Play Vitals",
    description = "Fetches crash and ANR rates for one Google Play application/version window.",
    group = STORE_GROUP,
    subgroup = STORE_SUBGROUP,
    outputs = [
        OutputSlot(name = "out", kind = SlotKind.OBJECT, typeLabel = "Play vitals"),
    ],
    settings = [
        SettingSlot(name = "applicationId", control = SettingControl.TEXT, label = "Application id", required = true),
        SettingSlot(name = "appVersion", control = SettingControl.TEXT, label = "App version", required = true),
        SettingSlot(name = "versionCode", control = SettingControl.NUMBER, label = "Version code"),
        SettingSlot(name = "windowHours", control = SettingControl.NUMBER, label = "Window (hours)", default = "24"),
        SettingSlot(
            name = "credentialSecretName", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "Play credential", required = true,
        ),
    ],
)
@Serializable
@SerialName("store.playVitals")
class PlayVitalsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val applicationId: String = "",
    val appVersion: String = "",
    val versionCode: Long? = null,
    val windowHours: Long = 24,
    val credentialSecretName: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        requireSetting(applicationId, "applicationId", "Play Vitals node '$label'")
        requireSetting(appVersion, "appVersion", "Play Vitals node '$label'")
        require(windowHours > 0) { "Play Vitals node '$label' requires a positive windowHours" }
        val result = provide<PlayPublisher>().vitals(
            credential(context, credentialSecretName, "Play Vitals node '$label'"),
            applicationId,
            versionCode,
            Duration.ofHours(windowHours),
        )
        return jsonValue(
            buildJsonObject {
                put("applicationId", applicationId)
                put("store", "GOOGLE_PLAY")
                put("appVersion", appVersion)
                versionCode?.let { put("versionCode", it) }
                put("crashRate", result.crashRate)
                put("anrRate", result.anrRate)
                put("windowSeconds", result.window.seconds)
                put("observedAt", OffsetDateTime.now().toString())
            },
        )
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue = jsonValue(
        buildJsonObject {
            put("applicationId", applicationId)
            put("store", "GOOGLE_PLAY")
            put("appVersion", appVersion)
            put("windowSeconds", Duration.ofHours(windowHours.coerceAtLeast(1)).seconds)
            put("dryRun", true)
        },
    )
}

/** Fetches recent Google Play reviews for rating alerts, digests, and analytics ingestion. */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Play Reviews",
    description = "Fetches recent Google Play reviews as normalized JSON.",
    group = STORE_GROUP,
    subgroup = STORE_SUBGROUP,
    outputs = [OutputSlot(name = "out", kind = SlotKind.OBJECT, typeLabel = "Play reviews")],
    settings = [
        SettingSlot(name = "applicationId", control = SettingControl.TEXT, label = "Application id", required = true),
        SettingSlot(name = "appVersion", control = SettingControl.TEXT, label = "App version", required = true),
        SettingSlot(name = "maxResults", control = SettingControl.NUMBER, label = "Maximum reviews", default = "50"),
        SettingSlot(
            name = "credentialSecretName", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "Play credential", required = true,
        ),
    ],
)
@Serializable
@SerialName("store.playReviews")
class PlayReviewsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val applicationId: String = "",
    val appVersion: String = "",
    val maxResults: Int = 50,
    val credentialSecretName: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        requireSetting(applicationId, "applicationId", "Play Reviews node '$label'")
        requireSetting(appVersion, "appVersion", "Play Reviews node '$label'")
        val reviews = provide<PlayPublisher>().reviews(
            credential(context, credentialSecretName, "Play Reviews node '$label'"),
            applicationId,
            maxResults,
        )
        return reviewValue("GOOGLE_PLAY", applicationId, appVersion, reviews.map { review ->
            buildJsonObject {
                put("id", review.id)
                put("rating", review.rating)
                put("body", review.text)
                review.author?.let { put("reviewer", it) }
                review.locale?.let { put("locale", it) }
                review.createdEpochSeconds?.let { put("createdEpochSeconds", it) }
            }
        })
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        reviewValue("GOOGLE_PLAY", applicationId, appVersion, emptyList(), dryRun = true)
}

/** Fetches recent App Store customer reviews for rating alerts, digests, and analytics ingestion. */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "App Store Reviews",
    description = "Fetches recent App Store customer reviews as normalized JSON.",
    group = STORE_GROUP,
    subgroup = STORE_SUBGROUP,
    outputs = [OutputSlot(name = "out", kind = SlotKind.OBJECT, typeLabel = "App Store reviews")],
    settings = [
        SettingSlot(name = "applicationId", control = SettingControl.TEXT, label = "Bundle id", required = true),
        SettingSlot(name = "appVersion", control = SettingControl.TEXT, label = "App version", required = true),
        SettingSlot(name = "maxResults", control = SettingControl.NUMBER, label = "Maximum reviews", default = "50"),
        SettingSlot(
            name = "credentialSecretName", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "App Store credential", required = true,
        ),
    ],
)
@Serializable
@SerialName("store.appStoreReviews")
class AppStoreReviewsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val applicationId: String = "",
    val appVersion: String = "",
    val maxResults: Int = 50,
    val credentialSecretName: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        requireSetting(applicationId, "applicationId", "App Store Reviews node '$label'")
        requireSetting(appVersion, "appVersion", "App Store Reviews node '$label'")
        val reviews = provide<AppStorePublisher>().reviews(
            credential(context, credentialSecretName, "App Store Reviews node '$label'"),
            applicationId,
            maxResults,
        )
        return reviewValue("APP_STORE", applicationId, appVersion, reviews.map { review ->
            buildJsonObject {
                put("id", review.id)
                put("rating", review.rating)
                review.title?.let { put("title", it) }
                put("body", review.body)
                review.reviewer?.let { put("reviewer", it) }
                review.territory?.let { put("territory", it) }
                review.createdAt?.let { put("createdAt", it) }
            }
        })
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        reviewValue("APP_STORE", applicationId, appVersion, emptyList(), dryRun = true)
}

/** Fetches App Store review, build-processing, phased-release, and TestFlight-group state. */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "App Store State",
    description = "Fetches live App Store review, phased-release, build-processing, and TestFlight-group state.",
    group = STORE_GROUP,
    subgroup = STORE_SUBGROUP,
    outputs = [OutputSlot(name = "out", kind = SlotKind.OBJECT, typeLabel = "App Store state")],
    settings = [
        SettingSlot(name = "applicationId", control = SettingControl.TEXT, label = "Bundle id", required = true),
        SettingSlot(name = "appVersion", control = SettingControl.TEXT, label = "App version", required = true),
        SettingSlot(name = "buildNumber", control = SettingControl.TEXT, label = "Build number"),
        SettingSlot(
            name = "credentialSecretName", control = SettingControl.REFERENCE, reference = ReferenceSource.SECRET,
            label = "App Store credential", required = true,
        ),
    ],
)
@Serializable
@SerialName("store.appStoreState")
class AppStoreStateNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val applicationId: String = "",
    val appVersion: String = "",
    val buildNumber: String = "",
    val credentialSecretName: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        requireSetting(applicationId, "applicationId", "App Store State node '$label'")
        requireSetting(appVersion, "appVersion", "App Store State node '$label'")
        val state = provide<AppStorePublisher>().state(
            credential(context, credentialSecretName, "App Store State node '$label'"),
            applicationId,
            appVersion,
            buildNumber.ifBlank { null },
        )
        return jsonValue(
            buildJsonObject {
                put("applicationId", applicationId)
                put("store", "APP_STORE")
                put("appVersion", appVersion)
                state.buildNumber?.let { put("buildNumber", it) }
                put("reviewState", state.reviewState)
                state.betaReviewState?.let { put("betaReviewState", it) }
                state.buildProcessingState?.let { put("buildProcessingState", it) }
                state.phasedReleaseState?.let { put("phasedReleaseState", it) }
                put("testFlightGroups", buildJsonArray { state.testFlightGroups.forEach(::add) })
                put("testFlightCrashFeedback", buildJsonArray {
                    state.testFlightCrashFeedback.forEach { feedback ->
                        add(buildJsonObject {
                            put("id", feedback.id)
                            feedback.comment?.let { put("comment", it) }
                            feedback.email?.let { put("email", it) }
                            feedback.deviceModel?.let { put("deviceModel", it) }
                            feedback.osVersion?.let { put("osVersion", it) }
                            feedback.createdAt?.let { put("createdAt", it) }
                        })
                    }
                })
                put("observedAt", OffsetDateTime.now().toString())
            },
        )
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue = jsonValue(
        buildJsonObject {
            put("applicationId", applicationId)
            put("store", "APP_STORE")
            put("appVersion", appVersion)
            put("dryRun", true)
        },
    )
}

private fun reviewValue(
    store: String,
    applicationId: String,
    appVersion: String,
    reviews: List<JsonElement>,
    dryRun: Boolean = false,
): PipelineValue = jsonValue(
    buildJsonObject {
        put("applicationId", applicationId)
        put("store", store)
        put("appVersion", appVersion)
        put("observedAt", OffsetDateTime.now().toString())
        put("reviews", buildJsonArray { reviews.forEach(::add) })
        if (dryRun) put("dryRun", true)
    },
)

private fun jsonValue(value: JsonElement): PipelineValue = PipelineValue.of(value, JsonElement.serializer())
