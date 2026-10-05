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
import bosca.server.LanguageItem
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import java.util.Locale
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class BaseBibleRouteCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // GetBible is the simplest concrete subclass of BaseBibleRoute; its constructor order is
    // (metadataService, metadataPermissionEvaluator, bibleService).
    private val route = GetBible(metadataService, metadataPermissionEvaluator, bibleService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private fun metadata(
        id: UUID = testId,
        version: Int = 1,
        languageTag: String = "en",
        deleted: Boolean = false,
    ) = Metadata(
        id = id,
        version = version,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = languageTag,
        deleted = deleted,
        workflowStateId = "published",
    )

    private fun bible(
        id: UUID = testId,
        version: Int = 1,
        variant: String = "default",
    ) = Bible(
        metadataId = id,
        version = version,
        systemId = "test-system",
        variant = variant,
        defaultVariant = true,
        name = "Test Bible",
        nameLocal = "Test Bible",
        description = "A test bible",
        abbreviation = "TST",
        abbreviationLocal = "TST",
        styles = JsonNull,
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = emptyMap(),
        acceptLanguageItems: List<LanguageItem> = emptyList(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        every { request.acceptLanguageItems() } returns acceptLanguageItems
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    // BaseBibleRoute exposes getMetadata/getBible/getBibleByLanguage as protected member
    // extension functions on ServerCall. Reflectively, callSuspend binds positionally as
    // (instanceReceiver, extensionReceiver, valueParams...) => (route, call, auth).
    private fun baseFunction(name: String): KFunction<*> =
        BaseBibleRoute::class.functions.first { it.name == name }
            .also { it.isAccessible = true }

    private suspend fun invokeGetMetadata(call: ServerCall, auth: AuthenticationContext): Metadata? {
        val fn = baseFunction("getMetadata")
        return try {
            fn.callSuspend(route, call, auth) as Metadata?
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private suspend fun invokeGetBible(call: ServerCall, auth: AuthenticationContext): Bible {
        val fn = baseFunction("getBible")
        return try {
            fn.callSuspend(route, call, auth) as Bible
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private suspend fun invokeGetBibleByLanguage(call: ServerCall, auth: AuthenticationContext): Bible {
        val fn = baseFunction("getBibleByLanguage")
        return try {
            fn.callSuspend(route, call, auth) as Bible
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    // ----- getMetadata -----

    @Test
    fun `getMetadata resolves by id when no version query param`() = runTest {
        val call = createCall()
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md

        val result = invokeGetMetadata(call, authenticationContext)

        assertSame(md, result)
        coVerify { metadataService.getById(testId) }
        coVerify { metadataPermissionEvaluator.verifyAllowed(authenticationContext, md, PermissionAction.VIEW) }
    }

    @Test
    fun `getMetadata resolves by id and version when version query param present`() = runTest {
        val call = createCall(queryParams = mapOf("version" to "3"))
        val md = metadata(version = 3)
        coEvery { metadataService.getById(testId, 3) } returns md

        val result = invokeGetMetadata(call, authenticationContext)

        assertSame(md, result)
        coVerify { metadataService.getById(testId, 3) }
    }

    @Test
    fun `getMetadata ignores non-numeric version and uses single-arg lookup`() = runTest {
        val call = createCall(queryParams = mapOf("version" to "not-a-number"))
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md

        val result = invokeGetMetadata(call, authenticationContext)

        assertSame(md, result)
        coVerify { metadataService.getById(testId) }
    }

    @Test
    fun `getMetadata returns null when service returns null`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null

        val result = invokeGetMetadata(call, authenticationContext)

        assertNull(result)
    }

    @Test
    fun `getMetadata returns null when metadata is deleted`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata(deleted = true)

        val result = invokeGetMetadata(call, authenticationContext)

        assertNull(result)
        coVerify(exactly = 0) { metadataPermissionEvaluator.verifyAllowed(any(), any(), any()) }
    }

    @Test
    fun `getMetadata returns null when id is not a valid uuid`() = runTest {
        val call = createCall(pathParams = mapOf("id" to "not-a-uuid"))

        val result = invokeGetMetadata(call, authenticationContext)

        assertNull(result)
    }

    @Test
    fun `getMetadata returns null when service throws`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } throws RuntimeException("boom")

        val result = invokeGetMetadata(call, authenticationContext)

        assertNull(result)
    }

    @Test
    fun `getMetadata returns null when id path parameter is missing`() = runTest {
        // With no "id" path parameter, the `val id: String by pathParameters` delegate throws
        // IllegalStateException on first read — but that first read happens at `UUID.parse(id)`
        // INSIDE getMetadata's `try { } catch (e: Exception)` block, which swallows it and yields
        // null (the same path as an unparseable UUID). So the observable behavior is a null return,
        // not a propagated exception.
        val call = createCall(pathParams = emptyMap())

        val result = invokeGetMetadata(call, authenticationContext)

        assertNull(result)
        coVerify(exactly = 0) { metadataService.getById(any()) }
        coVerify(exactly = 0) { metadataService.getById(any(), any()) }
    }

    // ----- getBible -----

    @Test
    fun `getBible returns bible for metadata found by id`() = runTest {
        val call = createCall()
        val md = metadata()
        val b = bible()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBible(call, authenticationContext)

        assertSame(b, result)
        coVerify { bibleService.getBible(testId, 1, null) }
    }

    @Test
    fun `getBible passes variant query param through to bible service`() = runTest {
        val call = createCall(queryParams = mapOf("variant" to "kjv"))
        val md = metadata()
        val b = bible(variant = "kjv")
        coEvery { metadataService.getById(testId) } returns md
        coEvery { bibleService.getBible(testId, 1, "kjv") } returns b

        val result = invokeGetBible(call, authenticationContext)

        assertSame(b, result)
        coVerify { bibleService.getBible(testId, 1, "kjv") }
    }

    @Test
    fun `getBible errors when bible service returns null`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { bibleService.getBible(testId, 1, null) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            invokeGetBible(call, authenticationContext)
        }
        assertEquals("Bible not found", ex.message)
    }

    @Test
    fun `getBible falls back to language lookup when metadata not found`() = runTest {
        val call = createCall(
            pathParams = mapOf("id" to "not-a-uuid"),
            queryParams = mapOf("language" to "en"),
        )
        val md = metadata()
        val b = bible()
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBible(call, authenticationContext)

        assertSame(b, result)
    }

    // ----- getBibleByLanguage -----

    @Test
    fun `getBibleByLanguage uses explicit language query param`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en"))
        val md = metadata(languageTag = "en")
        val b = bible()
        coEvery {
            metadataService.find(
                FindQueryInput(
                    contentTypes = listOf("bosca/v-bible"),
                    languageTags = listOf("eng", "en"),
                )
            )
        } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
        coVerify { metadataPermissionEvaluator.verifyAllowed(authenticationContext, md, PermissionAction.VIEW) }
    }

    @Test
    fun `getBibleByLanguage matches by two-letter language when iso3 has no match`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en"))
        // languageTag "en" matches the two-letter fallback but not the iso3 "eng" key.
        val md = metadata(languageTag = "en")
        val b = bible()
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
    }

    @Test
    fun `getBibleByLanguage matches by iso3 language`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en"))
        val md = metadata(languageTag = "eng")
        val b = bible()
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
    }

    @Test
    fun `getBibleByLanguage passes variant query param through`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en", "variant" to "kjv"))
        val md = metadata(languageTag = "en")
        val b = bible(variant = "kjv")
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, "kjv") } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
        coVerify { bibleService.getBible(testId, 1, "kjv") }
    }

    @Test
    fun `getBibleByLanguage falls back to accept-language header when no language param`() = runTest {
        val call = createCall(acceptLanguageItems = listOf(LanguageItem("en", 1.0f)))
        val md = metadata(languageTag = "en")
        val b = bible()
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
    }

    @Test
    fun `getBibleByLanguage resolves default locale for wildcard language`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "*"))
        val defaultLocale = Locale.getDefault()
        val md = metadata(languageTag = defaultLocale.language)
        val b = bible()
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
    }

    @Test
    fun `getBibleByLanguage errors when no metadata found for language`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en"))
        coEvery { metadataService.find(any()) } returns emptyList()

        val ex = assertFailsWith<IllegalStateException> {
            invokeGetBibleByLanguage(call, authenticationContext)
        }
        assertEquals("Bible not found for language [en]", ex.message)
    }

    @Test
    fun `getBibleByLanguage errors when bible service returns null for matched metadata`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en"))
        val md = metadata(languageTag = "en")
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            invokeGetBibleByLanguage(call, authenticationContext)
        }
        assertEquals("Bible not found", ex.message)
    }

    @Test
    fun `getBibleByLanguage throws NoSuchElement when found metadata does not match requested locale`() = runTest {
        // Requested language "en" but the only found metadata is tagged for a different language,
        // so no locale iteration produces a match and the loop exhausts.
        val call = createCall(queryParams = mapOf("language" to "en"))
        val md = metadata(languageTag = "fr")
        coEvery { metadataService.find(any()) } returns listOf(md)

        assertFailsWith<NoSuchElementException> {
            invokeGetBibleByLanguage(call, authenticationContext)
        }
    }

    @Test
    fun `getBibleByLanguage skips non-matching locale then matches a later one`() = runTest {
        // Two accept-language items: the first ("fr") has no matching metadata (continue),
        // the second ("en") matches — exercising the `continue` branch inside the loop.
        val call = createCall(
            acceptLanguageItems = listOf(
                LanguageItem("fr", 1.0f),
                LanguageItem("en", 0.9f),
            )
        )
        val md = metadata(languageTag = "en")
        val b = bible()
        coEvery { metadataService.find(any()) } returns listOf(md)
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = invokeGetBibleByLanguage(call, authenticationContext)

        assertSame(b, result)
    }
}
