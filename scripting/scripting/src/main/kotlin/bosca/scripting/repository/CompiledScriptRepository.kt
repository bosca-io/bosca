package bosca.scripting.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import kotlinx.serialization.Serializable

/**
 * Row payload used when persisting a compiled script — mirrors the columns of
 * `scripting.compiled_scripts`. Wrapping the values in a model lets the repository
 * generator bind them as a single model parameter, which is required because the
 * KSP layer does not treat [ByteArray] as a primitive in multi-parameter queries.
 */
@Serializable
data class CompiledScriptEntry(
    val key: String,
    val version: Int,
    val fingerprint: String,
    val compiled: ByteArray,
)

/**
 * Persistent storage for serialized compiled Kotlin scripts.
 *
 * Lets script runners survive cold starts without recompiling: after a script is compiled
 * locally and held in the in-memory Caffeine cache, the serialized bytecode is also written
 * here so any runner (including a freshly started JVM) can deserialize it on first request.
 *
 * The `fingerprint` identifies the compiler configuration that produced the bytes — entries
 * written by an incompatible compiler version are simply not found and trigger a recompile.
 * Invalidation by `key`/`version` clears all fingerprints so stale entries do not accumulate.
 */
@Repository
interface CompiledScriptRepository {

    @Query("select * from scripting.compiled_scripts where key = :key and version = :version and fingerprint = :fingerprint")
    suspend fun get(key: String, version: Int, fingerprint: String): CompiledScriptEntry?

    @Query("insert into scripting.compiled_scripts (key, version, fingerprint, compiled) values (:key, :version, :fingerprint, :compiled) on conflict (key, version, fingerprint) do update set compiled = excluded.compiled, created = now()")
    suspend fun put(entry: CompiledScriptEntry)

    @Query("delete from scripting.compiled_scripts where key = :key and version = :version")
    suspend fun deleteByKeyAndVersion(key: String, version: Int)

    @Query("delete from scripting.compiled_scripts")
    suspend fun deleteAll()
}
