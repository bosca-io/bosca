package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.GuideTemplateAttribute
import bosca.content.metadata.model.GuideTemplateStep
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideTemplateControllerCoverageTest {

    private val service = mockk<GuideTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideTemplateController(service, metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun template(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        rrule: String = "FREQ=DAILY",
        type: GuideType = GuideType.LINEAR,
        configuration: kotlinx.serialization.json.JsonElement? = null,
        defaultAttributes: kotlinx.serialization.json.JsonElement? = null
    ) = GuideTemplate(
        metadataId = metadataId,
        version = version,
        rrule = rrule,
        type = type,
        configuration = configuration,
        defaultAttributes = defaultAttributes
    )

    private fun metadata(id: UUID = UUID.random(), version: Int = 1) = Metadata(
        id = id,
        name = "template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        version = version
    )

    private fun attribute(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        key: String = "attr"
    ) = GuideTemplateAttribute(
        metadataId = metadataId,
        version = version,
        key = key,
        name = "Attribute",
        description = "An attribute"
    )

    private fun templateStep(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        id: Long = 1L
    ) = GuideTemplateStep(
        metadataId = metadataId,
        version = version,
        id = id,
        templateMetadataId = null,
        templateMetadataVersion = null,
        sort = 0
    )

    // ---------- type ----------

    @Test
    fun `type returns template type`() {
        assertEquals(GuideType.CALENDAR, controller.type(template(type = GuideType.CALENDAR)))
    }

    // ---------- configuration ----------

    @Test
    fun `configuration returns template configuration when present`() {
        val config = JsonPrimitive("config")
        assertEquals(config, controller.configuration(template(configuration = config)))
    }

    @Test
    fun `configuration returns null when absent`() {
        assertNull(controller.configuration(template(configuration = null)))
    }

    // ---------- defaultAttributes ----------

    @Test
    fun `defaultAttributes returns template default attributes when present`() {
        val attrs = JsonPrimitive("defaults")
        assertEquals(attrs, controller.defaultAttributes(template(defaultAttributes = attrs)))
    }

    @Test
    fun `defaultAttributes returns null when absent`() {
        assertNull(controller.defaultAttributes(template(defaultAttributes = null)))
    }

    // ---------- rrule ----------

    @Test
    fun `rrule returns template rrule`() {
        assertEquals("FREQ=WEEKLY", controller.rrule(template(rrule = "FREQ=WEEKLY")))
    }

    // ---------- attributes ----------

    @Test
    fun `attributes populates batch via service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, List<GuideTemplateAttribute>>(listOf(key))
        val attrs = listOf(attribute())

        coEvery { service.addTemplateAttributesToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, List<GuideTemplateAttribute>>>().setData(key, attrs)
        }

        controller.attributes(batch)

        assertEquals(attrs, batch.getData(key))
        coVerify { service.addTemplateAttributesToBatch(batch) }
    }

    @Test
    fun `attributes defaults unpopulated keys to empty list`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, List<GuideTemplateAttribute>>(listOf(key))

        coJustRun { service.addTemplateAttributesToBatch(any()) }

        controller.attributes(batch)

        val result = batch.getData(key)
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    // ---------- steps ----------

    @Test
    fun `steps populates batch via service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, List<GuideTemplateStep>>(listOf(key))
        val steps = listOf(templateStep())

        coEvery { service.addTemplateStepsToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, List<GuideTemplateStep>>>().setData(key, steps)
        }

        controller.steps(batch)

        assertEquals(steps, batch.getData(key))
        coVerify { service.addTemplateStepsToBatch(batch) }
    }

    @Test
    fun `steps defaults unpopulated keys to empty list`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, List<GuideTemplateStep>>(listOf(key))

        coJustRun { service.addTemplateStepsToBatch(any()) }

        controller.steps(batch)

        val result = batch.getData(key)
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    // ---------- metadata ----------

    @Test
    fun `metadata returns null when service returns null`() = runTest {
        val tmpl = template()

        coEvery { metadataService.getById(tmpl.metadataId, tmpl.version) } returns null

        assertNull(controller.metadata(authentication, tmpl))
    }

    @Test
    fun `metadata returns metadata when allowed`() = runTest {
        val metadataId = UUID.random()
        val tmpl = template(metadataId = metadataId, version = 3)
        val md = metadata(id = metadataId, version = 3)

        coEvery { metadataService.getById(metadataId, 3) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.VIEW) } returns Unit

        assertEquals(md, controller.metadata(authentication, tmpl))
        coVerify { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.VIEW) }
    }

    @Test
    fun `metadata propagates when permission denied`() = runTest {
        val metadataId = UUID.random()
        val tmpl = template(metadataId = metadataId, version = 1)
        val md = metadata(id = metadataId, version = 1)

        coEvery { metadataService.getById(metadataId, 1) } returns md
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.VIEW)
        } throws IllegalStateException("denied")

        kotlin.test.assertFailsWith<IllegalStateException> {
            controller.metadata(authentication, tmpl)
        }
    }

    @Test
    fun `metadata handles null authentication`() = runTest {
        val metadataId = UUID.random()
        val tmpl = template(metadataId = metadataId, version = 1)
        val md = metadata(id = metadataId, version = 1)

        coEvery { metadataService.getById(metadataId, 1) } returns md
        coEvery { permissionEvaluator.verifyAllowed(null, md, PermissionAction.VIEW) } returns Unit

        assertEquals(md, controller.metadata(null, tmpl))
    }
}
