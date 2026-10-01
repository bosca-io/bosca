@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package bosca.analytics.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irLong
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.isNullable

internal class ComposeCallInstrumentation(
    private val context: IrPluginContext,
    private val hooks: AnalyticsHooks,
    private val missingRuntime: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    fun prepare(call: IrCall, instrumentation: FunctionInstrumentation, fqName: String) {
        if (fqName.isVisibilityCandidate() && call.parameterIndex("modifier") != null && instrumentation.claimVisibility()) {
            wrapVisibilityModifier(call, instrumentation)
        }
    }

    fun instrument(call: IrCall, instrumentation: FunctionInstrumentation, fqName: String): IrExpression {
        wrapCallbacks(call, instrumentation, fqName)
        if (fqName.isInputControl()) wrapInputModifier(call, instrumentation, fqName)
        when {
            fqName.isScrollModifier() -> wrapScrollState(call, instrumentation, fqName)
            fqName.isLazyList() -> provideLazyListState(call, instrumentation, fqName)
        }
        return if (fqName.isNavigation3Display()) instrumentNavigation(call, instrumentation) else call
    }

    private fun wrapCallbacks(call: IrCall, instrumentation: FunctionInstrumentation, fqName: String) {
        val targets = fqName.callbackTargets().mapNotNull { target ->
            val index = call.parameterIndex(target.parameter) ?: return@mapNotNull null
            val callback = call.arguments.getOrNull(index) ?: return@mapNotNull null
            if (callback is IrConst && callback.value == null) return@mapNotNull null
            Callback(index, callback, target)
        }
        if (targets.isEmpty()) return
        val id = instrumentation.nextControlId(fqName.substringAfterLast('.'))
        targets.forEach { callback ->
            val nullable = call.symbol.owner.parameters[callback.index].type.isNullable()
            val hook = callback.target.hook(nullable) ?: return@forEach missingRuntime(callback.target.action)
            if (callback.expression is IrCall && callback.expression.symbol == hook) return@forEach
            val builder = builder(instrumentation, call)
            call.arguments[callback.index] = builder.irCall(hook).apply {
                arguments[0] = callback.expression
                arguments[1] = builder.irString(id)
                arguments[2] = builder.irString(instrumentation.controlType(callback.target.elementType))
                var argument = 3
                if (callback.target.kind == CallbackKind.ACTION) {
                    arguments[argument++] = builder.irString(callback.target.action)
                }
                arguments[argument++] = builder.irString(instrumentation.functionName)
                arguments[argument++] = builder.irString(instrumentation.pagePath)
                arguments[argument] = builder.irString(instrumentation.pageTitle)
            }
            log("wrapped ${callback.target.parameter} on $fqName in ${instrumentation.functionName}")
        }
    }

    private fun CallbackTarget.hook(nullable: Boolean): IrSimpleFunctionSymbol? = when (kind) {
        CallbackKind.ACTION -> if (nullable) hooks.nullableAction else hooks.action
        CallbackKind.BOOLEAN_CHANGE -> if (nullable) hooks.nullableBooleanChange else hooks.booleanChange
    }

    private fun wrapInputModifier(call: IrCall, instrumentation: FunctionInstrumentation, fqName: String) {
        val hook = hooks.inputModifier ?: return missingRuntime("input")
        val index = call.parameterIndex("modifier") ?: return
        val modifier = call.arguments.getOrNull(index)
        if (modifier is IrCall && modifier.symbol == hook) return
        val builder = builder(instrumentation, call)
        call.arguments[index] = builder.irCall(hook).apply {
            arguments[0] = modifier ?: builder.irNull()
            arguments[1] = builder.irString(instrumentation.nextControlId(fqName.substringAfterLast('.')))
            arguments[2] = builder.irString(instrumentation.functionName)
            arguments[3] = builder.irString(instrumentation.pagePath)
            arguments[4] = builder.irString(instrumentation.pageTitle)
        }
        log("tracked input $fqName in ${instrumentation.functionName}")
    }

    private fun wrapVisibilityModifier(call: IrCall, instrumentation: FunctionInstrumentation) {
        val hook = hooks.visibilityModifier ?: return missingRuntime("visibility")
        val index = call.parameterIndex("modifier") ?: return
        val modifier = call.arguments.getOrNull(index)
        val builder = builder(instrumentation, call)
        call.arguments[index] = builder.irCall(hook).apply {
            arguments[0] = modifier ?: builder.irNull()
            arguments[1] = builder.irString(instrumentation.screenId)
            arguments[2] = builder.irString(instrumentation.controlType("content"))
            arguments[3] = builder.irString(instrumentation.functionName)
            arguments[4] = builder.irString(instrumentation.pagePath)
            arguments[5] = builder.irString(instrumentation.pageTitle)
            arguments[6] = IrConstImpl.float(
                call.startOffset,
                call.endOffset,
                context.irBuiltIns.floatType,
                instrumentation.visibilityThreshold,
            )
            arguments[7] = builder.irLong(instrumentation.visibilityDwellMillis)
        }
        log("tracked visibility in ${instrumentation.functionName}")
    }

    private fun wrapScrollState(call: IrCall, instrumentation: FunctionInstrumentation, fqName: String) {
        val hook = hooks.scroll ?: return missingRuntime("scroll")
        val index = call.parameterIndex("state") ?: return
        val state = call.arguments.getOrNull(index) ?: return
        val axis = if (fqName.substringAfterLast('.').startsWith("horizontal")) "horizontal" else "vertical"
        val builder = builder(instrumentation, call)
        call.arguments[index] = builder.irCall(hook).apply {
            arguments[0] = state
            arguments[1] = builder.irString(instrumentation.nextScrollId())
            arguments[2] = builder.irString(axis)
            arguments[3] = builder.irString(instrumentation.functionName)
            arguments[4] = builder.irString(instrumentation.pagePath)
            arguments[5] = builder.irString(instrumentation.pageTitle)
        }
        log("tracked $fqName in ${instrumentation.functionName}")
    }

    private fun provideLazyListState(call: IrCall, instrumentation: FunctionInstrumentation, fqName: String) {
        val hook = hooks.lazyList ?: return missingRuntime("lazy-list")
        val index = call.parameterIndex("state") ?: return
        val state = call.arguments.getOrNull(index)
        val axis = if (fqName.substringAfterLast('.') == "LazyRow") "horizontal" else "vertical"
        val builder = builder(instrumentation, call)
        call.arguments[index] = builder.irCall(hook).apply {
            arguments[0] = state ?: builder.irNull()
            arguments[1] = builder.irString(instrumentation.nextScrollId())
            arguments[2] = builder.irString(axis)
            arguments[3] = builder.irString(instrumentation.functionName)
            arguments[4] = builder.irString(instrumentation.pagePath)
            arguments[5] = builder.irString(instrumentation.pageTitle)
        }
        log("tracked $fqName in ${instrumentation.functionName}")
    }

    private fun instrumentNavigation(call: IrCall, instrumentation: FunctionInstrumentation): IrExpression {
        val hook = hooks.navigation ?: return call.also { missingRuntime("navigation") }
        val index = call.parameterIndex("backStack") ?: return call
        val stack = call.arguments.getOrNull(index) ?: return call
        val builder = builder(instrumentation, call)
        return builder.irBlock(resultType = call.type) {
            val temporary = irTemporary(stack, nameHint = "analyticsBackStack")
            call.arguments[index] = irGet(temporary)
            +irCall(hook).apply {
                arguments[0] = irGet(temporary)
                arguments[1] = irString(instrumentation.functionName)
            }
            +call
        }.also { log("tracked Navigation 3 in ${instrumentation.functionName}") }
    }

    private fun builder(function: FunctionInstrumentation, expression: IrExpression) = DeclarationIrBuilder(
        context,
        function.function.symbol,
        expression.startOffset,
        expression.endOffset,
    )

    private data class Callback(
        val index: Int,
        val expression: IrExpression,
        val target: CallbackTarget,
    )
}
