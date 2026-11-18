package bosca.bml.ide

import bosca.bml.library.BmlImportResolver
import bosca.bml.parser.BmlParser as SourceParser
import bosca.bml.project.ContentDigest
import com.intellij.lang.ASTNode
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.io.IOException

/** Component bindings in one BML module: local declarations plus explicitly imported binary exports. */
object BmlComponentDecls {
    data class Decl(
        val tag: String,
        val navElement: PsiElement?,
        val props: List<String>,
        val origin: String = "component",
        val navigationProblem: String? = null,
    )
    data class Problem(val file: PsiFile, val range: TextRange, val message: String)
    data class Scope(val declarations: List<Decl>, val problems: List<Problem>)

    // Content events, including replaced JARs, matter as well as roots and unsaved PSI edits.
    private val vfsChanges = ModificationTracker { VirtualFileManager.getInstance().modificationCount }

    /** Current bindings; edits to imports, source attachments, and dependency roots invalidate them. */
    fun scope(file: PsiFile): Scope = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(
            build(file), PsiModificationTracker.MODIFICATION_COUNT,
            ProjectRootModificationTracker.getInstance(file.project), vfsChanges,
        )
    }

    /** Every visible local or imported component, including metadata-only exports without sources. */
    fun all(file: PsiFile): List<Decl> = scope(file).declarations

    /** The original declaration for [tag], or null for native tags, invalid imports, or unavailable sources. */
    fun find(file: PsiFile, tag: String): PsiElement? = all(file).firstOrNull { it.tag == tag }?.navElement

    /** Literal and bound attribute completion share the same resolved component contract. */
    fun propsOf(file: PsiFile, tag: String): List<String> = all(file).firstOrNull { it.tag == tag }?.props.orEmpty()

    private fun build(file: PsiFile): Scope {
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return Scope(localDeclarations(file), emptyList())
        val manager = PsiManager.getInstance(file.project)
        val sourceRoot = sourceRoot(file)
        val localFiles = FileTypeIndex.getFiles(BmlFileType, GlobalSearchScope.moduleScope(module))
            .filter { sourceRoot == null || VfsUtilCore.isAncestor(sourceRoot, it, false) }
            .mapNotNull(manager::findFile).filter { it.name != "imports.bml" }
        val locals = (localFiles + file).distinctBy { it.virtualFile ?: it }.flatMap(::localDeclarations)
        val roots = BmlLibraryRoots.collect(module)
        val problems = roots.problems.mapTo(mutableListOf()) { problem(file, it) }
        val globals = sourceRoot?.findChild("imports.bml")?.let(manager::findFile)?.takeIf { it != file }
        fun imports(source: PsiFile, importsOnly: Boolean): BmlImportResolver.ParsedImports {
            val parsed = SourceParser.parse(source.text)
            val result = BmlImportResolver.parse(source.virtualFile?.url ?: source.name, parsed.document, importsOnly)
            // Incomplete page markup must not hide otherwise valid imports while completing a tag.
            if (importsOnly) parsed.diagnostics.forEach {
                problems += Problem(source, range(source, it.span.startOffset, it.span.endOffset), it.message)
            }
            return result
        }
        val globalImports = globals?.let { imports(it, true) }
        val fileImports = imports(file, file.name == "imports.bml")
        val resolution = try {
            BmlImportResolver(roots.libraries.map { it.dependency }).resolve(
                globalImports, fileImports, locals.mapTo(mutableSetOf()) { it.tag },
            )
        } catch (e: IllegalArgumentException) {
            problems += problem(file, e.message ?: "Invalid BML dependency graph")
            return Scope(locals, problems)
        }
        resolution.problems.forEach {
            val owner = if (it.source == globalImports?.source) globals else file
            problems += Problem(owner, range(owner, it.diagnostic.span.startOffset, it.diagnostic.span.endOffset), it.diagnostic.message)
        }
        if (problems.isNotEmpty()) return Scope(locals, problems)
        val libraries = roots.libraries.associateBy { it.dependency.manifest.library }
        val imported = resolution.bindings.map { (tag, binding) ->
            val library = libraries.getValue(binding.dependency.manifest.library)
            val manifest = library.dependency.manifest
            val component = binding.component
            val candidates = library.sources.mapNotNull { root ->
                root.findFileByRelativePath(component.source.entry)
                    ?: if (library.projectSource) root.findFileByRelativePath(component.source.entry.removePrefix("bml/")) else null
            }
            val matching = candidates.firstOrNull { candidate ->
                try {
                    ContentDigest.sha256(candidate.contentsToByteArray()) == component.source.digest
                } catch (_: IOException) {
                    false
                }
            }
            val target = matching?.let(manager::findFile)?.let(::localDeclarations)
                ?.singleOrNull { it.tag == component.tag }?.navElement
            val message = if (target == null) {
                "BML sources for ${manifest.library}:${manifest.version} are missing or do not match the binary. " +
                    "Download matching sources with Gradle and reload the project, or attach them in Project Structure > Libraries."
            } else null
            Decl(tag, target, component.props.map { it.name }, "${manifest.library}:${manifest.version}", message)
        }
        return Scope(locals + imported, problems)
    }

    private fun sourceRoot(file: PsiFile): VirtualFile? {
        val virtualFile = file.virtualFile ?: return null
        val index = ProjectFileIndex.getInstance(file.project)
        return index.getSourceRootForFile(virtualFile) ?: index.getContentRootForFile(virtualFile)
    }

    private fun problem(file: PsiFile, message: String) = Problem(file, range(file, 0, 1), message)
    private fun range(file: PsiFile, start: Int, end: Int): TextRange =
        TextRange(start.coerceIn(0, file.textLength), end.coerceIn(start.coerceIn(0, file.textLength), file.textLength))

    private fun localDeclarations(file: PsiFile): List<Decl> {
        val result = mutableListOf<Decl>()
        var node: ASTNode? = file.node.firstChildNode
        var lastAngle = ""
        var current: Pair<String, PsiElement>? = null
        var props = mutableListOf<String>()
        fun finish() {
            current?.let { (tag, target) -> result += Decl(tag, target, props.toList()) }
            current = null
            props = mutableListOf()
        }
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ANGLE -> lastAngle = node.text
                BmlTokens.TAG_KEYWORD -> when (node.text) {
                    "component" -> {
                        finish()
                        if (lastAngle != "</") literalAfter(node, "tag")?.let { current = it }
                    }
                    "prop" -> if (lastAngle != "</" && current != null) literalAfter(node, "name")?.let { props += it.first }
                }
            }
            node = node.treeNext
        }
        finish()
        return result
    }

    private fun literalAfter(keyword: ASTNode, name: String): Pair<String, PsiElement>? {
        var node: ASTNode? = keyword.treeNext
        var sawAttr = false
        while (node != null) {
            when (node.elementType) {
                BmlTokens.ANGLE -> return null
                BmlTokens.ATTR_NAME -> sawAttr = node.text == name
                BmlElementTypes.ATTR_VALUE_HOST -> if (sawAttr) {
                    val raw = node.text
                    if (raw.length >= 2 && raw.first() in "\"'" && raw.last() == raw.first()) {
                        return raw.substring(1, raw.length - 1) to node.psi
                    }
                    return null
                }
            }
            node = node.treeNext
        }
        return null
    }
}
