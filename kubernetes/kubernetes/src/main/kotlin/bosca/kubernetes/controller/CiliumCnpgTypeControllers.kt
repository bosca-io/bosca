package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.K8sCnpgBackup
import bosca.kubernetes.model.K8sCnpgCluster
import bosca.kubernetes.model.K8sCnpgClusterDetail
import bosca.kubernetes.model.K8sCnpgInstance
import bosca.kubernetes.model.K8sCnpgParameter
import bosca.kubernetes.model.WorkloadStatus

/**
 * Field projections for the CNPG wire shape. Pure record accessors —
 * authorization happens upstream at [KubernetesQueriesController].
 *
 * (Filename retained as `CiliumCnpgTypeControllers` for git
 * blame continuity even though the Cilium controllers are gone; a
 * rename can come in its own commit.)
 */

@TypeController(type = "CnpgCluster")
class CnpgClusterTypeController : GraphQLController<K8sCnpgCluster> {
    @Field fun name(c: K8sCnpgCluster): String = c.name
    @Field fun namespace(c: K8sCnpgCluster): String = c.namespace
    @Field fun instances(c: K8sCnpgCluster): Int = c.instances
    @Field fun primary(c: K8sCnpgCluster): String = c.primary
    @Field fun postgresVersion(c: K8sCnpgCluster): String = c.postgresVersion
    @Field fun image(c: K8sCnpgCluster): String = c.image
    @Field fun status(c: K8sCnpgCluster): String = c.status
    @Field fun statusKind(c: K8sCnpgCluster): WorkloadStatus = c.statusKind
    @Field fun age(c: K8sCnpgCluster): String = c.age
    @Field fun backupSchedule(c: K8sCnpgCluster): String = c.backupSchedule
    @Field fun lastBackup(c: K8sCnpgCluster): String = c.lastBackup
}

@TypeController(type = "CnpgInstance")
class CnpgInstanceTypeController : GraphQLController<K8sCnpgInstance> {
    @Field fun name(i: K8sCnpgInstance): String = i.name
    @Field fun role(i: K8sCnpgInstance): String = i.role
    @Field fun status(i: K8sCnpgInstance): String = i.status
    @Field fun ready(i: K8sCnpgInstance): Boolean = i.ready
    @Field fun node(i: K8sCnpgInstance): String = i.node
    @Field fun zone(i: K8sCnpgInstance): String = i.zone
    @Field fun pvcSize(i: K8sCnpgInstance): String = i.pvcSize
    @Field fun restarts(i: K8sCnpgInstance): Int = i.restarts
    @Field fun age(i: K8sCnpgInstance): String = i.age
}

@TypeController(type = "CnpgBackup")
class CnpgBackupTypeController : GraphQLController<K8sCnpgBackup> {
    @Field fun name(b: K8sCnpgBackup): String = b.name
    @Field fun method(b: K8sCnpgBackup): String = b.method
    @Field fun phase(b: K8sCnpgBackup): String = b.phase
    @Field fun statusKind(b: K8sCnpgBackup): WorkloadStatus = b.statusKind
    @Field fun started(b: K8sCnpgBackup): String = b.started
    @Field fun completed(b: K8sCnpgBackup): String = b.completed
    @Field fun duration(b: K8sCnpgBackup): String = b.duration
}

@TypeController(type = "CnpgParameter")
class CnpgParameterTypeController : GraphQLController<K8sCnpgParameter> {
    @Field fun key(p: K8sCnpgParameter): String = p.key
    @Field fun value(p: K8sCnpgParameter): String = p.value
}

@TypeController(type = "CnpgClusterDetail")
class CnpgClusterDetailTypeController : GraphQLController<K8sCnpgClusterDetail> {
    @Field fun cluster(d: K8sCnpgClusterDetail): K8sCnpgCluster = d.cluster
    @Field fun storageSize(d: K8sCnpgClusterDetail): String = d.storageSize
    @Field fun storageClass(d: K8sCnpgClusterDetail): String = d.storageClass
    @Field fun backupDestinationPath(d: K8sCnpgClusterDetail): String = d.backupDestinationPath
    @Field fun backupRetention(d: K8sCnpgClusterDetail): String = d.backupRetention
    @Field fun instances(d: K8sCnpgClusterDetail): List<K8sCnpgInstance> = d.instances
    @Field fun backups(d: K8sCnpgClusterDetail): List<K8sCnpgBackup> = d.backups
    @Field fun parameters(d: K8sCnpgClusterDetail): List<K8sCnpgParameter> = d.parameters
}
