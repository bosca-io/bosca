package bosca.bible.providers.model

import bosca.bible.Chapter
import bosca.bible.bibleJson
import bosca.db.Mapper
import bosca.db.mapper.JsonbMapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.reflect.KClass

/** Persists the shared chapter model using its existing component and style serialization. */
object BibleChapterMapper : Mapper<Chapter> {

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): Chapter? =
        JsonbMapper.map(bibleJson, Chapter.serializer(), result, index)

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): Chapter? =
        JsonbMapper.map(bibleJson, Chapter.serializer(), result, name)

    override fun bind(type: KClass<*>, arguments: List<KClass<*>>, stmt: PreparedStatement, index: Int, value: Chapter?): PreparedStatement =
        JsonbMapper.bind(bibleJson, Chapter.serializer(), stmt, index, value)
}
