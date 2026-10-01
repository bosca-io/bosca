package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.CredentialType
import bosca.security.model.PrincipalCredential
import bosca.serialization.UUID

@Repository
interface PrincipalCredentialsRepository {

    @Query("select * from principal_credentials where id = :id")
    suspend fun getById(id: Long): PrincipalCredential?

    @Query("select * from principal_credentials where lower(attributes->>'identifier') = lower(:identifier) and type = (:type)::principal_credential_type")
    suspend fun getByIdentifier(identifier: String, type: CredentialType): List<PrincipalCredential>

    @Query("select * from principal_credentials where principal = :id and type = (:type)::principal_credential_type")
    suspend fun getByPrincipalId(id: UUID, type: CredentialType): List<PrincipalCredential>

    @Query("select * from principal_credentials where principal = :id")
    suspend fun getByPrincipalId(id: UUID): List<PrincipalCredential>

    @Query("insert into principal_credentials (principal, type, attributes, originator, last_originator) values (:principal, (:type)::principal_credential_type, :attributesJson, :originator, :lastOriginator) returning *")
    suspend fun add(credential: PrincipalCredential): PrincipalCredential

    @Query("update principal_credentials set principal = :principal, type = (:type)::principal_credential_type, attributes = :attributesJson where id = :id returning *")
    suspend fun update(credential: PrincipalCredential): PrincipalCredential

    @Query("update principal_credentials set attributes = jsonb_set(jsonb_set(attributes, '{last_used_at}', to_jsonb(:lastUsedAt::text)), '{last_used_ip}', CASE WHEN :lastUsedIp IS NULL THEN 'null'::jsonb ELSE to_jsonb(:lastUsedIp::text) END) where id = :id")
    suspend fun updateLastUsed(id: Long, lastUsedAt: String, lastUsedIp: String?)

    @Query("update principal_credentials set last_originator = :lastOriginator where id = :id")
    suspend fun updateLastOriginator(id: Long, lastOriginator: String)

    @Query("delete from principal_credentials where principal = :principalId and type = (:type)::principal_credential_type and lower(attributes->>'identifier') = lower(:identifier)")
    suspend fun delete(principalId: UUID, type: CredentialType, identifier: String)
}
