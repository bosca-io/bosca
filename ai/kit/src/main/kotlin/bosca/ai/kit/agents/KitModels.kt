package bosca.ai.kit.agents

import ai.koog.prompt.llm.LLModel

/** Fallback models for direct metadata, localization and release-note generation. */
class KitModels(
    val default: LLModel,
    val write: LLModel = default,
    val describe: LLModel = default,
    val topics: LLModel = default,
    val readingTime: LLModel = default,
)
