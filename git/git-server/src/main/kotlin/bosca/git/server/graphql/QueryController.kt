package bosca.git.server.graphql

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
    @Field fun git() = Git

    @Field fun github() = GitHub

    @Field fun security() = Security

    @Field fun profiles() = Profiles

    @Field fun search() = Search

    @Field fun pipelines() = Pipelines

    @Field fun scheduler() = Scheduler
}
