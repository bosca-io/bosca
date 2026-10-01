package bosca.features

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeaturesTest {

    @Test
    fun `feature resolver defaults missing values and reads configured booleans`() {
        val config = ApplicationConfig.load(
            """
            present:
              enabled: true
            disabled:
              enabled: false
            """.trimIndent().byteInputStream(),
        )

        assertTrue(featureEnabled(config, "present.enabled"))
        assertFalse(featureEnabled(config, "disabled.enabled"))
        assertFalse(featureEnabled(config, "missing.enabled"))
    }

    @Test
    fun `feature flags resolve configured booleans through the application`() {
        BoscaApplication(ApplicationConfig.load(
            """
            community:
              enabled: true
            comments:
              enabled: false
            chat:
              enabled: true
            introspection:
              enabled: false
            workops:
              enabled: true
            ecommerce:
              enabled: false
            gateway:
              enabled: true
            kubernetes:
              enabled: false
            analyticsProcessor:
              enabled: true
            embedding:
              enabled: false
            """.trimIndent().byteInputStream(),
        ))

        assertTrue(Features.community)
        assertFalse(Features.comments)
        assertTrue(Features.chat)
        assertFalse(Features.introspection)
        assertTrue(Features.workops)
        assertFalse(Features.ecommerce)
        assertTrue(Features.gateway)
        assertFalse(Features.kubernetes)
        assertTrue(Features.analyticsProcessor)
        assertFalse(Features.embeddings)
    }
}
