package bosca.bible

import kotlinx.serialization.Serializable

@Serializable
data class Reference(val usfm: String) {

    val bookUsfm: String by lazy {
        this.usfm.split('.')[0]
    }

    val chapterUsfm: String by lazy {
        val parts = this.usfm.split('.')
        if (parts.size == 1) ""
        else parts[0] + '.' + parts[1]
    }

    val chapter: String by lazy {
        val parts = usfm.split(".")
        if (parts.size == 1) ""
        else parts[1]
    }

    val number: String by lazy {
        val parts = usfm.split(".")
        if (parts.size != 3) "" else parts[2]
    }

    val references: List<Reference> by lazy {
        usfm.split('+').map { Reference(it) }
    }

    operator fun plus(other: Reference): Reference {
        return Reference(usfm + '+' + other.usfm)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reference) return false

        return usfm == other.usfm
    }

    override fun hashCode() = usfm.hashCode()
    override fun toString() = usfm

    fun toHuman(book: IBook): String = toHuman(book.name.long.ifEmpty {
        book.name.short
    })

    fun toHuman(bookName: String): String = buildString {
        append(bookName)
        if (chapter.isNotEmpty()) {
            append(" ")
            append(chapter)
            val verses = mutableListOf<Int>()
            for (reference in references) {
                val verse = reference.number
                if (verse.isNotEmpty()) {
                    val parsedVerse = verse.toIntOrNull()
                    if (parsedVerse != null) {
                        verses.add(parsedVerse)
                    }
                }
            }
            if (verses.isNotEmpty()) {
                verses.sort()
                append(":")
                if (verses.size > 1) {
                    append(verses.first().toString())
                    append("-")
                    append(verses.last().toString())
                } else {
                    append(verses.first().toString())
                }
            }
        }
    }

    companion object {

        fun parse(bible: IBible, human: String): List<Reference> {
            val parts = human.split(',')
            val references = mutableListOf<Reference>()
            for (part in parts) {
                val reference = parseSingle(bible, part.trim())
                if (reference != null) {
                    references.add(reference)
                }
            }
            // Verses of the same book and chapter join into one reference; anything else stays apart.
            val joinedReferences = mutableMapOf<String, String>()
            for (reference in references) {
                val key = reference.chapterUsfm.ifEmpty { reference.usfm }
                val usfms = joinedReferences[key]
                joinedReferences[key] = if (usfms == null) reference.usfm else usfms + '+' + reference.usfm
            }
            val finalReferences = mutableListOf<Reference>()
            for (usfm in joinedReferences.values) {
                finalReferences.add(Reference(usfm))
            }
            return finalReferences
        }

        /**
         * The book a reference starts with: the longest of every book's names that begins the text and
         * ends where a word ends, so "Judges 5" isn't taken for Jude's "Jud" and "Psst" isn't "Ps". A
         * plural name ("Psalms") also matches as a singular ("Psalm 23"). Ties go to the earlier book.
         *
         * @param humanLower The reference, lowercased.
         * @param books Each book with its names.
         * @return The book and how many characters of the text its name took, or null when none matches.
         */
        fun <T> matchBook(humanLower: String, books: List<Pair<T, List<String>>>): Pair<T, Int>? {
            var best: Pair<T, Int>? = null
            for ((book, names) in books) {
                for (name in names) {
                    val lower = name.lowercase().trim()
                    if (lower.isEmpty()) continue
                    val forms = if (lower.length > 3 && lower.endsWith('s')) listOf(lower, lower.dropLast(1)) else listOf(lower)
                    for (form in forms) {
                        if (!humanLower.startsWith(form)) continue
                        val endsAtWord = humanLower.length == form.length || !humanLower[form.length].isLetter()
                        if (endsAtWord && form.length > (best?.second ?: 0)) {
                            best = book to form.length
                        }
                    }
                }
            }
            return best
        }

        private fun parseSingle(bible: IBible, human: String): Reference? {
            val humanLower = human.lowercase()
            val (book, nameLength) = matchBook(humanLower, bible.books.map { it to listOf(it.name.long, it.name.short, it.name.abbreviation) })
                ?: return null
            val nonBook = humanLower.substring(nameLength).trim()
            if (nonBook.isEmpty()) {
                return Reference(book.reference.usfm)
            }
            val numberParts = nonBook.split(':').toMutableList()
            val chapter = book.chapters.find { it.reference.chapter.lowercase() == numberParts[0].trim().lowercase() }
            if (chapter == null) {
                return book.reference
            }
            if (numberParts.size == 1) {
                return chapter.reference
            }
            if (numberParts[1].contains('–')) {
                numberParts[1] = numberParts[1].replace('–', '-')
            }
            if (numberParts[1].contains('-')) {
                val rangeParts = numberParts[1].split('-')
                if (rangeParts.size == 2) {
                    val start = rangeParts[0].toInt()
                    val end = rangeParts[1].toInt()
                    val usfms = mutableListOf<String>()
                    var i = start
                    while (i <= end) {
                        usfms.add("${chapter.reference.usfm}.$i")
                        i++
                    }
                    return Reference(usfms.joinToString("+"))
                } else {
                    numberParts[1] = rangeParts[0]
                }
            }
            return Reference("${chapter.reference.usfm}.${numberParts[1]}")
        }
    }
}