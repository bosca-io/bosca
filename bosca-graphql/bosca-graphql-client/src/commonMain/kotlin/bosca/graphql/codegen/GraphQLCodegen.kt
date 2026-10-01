package bosca.graphql.codegen

import bosca.graphql.language.Document
import bosca.graphql.language.Field
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.SelectionSet
import bosca.graphql.parser.Parser
import bosca.graphql.schema.GraphQLSchema

/** A generated source file: its [name] (e.g. `GetUser.kt`) and Kotlin [content]. */
data class GeneratedFile(val name: String, val content: String)

/**
 * Project-level orchestrator over [KotlinClientGenerator]: turns a set of `.graphql` documents
 * (operations + shared fragments, arranged across files however the author likes) plus a schema into
 * one generated Kotlin file per operation. Pure and multiplatform — the Gradle plugin / CLI add the
 * file I/O around it.
 *
 * Fragments may live in their own files and be shared by many operations; each operation's emitted file
 * carries exactly the fragments it transitively references, so every file is self-contained and the
 * server receives a complete document.
 */
class GraphQLCodegen(
    private val schema: GraphQLSchema,
    private val scalarMappings: Map<String, ScalarMapping> = emptyMap(),
) {
    /** Build from raw schema SDL (typically the checked-in `schema.graphqls`). */
    constructor(schemaSdl: String, scalarMappings: Map<String, ScalarMapping> = emptyMap()) :
        this(GraphQLSchema.fromSdl(schemaSdl), scalarMappings)

    /**
     * Generate the Kotlin client across [sources] (each the text of one `.graphql` file), all into [packageName]:
     * one file per operation PLUS one file per shared enum / input object. Enums and input objects are emitted
     * exactly once (not inlined into each operation) so types shared by multiple operations don't collide in the
     * package. Throws on an anonymous operation, a duplicate operation or fragment name across the sources, or an
     * operation that references an undefined fragment.
     */
    fun generate(sources: List<String>, packageName: String): List<GeneratedFile> {
        val generator = KotlinClientGenerator(schema, scalarMappings)
        val stitched = stitch(sources)
        val allFragments = sources.flatMap { Parser.parse(it).definitions }
            .filterIsInstance<FragmentDefinition>().associateBy { it.name }

        // Pass 1 — discover the union of enums + (transitive) input objects + spread named fragments across ops.
        val sharedEnums = linkedSetOf<String>()
        val sharedInputs = linkedSetOf<String>()
        val referencedFragments = linkedSetOf<String>()
        for (operation in stitched) {
            generator.generate(operation.document, packageName) // result discarded; populates the referenced sets
            sharedEnums += generator.referencedEnumNames()
            sharedInputs += generator.referencedInputNames()
            referencedFragments += generator.referencedFragmentNames()
        }
        val hoisted = sharedEnums + sharedInputs

        // Pass 2 — emit each operation (its nested types implement the fragment interfaces they spread, referencing
        // — not re-declaring — the shared enums/inputs), plus one file per shared enum / input object, and one
        // I<Fragment> interface-tree file per spread named fragment.
        val files = buildList {
            stitched.forEach { add(GeneratedFile("${it.name}.kt", generator.generate(it.document, packageName, hoisted))) }
            sharedEnums.forEach { add(GeneratedFile("$it.kt", generator.emitEnumFile(it, packageName))) }
            sharedInputs.forEach { add(GeneratedFile("$it.kt", generator.emitInputFile(it, packageName))) }
            generator.useFragments(allFragments)
            referencedFragments.forEach {
                add(GeneratedFile("${KotlinClientGenerator.INTERFACE_PREFIX}$it.kt", generator.emitFragmentInterfaceFile(it, packageName)))
            }
        }
        val duplicate = files.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicate == null) { "Generated file name collision on '$duplicate' (an operation and a type share a name)." }
        return files
    }

    /**
     * Generate one TypeScript [GeneratedFile] per operation across [sources] — the second emit target for BML
     * islands. [tsScalarMappings] maps custom scalars to TypeScript types (independent of the
     * Kotlin mappings). Same stitching/validation as [generate].
     */
    fun generateTypeScript(
        sources: List<String>,
        tsScalarMappings: Map<String, TsScalarMapping> = emptyMap(),
        options: TypeScriptOptions = TypeScriptOptions(),
    ): List<GeneratedFile> {
        val generator = TypeScriptClientGenerator(schema, tsScalarMappings)
        return stitch(sources).map { (name, document) ->
            GeneratedFile("$name.ts", generator.generate(document, options))
        }
    }

    /** One operation paired with a self-contained document (the operation + only the fragments it transitively uses). */
    private data class Stitched(val name: String, val document: Document)

    /** Parse every source into one definition pool, then pair each operation with its transitive fragment closure. */
    private fun stitch(sources: List<String>): List<Stitched> {
        val definitions = sources.flatMap { Parser.parse(it).definitions }

        val fragments = linkedMapOf<String, FragmentDefinition>()
        for (fragment in definitions.filterIsInstance<FragmentDefinition>()) {
            require(fragments.put(fragment.name, fragment) == null) {
                "Duplicate fragment name '${fragment.name}' across the GraphQL sources."
            }
        }

        val seen = mutableSetOf<String>()
        return definitions.filterIsInstance<OperationDefinition>().map { operation ->
            val name = operation.name
                ?: error("Every operation must be named to generate a typed client; found an anonymous operation.")
            require(seen.add(name)) { "Duplicate operation name '$name' across the GraphQL sources." }
            Stitched(name, Document(listOf(operation) + transitiveFragments(operation, fragments)))
        }
    }

    /** The fragment definitions [operation] references, transitively, in first-seen order. */
    private fun transitiveFragments(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
    ): List<FragmentDefinition> {
        val collected = linkedSetOf<String>()

        fun visit(selectionSet: SelectionSet) {
            for (selection in selectionSet.selections) {
                when (selection) {
                    is Field -> selection.selectionSet?.let { visit(it) }
                    is InlineFragment -> visit(selection.selectionSet)
                    is FragmentSpread -> if (collected.add(selection.name)) {
                        val fragment = fragments[selection.name]
                            ?: error("Operation '${operation.name}' references unknown fragment '...${selection.name}'.")
                        visit(fragment.selectionSet)
                    }
                }
            }
        }

        visit(operation.selectionSet)
        return collected.map { fragments.getValue(it) }
    }
}
