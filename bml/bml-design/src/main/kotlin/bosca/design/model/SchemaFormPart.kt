package bosca.design.model

/** A structural layout boundary or an ordinary BML-rendered Forms node. */
data class SchemaFormPart(val markup: String = "", val node: SchemaFormNode = SchemaFormNode("", "markup"))
