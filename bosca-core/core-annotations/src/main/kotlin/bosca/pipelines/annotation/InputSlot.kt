package bosca.pipelines.annotation

import kotlin.reflect.KClass

/**
 * Declares one typed input slot (port) of a `@PipelineNodeType`. The slot is matched to an inbound
 * edge by its [name] (the edge's target port). Authors constrain the slot with whichever of the
 * three composable forms fit the node — the executor ANDs whichever are present:
 *
 *  - [kind] — the coarse JSON kind the value must be, including [SlotKind.UUID]. [SlotKind.ANY]
 *    (the default) imposes no kind constraint.
 *  - [type] — the **specific class** an `OBJECT` value must be (a class literal, e.g.
 *    `Collection::class`), for slots that demand a *specific* object rather than any object. KSP
 *    resolves it to the type's serial name, checked at run time against the inbound value's carried
 *    serializer; [Unit] = any object. (A value already reshaped to plain JSON — e.g. by a JSONata
 *    node — has no carried type, so this check is skipped and [schema] is relied on.)
 *  - [schema] — a JSON-Schema-subset document (the same subset the engine validates pipeline
 *    input/output contracts with: `type`/`properties`/`required`/`items`/`enum`) the value's JSON
 *    form must satisfy; `""` = none. Enforced even when the upstream output kind is unknown.
 *
 * A [required] slot with no inbound value fails the run.
 *
 * Used only as a nested value of [PipelineNodeType.inputs]; never applied to a declaration directly.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class InputSlot(
    val name: String,
    val kind: SlotKind = SlotKind.ANY,
    /** Palette display label; `""` derives one from [kind]. */
    val typeLabel: String = "",
    /**
     * A short, human description of what this slot expects — shown in the editor's node inspector so a
     * builder sees the requirement up front instead of discovering it at run time (e.g. "Any value
     * carrying a metadata id"). `""` = none.
     */
    val description: String = "",
    /**
     * The specific type an `OBJECT` slot must be, as a class literal (e.g. `Collection::class`) —
     * type-safe and rename-proof; KSP resolves it to the value's serial name (honoring `@SerialName`)
     * for the descriptor the validator matches on. [Unit] (the default) = any object.
     */
    val type: KClass<*> = Unit::class,
    /** JSON-Schema-subset document the value's JSON form must satisfy; `""` = none. */
    val schema: String = "",
    val required: Boolean = true,
)
