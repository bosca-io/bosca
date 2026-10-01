package bosca.bml.annotations

/**
 * Marks a generated page render unit. The K2 plugin discovers these (FIR
 * predicate / IR collector) to wire routing and SSR entry points.
 *
 * @param route the URL route pattern, e.g. `/lists/{id}`.
 * @param render whether the page is rendered per request or pre-rendered at build time.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class BmlPage(val route: String, val render: RenderMode = RenderMode.Request)
