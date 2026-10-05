package bosca.pipelines.annotation

import kotlin.reflect.KClass

/**
 * Declares one named output port of a `@PipelineNodeType`. A node emits a value on a port via
 * `PipelineValue.onPort(name)`, and the executor routes it along edges whose `sourcePort` matches —
 * the same routing the Condition node's `true`/`false` branches already use. A node that declares
 * output ports emits every value (success or error) on one of them.
 *
 * [error] marks a **failure** port: a value emitted there represents a recoverable error (e.g. an
 * integration reporting "already exists") that downstream nodes can branch on, rather than a thrown
 * exception that aborts the whole run. The contract:
 *  - an error port **wired** to a downstream node routes the payload there (and the success branch is
 *    skipped, like an untaken Condition branch);
 *  - an error port **emitted on but not wired** fails the run, surfacing the payload — an unhandled
 *    error never reports success.
 *
 * A non-error port that is unwired simply ends that branch.
 *
 * [kind], [type], [typeLabel] and [description] declare what the port emits, mirroring [InputSlot] on
 * the producing side: the connection validator checks a downstream slot against *this specific port*
 * (not just the node's implicit output), and the editor shows the port's label/type/description in
 * the inspector's "Produces" section. [type] is the specific object class an `OBJECT` port carries (a
 * class literal, e.g. `Collection::class`); KSP resolves it to the value's serial name,
 * matched against a downstream object slot's [InputSlot.type]. [Unit] (the default) = untyped/any
 * object. [typeLabel] is a human display label for the emitted value (`""` = none — the editor falls
 * back to the port name); [description] is a short note about what the port emits (`""` = none).
 *
 * Used only as a nested value of [PipelineNodeType.outputs]; never applied to a declaration directly.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class OutputSlot(
    val name: String,
    val kind: SlotKind = SlotKind.ANY,
    val error: Boolean = false,
    val type: KClass<*> = Unit::class,
    val typeLabel: String = "",
    val description: String = "",
)
