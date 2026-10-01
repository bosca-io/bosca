package bosca.content.embedding.model

/**
 * One tokenizer-bounded semantic embedding for a contiguous portion of a content document.
 *
 * @property index zero-based chunk order within the extracted document text
 * @property tokenStart inclusive global source-token offset within the extracted document
 * @property tokenEnd exclusive global source-token offset within the extracted document
 * @property tokenCount number of source tokens represented by this chunk, excluding model special tokens
 * @property aggregationWeight overlap-aware source-token mass used when pooling chunks into one item vector
 * @property embedding semantic vector returned by the embedding model
 */
data class EmbeddingChunk(
    val index: Int,
    val tokenCount: Int,
    val tokenStart: Int = 0,
    val tokenEnd: Int = tokenStart + tokenCount,
    val aggregationWeight: Double = tokenCount.toDouble(),
    val embedding: List<Float>,
)
