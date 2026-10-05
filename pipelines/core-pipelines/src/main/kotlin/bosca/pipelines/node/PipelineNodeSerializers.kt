package bosca.pipelines.node

import kotlinx.serialization.modules.SerializersModule

/**
 * A per-module contribution of polymorphic [PipelineNode] subclass serializers — registered with
 * **explicit** `subclass(X::class, X.serializer())` entries (no reflection, GraalVM-native safe).
 *
 * KSP generates one implementation per module that declares any `@PipelineNodeType`; the engine
 * aggregates them via `ProviderRegistry.findAll(PipelineNodeSerializers::class)` into the
 * `SerializersModule` used to (de)serialize stored pipeline graphs (folded onto the platform's
 * global module). This is how the open polymorphic node graph survives native compilation while
 * staying extensible across modules.
 */
interface PipelineNodeSerializers {

    val module: SerializersModule

    /**
     * Palette metadata for each contributed node type (key = the node's `@SerialName`
     * discriminator). Aggregated the same way as [module] to drive the registry GraphQL and the
     * Studio pipeline-editor palette — fully data-driven, no hardcoded node lists in the UI.
     */
    val descriptors: List<NodeDescriptor> get() = emptyList()
}
