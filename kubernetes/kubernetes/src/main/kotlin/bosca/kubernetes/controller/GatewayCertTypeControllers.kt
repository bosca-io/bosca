package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.K8sCertificate
import bosca.kubernetes.model.K8sGateway
import bosca.kubernetes.model.K8sGatewayClass
import bosca.kubernetes.model.K8sHttpRoute
import bosca.kubernetes.model.K8sIssuer
import bosca.kubernetes.model.WorkloadStatus

/**
 * Field projections for Gateway API + cert-manager wire shapes.
 * Pure record accessors — authorization happens upstream in
 * [KubernetesQueriesController].
 */

@TypeController(type = "K8sGatewayClass")
class GatewayClassTypeController : GraphQLController<K8sGatewayClass> {
    @Field fun name(g: K8sGatewayClass): String = g.name
    @Field fun controller(g: K8sGatewayClass): String = g.controller
    @Field fun accepted(g: K8sGatewayClass): Boolean = g.accepted
    @Field fun age(g: K8sGatewayClass): String = g.age
}

@TypeController(type = "K8sGateway")
class GatewayTypeController : GraphQLController<K8sGateway> {
    @Field fun id(g: K8sGateway): String = g.id
    @Field fun name(g: K8sGateway): String = g.name
    @Field fun namespace(g: K8sGateway): String = g.namespace
    @Field fun gatewayClass(g: K8sGateway): String = g.gatewayClass
    @Field fun addresses(g: K8sGateway): List<String> = g.addresses
    @Field fun listeners(g: K8sGateway): Int = g.listeners
    @Field fun routes(g: K8sGateway): Int = g.routes
    @Field fun status(g: K8sGateway): WorkloadStatus = g.status
    @Field fun age(g: K8sGateway): String = g.age
}

@TypeController(type = "K8sHTTPRoute")
class HttpRouteTypeController : GraphQLController<K8sHttpRoute> {
    @Field fun id(r: K8sHttpRoute): String = r.id
    @Field fun name(r: K8sHttpRoute): String = r.name
    @Field fun namespace(r: K8sHttpRoute): String = r.namespace
    @Field fun parents(r: K8sHttpRoute): List<String> = r.parents
    @Field fun hosts(r: K8sHttpRoute): List<String> = r.hosts
    @Field fun paths(r: K8sHttpRoute): List<String> = r.paths
    @Field fun backends(r: K8sHttpRoute): List<String> = r.backends
    @Field fun rules(r: K8sHttpRoute): Int = r.rules
    @Field fun age(r: K8sHttpRoute): String = r.age
    @Field fun status(r: K8sHttpRoute): WorkloadStatus = r.status
}

@TypeController(type = "CertificateResource")
class CertificateResourceTypeController : GraphQLController<K8sCertificate> {
    @Field fun id(c: K8sCertificate): String = c.id
    @Field fun name(c: K8sCertificate): String = c.name
    @Field fun namespace(c: K8sCertificate): String = c.namespace
    @Field fun dns(c: K8sCertificate): List<String> = c.dns
    @Field fun issuer(c: K8sCertificate): String = c.issuer
    @Field fun status(c: K8sCertificate): String = c.status
    @Field fun readySince(c: K8sCertificate): String = c.readySince
    @Field fun expiresInDays(c: K8sCertificate): Int = c.expiresInDays
    @Field fun renewsInDays(c: K8sCertificate): Int = c.renewsInDays
    @Field fun secretName(c: K8sCertificate): String = c.secretName
    @Field fun error(c: K8sCertificate): String? = c.error
}

@TypeController(type = "IssuerResource")
class IssuerResourceTypeController : GraphQLController<K8sIssuer> {
    @Field fun id(i: K8sIssuer): String = i.id
    @Field fun kind(i: K8sIssuer): String = i.kind
    @Field fun name(i: K8sIssuer): String = i.name
    @Field fun type(i: K8sIssuer): String = i.type
    @Field fun server(i: K8sIssuer): String = i.server
    @Field fun status(i: K8sIssuer): String = i.status
    @Field fun age(i: K8sIssuer): String = i.age
    @Field fun certs(i: K8sIssuer): Int = i.certs
    @Field fun namespace(i: K8sIssuer): String? = i.namespace
}
