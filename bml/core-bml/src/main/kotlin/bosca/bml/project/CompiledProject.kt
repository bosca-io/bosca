package bosca.bml.project

import kotlinx.serialization.Serializable

/**
 * The serializable snapshot a BML project compiles to — what the
 * `bml-server` serves and the CLI/dev loop swaps.
 */
@Serializable
data class CompiledProject(
    val name: String,
    val version: String,
    val pages: Map<String, CompiledPage> = emptyMap(),       // key (route) -> page
    val islandBundles: Map<String, String> = emptyMap(),     // island name -> JS bundle digest/ref
    val stylesheets: Map<String, String> = emptyMap(),       // name -> CSS
    val assets: Map<String, String> = emptyMap(),            // path -> content digest
    val discovery: DiscoveryFiles = DiscoveryFiles(),
)

/** A single compiled page. */
@Serializable
data class CompiledPage(
    val route: String,
    val renderObject: String,                 // FQN of the generated render object
    val prerendered: Boolean = false,         // render="prerender"
    val prerenderedHtml: String? = null,      // baked HTML when prerendered
    val metadataId: String? = null,           // Bosca content metadata ref
)

/** SEO discovery files generated for the project. */
@Serializable
data class DiscoveryFiles(
    val sitemap: String? = null,
    val robots: String? = null,
    val llms: String? = null,
)
