@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package bosca.analytics.compiler

import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.name.FqName

internal val AUTO_ANNOTATION = FqName("bosca.analytics.AutoInstrument")
internal val SCREEN_ANNOTATION = FqName("bosca.analytics.AnalyticsScreen")
internal val IGNORE_ANNOTATION = FqName("bosca.analytics.AnalyticsIgnore")
internal val COMPOSABLE_ANNOTATION = FqName("androidx.compose.runtime.Composable")
internal const val COMPOSE_RUNTIME_PACKAGE = "bosca.analytics.compose."

internal enum class CallbackKind {
    ACTION,
    BOOLEAN_CHANGE,
}

internal data class CallbackTarget(
    val parameter: String,
    val action: String,
    val elementType: String,
    val kind: CallbackKind = CallbackKind.ACTION,
)

internal fun IrCall.parameterIndex(name: String): Int? = symbol.owner.parameters
    .indexOfFirst { it.name.asString() == name }
    .takeIf { it >= 0 }

internal fun String.callbackTargets(): List<CallbackTarget> {
    if (this == "androidx.compose.foundation.combinedClickable") {
        return listOf(
            CallbackTarget("onClick", "click", "click"),
            CallbackTarget("onLongClick", "long_click", "long_click"),
            CallbackTarget("onDoubleClick", "double_click", "double_click"),
        )
    }
    if (this == "androidx.compose.foundation.toggleable") {
        return listOf(CallbackTarget("onValueChange", "toggle", "toggle", CallbackKind.BOOLEAN_CHANGE))
    }
    if (this == "androidx.compose.foundation.selectable") {
        return listOf(CallbackTarget("onClick", "select", "selection"))
    }
    val function = substringAfterLast('.')
    return when {
        function in BOOLEAN_CONTROLS && isSupportedComposePackage() -> listOf(
            CallbackTarget("onCheckedChange", "toggle", function.lowercase(), CallbackKind.BOOLEAN_CHANGE),
        )
        function in SLIDER_CONTROLS && isSupportedComposePackage() -> listOf(
            CallbackTarget("onValueChangeFinished", "value_change_finished", function.lowercase()),
        )
        function in CLICK_CONTROLS && isSupportedComposePackage() -> listOf(
            CallbackTarget("onClick", "click", function.lowercase()),
        )
        this == "androidx.compose.foundation.clickable" -> listOf(
            CallbackTarget("onClick", "click", "click"),
        )
        else -> emptyList()
    }
}

internal fun String.isInputControl(): Boolean =
    substringAfterLast('.') in INPUT_CONTROLS && isSupportedComposePackage()

internal fun String.isVisibilityCandidate(): Boolean = startsWith("androidx.compose.")

internal fun String.isNavigation3Display(): Boolean = this == "androidx.navigation3.ui.NavDisplay"

internal fun String.isScrollModifier(): Boolean =
    this == "androidx.compose.foundation.verticalScroll" || this == "androidx.compose.foundation.horizontalScroll"

internal fun String.isLazyList(): Boolean =
    this == "androidx.compose.foundation.lazy.LazyColumn" || this == "androidx.compose.foundation.lazy.LazyRow"

internal fun IrAnnotation?.stringArgument(index: Int): String? =
    (this?.arguments?.getOrNull(index) as? IrConst)?.value as? String

internal fun IrAnnotation?.booleanArgument(index: Int): Boolean? =
    (this?.arguments?.getOrNull(index) as? IrConst)?.value as? Boolean

internal fun IrAnnotation?.floatArgument(index: Int): Float? =
    ((this?.arguments?.getOrNull(index) as? IrConst)?.value as? Number)?.toFloat()

internal fun IrAnnotation?.longArgument(index: Int): Long? =
    ((this?.arguments?.getOrNull(index) as? IrConst)?.value as? Number)?.toLong()

private fun String.isSupportedComposePackage(): Boolean = COMPOSE_PACKAGES.any(::startsWith)

private val COMPOSE_PACKAGES = setOf(
    "androidx.compose.material.",
    "androidx.compose.material3.",
    "androidx.compose.foundation.",
)

private val CLICK_CONTROLS = setOf(
    "Button",
    "TextButton",
    "OutlinedButton",
    "ElevatedButton",
    "FilledTonalButton",
    "IconButton",
    "FilledIconButton",
    "FilledTonalIconButton",
    "OutlinedIconButton",
    "FloatingActionButton",
    "ExtendedFloatingActionButton",
    "DropdownMenuItem",
    "NavigationBarItem",
    "NavigationRailItem",
    "NavigationDrawerItem",
    "Tab",
    "LeadingIconTab",
    "FilterChip",
    "InputChip",
    "AssistChip",
    "SuggestionChip",
    "Card",
    "ElevatedCard",
    "OutlinedCard",
    "RadioButton",
)

private val BOOLEAN_CONTROLS = setOf(
    "Checkbox",
    "Switch",
    "IconToggleButton",
    "FilledIconToggleButton",
    "FilledTonalIconToggleButton",
    "OutlinedIconToggleButton",
)

private val SLIDER_CONTROLS = setOf("Slider", "RangeSlider")

private val INPUT_CONTROLS = setOf(
    "TextField",
    "OutlinedTextField",
    "BasicTextField",
    "BasicSecureTextField",
    "SearchBar",
    "DockedSearchBar",
)
