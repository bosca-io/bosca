package bosca.content.metadata.graphql

import bosca.bible.IBibleAttribution
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleAttribution")
class BibleAttributionController : GraphQLController<IBibleAttribution> {
    @Field
    fun copyright(attribution: IBibleAttribution) = attribution.copyright

    @Field
    fun citation(attribution: IBibleAttribution) = attribution.citation

    @Field
    fun informationUrl(attribution: IBibleAttribution) = attribution.informationUrl
}
