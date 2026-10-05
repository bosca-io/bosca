@file:OptIn(ExperimentalSerializationApi::class)

package bosca.communications.configuration

import bosca.bml.message.client.BmlMessageServerClient
import bosca.configuration.service.ConfigurationService
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.communications.mailers.Mailer
import bosca.communications.mailers.MailerConfiguration
import bosca.communications.mailers.MailerType
import bosca.communications.mailers.mailgun.MailgunMailer
import bosca.communications.mailers.sendgrid.SendGridMailer
import bosca.communications.push.PushSender
import bosca.communications.push.PushSenderImpl
import bosca.communications.service.BmlMessageServerTokenProvider
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

object JobQueueNames {
    const val messagesJobQueue = "messagesJobQueue"
    const val messagesRunner = "messagesJobQueueRunner"
    const val messagesQueueName = "messages"
}

@Providers
class Configuration {

    @Provider(name = JobQueueNames.messagesRunner)
    fun messagesJobQueueRunner(
        @ProviderName(JobQueueNames.messagesJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)

    @Provider(singleton = true, name = JobQueueNames.messagesJobQueue)
    fun messagesJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.messagesQueueName)

    @Provider
    fun mailerConfiguration(application: BoscaApplication): MailerConfiguration =
        application.environment.config.property("mailer").getAs<MailerConfiguration>()

    @Provider(singleton = true)
    fun mailer(
        json: Json,
        configuration: MailerConfiguration,
        configurationService: ConfigurationService,
    ): Mailer =
        when (configuration.type) {
            MailerType.SENDGRID -> SendGridMailer(
                json,
                configurationService,
            )

            MailerType.MAILGUN -> MailgunMailer(
                json,
                configurationService,
            )
        }

    @Provider(singleton = true)
    fun bmlMessageServerClient(): BmlMessageServerClient =
        // Cluster-internal service address (the KubernetesControllerClient pattern). The message
        // server's render API is private — only its asset routes are ever routed publicly.
        BmlMessageServerClient(System.getenv("BML_MESSAGE_SERVER_URL") ?: "http://bml-message-server:9093")

    @Provider(singleton = true)
    fun bmlMessageServerTokenProvider(securityService: SecurityService): BmlMessageServerTokenProvider =
        BmlMessageServerTokenProvider(securityService)

    @Provider(singleton = true)
    fun pushSender(json: Json, configurationService: ConfigurationService): PushSender =
        PushSenderImpl(json, configurationService)

    @Provider(name = "communications-migrations")
    fun migration(): Migration = CommunicationsMigration()
}
