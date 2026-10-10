package bosca.content.metadata.service

import bosca.bible.Reference
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook

internal object ReferenceParser {

    suspend fun parse(bibleService: BibleService, bible: Bible, human: String): List<Reference> {
        val parts = human.split(',')
        val references = mutableListOf<Reference>()
        for (part in parts) {
            val reference = parseSingle(bibleService, bible, part.trim())
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

    private suspend fun parseSingle(bibleService: BibleService, bible: Bible, human: String): Reference? {
        val humanLower = human.lowercase()
        val books = bibleService.getBooks(bible)
        val (book, nameLength) = Reference.matchBook(humanLower, books.map { it to listOfNotNull(it.nameLong, it.nameShort, it.abbreviation) })
            ?: return null
        val nonBook = humanLower.substring(nameLength).trim()
        if (nonBook.isEmpty()) {
            return Reference(book.usfm)
        }
        val numberParts = nonBook.split(':').toMutableList()
        val chapters = bibleService.getChapters(book)
        val chapter = chapters.find {
            val reference = Reference(it.usfm)
            val number = reference.chapter.lowercase()
            val otherNumber = numberParts[0].lowercase()
            number == otherNumber
        }
        if (chapter == null) {
            return Reference(book.usfm)
        }
        if (numberParts.size == 1) {
            return Reference(chapter.usfm)
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
                    usfms.add("${chapter.usfm}.$i")
                    i++
                }
                return Reference(usfms.joinToString("+"))
            } else {
                numberParts[1] = rangeParts[0]
            }
        }
        return Reference("${chapter.usfm}.${numberParts[1]}")
    }
}
