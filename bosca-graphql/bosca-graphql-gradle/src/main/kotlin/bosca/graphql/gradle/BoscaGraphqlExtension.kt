package bosca.graphql.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * DSL for the `boscaGraphql { }` block. Every property has a convention, so a module that follows the layout
 * (operations + `schema.graphqls` under `src/main/graphql`) needs no configuration at all.
 */
abstract class BoscaGraphqlExtension {
    /** Directory holding the `.graphql` operation files (and, by convention, `schema.graphqls`). Default `src/main/graphql`. */
    abstract val sourceDir: DirectoryProperty

    /** The checked-in composed schema the codegen runs against. Default `src/main/graphql/schema.graphqls`. */
    abstract val schemaFile: RegularFileProperty

    /** Package for the generated operations. Default `bosca.graphql.client.generated`. */
    abstract val packageName: Property<String>

    /** Custom scalar mappings: GraphQL scalar name → `KotlinType[:importFqn][:serializerFqn]`. */
    abstract val scalarMappings: MapProperty<String, String>

    /** Also generate browser TypeScript operations. Disabled for non-web consumers by default. */
    abstract val generateTypeScript: Property<Boolean>

    /** Output directory for generated browser operations. Default `build/generated/bosca-graphql/typescript`. */
    abstract val typeScriptOutputDir: DirectoryProperty

    /** Module providing the generated operation runtime (`graphql`, `BoscaOperation`). Default `@bosca/bml`. */
    abstract val typeScriptRuntimeModule: Property<String>

    /** Custom scalar mappings for TypeScript: GraphQL scalar name → `tsType[:importName:importFrom]`. */
    abstract val typeScriptScalarMappings: MapProperty<String, String>

    /** Endpoint used by `downloadBoscaGraphqlSchema` to refresh [schemaFile] via introspection. */
    abstract val endpoint: Property<String>

    /** Extra headers (e.g. `Authorization`) sent with the introspection request. */
    abstract val headers: MapProperty<String, String>

    /**
     * The generator artifact resolved into the codegen worker classpath. Default `io.bosca:bosca-graphql-client`
     * (the base coordinate); the plugin's resolvable configuration carries JVM attributes that select its jvm
     * variant, so the workspace composite can source-substitute it to the local project with no publish step.
     */
    abstract val generatorCoordinate: Property<String>
}
