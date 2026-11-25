package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitDelegationArtifact
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitDelegationArtifactController : GraphQLController<KitDelegationArtifact> {
    @Field fun metadataId(value: KitDelegationArtifact) = value.metadataId
    @Field fun label(value: KitDelegationArtifact) = value.label
}
