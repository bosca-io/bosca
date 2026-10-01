package bosca.bible.style

class StyleRegistry {

    private val styles = mutableMapOf<String, IStyle>()

    fun register(styles: List<IStyle>) {
        styles.associateByTo(this.styles) { it.id }
    }

    operator fun get(id: String) = styles[id]
}