package bosca.content.embedding.service

import bosca.content.embedding.model.EmbeddingChunk
import bosca.content.metadata.model.Metadata
import bosca.service.Service

/**
 * Computes semantic embeddings for content text via the text-embeddings-inference sidecar.
 *
 * The recommender's index-time pipeline uses this to store per-metadata embedding chunks. Embedding is a gated,
 * optional enrichment: disabled or blank inputs return no result, while failures from an enabled embedding
 * provider propagate so durable jobs can retry them.
 */
interface EmbeddingService : Service {

    /**
     * Returns tokenizer-bounded semantic embedding chunks for [text], or `null` when embeddings are disabled
     * (`embedding.enabled` is off) or the text is blank. Inputs longer than the serving model's context
     * window are split using the serving model's tokenizer without dividing overlapping UTF-8 source spans.
     * Provider failures propagate to the caller.
     */
    suspend fun embed(text: String): List<EmbeddingChunk>?

    /**
     * Computes and stores semantic embedding chunks for [metadata] using the same body-text extraction as
     * content search.
     *
     * @return true when embedding chunks were stored, or false when embeddings are disabled, the content has
     *   no extractable text, the metadata disappeared, or the source changed again during the bounded retry
     */
    suspend fun embed(metadata: Metadata): Boolean

    /**
     * Recomputes semantic data in offset-paged batches for ready, recommendable metadata.
     *
     * @param overwriteExisting false to fill only missing embeddings, true to refresh every eligible item
     * @param batchSize number of metadata entries to inspect per page
     * @return the number of embeddings written
     */
    suspend fun backfill(overwriteExisting: Boolean, batchSize: Int): Long
}
