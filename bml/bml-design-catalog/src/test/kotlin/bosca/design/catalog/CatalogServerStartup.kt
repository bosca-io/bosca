package bosca.design.catalog

private val boundPortMessage = Regex(
    """(?m)^\d{2}:\d{2}:\d{2} INFO +bosca\.server\.netty\.NettyServerEngine - Bosca server started on port (\d+) with .+$""",
)

/** Reads the post-bind message from this child's private log, rather than probing an unowned port. */
internal fun catalogBoundPort(log: String): Int? = boundPortMessage.find(log)?.groupValues?.get(1)
    ?.toIntOrNull()?.takeIf { it in 1..65535 }
