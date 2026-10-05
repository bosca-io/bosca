package bosca.gateway.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface GatewayConfigVersionRepository {

    @Query("select version from gateway.config_version where id = 1")
    suspend fun getVersion(): String

    @Query(
        """
        update gateway.config_version
        set version = gen_random_uuid()::text, updated_at = now()
        where id = 1
        returning version
        """
    )
    suspend fun bump(): String
}
