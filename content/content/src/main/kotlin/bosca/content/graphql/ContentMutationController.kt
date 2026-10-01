package bosca.content.graphql

import bosca.category.graphql.CategoryMutation
import bosca.content.collection.graphql.CollectionMutation
import bosca.content.healthcheck.ContentHealthCheckMutation
import bosca.content.metadata.graphql.MetadataMutation
import bosca.content.state.graphql.WorkflowStatesMutation
import bosca.content.tools.graphql.TemplateAttributeToolsMutation
import bosca.content.transition.graphql.TransitionsMutation
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.source.graphql.SourceMutation

object ContentMutation

@TypeController
class ContentMutationController : GraphQLController<ContentMutation> {

    @Field
    fun collection() = CollectionMutation

    @Field
    fun metadata() = MetadataMutation

    @Field
    fun rebuildStorageSystemContent() = false

    @Field
    fun resizeImage() = false

    @Field
    fun sources() = SourceMutation

    @Field
    fun states() = WorkflowStatesMutation

    @Field
    fun category() = CategoryMutation

    @Field
    fun healthCheck() = ContentHealthCheckMutation

    @Field
    fun transitions() = TransitionsMutation

    @Field
    fun templateAttributeTools() = TemplateAttributeToolsMutation
}
