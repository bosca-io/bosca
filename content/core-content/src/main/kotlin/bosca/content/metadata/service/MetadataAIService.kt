package bosca.content.metadata.service

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.service.Service

/**
 * Generates AI metadata *for* a document — a concise description, the relevant topic collections, and an
 * estimated reading time. **Read-only**: it returns suggestions, it does not persist them.
 *
 * This is a contract: content depends only on it, while the implementation lives in the AI layer (Kit's
 * sub-agents) so the generation never leaks into the content module. The document text is **provided or
 * fetched** — if [document] is given (the editor's live content) it is used, otherwise the stored
 * document for the metadata is read.
 */
interface MetadataAIService : Service {

    /** A concise (~30-word) meta description summarizing [metadata]'s document. */
    suspend fun description(metadata: Metadata, document: DocumentInput?): String

    /** The platform topic collections relevant to [metadata]'s document (matched from the topic catalog). */
    suspend fun topics(metadata: Metadata, document: DocumentInput?): List<Collection>

    /** The estimated reading time of [metadata]'s document, in whole minutes. */
    suspend fun readingTimeInMinutes(metadata: Metadata, document: DocumentInput?): Int
}
