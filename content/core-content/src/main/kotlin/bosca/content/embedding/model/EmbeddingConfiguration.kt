package bosca.content.embedding.model

/**
 * Connection + prompting settings for the semantic embedding service (HuggingFace Text Embeddings
 * Inference serving EmbeddingGemma). Read from the `embedding` config block; every field has a default
 * that works with the in-cluster/compose sidecar.
 *
 * @property enabled master switch (config key `embedding.enabled`). Defaults off so deployments without
 *   the embedding sidecar never call it; when off, [EmbeddingService.embed] returns null without any HTTP.
 * @property url base URL of the TEI service (its model information, tokenizer, and embedding endpoints
 *   are called).
 * @property model the model TEI serves — informational; TEI is configured with `--model-id`.
 * @property dimension expected embedding length; must match the `metadata_embeddings.embedding` column.
 * @property documentPrompt optional template wrapped around the text before embedding. A non-blank prompt
 *   must contain exactly one `{text}` placeholder; blank sends the raw text. EmbeddingGemma benefits from
 *   its document prompt, but only set this if TEI does not already apply it — otherwise the prompt would be
 *   applied twice.
 * @property chunkOverlapTokens target number of source tokens repeated between adjacent chunks. Overlap
 *   preserves context around chunk boundaries; overlap-aware aggregation weights prevent repeated tokens
 *   from increasing a document's importance during recommendation-model training.
 * @property timeoutSeconds per-request connect/read timeout.
 */
data class EmbeddingConfiguration(
    val enabled: Boolean = false,
    val url: String = "http://text-embeddings-inference:80",
    val model: String = "google/embeddinggemma-300m",
    val dimension: Int = 768,
    val documentPrompt: String = "",
    val chunkOverlapTokens: Int = 128,
    val timeoutSeconds: Int = 30,
)
