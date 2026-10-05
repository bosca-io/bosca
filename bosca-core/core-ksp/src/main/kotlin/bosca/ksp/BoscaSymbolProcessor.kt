package bosca.ksp

import bosca.cache.annotations.Serializer
import bosca.db.annotation.Repository
import bosca.events.annotation.JobEvent
import bosca.graphql.annotations.Schemas
import bosca.graphql.annotations.TypeController
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.generator.cache.CacheSerializersRegistrarGenerator
import bosca.ksp.generator.db.RepositoryProviderGenerator
import bosca.ksp.generator.graphql.ControllerDispatcherGenerator
import bosca.ksp.generator.graphql.ControllerDispatcherProviderGenerator
import bosca.ksp.generator.graphql.ControllerProviderGenerator
import bosca.ksp.generator.graphql.DispatchersRegistryGenerator
import bosca.ksp.generator.job.JobConfigurationEnqueuerGenerator
import bosca.ksp.generator.job.JobEnqueueGenerator
import bosca.ksp.generator.job.JobEventGenerator
import bosca.ksp.generator.job.JobExecutorProviderGenerator
import bosca.ksp.generator.job.EventCatalogRegistryGenerator
import bosca.ksp.generator.pipeline.PipelineNodeIoGenerator
import bosca.ksp.generator.pipeline.PipelineNodeSerializersGenerator
import bosca.ksp.visitors.PipelineNodeVisitor
import bosca.pipelines.annotation.PipelineNodeType
import bosca.ksp.generator.job.SchedulableJobRegistryGenerator
import bosca.ksp.generator.page.PageProviderGenerator
import bosca.ksp.generator.page.PagesGenerator
import bosca.ksp.generator.route.RouteProviderGenerator
import bosca.ksp.generator.route.RoutesGenerator
import bosca.ksp.generator.serialization.SerializerRegistrarGenerator
import bosca.ksp.visitors.CacheSerializerVisitor
import bosca.ksp.visitors.JobDefinitionVisitor
import bosca.ksp.visitors.JobEventVisitor
import bosca.ksp.visitors.PageControllerVisitor
import bosca.ksp.visitors.RepositoryVisitor
import bosca.ksp.visitors.RouteControllerVisitor
import bosca.ksp.visitors.SchemaVisitor
import bosca.ksp.visitors.SchedulableVisitor
import bosca.ksp.visitors.SerializableClassVisitor
import bosca.ksp.visitors.TypeControllerVisitor
import kotlinx.serialization.Serializable
import bosca.pages.annotations.PageController
import bosca.queue.annotations.JobDefinition
import bosca.routes.annotations.RouteController
import bosca.scheduler.annotations.Schedulable
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSVisitorVoid

class BoscaSymbolProcessor(
    codeGenerator: CodeGenerator,
    logger: KSPLogger,
    providerRegistrarPrefix: String
) : AbstractBoscaSymbolProcessor(codeGenerator, logger, providerRegistrarPrefix) {

    private val cacheSerializerVisitor = CacheSerializerVisitor(processed)
    private val pageProviderVisitor = PageControllerVisitor(processed)
    private val routeProviderVisitor = RouteControllerVisitor(processed)
    private val jobDefinitionVisitor = JobDefinitionVisitor(processed)
    private val jobEventVisitor = JobEventVisitor(processed, logger)
    private val typeControllerVisitor = TypeControllerVisitor(processed)
    private val repositoryVisitor = RepositoryVisitor(processed)
    private val schemaVisitor = SchemaVisitor(processed, logger)
    private val schedulableVisitor = SchedulableVisitor(processed, logger)
    // Serializable registration is independent of the other annotations on a class. In particular,
    // @JobEvent and @PipelineNodeType classes are also @Serializable and must still reach the type
    // catalog, so this visitor needs its own per-round de-duplication set rather than the shared one.
    private val serializableClassVisitor = SerializableClassVisitor(mutableSetOf())
    private val pipelineNodeVisitor = PipelineNodeVisitor(processed, logger)

    private val typeControllerGenerators = listOf(
        ControllerDispatcherGenerator(codeGenerator, logger),
        ControllerProviderGenerator(codeGenerator),
        ControllerDispatcherProviderGenerator(codeGenerator),
        DispatchersRegistryGenerator(codeGenerator, providerRegistrarPrefix),
    )
    private val pageControllerGenerator = listOf(
        PageProviderGenerator(codeGenerator),
        PagesGenerator(codeGenerator, providerRegistrarPrefix),
    )
    private val routeControllerGenerator = listOf(
        RouteProviderGenerator(codeGenerator),
        RoutesGenerator(codeGenerator, providerRegistrarPrefix),
    )
    private val jobGenerators = listOf(
        JobExecutorProviderGenerator(codeGenerator),
        JobEnqueueGenerator(codeGenerator),
        JobConfigurationEnqueuerGenerator(codeGenerator)
    )
    private val jobEventGenerators = listOf(
        JobEventGenerator(codeGenerator),
        EventCatalogRegistryGenerator(codeGenerator, providerRegistrarPrefix),
    )
    private val pipelineNodeGenerators = listOf(
        PipelineNodeSerializersGenerator(codeGenerator, providerRegistrarPrefix),
        PipelineNodeIoGenerator(codeGenerator),
    )
    private val repositoryGenerators = listOf(
        bosca.ksp.generator.db.RepositoryGenerator(codeGenerator, logger),
        RepositoryProviderGenerator(codeGenerator),
    )
    private val schemaGenerators = listOf(
        bosca.ksp.generator.graphql.SchemaGenerator(codeGenerator, providerRegistrarPrefix, logger),
    )
    private val schedulableGenerators = listOf(
        SchedulableJobRegistryGenerator(codeGenerator, providerRegistrarPrefix)
    )
    private val cacheSerializerGenerators = listOf(
        CacheSerializersRegistrarGenerator(codeGenerator, providerRegistrarPrefix)
    )
    private val nativeSerializerGenerators = listOf(
        SerializerRegistrarGenerator(codeGenerator, providerRegistrarPrefix)
    )
    override val allGenerators = typeControllerGenerators + repositoryGenerators + schemaGenerators + schedulableGenerators

    override fun newVisitors(resolver: Resolver): Array<Pair<Map<Boolean, List<KSAnnotated>>, KSVisitorVoid>> = arrayOf(
        Pair(resolver.resolve(JobDefinition::class), jobDefinitionVisitor),
        Pair(resolver.resolve(JobEvent::class), jobEventVisitor),
        Pair(resolver.resolve(PipelineNodeType::class), pipelineNodeVisitor),
        Pair(resolver.resolve(TypeController::class), typeControllerVisitor),
        Pair(resolver.resolve(Serializer::class), cacheSerializerVisitor),
        Pair(resolver.resolve(PageController::class), pageProviderVisitor),
        Pair(resolver.resolve(RouteController::class), routeProviderVisitor),
        Pair(resolver.resolve(Repository::class), repositoryVisitor),
        Pair(resolver.resolve(Schemas::class), schemaVisitor),
        Pair(resolver.resolve(Schedulable::class), schedulableVisitor),
        Pair(resolver.resolve(Serializable::class), serializableClassVisitor),
    )

    override fun canBeEmpty(generator: AbstractGenerator<*>?) = generator is CacheSerializersRegistrarGenerator || generator is SerializerRegistrarGenerator

    override fun incrementalGenerate(finish: Boolean) {
        jobDefinitionVisitor.generate(jobGenerators)
        jobEventVisitor.generate(jobEventGenerators)
        pipelineNodeVisitor.generate(pipelineNodeGenerators)
        typeControllerVisitor.generate(typeControllerGenerators)
        repositoryVisitor.generate(repositoryGenerators)
        pageProviderVisitor.generate(pageControllerGenerator)
        routeProviderVisitor.generate(routeControllerGenerator)
        schemaVisitor.generate(schemaGenerators)
        schedulableVisitor.generate(schedulableGenerators)
        if (finish) {
            cacheSerializerVisitor.generate(cacheSerializerGenerators)
            serializableClassVisitor.generate(nativeSerializerGenerators)
        }
    }
}

class BoscaSymbolProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): BoscaSymbolProcessor {
        var prefix = environment.options["ProviderRegistrarPrefix"] ?: ""
        if (environment.options["isTest"]?.toBoolean() ?: false) {
            prefix = "Test$prefix"
        }
        return BoscaSymbolProcessor(
            environment.codeGenerator,
            environment.logger,
            prefix
        )
    }
}
