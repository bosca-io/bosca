package bosca.content.metadata.graphql

import bosca.configuration.ContentSchemaRegistrar
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BibleProvidersSchemaTest {

    @Test
    fun `generated content schema exposes provider administration namespaces`() = runTest {
        val schema = ContentSchemaRegistrar().load()
        assertTrue(schema.contains("bibleProviders: BibleProviders!"))
        assertTrue(schema.contains("bibleProviders: BibleProvidersMutation!"))
        assertTrue(schema.contains("type BibleProviderConfiguration"))
    }

    @Test
    fun `configuration output cannot expose saved credentials`() {
        val schema = assertNotNull(javaClass.getResourceAsStream("/graphql/metadata/bible-providers.graphqls"))
            .bufferedReader().use { it.readText() }
        val configuration = schema.substringAfter("type BibleProviderConfiguration {").substringBefore("}")
        assertTrue(configuration.contains("hasApiKey: Boolean!"))
        assertFalse(configuration.contains("apiKey:"))
        assertFalse(schema.contains("fumsToken"))
    }
}
