package bosca.analytics.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal data class AnalyticsHooks(
    val screen: IrSimpleFunctionSymbol?,
    val action: IrSimpleFunctionSymbol?,
    val nullableAction: IrSimpleFunctionSymbol?,
    val booleanChange: IrSimpleFunctionSymbol?,
    val nullableBooleanChange: IrSimpleFunctionSymbol?,
    val inputModifier: IrSimpleFunctionSymbol?,
    val visibilityModifier: IrSimpleFunctionSymbol?,
    val navigation: IrSimpleFunctionSymbol?,
    val scroll: IrSimpleFunctionSymbol?,
    val lazyList: IrSimpleFunctionSymbol?,
)

internal fun IrPluginContext.analyticsHooks(file: IrFile): AnalyticsHooks {
    val finder = finderForSource(file)
    return AnalyticsHooks(
        screen = finder.findFunctions(SCREEN_HOOK).singleOrNull(),
        action = finder.findFunctions(ACTION_HOOK).singleOrNull(),
        nullableAction = finder.findFunctions(NULLABLE_ACTION_HOOK).singleOrNull(),
        booleanChange = finder.findFunctions(BOOLEAN_CHANGE_HOOK).singleOrNull(),
        nullableBooleanChange = finder.findFunctions(NULLABLE_BOOLEAN_CHANGE_HOOK).singleOrNull(),
        inputModifier = finder.findFunctions(INPUT_MODIFIER_HOOK).singleOrNull(),
        visibilityModifier = finder.findFunctions(VISIBILITY_MODIFIER_HOOK).singleOrNull(),
        navigation = finder.findFunctions(NAVIGATION_HOOK).singleOrNull(),
        scroll = finder.findFunctions(SCROLL_HOOK).singleOrNull(),
        lazyList = finder.findFunctions(LAZY_LIST_HOOK).singleOrNull(),
    )
}

private val SCREEN_HOOK = CallableId(FqName("bosca.analytics.compose"), Name.identifier("AutoInstrumentedScreen"))
private val ACTION_HOOK = CallableId(FqName("bosca.analytics.compose"), Name.identifier("rememberAutoInstrumentedAction"))
private val NULLABLE_ACTION_HOOK =
    CallableId(FqName("bosca.analytics.compose"), Name.identifier("rememberAutoInstrumentedNullableAction"))
private val BOOLEAN_CHANGE_HOOK =
    CallableId(FqName("bosca.analytics.compose"), Name.identifier("rememberAutoInstrumentedBooleanChange"))
private val NULLABLE_BOOLEAN_CHANGE_HOOK =
    CallableId(FqName("bosca.analytics.compose"), Name.identifier("rememberAutoInstrumentedNullableBooleanChange"))
private val INPUT_MODIFIER_HOOK =
    CallableId(FqName("bosca.analytics.compose"), Name.identifier("autoInstrumentedInputModifier"))
private val VISIBILITY_MODIFIER_HOOK =
    CallableId(FqName("bosca.analytics.compose"), Name.identifier("autoInstrumentedVisibilityModifier"))
private val NAVIGATION_HOOK =
    CallableId(FqName("bosca.analytics.compose"), Name.identifier("AutoInstrumentedNavigation"))
private val SCROLL_HOOK = CallableId(FqName("bosca.analytics.compose"), Name.identifier("autoInstrumentedScrollState"))
private val LAZY_LIST_HOOK = CallableId(FqName("bosca.analytics.compose"), Name.identifier("autoInstrumentedLazyListState"))
