package bosca.recommendations

import bosca.experimentation.configuration.ExperimentationConfig
import bosca.recommendations.configuration.Configuration
import bosca.recommendations.configuration.RecommendationsMigration
import bosca.recommendations.installer.PackageInstallerRegistry
import bosca.recommendations.installer.RecommendationsInstaller
import bosca.recommendations.installer.TuningAttributeTypeInstaller
import bosca.recommendations.ml.TfServingConfiguration
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the module's DI provider wiring: the package-installer registry (installers + the installable
 * package definition) and the infrastructure configuration (migration, job queue, job runner).
 */
class ConfigurationAndRegistryTest {

    @Test
    fun `package installer registry provides both installers and the package definition`() {
        val registry = PackageInstallerRegistry()
        val recommendationsInstaller =
            registry.recommendationsInstaller(
                mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), ExperimentationConfig(),
            )
        assertTrue(recommendationsInstaller is RecommendationsInstaller)
        assertEquals("1.0.36", recommendationsInstaller.version)
        assertTrue(registry.tuningAttributeTypeInstaller(mockk()) is TuningAttributeTypeInstaller)

        val pkg = registry.recommendationsPackage()
        assertEquals("recommendations", pkg.key)
        assertEquals("1.0.30", pkg.versions.last().version)
        assertEquals(listOf("recommendations"), pkg.versions.last().installerNames)
        assertTrue(pkg.versions.none { it.version == "1.0.14" })
    }

    @Test
    fun `configuration provides the migration, job queue and runner`() {
        val configuration = Configuration()
        assertTrue(configuration.migration() is RecommendationsMigration)

        val factory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        every { factory.create(any()) } returns queue
        assertEquals(queue, configuration.recommendationsJobQueue(factory))

        val runner = configuration.recommendationsJobQueueRunner(queue, mockk(relaxed = true), mockk(relaxed = true))
        assertNotNull(runner)
    }

    @Test
    fun `content model serving configuration is independent infrastructure config`() {
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(
            ApplicationConfig.load(
                """
                    recommendations:
                      tfServing:
                        url: http://content-serving:8501
                        modelName: personalized-v2
                        contentModelName: content-v2
                        timeoutSeconds: "7"
                """.trimIndent().byteInputStream(),
            ),
        )

        val result = Configuration().tfServingConfiguration(application)

        assertEquals("http://content-serving:8501", result.url)
        assertEquals("personalized-v2", result.modelName)
        assertEquals("content-v2", result.contentModelName)
        assertEquals(7, result.timeoutSeconds)
        assertEquals(null, result.modelVersion)
    }

    @Test
    fun `content model serving configuration uses defaults when properties are absent`() {
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(
            ApplicationConfig.load("recommendations: {}".byteInputStream()),
        )

        val result = Configuration().tfServingConfiguration(application)

        assertEquals(TfServingConfiguration(), result)
    }

    @Test
    fun `content model serving configuration uses the default timeout when the value is invalid`() {
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(
            ApplicationConfig.load(
                """
                    recommendations:
                      tfServing:
                        timeoutSeconds: invalid
                """.trimIndent().byteInputStream(),
            ),
        )

        val result = Configuration().tfServingConfiguration(application)

        assertEquals(TfServingConfiguration().timeoutSeconds, result.timeoutSeconds)
    }
}
