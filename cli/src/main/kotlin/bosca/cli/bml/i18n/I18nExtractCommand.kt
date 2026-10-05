package bosca.cli.bml.i18n

/**
 * Prints the compile-time key inventory — what `push` would feed the localization project.
 * Offline: reads only the manifest (build the project first so it is current).
 */
class I18nExtractCommand : I18nManifestCommand(
    name = "extract",
    helpText = "List the localization keys the compiled BML project declares (from the i18n manifest)",
) {
    override fun run() {
        val entries = loadManifest().sortedBy { it.key }
        if (entries.isEmpty()) {
            echo("No localization keys in ${manifestFile.path}.")
            return
        }
        for (entry in entries) {
            val shape = when {
                entry.plural -> "plural[${entry.forms.keys.sorted().joinToString(",")}]"
                entry.message != null -> "text"
                else -> "key-only"
            }
            val placeholders = entry.placeholders
                .takeIf { it.isNotEmpty() }
                ?.joinToString(",", prefix = " {", postfix = "}") { it.name }
                .orEmpty()
            echo("${entry.key}  [${entry.origin.lowercase()}/$shape]$placeholders  ${entry.file}:${entry.line}")
        }
        val byOrigin = entries.groupingBy { it.origin.lowercase() }.eachCount()
        echo()
        echo(
            "${entries.size} key(s): " +
                byOrigin.entries.joinToString(", ") { "${it.value} ${it.key}" } +
                ". Dynamic t(<expr>) keys are not extractable and are not listed.",
        )
    }
}
