package bosca.ecommerce.service

import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**the content→ecommerce publish bridge forwards the published metadata id + version to the product service. */
@OptIn(ExperimentalUuidApi::class)
class EcommerceMetadataPublishListenerTest {

    private val productService = mockk<ProductService>(relaxed = true)
    private val listener = EcommerceMetadataPublishListener(productService)

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `onPublished forwards the metadata id and version to onContentPublished`() = runTest {
        val metadataId = UUID.random()

        listener.onPublished(metadataId, version = 7)

        coVerify(exactly = 1) { productService.onContentPublished(metadataId, 7) }
    }
}
