package bosca.pipelines.node

/**
 * A node whose **input** slot types are declared **per instance**, refining the node type's static
 * [NodeInputSlot]s — the input-side mirror of [HasDeclaredOutput]. A generic slot (say, an email
 * template node's `payload`, ANY by type because the required contract depends on *which* template
 * the instance picked) declares here what the wired value must be, and the
 * [SlotConnectionValidator] enforces it: the slot behaves as a type-pinned OBJECT slot, so a source
 * that does not declare the matching type is refused at connect time.
 *
 * Pure metadata read off the decoded node instance — no registry, no reflection.
 */
interface HasDeclaredInputs {
    /**
     * Slot name → the specific type (a serial name, `shape:<name>`, or `email:<project>/<template>`)
     * this instance requires on that slot. A slot not in the map keeps its static declaration.
     */
    val declaredInputTypes: Map<String, String>
}
