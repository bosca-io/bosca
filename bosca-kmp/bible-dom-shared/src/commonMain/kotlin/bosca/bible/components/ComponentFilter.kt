package bosca.bible.components

import bosca.bible.Reference

private data class FilterContext(
    var foundStarts: Int
)

fun IComponent.findVerses(references: List<Reference>? = null): List<Reference> {
    val verses = mutableListOf<Reference>()
    findVerses(verses, references)
    return verses
}

private fun IComponent.findVerses(verses: MutableList<Reference>, references: List<Reference>?) {
    when (this) {
        is VerseStart -> {
            if (references != null) {
                if (references.contains(reference)) {
                    verses.add(reference)
                }
            } else {
                verses.add(reference)
            }
        }

        is VerseEnd -> {}
        is Break -> {}
        is Text -> {}
        is ComponentContainer -> {
            components.forEach { it.findVerses(verses, references) }
        }
    }
}

fun IComponent.filter(reference: Reference): IComponent? {
    val ctx = FilterContext(foundStarts = 0)
    val refs = reference.references
    return filter(refs, ctx)
}

private fun IComponent.filter(references: List<Reference>, ctx: FilterContext): IComponent? {
    return when (this) {
        is VerseStart -> {
            if (references.any { requested -> reference.references.contains(requested) }) {
                ctx.foundStarts += 1
                this
            } else {
                null
            }
        }

        is VerseEnd -> {
            if (ctx.foundStarts > 0) {
                ctx.foundStarts -= 1
                this
            } else {
                null
            }
        }

        is Break -> {
            if (ctx.foundStarts > 0) {
                this
            } else {
                null
            }
        }

        is Text -> {
            if (ctx.foundStarts > 0) {
                this
            } else {
                null
            }
        }

        is ComponentContainer -> {
            val filtered = components.mapNotNull { it.filter(references, ctx) }
            if (filtered.isEmpty()) {
                null
            } else {
                ComponentContainer(type, filtered, style)
            }
        }
    }
}
