package bosca.server.graphql.controllers

import bosca.features.Features
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime

object Server

data class ServerFeatures(
    val chat: Boolean,
    val community: Boolean,
    val comments: Boolean,
    val introspection: Boolean,
    val analyticsProcessor: Boolean,
    val workops: Boolean,
    val ecommerce: Boolean,
)

@TypeController
class ServerController : GraphQLController<Server> {

    @Field
    fun now(): OffsetDateTime = OffsetDateTime.now()

    @Field
    fun features(): ServerFeatures = ServerFeatures(
        chat = Features.chat,
        community = Features.community,
        comments = Features.comments,
        introspection = Features.introspection,
        analyticsProcessor = Features.analyticsProcessor,
        workops = Features.workops,
        ecommerce = Features.ecommerce,
    )
}

@TypeController
class ServerFeaturesController : GraphQLController<ServerFeatures> {

    @Field
    fun chat(features: ServerFeatures) = features.chat

    @Field
    fun community(features: ServerFeatures) = features.community

    @Field
    fun comments(features: ServerFeatures) = features.comments

    @Field
    fun introspection(features: ServerFeatures) = features.introspection

    @Field
    fun analyticsProcessor(features: ServerFeatures) = features.analyticsProcessor

    @Field
    fun workops(features: ServerFeatures) = features.workops

    @Field
    fun ecommerce(features: ServerFeatures) = features.ecommerce
}