package bosca.git.ui

import bosca.bml.render.currentRenderContext

/** Loads repository search independently of a remembered owner filter. */
suspend fun searchPage(): SearchModel {
    val ctx = currentRenderContext()
    val model = SearchModel()
    model.load(
        ctx.query["repo"].orEmpty(),
        ctx.query["q"].orEmpty(),
        ctx.query["mode"].orEmpty(),
        ctx.query["ref"].orEmpty(),
        ctx,
    )
    return model
}
