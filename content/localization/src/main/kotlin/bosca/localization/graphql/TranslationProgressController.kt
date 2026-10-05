package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.TranslationProgress

/** Resolves field-level data on [TranslationProgress]. */
@TypeController
class TranslationProgressController : GraphQLController<TranslationProgress> {

    @Field
    fun totalStrings(progress: TranslationProgress): Int = progress.totalStrings

    @Field
    fun translatedStrings(progress: TranslationProgress): Int = progress.translatedStrings

    @Field
    fun approvedStrings(progress: TranslationProgress): Int = progress.approvedStrings

    @Field
    fun publishedStrings(progress: TranslationProgress): Int = progress.publishedStrings

    @Field
    fun aiGeneratedStrings(progress: TranslationProgress): Int = progress.aiGeneratedStrings

    @Field
    fun humanTranslatedStrings(progress: TranslationProgress): Int = progress.humanTranslatedStrings

    @Field
    fun percentage(progress: TranslationProgress): Double = progress.percentage
}
