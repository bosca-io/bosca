package bosca.messages.pages.localization

/**
 * Minimal RFC-4180 CSV reader. Fields are comma-separated and may be wrapped in double quotes; a
 * quoted field can contain commas, line breaks, and escaped quotes (`""`). Bare `\r` outside quotes
 * is treated as part of a `\r\n` line terminator and dropped. Returns one list of fields per record.
 *
 * Intentionally tiny — the repo ships no CSV library and the only input is the bundled seed file.
 */
object CsvReader {

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var recordHasContent = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> { inQuotes = true; recordHasContent = true }
                c == ',' -> { row.add(field.toString()); field.clear(); recordHasContent = true }
                c == '\r' -> { /* swallow; the paired \n terminates the record */ }
                c == '\n' -> {
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                    recordHasContent = false
                }
                else -> { field.append(c); recordHasContent = true }
            }
            i++
        }
        if (recordHasContent || field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
