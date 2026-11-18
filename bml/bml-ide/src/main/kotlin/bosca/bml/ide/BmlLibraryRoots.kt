package bosca.bml.ide

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryDependency
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Associates resolved binary metadata with its own source attachments, never a project-wide source search. */
object BmlLibraryRoots {
    data class Library(val dependency: BmlLibraryDependency, val sources: List<VirtualFile>, val projectSource: Boolean)
    data class Result(val libraries: List<Library>, val problems: List<String>)

    /** Compile dependencies retain the binary/source association supplied by the IDE's module model. */
    fun collect(module: Module): Result {
        val libraries = mutableListOf<Library>()
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun read(root: VirtualFile, sources: List<VirtualFile>, projectSource: Boolean) {
            val metadata = root.findFileByRelativePath(BmlLibraryArtifacts.MANIFEST_PATH) ?: return
            if (!seen.add(metadata.url)) return
            try {
                val manifest = BmlLibraryArtifacts.decode(VfsUtilCore.loadText(metadata))
                libraries += Library(BmlLibraryDependency(File(root.presentableUrl), manifest), sources, projectSource)
            } catch (e: IOException) {
                problems += "Cannot read BML library metadata in ${root.presentableUrl}: ${e.message}"
            } catch (e: SerializationException) {
                problems += "Invalid BML library metadata in ${root.presentableUrl}: ${e.message}"
            } catch (e: IllegalArgumentException) {
                problems += "Invalid BML library metadata in ${root.presentableUrl}: ${e.message}"
            }
        }
        val entries = OrderEnumerator.orderEntries(module).recursively().compileOnly()
        entries.forEachLibrary { library ->
            val sources = library.getFiles(OrderRootType.SOURCES).toList()
            library.getFiles(OrderRootType.CLASSES).forEach { read(it, sources, false) }
            true
        }
        entries.forEachModule { dependency ->
            if (dependency != module) {
                val sources = ModuleRootManager.getInstance(dependency).sourceRoots.toList()
                OrderEnumerator.orderEntries(dependency).withoutSdk().withoutLibraries().withoutDepModules()
                    .classes().roots.forEach { read(it, sources, true) }
            }
            true
        }
        return Result(libraries, problems)
    }
}
