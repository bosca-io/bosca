package bosca.analytics.compiler

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

internal class FunctionInstrumentation(
    val function: IrSimpleFunction,
    val composable: Boolean,
    val ignored: Boolean,
    val metadata: IrAnnotation?,
    val screen: IrAnnotation?,
) {
    private var controlIndex = 0
    private var scrollIndex = 0
    private var visibilityClaimed = false

    val functionName: String = function.fqNameWhenAvailable?.asString() ?: function.name.asString()
    private val inferredPath: String = function.name.asString().toPagePath()
    val screenId: String = screen.stringArgument(0).orEmpty().ifBlank {
        metadata.stringArgument(0).orEmpty().ifBlank { inferredPath }
    }
    val pagePath: String = if (screen == null) "" else {
        screen.stringArgument(1).orEmpty().ifBlank { screenId.asPagePath() }
    }
    val pageTitle: String = screen.stringArgument(2).orEmpty()

    fun nextControlId(control: String): String {
        controlIndex++
        val prefix = metadata.stringArgument(0).orEmpty().ifBlank { screenId }
        return "$prefix/${control.lowercase()}/$controlIndex"
    }

    fun nextScrollId(): String {
        scrollIndex++
        return "$screenId/scroll/$scrollIndex"
    }

    fun controlType(fallback: String): String = metadata.stringArgument(1).orEmpty()
        .takeUnless { it.isBlank() || it == "function" }
        ?: fallback.lowercase()

    fun claimVisibility(): Boolean {
        if (visibilityClaimed || metadata.booleanArgument(2) != true) return false
        visibilityClaimed = true
        return true
    }

    val visibilityThreshold: Float
        get() = metadata.floatArgument(3) ?: 0.5f

    val visibilityDwellMillis: Long
        get() = metadata.longArgument(4) ?: 1_000L

    fun isScreen(): Boolean = screen != null
}

private fun String.asPagePath(): String = if (startsWith('/')) this else "/$this"

private fun String.toPagePath(): String {
    val route = removeSuffix("Screen").removeSuffix("Page").removeSuffix("Route")
    return buildString {
        append('/')
        route.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('-')
            append(character.lowercaseChar())
        }
    }
}
