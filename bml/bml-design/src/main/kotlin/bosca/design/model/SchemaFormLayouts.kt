package bosca.design.model

import bosca.bml.render.Html

/**
 * Walks nested layouts before BML rendering, whose compiled library dependencies are acyclic.
 * Only fixed structural tags are emitted here; fields and display controls remain BML components.
 */
fun schemaFormParts(nodes: List<SchemaFormNode>): List<SchemaFormPart> = buildList {
    fun boundary(markup: String) { add(SchemaFormPart(markup = markup)) }
    fun walk(node: SchemaFormNode) {
        boundary("<div class=\"schema-node span-${node.columns}\"" +
            (if (node.type == "section" && node.label.isNotBlank()) " role=\"group\" aria-label=\"${Html.escape(node.label)}\"" else "") + ">")
        when (node.type) {
            "section", "row", "tab" -> {
                if (node.type != "tab" && node.label.isNotBlank()) {
                    boundary("<h3>${Html.escape(node.label)}</h3>")
                }
                boundary("<div class=\"${if (node.type == "row") "schema-row" else "schema-stack"}\">")
                node.children.forEach(::walk)
                boundary("</div>")
            }
            "tabs" -> {
                add(SchemaFormPart(node = node))
                for (child in node.children) {
                    boundary("<div role=\"tabpanel\" aria-label=\"${Html.escape(child.label)}\" " +
                        "data-schema-tab=\"${child.key}\" data-schema-tab-group=\"${node.key}\"" +
                        (if (child.key != node.firstTab) " hidden" else "") + ">")
                    walk(child)
                    boundary("</div>")
                }
            }
            else -> add(SchemaFormPart(node = node))
        }
        boundary("</div>")
    }
    nodes.forEach(::walk)
}
