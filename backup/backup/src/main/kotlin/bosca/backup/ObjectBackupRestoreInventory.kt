package bosca.backup

/** Selects one revision per logical target key and validates its portable destination path. */
internal fun ObjectBackupManifest.restoreObjects(): List<BackupObject> {
    val keys = mutableSetOf<Pair<String, String>>()
    return objects.map { entry ->
        val target = entry.copy(payloadPath = entry.key)
        target.validate()
        require(keys.add(entry.store to entry.key)) {
            "Multiple revisions target the same object: ${entry.store}/${entry.key}"
        }
        target
    }
}
