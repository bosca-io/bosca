package bosca.ai.kit.agents

import ai.koog.prompt.llm.LLModel

class KitModels(
    val default: LLModel,
    val route: LLModel = default,
    val chat: LLModel = default,
    val write: LLModel = default,
    val analytics: LLModel = default,
    val describe: LLModel = default,
    val topics: LLModel = default,
    val readingTime: LLModel = default,
    val script: LLModel = default,
    val pipeline: LLModel = default,
    val image: LLModel = default,
    val graphql: LLModel = default,
)
