package bosca.scheduler.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.model.JobDefinitionInfo

@TypeController
class JobDefinitionInfoController : GraphQLController<JobDefinitionInfo> {

    @Field
    fun id(info: JobDefinitionInfo) = info.id

    @Field
    fun name(info: JobDefinitionInfo) = info.name

    @Field
    fun displayName(info: JobDefinitionInfo): String {
        if (info.displayName.isNotBlank() && info.displayName != info.name) return info.displayName
        return info.name
            .split('-')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    @Field
    fun queueName(info: JobDefinitionInfo) = info.queueName

    @Field
    fun parameterSchema(info: JobDefinitionInfo) = info.parameterSchema
}