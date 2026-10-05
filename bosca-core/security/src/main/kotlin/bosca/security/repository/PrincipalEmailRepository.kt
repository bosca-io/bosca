package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * The verified-email uniqueness backstop: each verified email maps to exactly one principal,
 * enforced by the `principal_emails.email` primary key. Enforcement-only — `profile_attributes` remains
 * the live source of truth for email lookups.
 */
@Repository
interface PrincipalEmailRepository {

    @Query("select principal from principal_emails where email = lower(trim(:email))")
    suspend fun getByEmail(email: String): UUID?

    /** Every email currently registered to [principal] in the backstop. Used to release rows the principal no longer proves. */
    @Query("select email from principal_emails where principal = :principal")
    suspend fun getEmailsByPrincipal(principal: UUID): List<String>

    @Query("insert into principal_emails (email, principal) values (lower(trim(:email)), :principal)")
    suspend fun add(email: String, principal: UUID)

    @Query("delete from principal_emails where principal = :principal")
    suspend fun deleteByPrincipal(principal: UUID)

    /**
     * Releases a single [email] from the backstop, scoped to [principal] so it can only ever drop the
     * caller's own registration (the email PK already guarantees one owner). Used when a principal changes
     * away from a verified email it no longer holds.
     */
    @Query("delete from principal_emails where email = lower(trim(:email)) and principal = :principal")
    suspend fun delete(email: String, principal: UUID)
}
