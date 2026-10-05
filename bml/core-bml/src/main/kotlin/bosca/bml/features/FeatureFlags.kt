package bosca.bml.features

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.GraphQLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** The value and variation selected by Bosca for one feature flag. */
@Serializable
public data class FeatureFlagEvaluation(
    public val flagKey: String,
    public val variationKey: String,
    public val value: JsonElement,
    public val experimentId: String? = null,
    /** Whether evaluation was incomplete; a blank [variationKey] means consumers should use their fallback. */
    public val degraded: Boolean = false,
)

/**
 * Per-render feature-flag facade over BML's GraphQL data plane.
 *
 * Evaluations are cached for the lifetime of this instance, which is normally one [bosca.bml.render.RenderContext].
 * This keeps repeated expressions consistent within a render and prevents duplicate GraphQL calls. Feature-flag
 * transport, GraphQL, and response failures are logged and fail closed so an optional flag cannot prevent the page
 * from rendering. Typed helpers use their authored fallback for unavailable flags and values of the wrong JSON type.
 */
public class FeatureFlags(
    private val gql: GraphQLClient,
    /** Stable anonymous identity. Null means evaluation is unavailable and no request will be made. */
    public val installationId: String? = null,
    private val token: String? = null,
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, FeatureFlagEvaluation>()
    private var allEvaluations: List<FeatureFlagEvaluation>? = null

    /** Evaluates [flagKey], returning the complete variation metadata and raw JSON value. */
    public suspend fun evaluate(flagKey: String): FeatureFlagEvaluation = mutex.withLock {
        require(flagKey.isNotBlank()) { "Feature flag key must not be blank" }
        cache[flagKey]?.let { return@withLock it }
        val requiredInstallationId = installationId?.takeIf(String::isNotBlank)
        if (requiredInstallationId == null) {
            log.warn("bml feature flags: '{}' is unavailable because this render has no installation identity", flagKey)
            return@withLock unavailableEvaluation(flagKey).also { cache[flagKey] = it }
        }
        try {
            val data = gql.execute(
                EVALUATE_QUERY,
                buildJsonObject {
                    put("flagKey", flagKey)
                    put("installationId", requiredInstallationId)
                },
                EVALUATE_OPERATION,
                token,
            )
            val evaluation = parseEvaluation(data.requiredPath("featureFlags", "evaluate"))
            if (evaluation.flagKey != flagKey) {
                throw GraphQLException(
                    "Feature flag evaluation for '$flagKey' returned '${evaluation.flagKey}'",
                )
            }
            evaluation.also { cache[flagKey] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("bml feature flags: evaluation of '{}' failed; serving a degraded fallback", flagKey, e)
            unavailableEvaluation(flagKey).also { cache[flagKey] = it }
        }
    }

    /** Evaluates every active flag once and seeds subsequent per-key lookups from that snapshot. */
    public suspend fun evaluateAll(): List<FeatureFlagEvaluation> = mutex.withLock {
        allEvaluations?.let { return@withLock it }
        val requiredInstallationId = installationId?.takeIf(String::isNotBlank)
        if (requiredInstallationId == null) {
            log.warn("bml feature flags: bulk evaluation is unavailable because this render has no installation identity")
            return@withLock emptyList<FeatureFlagEvaluation>().also { allEvaluations = it }
        }
        try {
            val data = gql.execute(
                EVALUATE_ALL_QUERY,
                buildJsonObject { put("installationId", requiredInstallationId) },
                EVALUATE_ALL_OPERATION,
                token,
            )
            val values = data.requiredPath("featureFlags", "evaluateAll") as? JsonArray
                ?: throw GraphQLException("Feature flag evaluateAll returned a non-array value")
            values.map(::parseEvaluation).map { evaluation ->
                cache.getOrPut(evaluation.flagKey) { evaluation }
            }.also { evaluations ->
                allEvaluations = evaluations
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("bml feature flags: bulk evaluation failed; serving an empty snapshot", e)
            emptyList<FeatureFlagEvaluation>().also { allEvaluations = it }
        }
    }

    /** Returns whether [flagKey] selected the named [variationKey]. */
    public suspend fun variation(flagKey: String, variationKey: String): Boolean {
        val evaluation = evaluate(flagKey)
        return !evaluation.isUnknown() && evaluation.variationKey == variationKey
    }

    /** Returns a boolean flag, or [default] when the flag is unknown or is not a JSON boolean. */
    public suspend fun enabled(flagKey: String, default: Boolean = false): Boolean {
        val evaluation = evaluate(flagKey)
        if (evaluation.isUnknown()) return default
        val primitive = evaluation.value as? JsonPrimitive ?: return default
        return primitive.takeUnless { it.isString }?.booleanOrNull ?: default
    }

    /** Returns a string flag, or [default] when the flag is unknown or is not a JSON string. */
    public suspend fun string(flagKey: String, default: String): String {
        val evaluation = evaluate(flagKey)
        if (evaluation.isUnknown()) return default
        val primitive = evaluation.value as? JsonPrimitive ?: return default
        return primitive.takeIf { it.isString }?.contentOrNull ?: default
    }

    /** Returns a numeric flag as a [Double], or [default] for an unknown/non-numeric value. */
    public suspend fun number(flagKey: String, default: Double): Double {
        val evaluation = evaluate(flagKey)
        if (evaluation.isUnknown()) return default
        val primitive = evaluation.value as? JsonPrimitive ?: return default
        return primitive.takeUnless { it.isString }?.doubleOrNull ?: default
    }

    /** Returns the raw JSON flag value, or [default] when the flag key is unknown. */
    public suspend fun value(flagKey: String, default: JsonElement = JsonNull): JsonElement {
        val evaluation = evaluate(flagKey)
        return if (evaluation.isUnknown()) default else evaluation.value
    }

    private fun FeatureFlagEvaluation.isUnknown(): Boolean = variationKey.isBlank()

    private fun unavailableEvaluation(flagKey: String): FeatureFlagEvaluation = FeatureFlagEvaluation(
        flagKey = flagKey,
        variationKey = "",
        value = JsonNull,
        degraded = true,
    )

    private fun parseEvaluation(element: JsonElement): FeatureFlagEvaluation {
        val obj = element as? JsonObject
            ?: throw GraphQLException("Feature flag evaluation returned a non-object value")
        return FeatureFlagEvaluation(
            flagKey = obj.requiredString("flagKey"),
            variationKey = obj.requiredString("variationKey"),
            value = obj["value"] ?: throw GraphQLException("Feature flag evaluation omitted value"),
            experimentId = obj["experimentId"]
                ?.takeUnless { it is JsonNull }
                ?.let { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: throw GraphQLException("Feature flag evaluation returned an invalid experimentId")
                },
            degraded = (obj["degraded"] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.booleanOrNull
                ?: throw GraphQLException("Feature flag evaluation omitted degraded"),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw GraphQLException("Feature flag evaluation omitted $name")

    private fun JsonElement.requiredPath(vararg names: String): JsonElement {
        var current = this
        for (name in names) {
            current = (current as? JsonObject)?.get(name)?.takeUnless { it is JsonNull }
                ?: throw GraphQLException("Feature flag response omitted ${names.joinToString(".")}")
        }
        return current
    }

    private companion object {
        val log = LoggerFactory.getLogger(FeatureFlags::class.java)

        const val EVALUATE_OPERATION = "BmlEvaluateFeatureFlag"
        const val EVALUATE_ALL_OPERATION = "BmlEvaluateAllFeatureFlags"

        const val EVALUATE_QUERY = """
            query BmlEvaluateFeatureFlag(${'$'}flagKey: String!, ${'$'}installationId: String!) {
                featureFlags {
                    evaluate(flagKey: ${'$'}flagKey, installationId: ${'$'}installationId) {
                        flagKey
                        variationKey
                        value
                        experimentId
                        degraded
                    }
                }
            }
        """

        const val EVALUATE_ALL_QUERY = """
            query BmlEvaluateAllFeatureFlags(${'$'}installationId: String!) {
                featureFlags {
                    evaluateAll(installationId: ${'$'}installationId) {
                        flagKey
                        variationKey
                        value
                        experimentId
                        degraded
                    }
                }
            }
        """
    }
}
