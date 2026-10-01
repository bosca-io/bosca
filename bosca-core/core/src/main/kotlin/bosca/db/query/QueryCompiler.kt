package bosca.db.query

import java.io.InputStream

enum class QueryType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UNKNOWN
}

class CompiledQuery(
    val originalQuery: String,
    val sql: String,
    val type: QueryType,
    val parameters: List<Parameter>,
)

data class Parameter(
    val offset: Int,
    val position: Int,
    val name: String
)

object QueryCompiler {

    private fun String.scan(index: Int, find: String): Boolean {
        if (index + find.length > length) {
            return false
        }
        for (i in find.indices) {
            if (this[index + i] != find[i]) {
                return false
            }
        }
        return true
    }

    private val types = QueryType.entries.map { it.name.lowercase() }

    private fun String.scanForType(): QueryType {
        types.forEach {
            if (scan(0, it) && (length == it.length || !this[it.length].isLetterOrDigit())) {
                return when (it) {
                    "select" -> QueryType.SELECT
                    "insert" -> QueryType.INSERT
                    "update" -> QueryType.UPDATE
                    "delete" -> QueryType.DELETE
                    else -> throw IllegalArgumentException()
                }
            }
        }
        return QueryType.UNKNOWN
    }

    fun compileQuery(query: String, delimiter: Char = ':', throwOnUnknown: Boolean = true): CompiledQuery {
        val type = query.lowercase().trim().scanForType()
        if (type == QueryType.UNKNOWN && throwOnUnknown) {
            throw IllegalArgumentException("Unsupported query type")
        }
        val parameters = mutableListOf<Parameter>()
        val parameterName = StringBuilder()
        var start: Int = -1
        var lastChar = 0.toChar()
        for (index in query.indices) {
            val c = query[index]
            when (c) {
                delimiter -> {
                    if (start != -1) {
                        parameters.add(Parameter(parameters.size, start, parameterName.toString()))
                        parameterName.clear()
                        start = -1
                    }
                    if (lastChar != delimiter && index + 1 != query.length && query[index + 1] != delimiter) {
                        start = index
                    }
                }

                else -> {
                    if (start != -1) {
                        if (c.isLetterOrDigit() || c == '_') {
                            parameterName.append(c)
                        } else {
                            parameters.add(Parameter(parameters.size, start, parameterName.toString()))
                            parameterName.clear()
                            start = -1
                        }
                    }
                }
            }
            lastChar = c
        }
        if (start != -1) {
            parameters.add(Parameter(parameters.size, start, parameterName.toString()))
        }
        var modifiedQuery = query
        parameters.reversed().forEachIndexed { index, it ->
            modifiedQuery = modifiedQuery.replaceRange(it.position, it.position + it.name.length + 1, "?")
        }
        return CompiledQuery(
            query,
            modifiedQuery,
            type,
            parameters,
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun compile(`in`: InputStream): List<String> =
        `in`.readAllBytes()
            .decodeToString()
            .split(";")
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
}
