package bosca.cli.harness.egress

/**
 * Parses an egress proxy origin such as `http://kit-egress:3128`. Only the `http` scheme with an
 * explicit port is accepted; userinfo, paths, queries and fragments are rejected.
 *
 * @throws IllegalArgumentException when the value is not a plain `http://host:port` origin.
 */
internal fun parseEgressProxyOrigin(value: String): EgressDestination {
    require(value.startsWith("http://")) { "Egress proxy must be an http://host:port origin" }
    val authority = value.removePrefix("http://")
    require(authority.none { it in "/?#@" }) { "Egress proxy must be an http://host:port origin" }
    return EgressDestination.parse(authority, requirePort = true)
}
