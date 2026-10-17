package bosca.cli.artifacts

import bosca.cli.config.writePrivateFileAtomically
import java.io.File
import java.net.URI

/** Finds npm's user configuration, including npm's lowercase environment override. */
internal fun npmUserConfigFile(environment: Map<String, String> = System.getenv()): File {
    val home = environment["HOME"] ?: System.getProperty("user.home")
    val configured = environment["npm_config_userconfig"]?.takeIf { it.isNotBlank() }
        ?: environment["NPM_CONFIG_USERCONFIG"]?.takeIf { it.isNotBlank() }
        ?: return File(home, ".npmrc")
    return if (configured.startsWith("~/")) File(home, configured.removePrefix("~/")) else File(configured)
}

/** Updates only the Bosca scope and this registry's npm token, preserving other settings. */
internal fun configureNpmRegistry(file: File, registry: URI, token: String) {
    val target = file.canonicalFile
    val settings = linkedMapOf(
        "@bosca:registry" to registry.resolve("/npm/").toString(),
        "//${registry.rawAuthority}/npm/:_authToken" to token,
    )
    val existing = if (target.exists()) target.readText() else ""
    val newline = if ("\r\n" in existing) "\r\n" else "\n"
    val retained = existing.lineSequence().filter { line ->
        line.substringBefore('=').trim() !in settings
    }.joinToString(newline)
    val contents = buildString {
        append(retained)
        if (isNotEmpty() && !endsWith(newline)) append(newline)
        settings.forEach { (key, value) -> append(key).append('=').append(value).append(newline) }
    }
    writePrivateFileAtomically(target, contents)
}
