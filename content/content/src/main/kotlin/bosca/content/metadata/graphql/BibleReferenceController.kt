package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleReference
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class BibleReferenceController : GraphQLController<BibleReference> {

    @Field
    fun usfm(reference: BibleReference) = reference.usfm

    @Field
    fun human(reference: BibleReference) = reference.human

    @Field
    fun humanShort(reference: BibleReference) = reference.humanShort
}