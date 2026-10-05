package bosca.bml.server

import bosca.bml.graphql.HttpGraphQLClient
import bosca.bml.i18n.BmlLocalization
import bosca.bml.i18n.GraphQLLocalizationClient
import bosca.bml.i18n.MessageSource
import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlLocalePolicy
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point for `bml-server`. A real deployment passes the compiled project's generated pages (each
 * implements [BmlPageRenderer]); the server registers them directly on Bosca Server's router. This
 * default serves a minimal status page so the server is runnable standalone (`./gradlew :bml:bml-server:run`).
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    val status = object : BmlPageRenderer {
        override val route = "/"
        override suspend fun render(ctx: RenderContext) {
            ctx.writer.markup(
                "<!doctype html><html><head><title>bml-server</title></head>" +
                    "<body><h1>bml-server</h1><p>Running. Pass a compiled BML project's pages to serve content.</p></body></html>",
            )
        }
    }
    val graphqlEndpoint = System.getenv("BML_GRAPHQL_ENDPOINT") // configurable local/remote

    // Localization: binding a Bosca localization project makes it the source of
    // truth for BOTH the site's locales (source language = default, target languages = the
    // rest) and its strings. No project bound = unlocalized single-locale site.
    val localization = System.getenv("BML_LOCALIZATION_PROJECT")
        ?.takeIf { graphqlEndpoint != null }
        ?.let { project ->
            BmlLocalization(
                GraphQLLocalizationClient(
                    gql = HttpGraphQLClient(requireNotNull(graphqlEndpoint)),
                    project = project,
                    token = System.getenv("BML_LOCALIZATION_TOKEN"),
                    states = System.getenv("BML_LOCALIZATION_STATES")
                        ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                        ?: setOf("PUBLISHED"),
                ),
                ttl = System.getenv("BML_LOCALIZATION_TTL_SECONDS")?.toLongOrNull()?.seconds ?: 5.minutes,
            )
        }

    BmlServer(
        CompiledProject(name = "bml-server", version = "0.0.1"),
        listOf(status),
        port,
        graphqlEndpoint = graphqlEndpoint,
        localePolicy = localization ?: BmlLocalePolicy.static(),
        messageSource = localization ?: MessageSource.Empty,
    ).start()
}
