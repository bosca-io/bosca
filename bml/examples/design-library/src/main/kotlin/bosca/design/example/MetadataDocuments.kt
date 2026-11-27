package bosca.design.example

import bosca.content.metadata.model.Document as MetadataDocument
import bosca.documents.*
import kotlin.uuid.Uuid

/** Local content records for demonstrating a consumer-owned document form. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object MetadataDocuments {
    val containers = listOf(
        bosca.design.model.DocumentContainerOption("MAIN_CONTENT", "Main content", "Consumer article body"),
        bosca.design.model.DocumentContainerOption("RELATED", "Related content", "Consumer metadata selection", "METADATA", listOf("kind = \"article\"")),
        bosca.design.model.DocumentContainerOption("PASSAGE", "Scripture", "Consumer scripture selection", "BIBLE"),
    )
    val article = MetadataDocument(
        metadataId = Uuid.parse("00000000-0000-4000-8000-000000000003"),
        version = 2,
        title = "Consumer article",
        content = Content(Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "Consumer document"))),
            HeadingNode(HeadingAttributes(level = 1), content = listOf(TextNode(text = "Consumer title"))),
            HeadingNode(HeadingAttributes(level = 1), content = listOf(TextNode(text = "Consumer section"))),
            ContainerNode(ContainerAttributes(name = "MAIN_CONTENT", metadataId = Uuid.parse("00000000-0000-4000-8000-000000000004"), references = listOf("GEN.1"), renderer = "card"), content = listOf(
                ParagraphNode(content = listOf(TextNode(text = "Stored content "), MentionNode(MentionAttributes(id = "author-reference", label = "An author", entityType = "profile", mentionSuggestionChar = "@")))),
            )),
            BibleNode(BibleAttributes(metadataId = "scripture-content", references = listOf("GEN.1.1"))),
            ContainerNode(ContainerAttributes(name = "RELATED", metadataId = Uuid.parse("00000000-0000-4000-8000-000000000004"), renderer = "card")),
            ContainerNode(ContainerAttributes(name = "PASSAGE", metadataId = Uuid.parse("00000000-0000-4000-8000-000000000005"), references = listOf("GEN.1.1"))),
            CodeBlockNode(CodeBlockAttributes(language = "typescript"), content = listOf(TextNode(text = "const title = 'Consumer';"))),
        ))),
    )
}
