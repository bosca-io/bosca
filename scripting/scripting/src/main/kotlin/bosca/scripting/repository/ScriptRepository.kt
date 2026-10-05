package bosca.scripting.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface ScriptRepository {

    @Query("select * from scripting.scripts where deleted_at is null order by name")
    suspend fun getAll(): List<Script>

    @Query("select * from scripting.scripts order by name")
    suspend fun getAllIncludingDeleted(): List<Script>

    @Query("select * from scripting.scripts where type = :type and deleted_at is null order by name")
    suspend fun getByType(type: ScriptType): List<Script>

    @Query("select * from scripting.scripts where type = :type order by name")
    suspend fun getByTypeIncludingDeleted(type: ScriptType): List<Script>

    @Query("select * from scripting.scripts where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): Script?

    @Query("select * from scripting.scripts where id = any(:ids) and deleted_at is null")
    suspend fun getByIds(ids: List<UUID>): List<Script>

    @Query("select * from scripting.scripts where key = :key and deleted_at is null")
    suspend fun getByKey(key: String): Script?

    @Query("select key, id from scripting.scripts where deleted_at is null")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into scripting.scripts (key, name, description, type, source, public, input_schema, output_schema, configuration) values (:key, :name, :description, :type, :source, :public, :inputSchema, :outputSchema, :configuration) returning *")
    suspend fun add(script: Script): Script

    @Query("update scripting.scripts set key = :key, name = :name, description = :description, type = :type, source = :source, public = :public, version = version + 1, input_schema = :inputSchema, output_schema = :outputSchema, configuration = :configuration, modified = now() where id = :id and version = :version returning *")
    suspend fun update(script: Script): Script?

    @Query("update scripting.scripts set enabled = :enabled, modified = now() where id = :id returning *")
    suspend fun setEnabled(id: UUID, enabled: Boolean): Script

    @Query("update scripting.scripts set deleted_at = now(), enabled = false, modified = now() where id = :id returning *")
    suspend fun softDelete(id: UUID): Script?

    @Query("delete from scripting.scripts where id = :id")
    suspend fun deleteById(id: UUID)

    @Query("delete from scripting.scripts where deleted_at is not null and deleted_at < :before")
    suspend fun purgeDeletedBefore(before: OffsetDateTime)
}
