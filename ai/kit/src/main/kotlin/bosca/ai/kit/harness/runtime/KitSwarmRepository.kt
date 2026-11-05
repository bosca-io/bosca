package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitSwarmDispatch
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface KitSwarmRepository {
    /** Commits a single immutable dispatch alongside the generation's credential and admission. */
    @Query("""insert into kit.swarm_dispatch(id,session_id,profile,key,request,created)
        values (:id,:sessionId,:profile,:key,:request,:created)
        on conflict (profile,key) do update set key=excluded.key returning *""")
    suspend fun create(dispatch: KitSwarmDispatch): KitSwarmDispatch

    /** Reads the committed intent, including durable cancellation after a lost controller response. */
    @Query("select * from kit.swarm_dispatch where id=:id")
    suspend fun get(id: UUID): KitSwarmDispatch?

    /** Records observed materialization without clearing a concurrent cancellation. */
    @Query("update kit.swarm_dispatch set materialized=true where id=:id", returnUpdateCount = true)
    suspend fun materialized(id: UUID): Int

    /** Persists removal intent before contacting the manager. */
    @Query("update kit.swarm_dispatch set stopping=true where id=:id", returnUpdateCount = true)
    suspend fun stop(id: UUID): Int

    /** Drops encrypted credentials after the manager confirms removal. */
    @Query("update kit.swarm_dispatch set stopped=true, request=null where id=:id and stopping=true", returnUpdateCount = true)
    suspend fun stopped(id: UUID): Int
}
