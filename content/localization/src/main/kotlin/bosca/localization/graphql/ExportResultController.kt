package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.ExportResult

/** Resolves field-level data on [ExportResult]. */
@TypeController
class ExportResultController : GraphQLController<ExportResult> {

    @Field
    fun content(exportResult: ExportResult): String = exportResult.content

    @Field
    fun contentType(exportResult: ExportResult): String = exportResult.contentType

    @Field
    fun fileName(exportResult: ExportResult): String = exportResult.fileName
}
