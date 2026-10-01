package bosca.content.metadata.graphql

import bosca.content.graphql.MetadataBatchFilter
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStepContext
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.graphql.BatchLoaderEnvironment
import java.time.OffsetDateTime

@TypeController
class GuideController(
    private val metadataService: MetadataService,
    private val service: GuideService,
    private val permissionEvaluator: MetadataPermissionEvaluator
) : GraphQLController<Guide> {

    @Field
    fun type(guide: Guide) = guide.type

    @Field
    fun rrule(guide: Guide) = guide.rrule

    @Field
    suspend fun template(authentication: AuthenticationContext, batch: Batch<MetadataCacheKeyId, Metadata>, env: BatchLoaderEnvironment) {
        val templateKeys = env.keyContextsList.map {
            val guide = (it as BatchContext<*>).context as Guide
            MetadataCacheKeyId(guide.templateMetadataId ?: error("Guide does not have a template"), guide.templateMetadataVersion ?: 1)
        }
        val templateBatch = Batch<MetadataCacheKeyId, Metadata>(templateKeys)
        metadataService.getByIdBatched(templateBatch)
        batch.filter = MetadataBatchFilter(authentication, permissionEvaluator)
        batch.keys.forEachIndexed { index, key ->
            val templateKey = templateKeys[index]
            val template = templateBatch.getData(templateKey) ?: return@forEachIndexed
            batch.setData(key, template)
        }
    }

    @Field
    suspend fun recurrences(batch: Batch<MetadataCacheKeyId, List<OffsetDateTime>>) {
        val mappedBatch = Batch<MetadataCacheKeyId, Guide>(batch.keys)
        val counts = Batch<MetadataCacheKeyId, Long>(batch.keys)
        service.addGuidesToBatch(mappedBatch)
        service.addStepCountsToBatch(counts)
        batch.keys.forEach { key ->
            val guide = mappedBatch.getData(key) ?: return@forEach
            val count = counts.getData(key) ?: return@forEach
            val recurrences = guide.getRecurrenceDates(count.toInt())
            batch.setData(key, recurrences)
        }
    }

    @Field
    suspend fun step(
        guide: Guide,
        date: OffsetDateTime?,
        stepId: Long?
    ): GuideStepContext? {
        val step = if (stepId != null) {
            service.getGuideStep(guide.metadataId, guide.version, stepId)
        } else if (date != null) {
            if (guide.rrule == null) error("Guide does not have a recurrence rule")
            // TODO: cache this somewhere
            val stepCount = service.getStepCount(guide.metadataId, guide.version).toInt()
            val recurrences = guide.getRecurrenceDates(stepCount)
            var offset = 0
            for (d in recurrences) {
                if (d >= date) {
                    break
                }
                offset++
            }
            service.getGuideSteps(guide.metadataId, guide.version, offset, 1).firstOrNull()
        } else {
            error("must provide either stepId or date")
        }
        if (step != null) {
            // TODO: cache this somewhere
            return if (guide.rrule != null) {
                val dates = guide.getRecurrenceDates(step.sort + 1).iterator()
                for (@Suppress("unused") i in 0 until step.sort) {
                    dates.next()
                }
                GuideStepContext(guide, step, dates.next())
            } else {
                GuideStepContext(guide, step, null)
            }
        }
        return null
    }

    @Field
    suspend fun stepByOffset(
        guide: Guide,
        offset: Int
    ): GuideStepContext? {
        val steps = getSteps(guide, offset, 1)
        return steps.firstOrNull()
    }

    @Field
    suspend fun steps(
        guide: Guide,
        offset: Int?,
        limit: Int?
    ): List<GuideStepContext> {
        val steps = getSteps(guide, offset, limit)
        return steps
    }

    @Field
    suspend fun stepCount(batch: Batch<MetadataCacheKeyId, Long>) {
        service.addStepCountsToBatch(batch)
    }

    private suspend fun getSteps(guide: Guide, offset: Int?, limit: Int?): List<GuideStepContext> {
        val steps = service.getGuideSteps(guide.metadataId, guide.version, offset, limit)
        val rrule = guide.rrule
        if (rrule != null) {
            val lastStep = steps.lastOrNull()
            if (lastStep != null) {
                // TODO: cache this somewhere
                val recurrences = guide.getRecurrenceDates(steps.size + 1)
                val results = mutableListOf<GuideStepContext>()
                for ((index, step) in steps.withIndex()) {
                    results.add(GuideStepContext(guide, step, recurrences.getOrNull(index)))
                }
                return results
            } else {
                return emptyList()
            }
        } else {
            return steps.map { GuideStepContext(guide, it, null) }
        }
    }
}
