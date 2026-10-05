package bosca.calendar.configuration

import bosca.calendar.repository.ScheduledJobEventRepository
import bosca.calendar.repository.ScheduledJobEventRepositoryImpl
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class Configuration {

    @Provider(name = "calendar-migrations")
    fun migration(): Migration = CalendarMigration()

    @Provider(singleton = true)
    fun scheduledJobEventRepository(): ScheduledJobEventRepository = ScheduledJobEventRepositoryImpl()
}
