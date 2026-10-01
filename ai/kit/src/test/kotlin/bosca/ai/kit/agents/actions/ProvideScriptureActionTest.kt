package bosca.ai.kit.agents.actions

import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.kitRequest
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.Text
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.toJson
import bosca.content.metadata.service.BibleService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class ProvideScriptureActionTest {

    @Test
    fun `reports that no installed Bible can source the requested text`() = runTest {
        val bibleService = mockk<BibleService>()
        coEvery { bibleService.getBibles() } returns emptyList()

        val response = execute(
            bibleService,
            KitState(
                request = kitRequest("Quote John 3:16"),
                route = KitRoute.SCRIPTURE,
                references = listOf("John 3:16"),
            ),
        )

        assertEquals(
            "I can’t provide Bible text because no Bible translation is installed in Bosca.",
            response.text,
        )
    }

    @Test
    fun `reports unavailable requested translation and lists installed sources`() = runTest {
        val bibleService = mockk<BibleService>()
        val bible = bible(name = "Verified Test Bible", abbreviation = "VTB")
        coEvery { bibleService.getBibles() } returns listOf(bible)

        val response = execute(
            bibleService,
            KitState(
                request = kitRequest("Quote John 3:16 in Missing"),
                route = KitRoute.SCRIPTURE,
                references = listOf("John 3:16"),
                translation = "Missing",
            ),
        )

        assertEquals(
            "I couldn't find the requested translation \"Missing\". Available translations: Verified Test Bible (VTB).",
            response.text,
        )
    }

    @Test
    fun `uses the first installed Bible as default and requires references`() = runTest {
        val bibleService = mockk<BibleService>()
        val bible = bible(name = "Only Installed Bible", abbreviation = "")
        coEvery { bibleService.getBibles() } returns listOf(bible)

        val response = execute(
            bibleService,
            KitState(request = kitRequest("Give me a verse"), route = KitRoute.SCRIPTURE),
        )

        assertEquals(
            "I need a specific Bible reference before I can retrieve exact text from Only Installed Bible.",
            response.text,
        )
    }

    @Test
    fun `reports an unresolvable reference without inventing text`() = runTest {
        val bibleService = mockk<BibleService>()
        val bible = bible(name = "Verified Test Bible", abbreviation = "VTB", defaultVariant = true)
        coEvery { bibleService.getBibles() } returns listOf(bible)
        coEvery { bibleService.getReferences(bible, "Nowhere 9:99") } returns emptyList()

        val response = execute(
            bibleService,
            KitState(
                request = kitRequest("Quote Nowhere 9:99"),
                route = KitRoute.SCRIPTURE,
                references = listOf("Nowhere 9:99"),
            ),
        )

        assertEquals(
            "I couldn’t resolve exact Bible text for Nowhere 9:99 in Verified Test Bible (VTB).",
            response.text,
        )
    }

    @Test
    fun `retrieves a whole chapter from the default translation without verse filtering`() = runTest {
        val bibleService = mockk<BibleService>()
        val bible = bible(name = "Verified Test Bible", abbreviation = "Verified Test Bible", defaultVariant = true)
        val reference = Reference("JAS.1")
        val chapter = BibleChapter(
            metadataId = bible.metadataId,
            version = bible.version,
            variant = bible.variant,
            bookUsfm = "JAS",
            usfm = "JAS.1",
            components = ComponentContainer(
                ContainerType.PARAGRAPH,
                listOf(Text("Whole stored chapter.", null)),
                null,
            ).toJson(),
            sort = 1,
        )
        coEvery { bibleService.getBibles() } returns listOf(bible)
        coEvery { bibleService.getReferences(bible, "James 1") } returns listOf(reference)
        coEvery { bibleService.getChapter(bible, reference) } returns chapter
        coEvery { bibleService.getHumanLong(bible, reference) } returns "James 1"

        val response = execute(
            bibleService,
            KitState(
                request = kitRequest("Give me James 1"),
                route = KitRoute.SCRIPTURE,
                references = listOf("James 1"),
            ),
        )

        assertEquals(
            "Here are passages from Verified Test Bible:\n\n" +
                "**James 1**\n" +
                "Whole stored chapter.\n\n" +
                "Source: Verified Test Bible, retrieved from Bosca's installed Bible content.",
            response.text,
        )
    }

    private suspend fun execute(bibleService: BibleService, state: KitState): KitResponse.Text {
        val result = ProvideScriptureAction(bibleService).provide(state)
        return assertIs(result.response)
    }

    private fun bible(
        name: String,
        abbreviation: String,
        defaultVariant: Boolean = false,
    ) = Bible(
        metadataId = Uuid.parse("750e8400-e29b-41d4-a716-446655440000"),
        version = 1,
        systemId = "verified-test-bible",
        variant = "default",
        defaultVariant = defaultVariant,
        name = name,
        nameLocal = "",
        description = "",
        abbreviation = abbreviation,
        abbreviationLocal = "",
        styles = buildJsonObject { },
    )
}
