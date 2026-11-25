package bosca.cli.harness.egress

/** A proxy message head that cannot be accepted; [status] is the HTTP status to answer with. */
internal class ProxyHeadException(val status: Int, message: String) : Exception(message)
