package bosca.workops.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL SDL fragments that compose the Work Ops API surface.
 *
 * Each `@Schema` resource resolves against `src/main/resources/graphql/`;
 * the KSP-generated `WorkOpsSchemaRegistrar` reads each file, concatenates
 * the contents, and feeds the result to Bosca's central
 * `bosca.graphql.SchemaRegistry`. The file list intentionally matches the
 * areas R22 enumerates so each major feature family owns one root schema
 * file regardless of which phase populates it.
 *
 * Phase 1 ships every file as a comment-only placeholder so the registrar
 * has a stable surface to load and so later phases never have to invent
 * a new file (and therefore re-run KSP) just to add the first definition
 * for an area.
 */
@Schemas
interface SchemaRegistrar {

    /** Portfolio root queries and CRUD (R1). Populated in Phase 2. */
    @Schema("workops/portfolio.graphqls")
    val portfolio: String

    /** Program root queries and CRUD (R1, R9 milestones). Populated in Phase 2. */
    @Schema("workops/program.graphqls")
    val program: String

    /** Project root queries and CRUD (R1, R9 versions/components). Populated in Phase 2. */
    @Schema("workops/project.graphqls")
    val project: String

    /** Task lifecycle, comments, links, worklogs, attachments, content refs (R2, R6, R7, R15, R16, R21, R26). Populated in Phase 2+. */
    @Schema("workops/task.graphqls")
    val task: String

    /** Workflow definitions, schemes, and `transitionTask` (R4). Populated in Phase 3. */
    @Schema("workops/workflow.graphqls")
    val workflow: String

    /** WorkOpsBoards (kanban + scrum, project- and program-scope) (R8, R30). Populated in Phase 5+. */
    @Schema("workops/board.graphqls")
    val board: String

    /** WorkOpsSprints, sprint snapshots, velocity input (R8). Populated in Phase 5. */
    @Schema("workops/sprint.graphqls")
    val sprint: String

    /** Automation rules, triggers, conditions, actions, execution logs (R14). Populated in Phase 8. */
    @Schema("workops/automation.graphqls")
    val automation: String

    /** SLA policies, working calendars, per-task SLA goal state (R13, R31). Populated in Phase 8. */
    @Schema("workops/sla.graphqls")
    val sla: String

    /** Objectives and key results (R19). Populated in Phase 9. */
    @Schema("workops/okr.graphqls")
    val okr: String

    /** Saved filters + analytics-query data sources (R10, R32). Populated in Phase 6+. */
    @Schema("workops/dashboard.graphqls")
    val dashboard: String

    /** Audit history read surface (R17). Populated in Phase 10. */
    @Schema("workops/audit.graphqls")
    val audit: String

    /** Bulk operations and import/export (R24). Populated in Phase 10. */
    @Schema("workops/import.graphqls")
    val import: String

    /** WorkOpsVersions / releases (R9). Populated in Phase 5. */
    @Schema("workops/version.graphqls")
    val version: String

    /** WorkOpsComponents (R9). Populated in Phase 5. */
    @Schema("workops/component.graphqls")
    val component: String

    /** WorkOpsLabels (R9). Populated in Phase 5. */
    @Schema("workops/label.graphqls")
    val label: String

    /** Milestones (R9). Populated in Phase 5. */
    @Schema("workops/milestone.graphqls")
    val milestone: String

    /** Notifications, watchers, inbox, preferences (R12). Populated in Phase 7. */
    @Schema("workops/notification.graphqls")
    val notification: String

    /** Attachments (R15). Populated in Phase 8. */
    @Schema("workops/attachment.graphqls")
    val attachment: String

    /** Work logs (R16). Populated in Phase 8. */
    @Schema("workops/worklog.graphqls")
    val worklog: String

    /** Releases, shared components, cross-project (R9, R30). Populated in Phase 9+. */
    @Schema("workops/release.graphqls")
    val release: String

    /** Multi-repo dependency graph, artifact tracking, CI/CD, environments, and release gates (R24). */
    @Schema("workops/multirepo.graphqls")
    val multirepo: String

    /** Specs, requirements, and spec comments (R22). */
    @Schema("workops/spec.graphqls")
    val spec: String

    /** Aggregate namespace types for the single `workOps` root query/mutation. */
    @Schema("workops/workops.graphqls")
    val workops: String
}
