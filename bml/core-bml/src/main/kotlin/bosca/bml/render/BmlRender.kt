package bosca.bml.render

/** Run a generated page render function and collect its HTML. */
suspend fun renderToString(render: suspend (RenderContext) -> Unit): String {
    val ctx = RenderContext()
    render(ctx)
    return ctx.writer.toString()
}
