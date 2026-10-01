@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.TranslationHistory
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Resolves field-level data on [TranslationHistory]. */
@TypeController
class TranslationHistoryController : GraphQLController<TranslationHistory> {

    @Field
    fun id(history: TranslationHistory): UUID = history.id

    @Field
    fun translationId(history: TranslationHistory): UUID = history.translationId

    @Field
    fun tableName(history: TranslationHistory): String = history.tableName

    @Field
    fun fromState(history: TranslationHistory): TranslationState? = history.fromState

    @Field
    fun toState(history: TranslationHistory): TranslationState = history.toState

    @Field
    fun changedBy(history: TranslationHistory): UUID? = history.changedBy

    @Field
    fun origin(history: TranslationHistory): TranslationOrigin = history.origin

    @Field
    fun originDetail(history: TranslationHistory): String? = history.originDetail

    @Field
    fun previousText(history: TranslationHistory): String? = history.previousText

    @Field
    fun newText(history: TranslationHistory): String = history.newText

    @Field
    fun created(history: TranslationHistory): OffsetDateTime? = history.created
}
