<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'GraphQL',
  description: 'Call the API, then learn how schemas and controllers expose platform services.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>GraphQL</h1>
    <p class="subtitle">
      Call the API, then learn how schemas and controllers expose platform services.
    </p>

    <h2 id="calling">
      Call the GraphQL API
    </h2>
    <p>
      The local Compose endpoint is <code>http://bosca.localhost:3000/graphql</code>.
      Authenticate using the <NuxtLink to="/developers/security">login mutation</NuxtLink>,
      then send the access token as a bearer token. For example, after setting
      <code>BOSCA_TOKEN</code> to the returned token:
    </p>
    <CodeBlock
      lang="bash"
      :code="`curl -sS http://bosca.localhost:3000/graphql \\
  -H &quot;Authorization: Bearer $BOSCA_TOKEN&quot; \\
  -H 'Content-Type: application/json' \\
  --data '{&quot;query&quot;:&quot;query { content { categories { all { id name } } } }&quot;}'`"
    />
    <p>
      This category query requires editor authority. GraphQL responses can contain an
      <code>errors</code> array even when HTTP succeeds; check both <code>data</code> and
      <code>errors</code>. The following sections cover extending the Kotlin backend.
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
    <p>KSP generates schema registrars for the declared resources. The application loads its selected registrars to build the schema. A schema file must be listed and its registrar loaded to become available.</p>

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
    <p>A parameter matching the controller's model type receives the <strong>parent object</strong>. Other parameters supply arguments or request context.</p>

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
          <td>Request authentication context; its principal can be null</td>
        </tr>
        <tr>
          <td><code>AuthenticationContext?</code></td>
          <td>Nullable form of the request authentication context</td>
        </tr>
        <tr>
          <td><code>Batch&lt;K, V&gt;</code></td>
          <td>DataLoader batch context</td>
        </tr>
        <tr>
          <td><code>ResolverContext</code></td>
          <td>Bosca GraphQL resolver environment</td>
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

    <p>
      Complete every wiring layer when adding a domain: schema registrar, root namespace field,
      nested namespace fields, and controller dispatcher registrar. Keep GraphQL argument nullability
      and defaults in the SDL; a Kotlin default argument does not make an SDL argument optional.
    </p>
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
      Protected mutations explicitly check authentication and authorization through an evaluator before calling a service. An <code>AuthenticationContext</code> parameter alone does not require a logged-in principal.
    </Callout>

    <h2 id="subscriptions">
      Subscriptions
    </h2>
    <p>
      Subscription fields return <code>Flow&lt;T&gt;</code>, often backed by <code>PubSubService</code>.
      The server's <code>collection</code> subscription authenticates the caller and checks
      <code>collections:view</code> for scoped tokens before subscribing. This is an excerpt using
      the existing <code>readSubscription</code> helper:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@TypeController
class SubscriptionController(
    private val pubSubService: PubSubService,
) : GraphQLController<Subscription> {

    @Field
    fun collection(authenticationContext: AuthenticationContext): Flow<CollectionEvent> =\n        readSubscription(authenticationContext, ApiTokenScopes.COLLECTIONS_VIEW) { merge(
        pubSubService.subscribe(COLLECTION_STATE_CHANNEL, CollectionUpdated.serializer()),
        pubSubService.subscribe(COLLECTION_UPDATED_CHANNEL, CollectionUpdated.serializer())
    ).map { CollectionEvent(it.channel, it.message.id, it.message.languageTag) } }
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
