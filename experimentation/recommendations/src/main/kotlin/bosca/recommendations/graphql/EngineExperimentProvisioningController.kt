package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.EngineExperimentProvisioning
import bosca.serialization.UUID

/** GraphQL field wiring for the [EngineExperimentProvisioning] result returned by `provisionEngineExperiment`. */
@TypeController
class EngineExperimentProvisioningController : GraphQLController<EngineExperimentProvisioning> {

    @Field
    fun experimentId(provisioning: EngineExperimentProvisioning): UUID = provisioning.experimentId

    @Field
    fun flagKey(provisioning: EngineExperimentProvisioning): String = provisioning.flagKey

    @Field
    fun created(provisioning: EngineExperimentProvisioning): Boolean = provisioning.created
}
