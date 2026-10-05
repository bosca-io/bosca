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
        val joinedReferences = mutableMapOf<String, String>()
        for (reference in references) {
            var usfms = joinedReferences[reference.chapter]
            if (usfms == null) {
                usfms = reference.usfm
            } else {
                usfms += '+' + reference.usfm
            }
            joinedReferences[reference.chapter] = usfms
        }
        val finalReferences = mutableListOf<Reference>()
        for (usfm in joinedReferences.values) {
            finalReferences.add(Reference(usfm))
        }
        return finalReferences
    }

    private suspend fun parseSingle(bibleService: BibleService, bible: Bible, human: String): Reference? {
        val humanLower = human.lowercase()
        var book: BibleBook? = null
        var nonBook: String? = null
        val books = bibleService.getBooks(bible)
        for (b in books) {
            if (b.nameLong != null && humanLower.indexOf(b.nameLong?.lowercase() ?: "") == 0) {
                book = b
                nonBook = humanLower.substring(b.nameLong?.length ?: 0).trim()
            } else if (b.nameShort != null && humanLower.indexOf(b.nameShort?.lowercase() ?: "") == 0) {
                book = b
                nonBook = humanLower.substring(b.nameShort?.length ?: 0).trim()
            } else if (humanLower.indexOf(b.abbreviation.lowercase()) == 0) {
                book = b
                nonBook = humanLower.substring(b.abbreviation.length).trim()
            }
        }
        if (book == null || nonBook == null) {
            return null
        }
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