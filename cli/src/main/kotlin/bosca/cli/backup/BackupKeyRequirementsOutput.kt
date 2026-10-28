package bosca.cli.backup

/** Formats recorded dependencies without looking up or verifying runtime keys. */
internal fun backupKeyRequirementsLines(requiredKeys: Map<String, List<String>>): List<String> = buildList {
    add("Recorded encryption key requirements:")
    if (requiredKeys.isEmpty()) {
        add("  None recorded.")
    } else {
        for ((key, tables) in requiredKeys.toSortedMap()) {
            add("  $key: ${tables.joinToString(", ")}")
        }
    }
}
