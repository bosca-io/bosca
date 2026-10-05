package bosca.content.attributes.model

import kotlinx.serialization.Serializable

@Serializable
data class AttributesFilterInput(
    val attributes: List<String>? = emptyList(),
    val childAttributes: AttributesFilterInput? = null
) {

    fun filter(attributes: Any): Any =
        when (attributes) {
            is Map<*, *> -> {
                val filteredAttributes = mutableMapOf<String, Any>()
                for (attribute in this.attributes ?: emptyList()) {
                    attributes[attribute]?.let {
                        if (childAttributes != null) {
                            filteredAttributes[attribute] = childAttributes.filter(it)
                        } else {
                            filteredAttributes[attribute] = it
                        }
                    }
                }
                filteredAttributes.toMap()
            }

            is List<*> -> {
                val filtered = mutableListOf<Any>()
                for (attribute in attributes) {
                    val attr = attribute ?: continue
                    if (childAttributes != null) {
                        filtered.add(childAttributes.filter(attr))
                    } else {
                        filtered.add(attr)
                    }
                }
                filtered.toList()
            }

            else -> attributes
        }
}