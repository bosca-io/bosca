<script setup lang="ts">
useSeoMeta({ title: 'GraphQL' })
</script>

<template>
  <div class="doc-content article">
    <h1>GraphQL</h1>
    <p class="subtitle">
      Schema files, <code>@TypeController</code>, <code>@Field</code>, the namespace pattern, queries, mutations, subscriptions, and DataLoader batching.
    </p>

    <h2 id="schema-files">
      Schema Files
    </h2>
    <p>GraphQL schemas are <code>.graphqls</code> files under <code>src/main/resources/graphql/</code>:</p>
    <CodeBlock
      lang="graphql"
      :code="`type Categories {
    &quot;List all available categories&quot;
    all: [Category!]!
}

type Category {
    id: UUID!
    name: String!
}

input CategoryInput {
    name: String!
}

type CategoryMutation {
    add(category: CategoryInput!): Category!
    edit(id: UUID!, category: CategoryInput!): Category!
    delete(id: UUID!): Boolean!
}`"
    />

    <h2 id="schema-registration">
      Schema Registration
    </h2>
    <p>Each module declares which schema files to load with a <code>@Schemas</code> interface:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@Schemas
interface SchemaRegistrar {
    @Schema(&quot;categories.graphqls&quot;)
    val categories: String

    @Schema(&quot;collections.graphqls&quot;)
    val collections: String
}`"
    />
    <p>KSP merges all <code>@Schemas</code> interfaces across all modules into a single unified schema.</p>

    <h2 id="type-controller">
      TypeController &amp; Fields
    </h2>
    <p><code>@TypeController</code> marks a class as the field resolver for a GraphQL type. The type is inferred from <code>GraphQLController&lt;T&gt;</code>. Each method is annotated with <code>@Field</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@TypeController
class CategoryController : GraphQLController<Category> {

    @Field
    fun id(category: Category) = category.id

    @Field
    fun name(category: Category) = category.name
}`"
    />
    <p>The first parameter receives the <strong>parent object</strong> being resolved.</p>

    <h3 id="auto-inject">
      Automatic Parameter Injection
    </h3>
    <table>
      <thead>
        <tr>
          <th>Parameter Type</th>
          <th>What It Provides</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>AuthenticationContext</code></td>
          <td>Authenticated user (required — fails if unauthenticated)</td>
        </tr>
        <tr>
          <td><code>AuthenticationContext?</code></td>
          <td>Optional auth — <code>null</code> for unauthenticated requests</td>
        </tr>
        <tr>
          <td><code>Batch&lt;K, V&gt;</code></td>
          <td>DataLoader batch context</td>
        </tr>
        <tr>
          <td><code>DataFetchingEnvironment</code></td>
          <td>GraphQL Java environment</td>
        </tr>
        <tr>
          <td><code>ServerCall</code></td>
          <td>Underlying HTTP request</td>
        </tr>
      </tbody>
    </table>
    <p>All other parameters map to <strong>GraphQL field arguments</strong> by name.</p>

    <h2 id="namespace-pattern">
      The Namespace Pattern
    </h2>
    <p>Top-level fields return <strong>singleton objects</strong>, which are resolved by their own <code>@TypeController</code>:</p>
    <CodeBlock
      lang="kotlin"
      title="Query controller"
      :code="`object Categories

@TypeController
class CategoriesController(
    private val service: CategoryService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Categories> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Category> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.getAll()
    }
}`"
    />
    <CodeBlock
      lang="kotlin"
      title="Wiring into the tree"
      :code="`// In ContentController
@Field
fun categories() = Categories

// In QueryController (top-level)
@Field
fun content() = Content`"
    />
    <p>This creates the query path: <code>query {{ '{' }} content {{ '{' }} categories {{ '{' }} all {{ '{' }} ... {{ '}' }} {{ '}' }} {{ '}' }} {{ '}' }}</code></p>

    <h2 id="mutations">
      Mutation Controllers
    </h2>
    <p>Mutations follow the same namespace pattern with separate mutation objects:</p>
    <CodeBlock
      lang="kotlin"
      :code="`object CategoryMutation

@TypeController
class CategoryMutationController(
    private val service: CategoryService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<CategoryMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, category: CategoryInput): Category {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.add(category)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, category: CategoryInput): Category {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.edit(id, category)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(id)
        return true
    }
}`"
    />

    <Callout type="info">
      Every mutation follows: <strong>Authenticate</strong> (non-nullable <code>AuthenticationContext</code>) → <strong>Authorize</strong> (permission check) → <strong>Execute</strong> (delegate to service) → <strong>Return</strong>.
    </Callout>

    <h2 id="subscriptions">
      Subscriptions
    </h2>
    <p>Subscription fields return <code>Flow&lt;T&gt;</code> backed by <code>PubSubService</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@TypeController
class SubscriptionController(
    private val pubSubService: PubSubService,
) : GraphQLController<Subscription> {

    @Field
    fun collection() = merge(
        pubSubService.subscribe(COLLECTION_STATE_CHANNEL, CollectionUpdated.serializer()),
        pubSubService.subscribe(COLLECTION_UPDATED_CHANNEL, CollectionUpdated.serializer())
    ).map { CollectionEvent(it.channel, it.message.id, it.message.languageTag) }
}`"
    />

    <h2 id="batching">
      Batching (DataLoader)
    </h2>
    <p>Use <code>Batch&lt;K, V&gt;</code> in field resolvers to avoid N+1 queries:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@Field
suspend fun slug(batch: Batch<CollectionCacheKeyId, String>) {
    slugService.addCollectionSlugsToBatch(batch)
}`"
    />
    <p>
      The <code>ServiceCache</code> batch resolver handles loading all keys in a single query. See
      <NuxtLink to="/developers/caching">
        Caching
      </NuxtLink> for how batch resolvers are declared.
    </p>
  </div>
</template>
