package bosca.analytics.configuration

import bosca.analytics.iceberg.EventSchema
import bosca.analytics.installer.QueryInstaller
import bosca.analytics.installer.RawEventsQueryInstaller
import bosca.analytics.repository.CompositeEventRepository
import bosca.analytics.repository.ErrorGroupEventRepository
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.iceberg.IcebergEventRepository
import bosca.analytics.repository.nats.NatsEventRepository
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.transform.EventsTransform
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import bosca.db.migrations.Migration
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.nats.NatsConnectionPool
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.CatalogProperties
import org.apache.iceberg.CatalogUtil
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.aws.s3.S3FileIOProperties
import org.apache.iceberg.catalog.Catalog
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.SupportsNamespaces
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.data.Record
import org.apache.iceberg.jdbc.JdbcCatalog
import org.slf4j.LoggerFactory

@Providers
class AnalyticsConfiguration {

    private val log = LoggerFactory.getLogger(AnalyticsConfiguration::class.java)

    @Provider(name = "analytics-migrations")
    fun migration(): Migration = AnalyticsMigration()

    @Provider(singleton = true)
    fun eventProcessingConfiguration(application: BoscaApplication): EventProcessingConfiguration {
        return application.environment.config.propertyOrNull("eventProcessing")?.getAs() ?: EventProcessingConfiguration()
    }

    @Provider(singleton = true)
    fun analyticsQueryCacheConfiguration(application: BoscaApplication): AnalyticsQueryCacheConfiguration {
        val configured = application.environment.config.propertyOrNull("analyticsQueryCache")?.getAs()
            ?: AnalyticsQueryCacheConfiguration()
        val normalized = configured.copy(
            maxEntriesPerQuery = configured.maxEntriesPerQuery.coerceAtLeast(1),
            idleRetentionDays = configured.idleRetentionDays.coerceAtLeast(1),
            maxStaleIntervals = configured.maxStaleIntervals.coerceAtLeast(1),
            refreshQueryBatchSize = configured.refreshQueryBatchSize.coerceAtLeast(1),
            pruneBatchSize = configured.pruneBatchSize.coerceAtLeast(1),
        )
        if (normalized != configured) {
            log.warn(
                "Invalid analyticsQueryCache values were clamped to positive minimums: {}",
                normalized,
            )
        }
        return normalized
    }

    @Provider(singleton = true)
    fun icebergConfiguration(application: BoscaApplication): IcebergConfiguration {
        return application.environment.config.property("iceberg").getAs()
    }

    @Provider(singleton = true)
    fun icebergCatalog(configuration: IcebergConfiguration): Catalog {
        val properties: MutableMap<String?, String?> = HashMap()
        properties[CatalogProperties.CATALOG_IMPL] = JdbcCatalog::class.java.canonicalName
        properties[CatalogProperties.URI] = configuration.database.uri
        properties[CatalogProperties.FILE_IO_IMPL] = configuration.fileIO
        properties["${JdbcCatalog.PROPERTY_PREFIX}user"] = configuration.database.username
        properties["${JdbcCatalog.PROPERTY_PREFIX}password"] = configuration.database.password
        properties[CatalogProperties.WAREHOUSE_LOCATION] = configuration.warehouseLocation
        properties[S3FileIOProperties.ENDPOINT] = configuration.s3?.endpoint
        properties[S3FileIOProperties.PATH_STYLE_ACCESS] = (configuration.s3?.pathStyleAccess ?: true).toString()
        properties[S3FileIOProperties.ACCESS_KEY_ID] = configuration.s3?.accessKeyId
        properties[S3FileIOProperties.SECRET_ACCESS_KEY] = configuration.s3?.secretAccessKey
        return CatalogUtil.buildIcebergCatalog(configuration.catalog, properties, null)
    }

    @Provider(singleton = true, name = "icebergEventSchema")
    fun eventSchema(): Schema = EventSchema

    @Provider(singleton = true, name = "icebergEventTable")
    fun icebergEventTable(
        configuration: IcebergConfiguration,
        catalog: Catalog,
        @ProviderName(name = "icebergEventSchema")
        schema: Schema
    ): Table {
        val namespace = Namespace.of(configuration.namespace)
        val tableName = configuration.table
        if (catalog is SupportsNamespaces && !catalog.namespaceExists(namespace)) {
            catalog.createNamespace(namespace)
        }
        val eventsIdentifier = if (catalog is SupportsNamespaces) {
            TableIdentifier.of(namespace, tableName)
        } else {
            TableIdentifier.of(tableName)
        }
        return if (!catalog.tableExists(eventsIdentifier)) {
            catalog.createTable(eventsIdentifier, schema)
        } else {
            val table = catalog.loadTable(eventsIdentifier)
            evolveSchema(table, schema)
            table
        }
    }

    /**
     * Evolves an existing Iceberg table's schema to match the expected schema by adding
     * any missing top-level columns and any missing nested fields inside existing struct
     * columns. This ensures tables created by older versions of the application gain
     * newly defined fields such as the "error" struct (top-level) and, equally important,
     * new fields added inside pre-existing structs like "error.fingerprint".
     *
     * Iceberg's [org.apache.iceberg.UpdateSchema.addColumn] supports a dotted `parent`
     * path, so nested fields are added with
     * `update.addColumn("error", "fingerprint", Types.StringType.get())`.
     *
     * The recursion walks one level at a time and only descends into struct types; list
     * and map element types are left alone because the analytics event schema does not
     * rely on field-level evolution inside collections.
     */
    private fun evolveSchema(table: Table, expectedSchema: Schema) {
        val currentSchema = table.schema()
        val update = table.updateSchema()
        var dirty = false

        for (expectedField in expectedSchema.columns()) {
            val currentField = currentSchema.findField(expectedField.name())
            if (currentField == null) {
                if (expectedField.isRequired) {
                    update.addRequiredColumn(expectedField.name(), expectedField.type())
                } else {
                    update.addColumn(expectedField.name(), expectedField.type())
                }
                dirty = true
                continue
            }
            val expectedType = expectedField.type()
            val currentType = currentField.type()
            if (expectedType.isStructType && currentType.isStructType) {
                dirty = dirty or addMissingNestedFields(
                    update = update,
                    parentPath = expectedField.name(),
                    expected = expectedType.asStructType(),
                    current = currentType.asStructType(),
                )
            }
        }

        if (dirty) {
            update.commit()
        }
    }

    /**
     * Adds any fields from [expected] that are missing in [current] to the running
     * [update] under the given dot-separated [parentPath]. Returns true when at least
     * one addition was queued on the update.
     */
    private fun addMissingNestedFields(
        update: org.apache.iceberg.UpdateSchema,
        parentPath: String,
        expected: org.apache.iceberg.types.Types.StructType,
        current: org.apache.iceberg.types.Types.StructType,
    ): Boolean {
        var changed = false
        for (expectedField in expected.fields()) {
            val currentField = current.field(expectedField.name())
            if (currentField == null) {
                if (expectedField.isRequired) {
                    update.addRequiredColumn(parentPath, expectedField.name(), expectedField.type())
                } else {
                    update.addColumn(parentPath, expectedField.name(), expectedField.type())
                }
                changed = true
                continue
            }
            val expectedType = expectedField.type()
            val currentType = currentField.type()
            if (expectedType.isStructType && currentType.isStructType) {
                changed = changed or addMissingNestedFields(
                    update = update,
                    parentPath = "$parentPath.${expectedField.name()}",
                    expected = expectedType.asStructType(),
                    current = currentType.asStructType(),
                )
            }
        }
        return changed
    }

    @Provider(singleton = true, name = "icebergEventTransform")
    fun icebergEventTransform(
        @ProviderName(name = "icebergEventSchema")
        schema: Schema
    ): EventsTransform<List<Record>> = IcebergEventsToRecordTransform(schema)

    /**
     * Builds a composite [EventRepository] that fans out each [EventRepository.process]
     * call to the primary storage backend (NATS or Iceberg) **and** the error-group
     * aggregation service. This keeps the event consumer decoupled from the number
     * and identity of downstream sinks.
     *
     * The primary storage backend is selected by the `eventRepository.type` config:
     * - `nats` — forwards events to a NATS JetStream stream for downstream processing
     * - unset / anything else — writes events directly to an Apache Iceberg table
     */
    @Provider(singleton = true)
    suspend fun eventRepository(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        json: ObjectProvider<Json>,
        icebergConfiguration: ObjectProvider<IcebergConfiguration>,
        @ProviderName(name = "icebergEventTable") icebergTable: ObjectProvider<Table>,
        @ProviderName(name = "icebergEventTransform") icebergTransform: ObjectProvider<EventsTransform<List<Record>>>,
        protoBuf: ObjectProvider<ProtoBuf>,
        errorGroupService: ErrorGroupService,
    ): EventRepository {
        val storageRepository = when (application.environment.config.propertyOrNull("eventRepository.type")?.getString()) {
            "nats" -> NatsEventRepository(natsConnectionPool.get(), json.get())
            else -> IcebergEventRepository(
                icebergTable.get(),
                icebergTransform.get(),
                icebergConfiguration.get().localPath,
                protoBuf.get(),
            ).also { repository ->
                application.onShutdown { repository.shutdown() }
            }
        }
        return CompositeEventRepository(
            listOf(storageRepository, ErrorGroupEventRepository(errorGroupService))
        )
    }

    @Provider(singleton = true, name = JobQueueNames.analyticsJobQueue)
    fun analyticsJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.analyticsQueue)

    @Provider(name = JobQueueNames.analyticsRunner)
    fun analyticsJobQueueRunner(
        @ProviderName(JobQueueNames.analyticsJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider(name = "analytics-queries")
    fun analyticsQueriesInstaller(
        service: AnalyticsQueryService,
        securityService: SecurityService,
        application: BoscaApplication,
    ): PackageInstaller = QueryInstaller(
        service,
        securityService,
        experimentationSetting(application, "eventsTable", bosca.analytics.installer.DEFAULT_EVENTS_TABLE),
        experimentationSetting(application, "postgresCatalog", bosca.analytics.installer.DEFAULT_POSTGRES_CATALOG),
    )

    @Provider(name = "analytics-raw-events-queries")
    fun analyticsRawEventsQueriesInstaller(
        service: AnalyticsQueryService,
        securityService: SecurityService,
        application: BoscaApplication,
    ): PackageInstaller = RawEventsQueryInstaller(
        service,
        securityService,
        experimentationSetting(application, "eventsTable", bosca.analytics.installer.DEFAULT_EVENTS_TABLE),
    )

    /**
     * Reads a warehouse location from the shared `experimentation` block, which names the catalogs of
     * this installation for every Trino query the platform installs.
     */
    private fun experimentationSetting(application: BoscaApplication, name: String, default: String): String =
        application.environment.config.propertyOrNull("experimentation.$name")?.getString() ?: default

    @Provider(name = "analytics-queries")
    fun analyticsQueriesPackage(): PackageInstallation = PackageInstallation(
        key = "analytics-queries",
        name = "Analytics Queries",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("analytics-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.1",
                installerNames = listOf("analytics-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.2",
                installerNames = listOf("analytics-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.3",
                installerNames = listOf("analytics-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.4",
                installerNames = listOf("analytics-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.5",
                installerNames = listOf("analytics-queries", "analytics-raw-events-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.6",
                installerNames = listOf("analytics-queries", "analytics-raw-events-queries")
            ),
            PackageInstallationVersion(
                version = "1.0.7",
                installerNames = listOf("analytics-queries", "analytics-raw-events-queries")
            ),
            // Refresh activity queries so passive element/list impressions no longer qualify a user or
            // session as active; page impressions remain activity. QueryInstaller 1.0.5 clears its gate.
            PackageInstallationVersion(
                version = "1.0.8",
                installerNames = listOf("analytics-queries")
            ),
            // Refresh user, session, and page-impression metrics with session-start and bot filters.
            // QueryInstaller 1.0.6 clears the per-installer gate for existing installations.
            PackageInstallationVersion(
                version = "1.0.9",
                installerNames = listOf("analytics-queries")
            )
        )
    )
}
