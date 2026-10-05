package bosca.bible.components

import bosca.bible.style.IStyle
import bosca.bible.style.StyleRegistry
import kotlinx.serialization.Serializable

@Serializable
sealed interface IComponent {

    val style: IStyle?
}

fun IComponent.initializeStyles(registry: StyleRegistry) {
    style?.initialize(registry)
    if (this is ComponentContainer) {
        components.forEach { it.initializeStyles(registry) }
    }
}