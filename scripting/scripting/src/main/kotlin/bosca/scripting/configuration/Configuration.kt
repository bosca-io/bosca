package bosca.scripting.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.pubsub.PubSubService
import bosca.scripting.engine.Engine
import bosca.scripting.engine.RemoteEngine
import bosca.scripting.engine.ScriptSourceValidator
import bosca.scripting.engine.ScriptingSecurityConfiguration
import bosca.scripting.repository.ScriptingMigration
import bosca.scripting.security.ScriptPermissionEvaluator
import bosca.scripting.service.RemoteScriptExecutionServiceImpl
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.storage.service.ObjectStorageService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json

object JobQueueNames {
    const val scriptingJobQueue = "scriptingQueue"
    const val scriptingRunner = "scriptingQueueRunner"
    const val scriptingQueue = "scripting"
}

@Providers
class Configuration {

    @Provider(singleton = true)
    fun scriptingSecurityConfiguration(application: BoscaApplication): ScriptingSecurityConfiguration {
        return application.environment.config.propertyOrNull("scripting")
            ?.getAs<ScriptingSecurityConfiguration>()
            ?: ScriptingSecurityConfiguration()
    }

    @Provider(singleton = true)
    fun validator(): bosca.scripting.engine.ScriptSourceValidator =
        bosca.scripting.engine.ScriptSourceValidatorImpl()

    @Provider(singleton = true)
    suspend fun scriptingExecutionService(
        @ProviderName("remote-execution-service")
        remote: ObjectProvider<ScriptExecutionService>,
        @ProviderName("local-execution-service")
        local: ObjectProvider<ScriptExecutionService>
    ): ScriptExecutionService {
        if (local.exists) return local.get()
        return remote.get()
    }

    @Provider(singleton = true)
    suspend fun engine(
        @ProviderName("remote-engine")
        remoteEngine: ObjectProvider<Engine>,
        @ProviderName("local-engine")
        localEngine: ObjectProvider<Engine>
    ): Engine {
        if (localEngine.exists) return localEngine.get()
        return remoteEngine.get()
    }

    @Provider(singleton = true, name = "remote-engine")
    fun remoteEngine(validator: ScriptSourceValidator): Engine = RemoteEngine(validator)

    @Provider(singleton = true, name = "remote-execution-service")
    fun remoteScriptExecutionService(
        pubsub: PubSubService,
        objectStorageService: ObjectStorageService,
        json: Json,
    ): ScriptExecutionService = RemoteScriptExecutionServiceImpl(pubsub, objectStorageService, json)

    @Provider(name = "scripting-migrations")
    fun migration(): Migration = ScriptingMigration()

    @Provider(singleton = true, name = JobQueueNames.scriptingJobQueue)
    fun scriptingJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.scriptingQueue)

    @Provider(singleton = true, name = JobQueueNames.scriptingRunner)
    fun scriptingJobQueueRunner(
        @ProviderName(JobQueueNames.scriptingJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider(singleton = true)
    fun scriptPermissionEvaluator(
        service: ScriptService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = ScriptPermissionEvaluator(service, securityService, groupEvaluator)
}
