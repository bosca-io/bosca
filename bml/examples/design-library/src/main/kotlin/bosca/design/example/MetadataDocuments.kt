package bosca.design.example

import bosca.content.metadata.model.Document as MetadataDocument
import bosca.documents.*
import kotlin.uuid.Uuid

/** Local content records for demonstrating a consumer-owned document form. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object MetadataDocuments {
    val article = MetadataDocument(
        metadataId = Uuid.parse("00000000-0000-4000-8000-000000000003"),
        version = 2,
        title = "Consumer article",
        content = Content(Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = "Consumer document")))))),
    )
}
