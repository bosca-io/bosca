package bosca.kubernetes.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL SDL fragments that compose the kubernetes API surface.
 *
 * Each `@Schema` resource resolves against `src/main/resources/graphql/`;
 * the KSP-generated `KubernetesSchemaRegistrar` reads each file,
 * concatenates the contents, and feeds the result to Bosca's central
 * `bosca.graphql.SchemaRegistry`.
 *
 * Phase 1 ships the entire kubernetes schema as one file because the
 * domain types share enough cross-references (cluster ↔ workload ↔
 * pod ↔ node) that splitting them up creates more registrar churn
 * than it removes. Later phases can split if a single file grows
 * unwieldy.
 */
@Schemas
interface SchemaRegistrar {

    /** Cluster registration + every kubernetes read/mutation/subscription field. */
    @Schema("kubernetes/kubernetes.graphqls")
    val kubernetes: String
}
