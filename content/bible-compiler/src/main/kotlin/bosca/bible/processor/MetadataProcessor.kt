package bosca.bible.processor

import bosca.bible.usx.Metadata
import bosca.bible.usx.Publication

object MetadataProcessor {

    fun process(data: ByteArray): List<Metadata> {
        @Suppress("UNCHECKED_CAST")
        val metadata = XMLProcessor.process(data)["DBLMetadata"] as Map<String, Any>

        return getPublications(metadata).map {
            Metadata(metadata, it)
        }
    }

    private fun getPublications(metadata: Map<String, Any>): List<Publication> {
        @Suppress("UNCHECKED_CAST")
        val publications = metadata["publications"] as Map<String, Any>
        val publication = publications["publication"]
        return when (publication) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                listOf(Publication(publication as Map<String, Any>))
            }

            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                publication.map { Publication(it as Map<String, Any>) }
            }

            else -> {
                error("unknown publications")
            }
        }
    }
}