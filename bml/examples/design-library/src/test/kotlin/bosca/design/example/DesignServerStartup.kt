package bosca.design.example

private val boundPortMessage = Regex(
    """(?m)^\d{2}:\d{2}:\d{2} INFO +bosca\.server\.netty\.NettyServerEngine - Bosca server started on port (\d+) with .+$""",
)

/** Reads this child's post-bind message rather than accepting a connection to an unowned port. */
internal fun designBoundPort(log: String): Int? = boundPortMessage.find(log)?.groupValues?.get(1)
    ?.toIntOrNull()?.takeIf { it in 1..65535 }
