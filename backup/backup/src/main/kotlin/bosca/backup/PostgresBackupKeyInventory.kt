package bosca.backup

import bosca.db.connection

/** Reads known encrypted-table dependencies through the native capture's exported database view. */
internal suspend fun postgresBackupRequiredKeys(): Map<String, List<String>> {
    val sources = mapOf(
        "security.encryption.key" to mapOf(
            "public.configuration_values" to "value",
            "public.meilisearch_nodes" to "api_key",
            "kubernetes.cluster_credential" to "data",
            "kubernetes.helm_repo_credential" to "data",
        ),
        "PIPELINE_SECRET_KEY" to mapOf(
            "pipelines.pipeline_secret" to "encrypted_value",
            "git.pipeline_secrets" to "encrypted_value",
        ),
    )
    val database = connection()
    check(database.inTransaction) { "Encryption-key inventory requires the capture transaction" }
    val required = linkedMapOf<String, List<String>>()
    for ((key, tables) in sources) {
        val populated = mutableListOf<String>()
        for ((table, column) in tables) {
            val exists = database.useStatement("select to_regclass(?) is not null") { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Encrypted-table discovery returned no result" }
                    rows.getBoolean(1)
                }
            }
            if (!exists) continue
            // Identifiers come only from the fixed list above. No ciphertext leaves the database.
            val encrypted = database.useStatement("select exists (select 1 from $table where $column is not null)") { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Encrypted-table inspection returned no result" }
                    rows.getBoolean(1)
                }
            }
            if (encrypted) populated.add(table)
        }
        if (populated.isNotEmpty()) required[key] = populated
    }
    return required
}
