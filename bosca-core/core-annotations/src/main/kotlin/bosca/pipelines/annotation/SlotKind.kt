package bosca.pipelines.annotation

/**
 * The kind of value a node input slot accepts (or a node output produces).
 *
 * [ANY] imposes no constraint. The executor enforces a slot's kind at run time against the inbound
 * value's JSON form — native-safe, no reflection, mirroring the engine's `JsonSchemaValidator`.
 * [UUID] is a string whose content is a canonical UUID: the one kind the JSON-Schema subset can't
 * express on its own, which is why it is a first-class kind here rather than a schema `format`.
 *
 * Lives in `core-annotations` so `@InputSlot`/`@PipelineNodeType` can reference it directly as an
 * enum; `core-pipelines` reuses the same type in `NodeInputSlot`/`NodeDescriptor`.
 */
enum class SlotKind { ANY, STRING, INTEGER, NUMBER, BOOLEAN, UUID, OBJECT, ARRAY }
