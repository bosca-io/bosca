@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.git

import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.ArtifactsGitProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.git.service.ProducedArtifactVerifier
import bosca.git.service.RequiredArtifactVerifier
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

class ConfigurationTest {
    @AfterTest
    fun clearProviders() = ProviderRegistry.clear()

    @Test
    fun `selected registry integration supplies both Git extension points`() = runBlocking<Unit> {
        ProviderRegistry.clear()
        provides<ArtifactRepositoryService> { mockk() }
        ArtifactsGitProviderRegistrar().register()
        assertIs<RegistryProducedArtifactVerifier>(provide<ProducedArtifactVerifier>())
        assertIs<RegistryProducedArtifactVerifier>(provide<RequiredArtifactVerifier>())
    }
}
