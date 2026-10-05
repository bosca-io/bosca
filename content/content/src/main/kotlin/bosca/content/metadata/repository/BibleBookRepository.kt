package bosca.content.metadata.repository

import bosca.content.metadata.model.BibleBook
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface BibleBookRepository {

    @Query("insert into bible_books (metadata_id, version, variant, usfm, name_short, name_long, abbreviation, sort) values (:metadataId, :version, :variant, :usfm, :nameShort, :nameLong, :abbreviation, :sort) returning *")
    suspend fun add(book: BibleBook): BibleBook

    @Query("select * from bible_books where metadata_id = :id and version = :version and variant = :variant order by sort")
    suspend fun getByIdAndVariant(id: UUID, version: Int, variant: String): List<BibleBook>
}