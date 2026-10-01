package bosca.scheduler.annotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Schedulable(
    val name: String,
    val description: String = ""
)
