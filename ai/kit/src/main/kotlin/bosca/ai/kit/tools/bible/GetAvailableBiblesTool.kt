package bosca.ai.kit.tools.bible

import bosca.ai.kit.tools.KitTool
import bosca.content.find.FindQueryInput
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/**
 * Lists the Bible translations installed in the platform (content type `bosca/v-bible`).
 * The returned ids feed the other bible tools.
 */
class GetAvailableBiblesTool(
    private val metadataService: MetadataService,
) : KitTool<GetAvailableBiblesTool.Input, GetAvailableBiblesTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "bible_get_available",
    description = "List all available Bible translations in the system",
) {

    @Serializable
    class Input

    @Serializable
    data class BibleSummary(
        val id: String,
        val name: String,
        val languageTag: String,
    )

    @Serializable
    data class Output(
        val bibles: List<BibleSummary>,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val metadataList = metadataService.find(FindQueryInput(contentTypes = listOf("bosca/v-bible")))
        return Output(
            bibles = metadataList.map { metadata ->
                BibleSummary(
                    id = metadata.id.toString(),
                    name = metadata.name,
                    languageTag = metadata.languageTag,
                )
            },
        )
    }
}
