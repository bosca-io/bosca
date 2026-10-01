package bosca.profile.attribute.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ProfileAttributeRepository {

    @Query("select * from profile_attributes where profile = :profile order by priority desc, created desc")
    suspend fun getByProfile(profile: UUID): List<ProfileAttribute>

    @Query("select * from profile_attributes where profile = any(:profile) order by priority desc, created desc")
    suspend fun getByProfiles(profile: List<UUID>): List<ProfileAttribute>

    @Query("select * from profile_attributes where id = :id")
    suspend fun getById(id: UUID): ProfileAttribute?

    @Query("select * from profile_attributes where type_id = :typeId order by priority desc, created desc")
    suspend fun getByTypeId(typeId: String): List<ProfileAttribute>

    /** Sets the cached Personalization Signals; does not touch the attribute's value or `verified`. */
    @Query("update profile_attributes set signals = :signals where id = :id")
    suspend fun setSignals(id: UUID, signals: JsonElement?)

    @Query("select * from profile_attributes where verification_token = :token")
    suspend fun getByVerificationToken(token: String): ProfileAttribute?

    @Query("delete from profile_attributes where id = :id returning *")
    suspend fun deleteById(id: UUID): ProfileAttribute?

    @Query("insert into profile_attributes (profile, type_id, attributes, visibility, confidence, priority, source, metadata_id, expiration) values (:profile, :typeId, :attributes, :visibility, :confidence, :priority, :source, :metadataId, :expires) returning *")
    suspend fun add(attribute: ProfileAttribute): ProfileAttribute

    @Query("update profile_attributes set attributes = :attributes, visibility = :visibility, confidence = :confidence, priority = :priority, source = :source, metadata_id = :metadataId, expiration = :expires where id = :id returning *")
    suspend fun edit(attribute: ProfileAttribute): ProfileAttribute

    // ── Generic attribute-verification primitive ──────────────────────────────────────────────────────
    // The verified/verification_token/verification_source columns are not email-specific; these primitives
    // verify ANY attribute type, identifying the value by its JSON [key] (e.g. typeId "bosca.profiles.email"
    // + key "email", or a future "bosca.profiles.phone" + "phone"). The email IDENTITY layer
    // (getPrincipalByEmail / principal_emails) is the first consumer and stays in the security module.

    /**
     * Resolves attributes by a **proven** value: only `verified = true` rows of [typeId] whose JSON [key]
     * equals [value] (case-insensitive) match. This is the identity-resolution primitive behind lookups like
     * `getPrincipalByEmail`, so it must ignore client-set squat attributes (these types are unprotected —
     * anyone can attach a victim's value to their own profile). Control is proven only via the verification
     * link or a provider assertion, both of which set `verified` server-side.
     */
    @Query("select * from profile_attributes where type_id = :typeId and verified = true and lower(trim(attributes ->> :key)) = lower(trim(:value))")
    suspend fun getVerifiedByValue(typeId: String, key: String, value: String): List<ProfileAttribute>

    /**
     * Marks the [profile]'s [typeId] attribute whose JSON [key] equals [value] (case-insensitive) as
     * verified, recording [source] and clearing any pending token. With [verifyByToken] this is the ONLY
     * write path that sets `verified = true`; it is never reachable from GraphQL input (which flows through
     * [add]/[edit], neither of which touches the column), so a client cannot self-assert verification.
     * Scoped to a single profile (KSP `@Query` cannot mix a collection param with the other primitives).
     */
    @Query("update profile_attributes set verified = true, verification_source = :source, verification_token = null, verification_origin = null where type_id = :typeId and profile = :profile and lower(trim(attributes ->> :key)) = lower(trim(:value)) returning *")
    suspend fun markVerified(typeId: String, profile: UUID, key: String, value: String, source: String): List<ProfileAttribute>

    /**
     * Stamps a pending one-time [token] onto the [profile]'s (still-unverified) [typeId] attribute whose
     * JSON [key] equals [value]. The token is the proof carried by the verification challenge; [verifyByToken]
     * later redeems it to mark exactly this attribute verified — pinning control to a specific value rather
     * than inferring it. Skips already-verified rows. Returns the stamped rows so a caller can confirm a
     * target existed.
     */
    @Query("update profile_attributes set verification_token = :token, verification_origin = :origin where type_id = :typeId and profile = :profile and verified = false and lower(trim(attributes ->> :key)) = lower(trim(:value)) returning *")
    suspend fun setVerificationToken(typeId: String, profile: UUID, key: String, value: String, token: String, origin: String?): List<ProfileAttribute>

    /**
     * Redeems a verification [token]: marks every attribute (of ANY type) carrying it verified, records
     * [source], and clears the token (single-use). Resolving the attribute BY its (globally-unique) token is
     * what makes the mark exact — no type, value, or profile inference about which value was proven. Returns
     * the rows it verified (empty if the token matched nothing — e.g. already redeemed).
     */
    @Query("update profile_attributes set verified = true, verification_source = :source, verification_token = null, verification_origin = null where verification_token = :token returning *")
    suspend fun verifyByToken(token: String, source: String): List<ProfileAttribute>

    /**
     * Drops all verification state (`verified`, `verification_source`, `verification_token`) from an
     * attribute. Used when a client edit changes a verified value: the edit path can't confer proof of
     * control, so the new value must fall back to unverified and be re-verified.
     */
    @Query("update profile_attributes set verified = false, verification_source = null, verification_token = null, verification_origin = null where id = :id returning *")
    suspend fun clearVerification(id: UUID): ProfileAttribute
}
