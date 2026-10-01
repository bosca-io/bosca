package bosca.ksp.generator.db

import java.io.InputStream

enum class QueryType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE
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

    private fun String.typeAt(index: Int): QueryType? {
        val keyword = types.firstOrNull {
            scan(index, it) &&
                (index == 0 || (!this[index - 1].isLetterOrDigit() && this[index - 1] != '_')) &&
                (length == index + it.length || (!this[index + it.length].isLetterOrDigit() && this[index + it.length] != '_'))
        } ?: return null
        return QueryType.valueOf(keyword.uppercase())
    }

    private fun String.scanForType(): QueryType {
        typeAt(0)?.let { return it }
        if (!scan(0, "with") || (length > 4 && (this[4].isLetterOrDigit() || this[4] == '_'))) {
            throw IllegalArgumentException()
        }

        var depth = 0
        var sawCteBody = false
        var inStringLiteral = false
        var inQuotedIdentifier = false
        var index = 4
        while (index < length) {
            when (this[index]) {
                '\'' -> {
                    if (!inQuotedIdentifier) {
                        if (inStringLiteral && index + 1 < length && this[index + 1] == '\'') {
                            index += 2
                            continue
                        }
                        inStringLiteral = !inStringLiteral
                    }
                }
                '"' -> if (!inStringLiteral) inQuotedIdentifier = !inQuotedIdentifier
                '(' -> if (!inStringLiteral && !inQuotedIdentifier) {
                    depth++
                    sawCteBody = true
                }
                ')' -> if (!inStringLiteral && !inQuotedIdentifier) depth--
                else -> if (sawCteBody && depth == 0 && !inStringLiteral && !inQuotedIdentifier) {
                    typeAt(index)?.let { return it }
                }
            }
            index++
        }
        throw IllegalArgumentException()
    }

    fun compileQuery(query: String, delimiter: Char = ':'): CompiledQuery {
        val type = query.lowercase().trim().scanForType()
        val parameters = mutableListOf<Parameter>()
        val parameterName = StringBuilder()
        var start: Int = -1
        var lastChar = 0.toChar()
        var inStringLiteral = false
        var index = 0
        while (index < query.length) {
            val c = query[index]
            if (c == '\'') {
                if (inStringLiteral && index + 1 < query.length && query[index + 1] == '\'') {
                    index += 2
                    continue
                }
                inStringLiteral = !inStringLiteral
                lastChar = c
                index++
                continue
            }
            if (inStringLiteral) {
                lastChar = c
                index++
                continue
            }
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
            index++
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
