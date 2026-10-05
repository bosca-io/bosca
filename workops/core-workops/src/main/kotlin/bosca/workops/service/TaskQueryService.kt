package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.task.Task

/**
 * Result of a [TaskQueryService.search] call. The free-text terms
 * are returned alongside the rows so the GraphQL layer can light up
 * the Meilisearch path in Phase 23 without re-parsing the query.
 */
data class TaskSearchResult(
    val rows: List<Task>,
    val freeTextTerms: List<String>,
)

/**
 * R10's BQL search surface. The Phase 6 implementation:
 *
 *   1. Parses the BQL source.
 *   2. Validates the AST against the field + function catalogs.
 *   3. Plans the AST into a `WHERE` fragment + bound parameters.
 *   4. Resolves name-typed values (`priority = High`) against the
 *      seeded lookup tables.
 *   5. Executes the SQL directly against Postgres (the dynamic
 *      shape doesn't fit Bosca's `@Repository` / `@Query` model;
 *      the service uses the connection manager's
 *      `useStatement` directly, same as `FlywayMigration` does).
 *   6. Returns the rows together with any free-text fragments so
 *      Phase 23 can fan the same query into Meilisearch.
 */
interface TaskQueryService : Service {
    /** Validate-only — returns the parser/validator error list without running SQL. */
    suspend fun validate(bqlSource: String): List<bosca.workops.model.bql.BqlError>

    suspend fun search(
        bqlSource: String,
        actingProfileId: UUID?,
        offset: Long = 0,
        limit: Int = 50,
    ): TaskSearchResult
}
