package bosca.cli.swarm

/** Adds Atlas pages and file proxies without consuming Git clone, LFS, or registry protocol paths. */
internal fun atlasUiPathPattern(): String {
    val id = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    return listOf(
        gitUiPathPattern().removePrefix("^(").removeSuffix(")$"),
        "/favicon[.]svg",
        "/artifacts/namespaces(/$id)?/?",
        "/artifacts/repositories(/($id|upload))?/?",
        "/artifacts/settings/permissions/?",
        "/artifacts/settings/repositories/$id/?",
        "/artifacts/runs/$id/?",
        "/_artifacts(/.*)?",
    ).joinToString("|", prefix = "^(", postfix = ")$")
}

/** Matches Git transport, raw-file, CI artifact and webhook paths hosted by the optional Git backend. */
internal fun gitBackendPathPattern(): String =
    "^(/[^/]+/[^/]+[.]git(/.*)?|/[^/]+/[^/]+/raw(/.*)?|/ci(/.*)?|/api/webhooks(/.*)?)$"
