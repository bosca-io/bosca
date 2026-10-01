package bosca.bml.compiler.plugin

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension

/**
 * FIR phase. The build path generates the page objects as real Kotlin **source**
 * (via [BmlAdditionalSourcesExtension] + [bosca.bml.codegen.BmlCodeGenerator]) so embedded Kotlin
 * resolves through the frontend with no `.kt` files on disk. This FIR generator is reserved for
 * IDE-only synthesis (KEFS) of declarations the in-memory sources don't expose; it is a no-op for
 * the build so it never collides with the injected sources.
 */
class BmlFirDeclarationGenerator(
    session: FirSession,
    @Suppress("unused") private val sourceRoots: List<String>,
) : FirDeclarationGenerationExtension(session) {
    object Key : GeneratedDeclarationKey()
}
