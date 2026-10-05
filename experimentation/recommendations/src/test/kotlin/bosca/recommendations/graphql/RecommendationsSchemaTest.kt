package bosca.recommendations.graphql

import bosca.graphql.parser.Parser
import bosca.recommendations.configuration.RecommendationsSchemaRegistrar
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RecommendationsSchemaTest {

    @Test
    fun `module schema exposes saved contexts and request context selection`() = runBlocking<Unit> {
        val sdl = RecommendationsSchemaRegistrar().load()

        assertTrue("type RecommendationContext" in sdl)
        assertTrue("type RecommendationContexts" in sdl)
        assertTrue("type RecommendationContextsMutation" in sdl)
        assertTrue("contentFilter: RecommendationContentFilter!" in sdl)
        assertTrue("contextType: String" in sdl)
        assertTrue("contexts: RecommendationContexts!" in sdl)
        assertTrue("contexts: RecommendationContextsMutation!" in sdl)
        assertTrue("modelSelection: RecommendationModelSelection!" in sdl)
        assertTrue("personalizedVersions: [Long!]!" in sdl)

        Parser.parse(sdl)
    }
}
