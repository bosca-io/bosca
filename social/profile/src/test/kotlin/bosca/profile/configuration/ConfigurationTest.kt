package bosca.profile.configuration

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigurationTest {

    @Test
    fun `JobQueueNames profileJobQueue is profileQueue`() {
        assertEquals("profileQueue", JobQueueNames.profileJobQueue)
    }

    @Test
    fun `JobQueueNames profileRunner is profileQueueRunner`() {
        assertEquals("profileQueueRunner", JobQueueNames.profileRunner)
    }

    @Test
    fun `JobQueueNames profileQueue is profile`() {
        assertEquals("profile", JobQueueNames.profileQueue)
    }

    @Test
    fun `all JobQueueNames constants are non-empty`() {
        assertTrue(JobQueueNames.profileJobQueue.isNotEmpty())
        assertTrue(JobQueueNames.profileRunner.isNotEmpty())
        assertTrue(JobQueueNames.profileQueue.isNotEmpty())
    }

    @Test
    fun `profileJobQueue and profileQueue are different`() {
        assertNotEquals(JobQueueNames.profileJobQueue, JobQueueNames.profileQueue)
    }

    @Test
    fun `profileRunner and profileJobQueue are different`() {
        assertNotEquals(JobQueueNames.profileRunner, JobQueueNames.profileJobQueue)
    }

    @Test
    fun `social notification configuration uses the profile application origin`() {
        val application = application(
            """
            app:
              url: https://app.example
            social:
              notifications:
                url: https://studio.example
                profile-url: https://profiles.example
            """.trimIndent(),
        )

        val configuration = Configuration().socialNotificationConfiguration(application)

        assertEquals("https://studio.example", configuration.applicationUrl)
        assertEquals("https://profiles.example", configuration.profileApplicationUrl)
    }

    @Test
    fun `social notification configuration falls back to the social origin for profiles`() {
        val application = application(
            """
            app:
              url: https://app.example
            social:
              notifications:
                url: https://studio.example
            """.trimIndent(),
        )

        val configuration = Configuration().socialNotificationConfiguration(application)

        assertEquals("https://studio.example", configuration.profileApplicationUrl)
    }

    private fun application(config: String) = BoscaApplication(
        ApplicationConfig.load(config.byteInputStream()),
    )
}
