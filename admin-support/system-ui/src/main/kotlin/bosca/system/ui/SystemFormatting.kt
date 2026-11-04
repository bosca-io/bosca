package bosca.system.ui

import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

fun systemDecimal(value: Double?, digits: Int = 1): String = value?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
fun systemCount(value: Number?): String = value?.let { String.format(Locale.US, "%,d", it.toLong()) } ?: "—"
fun systemBytes(value: Long?): String {
    if (value == null) return "—"
    if (value < 1024) return "$value B"
    var size = value.toDouble(); var index = 0
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    while (size >= 1024 && index < units.lastIndex) { size /= 1024; index++ }
    return "${systemDecimal(size)} ${units[index]}"
}

fun systemDurationMillis(value: Long): String = value.milliseconds.toString()

fun systemDateParts(value: String?): Pair<String, String> {
    if (value.isNullOrBlank()) return "—" to ""
    return try {
        val date = java.time.OffsetDateTime.parse(value)
        val pattern = if (date.year == java.time.LocalDate.now().year) "MMM d" else "MMM d, yyyy"
        date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.US)) to date.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    } catch (_: java.time.format.DateTimeParseException) { value to "" }
}
