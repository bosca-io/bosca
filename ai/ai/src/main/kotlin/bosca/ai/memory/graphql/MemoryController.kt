package bosca.ai.memory.graphql

import bosca.ai.memory.Memory
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class MemoryController : GraphQLController<Memory> {
    @Field fun id(value: Memory) = value.id
    @Field fun version(value: Memory) = value.version
    @Field fun revision(value: Memory) = value.revision
    @Field fun statement(value: Memory) = value.statement
    @Field fun provenance(value: Memory) = value.provenance
    @Field fun sourceChanged(value: Memory) = value.sourceChanged
    @Field fun tags(value: Memory) = value.tags
    @Field fun created(value: Memory) = value.created
    @Field fun modified(value: Memory) = value.modified
}
