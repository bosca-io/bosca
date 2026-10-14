package bosca.git.ui


/** Validates the repository-relative path accepted by file write actions. */
internal fun repositoryFilePath(value: String): String {
    val path = value.trim().trimStart('/')
    require(
        path.isNotBlank() &&
            '\u0000' !in path &&
            path.split('/').none { it == ".." || it == "." || it.isBlank() }
    ) {
        "Enter a repository-relative file path without empty, . or .. segments."
    }
    return path
}
