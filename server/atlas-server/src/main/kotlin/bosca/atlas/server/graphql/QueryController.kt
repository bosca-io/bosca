package bosca.atlas.server.graphql

import bosca.git.graphql.Git
import bosca.git.graphql.GitHub
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.graphql.Pipelines
import bosca.profile.profile.graphql.Profiles
import bosca.scheduler.graphql.Scheduler
import bosca.search.graphql.Search
import bosca.security.graphql.Security

@TypeController
class QueryController : GraphQLController<Query> {
    @Field fun content() = bosca.content.graphql.Content

    @Field fun collaboration() = bosca.collaboration.graphql.Collaboration

    @Field fun backups() = bosca.backup.graphql.Backups

    @Field fun ai() = bosca.ai.graphql.Ai

    @Field fun models() = bosca.ai.models.graphql.Models

    @Field fun configurations() = bosca.configuration.graphql.Configurations

    @Field fun postgresAdmin() = bosca.postgres.admin.graphql.PostgresAdmin

    @Field fun natsAdmin() = bosca.nats.admin.graphql.NatsAdmin

    @Field fun meilisearchAdmin() = bosca.meilisearch.admin.graphql.MeilisearchAdmin

    @Field fun storageSystems() = bosca.storage.graphql.StorageSystems

    @Field fun packages() = bosca.installer.graphql.Packages

    @Field fun artifactsAdmin() = bosca.artifacts.admin.graphql.ArtifactsAdmin

    @Field fun git() = Git

    @Field fun github() = GitHub

    @Field fun security() = Security

    @Field fun profiles() = Profiles

    @Field fun search() = Search

    @Field fun pipelines() = Pipelines

    @Field fun scheduler() = Scheduler
}
