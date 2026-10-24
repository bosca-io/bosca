package bosca.bml.ide

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import org.jetbrains.kotlin.psi.KtBlockCodeFragment
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtFile

/**
 * Resolves Kotlin references inside a `.bml` embedded-Kotlin region by re-resolving the injected text in a
 * source-anchored [KtBlockCodeFragment] (K2 doesn't resolve the language injection in place). See the goto
 * handler for the why.
 */
object BmlFragmentResolver {

    fun resolve(contextHost: PsiElement, wrappedKotlin: String, offsetInFragment: Int): PsiElement? =
        withAnchoredFragment(contextHost, wrappedKotlin) { fragment ->
            val r = fragment.findReferenceAt(offsetInFragment)?.resolve() ?: return@withAnchoredFragment null
            // A match inside the synthetic fragment is a `provides`/`prop`/for-var use resolving to its
            // prefix declaration — map it back to the real declaration in the .bml (else Cmd-click would
            // land in an in-memory fragment, or, if dropped, do nothing). A match in a real file is the
            // declaration.
            if (r.containingFile is KtCodeFragment) {
                val name = (r as? PsiNamedElement)?.name
                name?.let {
                    BmlPageScope.findDeclarationElement(contextHost.containingFile, it, contextHost.textOffset)
                }
            } else {
                // The Kotlin plugin maps binary declarations to their matching source attachment.
                r.navigationElement
            }
        }

    /**
     * Runs [action] over source-anchored [KtBlockCodeFragment]s built from [text] until one yields a
     * non-null result. K2 code-fragment resolution is sensitive to the anchor element, so a few are
     * tried (file scope, then a class, then any top-level declaration). Project hosts use their module's
     * deps + libraries; attached BML sources inherit the Kotlin anchor's own library scope. This is the
     * shared workaround for K2 not resolving the injection in place — goto and member completion both
     * go through it.
     */
    fun <T : Any> withAnchoredFragment(contextHost: PsiElement, text: String, action: (KtBlockCodeFragment) -> T?): T? {
        val module = ModuleUtilCore.findModuleForPsiElement(contextHost)
        val ktFile = contextKotlinFile(contextHost) ?: return null
        val scope = module?.let { GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(it) }
        val anchors: List<PsiElement> = buildList {
            add(ktFile)
            ktFile.declarations.firstOrNull { it is org.jetbrains.kotlin.psi.KtClassOrObject }?.let { add(it) }
            ktFile.declarations.firstOrNull()?.let { add(it) }
        }
        for (anchor in anchors) {
            val result = try {
                val fragment = KtBlockCodeFragment(anchor.project, "__bmlFragment.kt", text, null, anchor)
                if (scope != null) fragment.forceResolveScope(scope)
                action(fragment)
            } catch (e: ProcessCanceledException) {
                throw e // NEVER swallow cancellation — the platform retries when stable
            } catch (e: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    /** Uses the host module or its own source attachment; unrelated modules never supply an anchor. */
    private fun contextKotlinFile(host: PsiElement): KtFile? {
        val project = host.project
        val module = ModuleUtilCore.findModuleForPsiElement(host)
        val ktType = FileTypeManager.getInstance().getFileTypeByExtension("kt")
        val scopes = if (module != null) {
            listOf(GlobalSearchScope.moduleScope(module))
        } else {
            val file = host.containingFile?.virtualFile ?: return null
            ProjectFileIndex.getInstance(project).getOrderEntriesForFile(file)
                .filterIsInstance<LibraryOrderEntry>()
                .flatMap { it.getRootFiles(OrderRootType.SOURCES).toList() }
                .distinct()
                .filter { VfsUtilCore.isAncestor(it, file, false) }
                .map { GlobalSearchScopesCore.directoryScope(project, it, true) }
        }
        return scopes.firstNotNullOfOrNull { scope ->
            FileTypeIndex.getFiles(ktType, scope).firstNotNullOfOrNull { file ->
                PsiManager.getInstance(project).findFile(file) as? KtFile
            }
        }
    }
}
