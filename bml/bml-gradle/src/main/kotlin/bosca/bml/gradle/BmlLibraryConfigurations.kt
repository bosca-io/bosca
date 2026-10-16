package bosca.bml.gradle

import groovy.json.JsonException
import groovy.json.JsonSlurper
import java.io.File

/** Collects resolver configuration candidates, including missing files and inherited JSON configs. */
internal class BmlLibraryConfigurations(
    private val dependencyLookup: (File) -> Unit,
) {
    private val inputs = sortedSetOf<String>()
    private val parsed = mutableSetOf<File>()
    val files: List<File> get() = inputs.map(::File)

    fun ancestors(directory: File) {
        generateSequence(directory) { it.parentFile }.forEach {
            track(it.resolve("package.json"))
            local(it)
        }
    }

    fun local(directory: File, includeMissing: Boolean = true) {
        for (name in listOf("tsconfig.json", "jsconfig.json")) {
            val file = directory.resolve(name)
            if (includeMissing || file.isFile) config(file)
        }
    }

    private fun track(file: File): File {
        val path = file.absoluteFile.normalize()
        // A directory candidate is not a config file and must not snapshot a whole checkout.
        if (!path.isDirectory) inputs.add(path.path)
        val real = path.canonicalFile
        if (!real.isDirectory) inputs.add(real.path)
        return real
    }

    private fun config(file: File) {
        val real = track(file)
        if (!real.isFile || !parsed.add(real)) return
        dependencyLookup(real.parentFile)
        val value = json(real)["extends"]
        val bases = if (value is List<*>) value.filterIsInstance<String>() else listOfNotNull(value as? String)
        for (base in bases) {
            if (File(base).isAbsolute || base == "." || base == ".." || base.startsWith("./") || base.startsWith("../") ||
                base.startsWith(".\\") || base.startsWith("..\\")) {
                val path = real.parentFile.resolve(if (base == "." || base == "..") "$base/tsconfig.json" else base)
                config(path)
                if (!path.name.endsWith(".json")) config(File("${path.path}.json"))
            } else {
                // Keep all candidate/conditional targets as inputs; esbuild selects the config.
                val parts = base.split('/')
                val packageName = parts.take(if (base.startsWith("@")) 2 else 1).joinToString("/")
                val subpath = "." + base.removePrefix(packageName)
                generateSequence(real.parentFile) { it.parentFile }.forEach { directory ->
                    val modules = directory.resolve("node_modules")
                    val root = modules.resolve(packageName)
                    val metadata = track(root.resolve("package.json"))
                    if (metadata.isFile) {
                        val pkg = json(metadata)
                        (pkg["tsconfig"] as? String)?.let { candidates(root.resolve(it)) }
                        exportTargets(pkg["exports"], subpath).forEach { config(root.resolve(it)) }
                    }
                    candidates(modules.resolve(base))
                }
            }
        }
    }

    private fun candidates(path: File) {
        config(path.resolve("tsconfig.json"))
        config(path)
        config(File("${path.path}.json"))
    }

    private fun exportTargets(value: Any?, subpath: String): List<String> = when (value) {
        is String -> listOf(value)
        is List<*> -> value.flatMap { exportTargets(it, subpath) }
        is Map<*, *> -> value.flatMap { (key, target) ->
            when {
                key !is String -> emptyList()
                !key.startsWith(".") || key == subpath -> exportTargets(target, subpath)
                '*' in key && subpath.startsWith(key.substringBefore('*')) && subpath.endsWith(key.substringAfter('*')) -> {
                    val start = key.substringBefore('*').length
                    val end = subpath.length - key.substringAfter('*').length
                    if (end < start) emptyList() else exportTargets(target, subpath).map { it.replace("*", subpath.substring(start, end)) }
                }
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun json(file: File): Map<*, *> {
        val text = file.readText().removePrefix("\uFEFF")
        // JSONC comments and trailing commas are legal in tsconfig/jsconfig. Preserve quoted text.
        val uncommented = JSON_TOKENS.replace(text) { if (it.value.startsWith('"')) it.value else " " }
        val normalized = TRAILING_COMMAS.replace(uncommented) { if (it.value.startsWith('"')) it.value else "" }
        if (normalized.isBlank()) return emptyMap<Any, Any>()
        return try {
            JsonSlurper().parseText(normalized) as? Map<*, *> ?: emptyMap<Any, Any>()
        } catch (_: JsonException) {
            // Discovery also visits unused configs. Keep invalid files as inputs; esbuild owns
            // syntax diagnostics for the configurations selected by the actual module graph.
            emptyMap<Any, Any>()
        }
    }

    private companion object {
        const val JSON_STRING = "\"(?:\\\\.|[^\"\\\\])*\""
        val JSON_TOKENS = Regex("$JSON_STRING|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/")
        val TRAILING_COMMAS = Regex("$JSON_STRING|,(?=\\s*[}\\]])")
    }
}
