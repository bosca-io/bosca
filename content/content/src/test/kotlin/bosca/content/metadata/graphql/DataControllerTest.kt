package bosca.content.metadata.graphql

import bosca.content.metadata.model.Data
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataControllerTest {

    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = DataController(metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `type returns data type`() {
        val data = Data(metadataId = UUID.random(), version = 1, type = DataType.ATTRIBUTES)

        assertEquals(DataType.ATTRIBUTES, controller.type(data))
    }

    @Test
    fun `template returns null when templateMetadataId is null`() = runTest {
        val data = Data(metadataId = UUID.random(), version = 1, type = DataType.ATTRIBUTES, templateMetadataId = null)

        assertNull(controller.template(authentication, data))
    }

    @Test
    fun `template returns null when templateMetadataVersion is null`() = runTest {
        val data = Data(
            metadataId = UUID.random(),
            version = 1,
            type = DataType.ATTRIBUTES,
            templateMetadataId = UUID.random(),
            templateMetadataVersion = null
        )

        assertNull(controller.template(authentication, data))
    }

    @Test
    fun `template returns metadata when allowed`() = runTest {
        val templateId = UUID.random()
        val metadata = Metadata(
            id = templateId,
            name = "Template",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )
        val data = Data(
            metadataId = UUID.random(),
            version = 1,
            type = DataType.ATTRIBUTES,
            templateMetadataId = templateId,
            templateMetadataVersion = 1
        )

        coEvery { metadataService.getById(templateId, 1) } returns metadata
        coEvery { permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW) } returns true

        assertEquals(metadata, controller.template(authentication, data))
    }

    @Test
    fun `template returns null when not allowed`() = runTest {
        val templateId = UUID.random()
        val metadata = Metadata(
            id = templateId,
            name = "Template",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )
        val data = Data(
            metadataId = UUID.random(),
            version = 1,
            type = DataType.ATTRIBUTES,
            templateMetadataId = templateId,
            templateMetadataVersion = 1
        )

        coEvery { metadataService.getById(templateId, 1) } returns metadata
        coEvery { permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW) } returns false

        assertNull(controller.template(authentication, data))
    }
}
