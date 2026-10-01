package bosca.bible.usx


class Attributes(attributes: Map<String, String>) {

    private val attributes: Map<String, String> = attributes.mapKeys { it.key.lowercase() }

    operator fun get(key: String): String? = attributes[key.lowercase()]

    override fun toString(): String = attributes.toString()
}