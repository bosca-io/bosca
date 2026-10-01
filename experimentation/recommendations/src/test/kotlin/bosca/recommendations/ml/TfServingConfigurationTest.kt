package bosca.recommendations.ml

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TfServingConfigurationTest {

    @Test
    fun `each parameter defaults independently and copy preserves the rest`() {
        // Single-arg constructions exercise each property's default-parameter branch independently.
        assertEquals("u", TfServingConfiguration(url = "u").url)
        assertEquals("m", TfServingConfiguration(modelName = "m").modelName)
        assertEquals("c", TfServingConfiguration(contentModelName = "c").contentModelName)
        assertEquals(9L, TfServingConfiguration(modelVersion = 9L).modelVersion)
        assertEquals(3, TfServingConfiguration(timeoutSeconds = 3).timeoutSeconds)
        assertEquals("recommender-personalized", TfServingConfiguration(url = "u").copy(modelVersion = 1L).modelName)
    }

    @Test
    fun `serializer round-trips a full value and decodes an empty object to defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val full = TfServingConfiguration(url = "u", modelName = "m", contentModelName = "c", modelVersion = 2L, timeoutSeconds = 8)
        val encoded = json.encodeToString(TfServingConfiguration.serializer(), full)
        assertEquals(full, json.decodeFromString(TfServingConfiguration.serializer(), encoded))
        // An empty object exercises the "field absent → use default" branch of every property.
        assertEquals(TfServingConfiguration(), json.decodeFromString(TfServingConfiguration.serializer(), "{}"))
        // Encoding a fully-default value exercises the serializer's "value equals default → skip" branches.
        assertEquals(TfServingConfiguration(), json.decodeFromString(TfServingConfiguration.serializer(), json.encodeToString(TfServingConfiguration.serializer(), TfServingConfiguration())))
        // encodeDefaults writes every field even when equal to its default → the "encode default" branch.
        val withDefaults = Json { encodeDefaults = true }
        assertEquals(TfServingConfiguration(), withDefaults.decodeFromString(TfServingConfiguration.serializer(), withDefaults.encodeToString(TfServingConfiguration.serializer(), TfServingConfiguration())))
        // A strict decoder rejects an unknown key → the decoder's unknown-field branch.
        assertFailsWith<Exception> { Json {}.decodeFromString(TfServingConfiguration.serializer(), """{"unexpected":1}""") }
    }

    @Test
    fun `defaults use standard TF Serving values`() {
        val config = TfServingConfiguration()
        assertEquals("http://tf-serving:8501", config.url)
        // Personalized (two-tower) model by default; content (cold) model is a separate served model.
        assertEquals("recommender-personalized", config.modelName)
        assertEquals("recommender-content", config.contentModelName)
        assertNull(config.modelVersion)
        assertEquals(10, config.timeoutSeconds)
    }

    @Test
    fun `custom values are stored correctly`() {
        val config = TfServingConfiguration(
            url = "http://custom-host:9000",
            modelName = "my-model",
            contentModelName = "my-content-model",
            modelVersion = 3,
            timeoutSeconds = 30,
        )
        assertEquals("http://custom-host:9000", config.url)
        assertEquals("my-model", config.modelName)
        assertEquals("my-content-model", config.contentModelName)
        assertEquals(3, config.modelVersion)
        assertEquals(30, config.timeoutSeconds)
    }

    @Test
    fun `model version URL segment is empty when version is null`() {
        val config = TfServingConfiguration(modelVersion = null)
        val versionPath = if (config.modelVersion != null) "/versions/${config.modelVersion}" else ""
        assertEquals("", versionPath)
    }

    @Test
    fun `model version URL segment includes version when specified`() {
        val config = TfServingConfiguration(modelVersion = 5)
        val versionPath = if (config.modelVersion != null) "/versions/${config.modelVersion}" else ""
        assertEquals("/versions/5", versionPath)
    }
}
