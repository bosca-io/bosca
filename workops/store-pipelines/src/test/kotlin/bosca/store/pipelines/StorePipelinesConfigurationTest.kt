@file:OptIn(InternalDI::class)

package bosca.store.pipelines

import bosca.di.ProviderRegistry
import bosca.di.StorePipelinesProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.workops.deploy.DeployTarget
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class StorePipelinesConfigurationTest {

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `store pipeline registration preserves WorkOps release deploy targets`() = runTest {
        ProviderRegistry.clear()
        val googlePlay = mockk<DeployTarget>()
        val appStore = mockk<DeployTarget>()
        provides<DeployTarget>(name = "GOOGLE_PLAY") { googlePlay }
        provides<DeployTarget>(name = "APP_STORE") { appStore }

        StorePipelinesProviderRegistrar().register()

        assertSame(googlePlay, provide<DeployTarget>("GOOGLE_PLAY"))
        assertSame(appStore, provide<DeployTarget>("APP_STORE"))
    }

    @Test
    fun `configuration constructs store publisher providers`() {
        val configuration = StorePipelinesConfiguration()

        assertIs<AndroidPublisherPlayPublisher>(configuration.playPublisher())
        assertIs<AppStoreConnectPublisher>(configuration.appStorePublisher())
    }
}
