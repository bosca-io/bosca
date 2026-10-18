package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlProviderPage
import bosca.bml.render.RenderContext
import bosca.di.ProviderRegistrar
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BmlProviderCompositionTest {
    private class Model
    private class FirstRegistrar : ProviderRegistrar {
        var calls = 0
        override fun register() {
            calls++
            provides<Model>(singleton = true) { Model() }
        }
    }
    private class AddedRegistrar : ProviderRegistrar {
        var calls = 0
        override fun register() { calls++ }
    }

    private fun page(route: String, registrars: List<ProviderRegistrar>) = object : BmlPageRenderer, BmlProviderPage {
        override val route = route
        override val providerRegistrars = registrars
        override suspend fun render(ctx: RenderContext) = Unit
    }

    @Test
    fun `installation deduplicates pages and hot swap preserves existing library singletons`() = runBlocking {
        val first = FirstRegistrar()
        val pages = listOf(page("/", listOf(first)), page("/next", listOf(first, FirstRegistrar())))
        val project = CompiledProject(name = "providers", version = "1")
        val server = BmlServer(project, pages, 0, dev = true)
        val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: 0\n".byteInputStream()))
        server.install(app)
        assertEquals(1, first.calls)
        val original = provide<Model>()
        val replacementFirst = FirstRegistrar()
        val added = AddedRegistrar()
        val replacement = BmlServer(project, listOf(page("/", listOf(replacementFirst, added))), 0, dev = true)
        val rollback = server.hotSwapFrom(replacement)
        assertEquals(0, replacementFirst.calls)
        assertEquals(1, added.calls)
        assertSame(original, provide<Model>())
        rollback()
        assertSame(original, provide<Model>())
        assertEquals(1, first.calls)
        val reintroduced = AddedRegistrar()
        server.hotSwapFrom(BmlServer(project, listOf(page("/", listOf(FirstRegistrar(), reintroduced))), 0, dev = true))
        assertEquals(0, reintroduced.calls)
        assertSame(original, provide<Model>())
    }
}
