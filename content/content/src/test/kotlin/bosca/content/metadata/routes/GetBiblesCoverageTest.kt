package bosca.content.metadata.routes

import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GetBiblesCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = GetBibles(metadataService, bibleService, metadataPermissionEvaluator)

    private val idOne = UUID.parse("00000000-0000-0000-0000-000000000001")
    private val idTwo = UUID.parse("00000000-0000-0000-0000-000000000002")

    private val findInput = FindQueryInput(contentTypes = listOf("bosca/v-bible"))

    private fun metadata(id: UUID, version: Int = 1) = Metadata(
        id = id,
        version = version,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun bible(id: UUID, version: Int = 1) = Bible(
        metadataId = id,
        version = version,
        systemId = "test-system",
        variant = "default",
        defaultVariant = true,
        name = "Test Bible",
        nameLocal = "Test Bible",
        description = "A test bible",
        abbreviation = "TST",
        abbreviationLocal = "TST",
        // Non-null styles so we can prove the route rewrites it to JsonNull.
        styles = JsonObject(mapOf("color" to JsonPrimitive("red"))),
    )

    private val call = mockk<ServerCall>(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(): List<Bible> {
        val method = GetBibles::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, authenticationContext) as List<Bible>
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeSerializer(): KSerializer<List<Bible>> {
        // serializer() is protected in the Route<T> base class, so invoke the
        // route's override via reflection (same technique used for execute()).
        val method = GetBibles::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        return method.call(route) as KSerializer<List<Bible>>
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `serializer returns list serializer for Bible`() {
        val serializer = invokeSerializer()
        assertEquals(ListSerializer(Bible.serializer()).descriptor, serializer.descriptor)
    }

    @Test
    fun `returns bibles for allowed metadata with styles rewritten to JsonNull`() = runTest {
        val md = metadata(idOne, version = 2)
        coEvery { metadataService.find(findInput) } returns listOf(md)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authenticationContext, listOf(md), PermissionAction.VIEW)
        } returns listOf(md)
        coEvery { bibleService.getBible(idOne, 2, null) } returns bible(idOne, version = 2)

        val result = executeRoute()

        assertEquals(1, result.size)
        // The route strips styles to JsonNull regardless of the stored value.
        assertSame(JsonNull, result[0].styles)
        assertEquals(idOne, result[0].metadataId)
        coVerify { bibleService.getBible(idOne, 2, null) }
    }

    @Test
    fun `drops metadata whose bible lookup returns null via mapNotNull`() = runTest {
        val mdOne = metadata(idOne)
        val mdTwo = metadata(idTwo)
        coEvery { metadataService.find(findInput) } returns listOf(mdOne, mdTwo)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(
                authenticationContext,
                listOf(mdOne, mdTwo),
                PermissionAction.VIEW,
            )
        } returns listOf(mdOne, mdTwo)
        coEvery { bibleService.getBible(idOne, 1, null) } returns bible(idOne)
        // Second entry has no bible -> mapNotNull drops it.
        coEvery { bibleService.getBible(idTwo, 1, null) } returns null

        val result = executeRoute()

        assertEquals(1, result.size)
        assertEquals(idOne, result[0].metadataId)
    }

    @Test
    fun `returns empty list when permission filter removes all metadata`() = runTest {
        val md = metadata(idOne)
        coEvery { metadataService.find(findInput) } returns listOf(md)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authenticationContext, listOf(md), PermissionAction.VIEW)
        } returns emptyList()

        val result = executeRoute()

        assertTrue(result.isEmpty())
        // No metadata survived filtering, so no bible lookups happen.
        coVerify(exactly = 0) { bibleService.getBible(any(), any(), any()) }
    }

    @Test
    fun `returns empty list when find returns no metadata`() = runTest {
        coEvery { metadataService.find(findInput) } returns emptyList()
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authenticationContext, emptyList(), PermissionAction.VIEW)
        } returns emptyList()

        val result = executeRoute()

        assertTrue(result.isEmpty())
    }
}
