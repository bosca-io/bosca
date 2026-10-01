@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationProjectFormat
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Resolves field-level data on [LocalizationProjectFormat]. */
@TypeController
class LocalizationProjectFormatController : GraphQLController<LocalizationProjectFormat> {

    @Field
    fun projectId(projectFormat: LocalizationProjectFormat): UUID = projectFormat.projectId

    @Field
    fun format(projectFormat: LocalizationProjectFormat): String = projectFormat.format

    @Field
    fun created(projectFormat: LocalizationProjectFormat): OffsetDateTime? = projectFormat.created
}
