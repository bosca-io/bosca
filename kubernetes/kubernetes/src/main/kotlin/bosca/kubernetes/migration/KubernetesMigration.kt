package bosca.kubernetes.migration

import bosca.db.migrations.Migration

/**
 * Owns the Flyway migration scripts that build the `kubernetes`
 * Postgres schema — cluster registry and encrypted kubeconfigs in
 * Phase 1; per-cluster caches, helm release history, audit, etc. in
 * later phases.
 *
 * Per Bosca convention, `bosca.db.migrations.FlywayMigration`
 * discovers SQL files exclusively through this `resources` list —
 * dropping a new file into `src/main/resources/db/migrations/`
 * without registering it here causes the migration runner to
 * silently skip it.
 */
class KubernetesMigration : Migration {

    override val schema: String = "kubernetes"

    override val resources: List<String> = listOf(
        "V1__kubernetes_initial.sql",
        "V2__helm_repos.sql",
        "V3__kubernetes_job_executions.sql",
        "V4__helm_repo_credentials.sql",
    )
}
