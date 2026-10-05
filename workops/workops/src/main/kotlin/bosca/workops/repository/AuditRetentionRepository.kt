package bosca.workops.repository

import bosca.db.connection
import bosca.serialization.UUID
import bosca.service.Service
import bosca.service.annotation.ServiceImplementation

/**
 * R17 retention is implemented through raw connection calls
 * because ALTER TABLE doesn't accept parameter binding through
 * Bosca's `@Repository` / `@Query` mapper.
 */
interface AuditRetentionRepository : Service {
    suspend fun listPartitions(): List<String>
    suspend fun detach(partition: String)
}

@ServiceImplementation
class AuditRetentionRepositoryImpl : AuditRetentionRepository {

    override suspend fun listPartitions(): List<String> {
        val out = mutableListOf<String>()
        connection().useStatement(
            """
            select table_name from information_schema.tables
            where table_schema = 'workops'
              and table_name like 'task_history\_%' escape '\'
            order by table_name
            """.trimIndent()
        ) { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) out.add(rs.getString(1))
        }
        return out
    }

    override suspend fun detach(partition: String) {
        // Validate the partition name to keep this safe from
        // injection — only `task_history_YYYYMM` is acceptable.
        require(partition.matches(Regex("^task_history_\\d{6}$"))) {
            "invalid partition name: $partition"
        }
        connection().useStatement(
            "alter table workops.task_history detach partition workops.$partition"
        ) { it.execute() }
    }
}
