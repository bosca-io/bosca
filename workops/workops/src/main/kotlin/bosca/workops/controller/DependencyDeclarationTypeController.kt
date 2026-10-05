package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.dependency.DependencyDeclaration

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsDependencyDeclaration")
class DependencyDeclarationTypeController : GraphQLController<DependencyDeclaration> {
    @Field fun id(d: DependencyDeclaration) = d.id
    @Field fun consumerProjectId(d: DependencyDeclaration) = d.consumerProjectId
    @Field fun consumerVersionId(d: DependencyDeclaration) = d.consumerVersionId
    @Field fun providerProjectId(d: DependencyDeclaration) = d.providerProjectId
    @Field fun providerVersionConstraint(d: DependencyDeclaration) = d.providerVersionConstraint
    @Field fun resolvedProviderVersionId(d: DependencyDeclaration) = d.resolvedProviderVersionId
    @Field fun dependencyType(d: DependencyDeclaration) = d.dependencyType
    @Field fun artifactCoordinates(d: DependencyDeclaration) = d.artifactCoordinates
    @Field fun status(d: DependencyDeclaration) = d.status
    @Field fun version(d: DependencyDeclaration) = d.version
}
