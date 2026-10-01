package bosca.db.annotation

import kotlin.annotation.Retention

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY)
annotation class ColumnName(val value: String)
