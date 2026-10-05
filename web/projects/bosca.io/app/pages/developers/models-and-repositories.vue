<script setup lang="ts">
useSeoMeta({ title: 'Models & Repositories' })
</script>

<template>
  <div class="doc-content article">
    <h1>Models &amp; Repositories</h1>
    <p class="subtitle">
      Data classes, the <code>@Repository</code> annotation, SQL mapping, and query patterns.
    </p>

    <h2 id="models">
      Models
    </h2>
    <p>
      Models are <code>@Serializable</code> data classes living in <code>core-*</code> modules so both contracts and implementations can reference them.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class Category(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
)`"
    />
    <ul>
      <li>Use <code>@Contextual</code> on <code>UUID</code> fields for proper serialization context.</li>
      <li>Default <code>id</code> to <code>UUID.NIL</code> for new entities — the database generates the real ID on insert.</li>
      <li>Keep models in the <code>core-*</code> module, not the implementation module.</li>
    </ul>

    <h3 id="input-types">
      Input Types
    </h3>
    <p>Input types represent data coming from GraphQL mutations:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class CategoryInput(
    val name: String,
)`"
    />

    <h2 id="repositories">
      Repositories
    </h2>
    <p>
      Repositories are <strong>interfaces</strong> annotated with <code>@Repository</code>. Each method is annotated with <code>@Query</code> containing raw SQL. KSP generates the full JDBC implementation at compile time.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Repository
interface CategoryRepository {

    @Query(&quot;select * from categories&quot;)
    suspend fun getAll(): List<Category>

    @Query(&quot;select * from categories where id = any(:ids)&quot;)
    suspend fun getAll(ids: List<UUID>): List<Category>

    @Query(&quot;select * from categories where id = :id&quot;)
    suspend fun getById(id: UUID): Category?

    @Query(&quot;delete from categories where id = :id&quot;)
    suspend fun deleteById(id: UUID)

    @Query(&quot;insert into categories (name) values (:name) returning *&quot;)
    suspend fun addCategory(category: Category): Category

    @Query(&quot;update categories set name = :name where id = :id returning *&quot;)
    suspend fun editCategory(category: Category): Category
}`"
    />

    <h2 id="named-params">
      Named Parameters
    </h2>
    <p>
      SQL parameters use <code>:paramName</code> syntax. When passing a model object, parameter names match the object's properties — <code>:name</code> maps to <code>category.name</code>.
    </p>

    <h2 id="return-types">
      Return Types
    </h2>
    <table>
      <thead>
        <tr>
          <th>Return type</th>
          <th>Behavior</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>List&lt;T&gt;</code></td>
          <td>Maps all rows to a list of model objects</td>
        </tr>
        <tr>
          <td><code>T?</code></td>
          <td>Maps the first row, or returns <code>null</code> if no rows</td>
        </tr>
        <tr>
          <td><code>T</code></td>
          <td>Maps the first row, throws if no rows</td>
        </tr>
        <tr>
          <td><code>Unit</code></td>
          <td>Executes the statement without reading results</td>
        </tr>
      </tbody>
    </table>

    <h2 id="returning">
      <code>returning *</code>
    </h2>
    <p>
      The <code>returning *</code> SQL clause causes the database to return the inserted/updated row. Combined with a model return type, this gives you the entity with database-generated fields:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Query(&quot;insert into categories (name) values (:name) returning *&quot;)
suspend fun addCategory(category: Category): Category`"
    />

    <h2 id="batch">
      Batch Lookups
    </h2>
    <p>
      Use <code>any(:ids)</code> with a <code>List</code> parameter for batch lookups — a single SQL query with an array parameter:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Query(&quot;select * from categories where id = any(:ids)&quot;)
suspend fun getAll(ids: List<UUID>): List<Category>`"
    />

    <Callout type="warn">
      <code>@Query</code> functions accept a single model parameter alongside any number of
      primitive parameters. To pass data from two model objects, destructure into primitive
      parameters at the call site.
    </Callout>

    <h2 id="query-options">
      @Query Options
    </h2>
    <table>
      <thead>
        <tr>
          <th>Option</th>
          <th>Default</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>returnUpdateCount</code></td>
          <td><code>false</code></td>
          <td>Return the number of affected rows instead of mapping results</td>
        </tr>
        <tr>
          <td><code>autoCloseResult</code></td>
          <td><code>true</code></td>
          <td>Automatically close the result set after reading</td>
        </tr>
        <tr>
          <td><code>autoCloseStatement</code></td>
          <td><code>true</code></td>
          <td>Automatically close the prepared statement after execution</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
