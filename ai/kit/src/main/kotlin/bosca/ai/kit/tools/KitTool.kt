package bosca.ai.kit.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer

/**
 * Base for Kit's Koog tools — the port target for the V1 `AbstractTool<I, O>`.
 *
 * It is a native Koog [Tool] (so the agent runtime drives it, features apply, and it is
 * testable/mockable), but keeps the V1 ergonomics: a `name`/`description` plus a suspend
 * [execute] that receives the [AuthenticationContext]. Authentication is resolved from the
 * ambient [KitToolContext] rather than passed as a tool argument, so the model can never
 * spoof identity and permission checks stay honest.
 *
 * Concrete tools hand in the **explicit, KSP-generated** `Input.serializer()` / `Output.serializer()`
 * rather than a `typeToken<T>()`. `typeToken<T>()` is `typeOf<T>()`, which forces kotlin-reflect's
 * `createType()` to deserialize the type's `@Metadata` at runtime — that path is unsupported in the
 * GraalVM native image (it throws "Unresolved class" from `kotlin.reflect.jvm.internal`) even though
 * the class is registered for serialization. Passing the compiled serializer keeps both the JSON
 * schema generation and arg encode/decode entirely reflection-free. The internal Koog
 * [KSerializerTypeToken] bridge is intentionally confined to this one class.
 *
 * @param TArgs the `@Serializable` argument type the tool accepts.
 * @param TResult the `@Serializable` result type the tool returns.
 */
@OptIn(InternalKoogSerializationApi::class)
abstract class KitTool<TArgs, TResult> : Tool<TArgs, TResult> {

    /**
     * Usual case: Koog generates the tool's JSON schema from [argsSerializer]'s descriptor.
     * Concrete tools pass `Input.serializer()` / `Output.serializer()`.
     */
    constructor(argsSerializer: KSerializer<TArgs>, resultSerializer: KSerializer<TResult>, name: String, description: String) :
        super(KSerializerTypeToken(argsSerializer), KSerializerTypeToken(resultSerializer), name, description)

    /**
     * Supplies a hand-written [descriptor] instead of generating one. Use this when the arg
     * type can't be schema-generated (e.g. it contains the recursive tiptap `Content`) and the
     * tool is invoked directly by code rather than chosen by an LLM.
     */
    constructor(argsSerializer: KSerializer<TArgs>, resultSerializer: KSerializer<TResult>, descriptor: ToolDescriptor) :
        super(KSerializerTypeToken(argsSerializer), KSerializerTypeToken(resultSerializer), descriptor)

    /** Run the tool for [input] on behalf of [authentication]. */
    abstract suspend fun execute(authentication: AuthenticationContext, input: TArgs): TResult

    final override suspend fun execute(args: TArgs): TResult {
        val authentication = currentCoroutineContext()[KitToolContext]?.authentication
            ?: error("KitToolContext is missing; launch Kit with a KitToolContext element so tools can resolve the caller")
        return execute(authentication, args)
    }
}
