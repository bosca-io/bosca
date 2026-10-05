package bosca.graphql.language

/**
 * Reusable traversal + immutable rewrite over the AST. Functional rather than a 35-method
 * enter/leave visitor: [preOrder] is a lazy [Sequence] that composes with `filterIsInstance`/`filter`/`count`,
 * and [transform] is a single bottom-up `(Node) -> Node` rewrite. The whole structural map lives once in
 * [replaceChildren]; [children], [preOrder], and [transform] are derived from it.
 *
 * Rewrites are **identity-preserving**: a node whose children are all unchanged is returned as-is (by
 * reference), so untouched subtrees aren't reallocated and callers can detect change with `===`. `commonMain`,
 * no dependencies.
 */

/** This node's direct child nodes, in source order. */
fun Node.children(): List<Node> = buildList { replaceChildren { add(it); it } }

/** A lazy pre-order (node-before-children) walk of this node and all descendants. */
fun Node.preOrder(): Sequence<Node> = sequence {
    yield(this@preOrder)
    for (child in children()) yieldAll(child.preOrder())
}

/**
 * Bottom-up immutable rewrite: each node's children are transformed first, then [transform] is applied to the
 * reconstructed node. Return the same node to leave it unchanged; the result is a structurally-new tree that
 * shares every untouched subtree with the original.
 */
fun Node.transform(transform: (Node) -> Node): Node = transform(replaceChildren { it.transform(transform) })

/** Map [block] over each element; returns the same list instance when nothing changed (preserves identity). */
private inline fun <reified T : Node> List<T>.replaceAll(block: (Node) -> Node): List<T> {
    var changed = false
    val result = ArrayList<T>(size)
    for (element in this) {
        val replacement = block(element) as T
        if (replacement !== element) changed = true
        result.add(replacement)
    }
    return if (changed) result else this
}

/** A copy of this node with [block] applied to each direct child; returns `this` when no child changed. */
private fun Node.replaceChildren(block: (Node) -> Node): Node = when (this) {
    // True leaves — no child nodes.
    is Variable, is IntValue, is FloatValue, is StringValue, is BooleanValue, is NullValue, is EnumValue, is NamedType -> this

    // Directive-only nodes.
    is ScalarTypeDefinition -> directives.replaceAll(block).let { if (it === directives) this else copy(directives = it) }
    is EnumValueDefinition -> directives.replaceAll(block).let { if (it === directives) this else copy(directives = it) }
    is ScalarTypeExtension -> directives.replaceAll(block).let { if (it === directives) this else copy(directives = it) }

    is Document -> definitions.replaceAll(block).let { if (it === definitions) this else copy(definitions = it) }

    is OperationDefinition -> {
        val vars = variableDefinitions.replaceAll(block)
        val dirs = directives.replaceAll(block)
        val sel = block(selectionSet) as SelectionSet
        if (vars === variableDefinitions && dirs === directives && sel === selectionSet) this
        else copy(variableDefinitions = vars, directives = dirs, selectionSet = sel)
    }

    is FragmentDefinition -> {
        val condition = block(typeCondition) as NamedType
        val dirs = directives.replaceAll(block)
        val sel = block(selectionSet) as SelectionSet
        if (condition === typeCondition && dirs === directives && sel === selectionSet) this
        else copy(typeCondition = condition, directives = dirs, selectionSet = sel)
    }

    is VariableDefinition -> {
        val variable = block(this.variable) as Variable
        val type = block(this.type) as Type
        val default = defaultValue?.let { block(it) as Value }
        val dirs = directives.replaceAll(block)
        if (variable === this.variable && type === this.type && default === defaultValue && dirs === directives) this
        else copy(variable = variable, type = type, defaultValue = default, directives = dirs)
    }

    is SelectionSet -> selections.replaceAll(block).let { if (it === selections) this else copy(selections = it) }

    is Field -> {
        val args = arguments.replaceAll(block)
        val dirs = directives.replaceAll(block)
        val sel = selectionSet?.let { block(it) as SelectionSet }
        if (args === arguments && dirs === directives && sel === selectionSet) this
        else copy(arguments = args, directives = dirs, selectionSet = sel)
    }

    is FragmentSpread -> directives.replaceAll(block).let { if (it === directives) this else copy(directives = it) }

    is InlineFragment -> {
        val condition = typeCondition?.let { block(it) as NamedType }
        val dirs = directives.replaceAll(block)
        val sel = block(selectionSet) as SelectionSet
        if (condition === typeCondition && dirs === directives && sel === selectionSet) this
        else copy(typeCondition = condition, directives = dirs, selectionSet = sel)
    }

    is Argument -> (block(value) as Value).let { if (it === value) this else copy(value = it) }
    is Directive -> arguments.replaceAll(block).let { if (it === arguments) this else copy(arguments = it) }

    is ListValue -> values.replaceAll(block).let { if (it === values) this else copy(values = it) }
    is ObjectValue -> fields.replaceAll(block).let { if (it === fields) this else copy(fields = it) }
    is ObjectField -> (block(value) as Value).let { if (it === value) this else copy(value = it) }

    is ListType -> (block(type) as Type).let { if (it === type) this else copy(type = it) }
    is NonNullType -> (block(type) as Type).let { if (it === type) this else copy(type = it) }

    is SchemaDefinition -> {
        val dirs = directives.replaceAll(block)
        val ops = operationTypes.replaceAll(block)
        if (dirs === directives && ops === operationTypes) this else copy(directives = dirs, operationTypes = ops)
    }
    is OperationTypeDefinition -> (block(type) as NamedType).let { if (it === type) this else copy(type = it) }

    is ObjectTypeDefinition -> {
        val ifaces = interfaces.replaceAll(block)
        val dirs = directives.replaceAll(block)
        val fields = fields.replaceAll(block)
        if (ifaces === interfaces && dirs === directives && fields === this.fields) this
        else copy(interfaces = ifaces, directives = dirs, fields = fields)
    }
    is InterfaceTypeDefinition -> {
        val ifaces = interfaces.replaceAll(block)
        val dirs = directives.replaceAll(block)
        val fields = fields.replaceAll(block)
        if (ifaces === interfaces && dirs === directives && fields === this.fields) this
        else copy(interfaces = ifaces, directives = dirs, fields = fields)
    }
    is UnionTypeDefinition -> {
        val dirs = directives.replaceAll(block)
        val members = types.replaceAll(block)
        if (dirs === directives && members === types) this else copy(directives = dirs, types = members)
    }
    is EnumTypeDefinition -> {
        val dirs = directives.replaceAll(block)
        val values = values.replaceAll(block)
        if (dirs === directives && values === this.values) this else copy(directives = dirs, values = values)
    }
    is InputObjectTypeDefinition -> {
        val dirs = directives.replaceAll(block)
        val fields = fields.replaceAll(block)
        if (dirs === directives && fields === this.fields) this else copy(directives = dirs, fields = fields)
    }
    is DirectiveDefinition -> arguments.replaceAll(block).let { if (it === arguments) this else copy(arguments = it) }

    is FieldDefinition -> {
        val args = arguments.replaceAll(block)
        val type = block(this.type) as Type
        val dirs = directives.replaceAll(block)
        if (args === arguments && type === this.type && dirs === directives) this
        else copy(arguments = args, type = type, directives = dirs)
    }
    is InputValueDefinition -> {
        val type = block(this.type) as Type
        val default = defaultValue?.let { block(it) as Value }
        val dirs = directives.replaceAll(block)
        if (type === this.type && default === defaultValue && dirs === directives) this
        else copy(type = type, defaultValue = default, directives = dirs)
    }

    is SchemaExtension -> {
        val dirs = directives.replaceAll(block)
        val ops = operationTypes.replaceAll(block)
        if (dirs === directives && ops === operationTypes) this else copy(directives = dirs, operationTypes = ops)
    }
    is ObjectTypeExtension -> {
        val ifaces = interfaces.replaceAll(block)
        val dirs = directives.replaceAll(block)
        val fields = fields.replaceAll(block)
        if (ifaces === interfaces && dirs === directives && fields === this.fields) this
        else copy(interfaces = ifaces, directives = dirs, fields = fields)
    }
    is InterfaceTypeExtension -> {
        val ifaces = interfaces.replaceAll(block)
        val dirs = directives.replaceAll(block)
        val fields = fields.replaceAll(block)
        if (ifaces === interfaces && dirs === directives && fields === this.fields) this
        else copy(interfaces = ifaces, directives = dirs, fields = fields)
    }
    is UnionTypeExtension -> {
        val dirs = directives.replaceAll(block)
        val members = types.replaceAll(block)
        if (dirs === directives && members === types) this else copy(directives = dirs, types = members)
    }
    is EnumTypeExtension -> {
        val dirs = directives.replaceAll(block)
        val values = values.replaceAll(block)
        if (dirs === directives && values === this.values) this else copy(directives = dirs, values = values)
    }
    is InputObjectTypeExtension -> {
        val dirs = directives.replaceAll(block)
        val fields = fields.replaceAll(block)
        if (dirs === directives && fields === this.fields) this else copy(directives = dirs, fields = fields)
    }
}
