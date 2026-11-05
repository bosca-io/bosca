package bosca.ai.kit.agents.strategy

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredRequest
import ai.koog.prompt.structure.StructuredRequestConfig
import ai.koog.prompt.structure.json.JsonStructure
import bosca.ai.kit.configuration.KitJson
import kotlinx.serialization.KSerializer

/**
 * A `Native` structured-output config whose JSON schema matches the **provider** behind [model] — so Kit
 * is not pinned to one LLM vendor.
 *
 * The schema generator is asked from the [promptExecutor], which returns the dialect its client speaks:
 * for OpenAI that is the strict variant that forces *every* property into `required` (the generic
 * generator omits defaulted fields, and OpenAI rejects that with *"'required' … must include every key in
 * properties"*); for Anthropic its own variant; otherwise the generic JSON-Schema generator. Centralized
 * here so every structured Kit sub-agent picks its schema the same provider-aware way.
 *
 * Callers hand in the **explicit, KSP-generated** [serializer] (`XResponse.serializer()`) rather than a
 * reified type. Koog's `reified` `JsonStructure.create<T>()` resolves the serializer via `serializer<T>()`
 * → `typeOf<T>()`, which fails in the GraalVM native image (kotlin-reflect's `createType()` throws
 * "Unresolved class"). Passing the compiled serializer keeps schema generation reflection-free.
 */
fun <Output> kitStructuredConfig(
    serializer: KSerializer<Output>,
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
): StructuredRequestConfig<Output> = StructuredRequestConfig(
    default = StructuredRequest.Native(
        JsonStructure.create(
            id = serializer.descriptor.serialName.substringAfterLast("."),
            serializer = serializer,
            json = json.json,
            schemaGenerator = promptExecutor.getStandardJsonSchemaGenerator(model),
        ),
    ),
)
