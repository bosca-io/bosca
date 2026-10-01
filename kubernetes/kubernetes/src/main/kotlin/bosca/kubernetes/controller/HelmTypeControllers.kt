package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.K8sHelmChart
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.model.K8sHelmChartVersion
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.model.K8sHelmRevision
import kotlinx.serialization.json.JsonElement

/**
 * Field projections for the helm wire shapes. Pure record accessors —
 * authorization happens upstream in the queries/mutations controllers.
 */

@TypeController(type = "HelmRepo")
class HelmRepoTypeController : GraphQLController<K8sHelmRepo> {
    @Field fun name(r: K8sHelmRepo): String = r.name
    @Field fun url(r: K8sHelmRepo): String = r.url
    @Field fun type(r: K8sHelmRepo): String = r.type
    @Field fun charts(r: K8sHelmRepo): Int = r.charts
    @Field fun lastUpdate(r: K8sHelmRepo): String = r.lastUpdate
}

@TypeController(type = "HelmChart")
class HelmChartTypeController : GraphQLController<K8sHelmChart> {
    @Field fun id(c: K8sHelmChart): String = c.id
    @Field fun name(c: K8sHelmChart): String = c.name
    @Field fun repo(c: K8sHelmChart): String = c.repo
    @Field fun version(c: K8sHelmChart): String = c.version
    @Field fun appVersion(c: K8sHelmChart): String = c.appVersion
    @Field fun description(c: K8sHelmChart): String = c.description
    @Field fun icon(c: K8sHelmChart): String? = c.icon
}

@TypeController(type = "HelmChartVersion")
class HelmChartVersionTypeController : GraphQLController<K8sHelmChartVersion> {
    @Field fun version(v: K8sHelmChartVersion): String = v.version
    @Field fun appVersion(v: K8sHelmChartVersion): String = v.appVersion
    @Field fun released(v: K8sHelmChartVersion): String = v.released
    @Field fun current(v: K8sHelmChartVersion): Boolean = v.current
}

@TypeController(type = "HelmChartValues")
class HelmChartValuesTypeController : GraphQLController<K8sHelmChartValues> {
    @Field fun defaultValues(v: K8sHelmChartValues): String = v.defaultValues
    @Field fun schema(v: K8sHelmChartValues): JsonElement? = v.schema
}

@TypeController(type = "HelmRelease")
class HelmReleaseTypeController : GraphQLController<K8sHelmRelease> {
    @Field fun id(r: K8sHelmRelease): String = r.id
    @Field fun name(r: K8sHelmRelease): String = r.name
    @Field fun namespace(r: K8sHelmRelease): String = r.namespace
    @Field fun chart(r: K8sHelmRelease): String = r.chart
    @Field fun chartVersion(r: K8sHelmRelease): String = r.chartVersion
    @Field fun appVersion(r: K8sHelmRelease): String = r.appVersion
    @Field fun revision(r: K8sHelmRelease): Int = r.revision
    @Field fun status(r: K8sHelmRelease): HelmStatus = r.status
    @Field fun updated(r: K8sHelmRelease): String = r.updated
    @Field fun installed(r: K8sHelmRelease): String = r.installed
    @Field fun repo(r: K8sHelmRelease): String = r.repo
    @Field fun repoUrl(r: K8sHelmRelease): String = r.repoUrl
    @Field fun description(r: K8sHelmRelease): String = r.description
}

@TypeController(type = "HelmRevision")
class HelmRevisionTypeController : GraphQLController<K8sHelmRevision> {
    @Field fun revision(r: K8sHelmRevision): Int = r.revision
    @Field fun updated(r: K8sHelmRevision): String = r.updated
    @Field fun status(r: K8sHelmRevision): HelmStatus = r.status
    @Field fun chart(r: K8sHelmRevision): String = r.chart
    @Field fun appVersion(r: K8sHelmRevision): String = r.appVersion
    @Field fun description(r: K8sHelmRevision): String = r.description
}
