package bosca.messages.pages.emails

import bosca.configuration.model.AdminOverrides
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmailLogoResolverTest {

    private val configurationService = mockk<ConfigurationService>()
    private val metadataService = mockk<MetadataService>()
    private val slugService = mockk<SlugService>(relaxed = true)
    private val objectStorageService = mockk<ObjectStorageService>()

    private val resolver = EmailLogoResolver(configurationService, metadataService, slugService, objectStorageService)

    private fun stubOverrides(json: String?) {
        if (json == null) {
            coEvery { configurationService.getByKey(AdminOverrides.KEY) } returns null
            return
        }
        val config = mockk<Configuration> { every { id } returns UUID.random() }
        coEvery { configurationService.getByKey(AdminOverrides.KEY) } returns config
        coEvery { configurationService.getValue(config.id) } returns Json.parseToJsonElement(json)
    }

    private fun stubAsset(id: UUID, type: String, svg: String? = null) {
        val metadata = mockk<Metadata>()
        every { metadata.contentType } returns type
        coEvery { metadataService.getById(id) } returns metadata
        if (svg != null) {
            coEvery { objectStorageService.getPath(metadata, null) } returns mockk()
            coEvery { objectStorageService.getString(any()) } returns svg
        }
    }

    @Test
    fun `returns null when no override is configured`() = runTest {
        stubOverrides(null)
        assertNull(resolver.resolve())
    }

    @Test
    fun `returns null when the override has no logo`() = runTest {
        stubOverrides("""{"title":"Acme"}""")
        assertNull(resolver.resolve())
    }

    @Test
    fun `embeds an svg override resolved by uuid`() = runTest {
        val id = UUID.random()
        stubOverrides("""{"logo":{"expanded":{"light":"$id"}}}""")
        stubAsset(id, "image/svg+xml", svg = "<svg>logo</svg>")

        assertEquals(EmailLogo.Svg("<svg>logo</svg>"), resolver.resolve())
    }

    @Test
    fun `prefers the auth slug logo field`() = runTest {
        val id = UUID.random()
        stubOverrides("""{"logo":{"slug":"$id"}}""")
        stubAsset(id, "image/svg+xml", svg = "<svg>auth</svg>")

        assertEquals(EmailLogo.Svg("<svg>auth</svg>"), resolver.resolve())
    }

    @Test
    fun `links a raster icon as an image`() = runTest {
        val id = UUID.random()
        stubOverrides("""{"logo":{"icon":"$id"}}""")
        stubAsset(id, "image/png")

        assertEquals(EmailLogo.Image(id.toString()), resolver.resolve())
    }

    @Test
    fun `ignores a non-image asset`() = runTest {
        val id = UUID.random()
        stubOverrides("""{"logo":{"icon":"$id"}}""")
        stubAsset(id, "application/pdf")

        assertNull(resolver.resolve())
    }

    @Test
    fun `falls back to the dark variant when light is absent`() = runTest {
        val id = UUID.random()
        stubOverrides("""{"logo":{"expanded":{"dark":"$id"}}}""")
        stubAsset(id, "image/svg+xml", svg = "<svg/>")

        assertEquals(EmailLogo.Svg("<svg/>"), resolver.resolve())
    }

    @Test
    fun `returns null when the asset read fails`() = runTest {
        val id = UUID.random()
        stubOverrides("""{"logo":{"expanded":{"light":"$id"}}}""")
        val metadata = mockk<Metadata> { every { contentType } returns "image/svg+xml" }
        coEvery { metadataService.getById(id) } returns metadata
        coEvery { objectStorageService.getPath(metadata, null) } returns mockk()
        coEvery { objectStorageService.getString(any()) } throws RuntimeException("storage down")

        assertNull(resolver.resolve())
    }
}
