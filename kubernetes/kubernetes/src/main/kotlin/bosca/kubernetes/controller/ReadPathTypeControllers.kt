package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResource
import bosca.kubernetes.model.ConfigResourceEntry
import bosca.kubernetes.model.K8sIngress
import bosca.kubernetes.model.K8sNetworkPolicy
import bosca.kubernetes.model.K8sPvc
import bosca.kubernetes.model.K8sRole
import bosca.kubernetes.model.K8sRoleBinding
import bosca.kubernetes.model.K8sRoleSubject
import bosca.kubernetes.model.K8sCustomResource
import bosca.kubernetes.model.K8sOperator
import bosca.kubernetes.model.K8sService
import bosca.kubernetes.model.K8sServiceAccount
import bosca.kubernetes.model.K8sStorageClass
import bosca.kubernetes.model.WorkloadStatus

/**
 * Field projections for the v1 read-path types beyond the core
 * cluster/workload/pod/node/event set. Every one is a pure record
 * projection — authorization is enforced upstream in
 * [KubernetesQueriesController]. Grouping them in one file keeps the
 * `controller/` directory navigable; each class still owns its own
 * `@TypeController` so KSP wires per-field dispatchers independently.
 */

@TypeController(type = "ConfigResource")
class ConfigResourceTypeController : GraphQLController<ConfigResource> {
    @Field fun id(r: ConfigResource): String = r.id
    @Field fun kind(r: ConfigResource): ConfigKind = r.kind
    @Field fun name(r: ConfigResource): String = r.name
    @Field fun namespace(r: ConfigResource): String = r.namespace
    @Field fun keys(r: ConfigResource): List<String> = r.keys
    @Field fun size(r: ConfigResource): String = r.size
    @Field fun age(r: ConfigResource): String = r.age
    @Field fun secretType(r: ConfigResource): String? = r.secretType
    @Field fun managedBy(r: ConfigResource): String? = r.managedBy
}

@TypeController(type = "ConfigResourceEntry")
class ConfigResourceEntryTypeController : GraphQLController<ConfigResourceEntry> {
    @Field fun key(e: ConfigResourceEntry): String = e.key
    @Field fun value(e: ConfigResourceEntry): String = e.value
}

@TypeController(type = "Service")
class ServiceTypeController : GraphQLController<K8sService> {
    @Field fun id(s: K8sService): String = s.id
    @Field fun name(s: K8sService): String = s.name
    @Field fun namespace(s: K8sService): String = s.namespace
    @Field fun type(s: K8sService): String = s.type
    @Field fun clusterIP(s: K8sService): String = s.clusterIP
    @Field fun externalIP(s: K8sService): String = s.externalIP
    @Field fun ports(s: K8sService): List<String> = s.ports
    @Field fun selector(s: K8sService): String = s.selector
    @Field fun age(s: K8sService): String = s.age
    @Field fun endpoints(s: K8sService): Int = s.endpoints
}

@TypeController(type = "Ingress")
class IngressTypeController : GraphQLController<K8sIngress> {
    @Field fun id(i: K8sIngress): String = i.id
    @Field fun name(i: K8sIngress): String = i.name
    @Field fun namespace(i: K8sIngress): String = i.namespace
    @Field fun ingressClass(i: K8sIngress): String = i.ingressClass
    @Field fun hosts(i: K8sIngress): List<String> = i.hosts
    @Field fun paths(i: K8sIngress): List<String> = i.paths
    @Field fun backends(i: K8sIngress): List<String> = i.backends
    @Field fun tls(i: K8sIngress): Boolean = i.tls
    @Field fun age(i: K8sIngress): String = i.age
}

@TypeController(type = "NetworkPolicy")
class NetworkPolicyTypeController : GraphQLController<K8sNetworkPolicy> {
    @Field fun id(n: K8sNetworkPolicy): String = n.id
    @Field fun name(n: K8sNetworkPolicy): String = n.name
    @Field fun namespace(n: K8sNetworkPolicy): String = n.namespace
    @Field fun podSelector(n: K8sNetworkPolicy): String = n.podSelector
    @Field fun ingress(n: K8sNetworkPolicy): String = n.ingress
    @Field fun egress(n: K8sNetworkPolicy): String = n.egress
    @Field fun age(n: K8sNetworkPolicy): String = n.age
}

@TypeController(type = "StorageClass")
class StorageClassTypeController : GraphQLController<K8sStorageClass> {
    @Field fun name(s: K8sStorageClass): String = s.name
    @Field fun provisioner(s: K8sStorageClass): String = s.provisioner
    @Field fun reclaim(s: K8sStorageClass): String = s.reclaim
    @Field fun binding(s: K8sStorageClass): String = s.binding
    @Field fun isDefault(s: K8sStorageClass): Boolean = s.isDefault
    @Field fun age(s: K8sStorageClass): String = s.age
    @Field fun parameters(s: K8sStorageClass): String = s.parameters
}

@TypeController(type = "PersistentVolumeClaim")
class PvcTypeController : GraphQLController<K8sPvc> {
    @Field fun id(p: K8sPvc): String = p.id
    @Field fun name(p: K8sPvc): String = p.name
    @Field fun namespace(p: K8sPvc): String = p.namespace
    @Field fun status(p: K8sPvc): String = p.status
    @Field fun volume(p: K8sPvc): String = p.volume
    @Field fun capacity(p: K8sPvc): String = p.capacity
    @Field fun accessMode(p: K8sPvc): String = p.accessMode
    @Field fun storageClass(p: K8sPvc): String = p.storageClass
    @Field fun age(p: K8sPvc): String = p.age
    @Field fun usedPercent(p: K8sPvc): Int? = p.usedPercent
    @Field fun workload(p: K8sPvc): String? = p.workload
}

@TypeController(type = "Role")
class RoleTypeController : GraphQLController<K8sRole> {
    @Field fun id(r: K8sRole): String = r.id
    @Field fun kind(r: K8sRole): String = r.kind
    @Field fun name(r: K8sRole): String = r.name
    @Field fun builtin(r: K8sRole): Boolean = r.builtin
    @Field fun bindings(r: K8sRole): Int = r.bindings
    @Field fun rules(r: K8sRole): Int = r.rules
    @Field fun age(r: K8sRole): String = r.age
    @Field fun namespace(r: K8sRole): String? = r.namespace
    @Field fun description(r: K8sRole): String = r.description
}

@TypeController(type = "RoleSubject")
class RoleSubjectTypeController : GraphQLController<K8sRoleSubject> {
    @Field fun kind(s: K8sRoleSubject): String = s.kind
    @Field fun name(s: K8sRoleSubject): String = s.name
    @Field fun namespace(s: K8sRoleSubject): String? = s.namespace
}

@TypeController(type = "RoleBinding")
class RoleBindingTypeController : GraphQLController<K8sRoleBinding> {
    @Field fun id(b: K8sRoleBinding): String = b.id
    @Field fun kind(b: K8sRoleBinding): String = b.kind
    @Field fun name(b: K8sRoleBinding): String = b.name
    @Field fun role(b: K8sRoleBinding): String = b.role
    @Field fun subjects(b: K8sRoleBinding): List<K8sRoleSubject> = b.subjects
    @Field fun namespace(b: K8sRoleBinding): String? = b.namespace
    @Field fun age(b: K8sRoleBinding): String = b.age
}

@TypeController(type = "ServiceAccount")
class ServiceAccountTypeController : GraphQLController<K8sServiceAccount> {
    @Field fun id(sa: K8sServiceAccount): String = sa.id
    @Field fun name(sa: K8sServiceAccount): String = sa.name
    @Field fun namespace(sa: K8sServiceAccount): String = sa.namespace
    @Field fun pods(sa: K8sServiceAccount): Int = sa.pods
    @Field fun secrets(sa: K8sServiceAccount): Int = sa.secrets
    @Field fun bindings(sa: K8sServiceAccount): Int = sa.bindings
    @Field fun age(sa: K8sServiceAccount): String = sa.age
    @Field fun iamRole(sa: K8sServiceAccount): String? = sa.iamRole
}

@TypeController(type = "Operator")
class OperatorTypeController : GraphQLController<K8sOperator> {
    @Field fun id(o: K8sOperator): String = o.id
    @Field fun name(o: K8sOperator): String = o.name
    @Field fun version(o: K8sOperator): String = o.version
    @Field fun group(o: K8sOperator): String = o.group
    @Field fun namespace(o: K8sOperator): String = o.namespace
    @Field fun status(o: K8sOperator): WorkloadStatus = o.status
    @Field fun instances(o: K8sOperator): Int = o.instances
    @Field fun kinds(o: K8sOperator): List<String> = o.kinds
    @Field fun description(o: K8sOperator): String = o.description
}

@TypeController(type = "CustomResource")
class CustomResourceTypeController : GraphQLController<K8sCustomResource> {
    @Field fun id(r: K8sCustomResource): String = r.id
    @Field fun kind(r: K8sCustomResource): String = r.kind
    @Field fun group(r: K8sCustomResource): String = r.group
    @Field fun version(r: K8sCustomResource): String = r.version
    @Field fun namespace(r: K8sCustomResource): String = r.namespace
    @Field fun name(r: K8sCustomResource): String = r.name
    @Field fun age(r: K8sCustomResource): String = r.age
    @Field fun status(r: K8sCustomResource): WorkloadStatus = r.status
    @Field fun detail(r: K8sCustomResource): String = r.detail
}
