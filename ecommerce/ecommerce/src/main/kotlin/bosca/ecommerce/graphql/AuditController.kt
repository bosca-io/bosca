package bosca.ecommerce.graphql

import bosca.ecommerce.model.Audit
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Field wiring for the `EcomAudit` GraphQL type (the audit-log read view). The type name is set
 * explicitly because the Kotlin model is `Audit` but the SDL type is namespaced `EcomAudit`. The
 * read itself is admin-gated at `Ecom.audit`; the `before`/`after`/`details` jsonb snapshots pass
 * through as the JSON scalar.
 */
@TypeController(type = "EcomAudit")
class AuditController : GraphQLController<Audit> {

    @Field
    fun id(source: Audit): UUID = source.id

    @Field
    fun entityType(source: Audit): String = source.entityType

    @Field
    fun entityId(source: Audit): UUID = source.entityId

    @Field
    fun action(source: Audit): String = source.action

    @Field
    fun principalId(source: Audit): UUID? = source.principalId

    @Field
    fun profileId(source: Audit): UUID? = source.profileId

    @Field
    fun storeId(source: Audit): UUID? = source.storeId

    @Field
    fun before(source: Audit): JsonElement? = source.before

    @Field
    fun after(source: Audit): JsonElement? = source.after

    @Field
    fun details(source: Audit): JsonElement? = source.details

    @Field
    fun created(source: Audit): OffsetDateTime = source.created
}
