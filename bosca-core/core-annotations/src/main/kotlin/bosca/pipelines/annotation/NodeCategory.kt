package bosca.pipelines.annotation

/**
 * Grouping/category for a node type in the editor palette.
 *
 * Lives in `core-annotations` (not `core-pipelines`) so `@PipelineNodeType` can reference it
 * directly as an enum — annotations may carry enum values, and the KSP node registrar reads the
 * entry name without any string mapping.
 */
enum class NodeCategory { INPUT, OUTPUT, TRANSFORM, FETCH, COMBINE, ROUTE, ACTION }
