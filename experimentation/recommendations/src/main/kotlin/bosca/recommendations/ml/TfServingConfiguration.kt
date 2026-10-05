package bosca.recommendations.ml

import kotlinx.serialization.Serializable

/**
 * Configuration for connecting to the TensorFlow Serving instance that hosts recommendation models.
 * The module-level instance configures the always-on content model. A PERSONALIZED strategy may store
 * the same shape in its `configuration` JSON to select a different endpoint or personalized model.
 */
@Serializable
data class TfServingConfiguration(
    /** The base URL of the TF Serving REST API (e.g., "http://tf-serving:8501"). */
    val url: String = "http://tf-serving:8501",
    /**
     * The **personalized** (two-tower) model registered in TF Serving. Serves user->items retrieval
     * (`serving_default`) — which returns empty for users it never trained on — and the learned `rank`
     * head. This is the model the model-vs-model A/B pins versions of.
     */
    val modelName: String = "recommender-personalized",
    /**
     * The **content** (cold) model registered in TF Serving. Serves item->item `similar` only, so it is
     * queried BY ITEM to build the always-on content base (and to answer similar/co-engaged). Trained every
     * run, so it exists even with zero interactions.
     */
    val contentModelName: String = "recommender-content",
    /** Optional personalized-model version to pin (the A/B arm). Null uses the latest version. */
    val modelVersion: Long? = null,
    /** Request timeout in seconds for model prediction calls. */
    val timeoutSeconds: Int = 10,
    /** Exact content export selected alongside a context's personalized export. Null uses latest. */
    val contentModelVersion: Long? = null,
)
