package bosca.analytics.persistence.room

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        AnalyticsContextEntity::class,
        AnalyticsEventEntity::class,
        AnalyticsFeatureFlagCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(AnalyticsDatabaseConstructor::class)
internal abstract class AnalyticsDatabase : RoomDatabase() {
    abstract fun storage(): AnalyticsStorageDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object AnalyticsDatabaseConstructor : RoomDatabaseConstructor<AnalyticsDatabase> {
    override fun initialize(): AnalyticsDatabase
}

internal fun buildAnalyticsDatabase(builder: RoomDatabase.Builder<AnalyticsDatabase>): AnalyticsDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
