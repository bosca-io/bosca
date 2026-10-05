@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package bosca.analytics.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

internal class AnalyticsTransformer(
    private val context: IrPluginContext,
    private val hooks: AnalyticsHooks,
    private val messages: MessageCollector,
    private val verbose: Boolean,
) : IrElementTransformerVoid() {
    private val functions = mutableListOf<FunctionInstrumentation>()
    private val calls = ComposeCallInstrumentation(context, hooks, ::missingComposeRuntime, ::log)

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        val parent = declaration.parentClassOrNull
        val ignored = declaration.hasAnnotation(IGNORE_ANNOTATION) || parent?.hasAnnotation(IGNORE_ANNOTATION) == true
        val instrumentation = FunctionInstrumentation(
            function = declaration,
            composable = declaration.hasAnnotation(COMPOSABLE_ANNOTATION),
            ignored = ignored || declaration.isRuntimeHook(),
            metadata = declaration.getAnnotation(AUTO_ANNOTATION) ?: parent?.getAnnotation(AUTO_ANNOTATION),
            screen = declaration.getAnnotation(SCREEN_ANNOTATION),
        )
        functions += instrumentation
        val result = super.visitSimpleFunction(declaration)
        functions.removeAt(functions.lastIndex)

        if (!instrumentation.ignored && instrumentation.composable && instrumentation.isScreen()) {
            instrumentScreen(instrumentation)
        }
        return result
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val current = functions.lastOrNull()
        val originalName = expression.symbol.owner.fqNameWhenAvailable?.asString()
        if (current != null && current.composable && !current.ignored && originalName != null) {
            calls.prepare(expression, current, originalName)
        }
        val call = super.visitCall(expression) as IrCall
        val instrumentation = functions.lastOrNull() ?: return call
        if (!instrumentation.composable || instrumentation.ignored) return call
        val fqName = call.symbol.owner.fqNameWhenAvailable?.asString() ?: return call
        return calls.instrument(call, instrumentation, fqName)
    }

    private fun instrumentScreen(instrumentation: FunctionInstrumentation) {
        val hook = hooks.screen ?: return missingComposeRuntime("screen")
        val function = instrumentation.function
        val body = function.body ?: return
        val builder = IrBlockBodyBuilder(
            context = context,
            scope = Scope(function.symbol),
            startOffset = function.startOffset,
            endOffset = function.endOffset,
        )
        val call = builder.irCall(hook).apply {
            arguments[0] = builder.irString(instrumentation.screenId)
            arguments[1] = builder.irString(instrumentation.pagePath)
            arguments[2] = builder.irString(instrumentation.pageTitle)
            arguments[3] = builder.irString(instrumentation.functionName)
        }
        when (body) {
            is IrBlockBody -> body.statements.add(0, call)
            is IrExpressionBody -> function.body = builder.blockBody {
                +call
                +irReturn(body.expression)
            }
            else -> return
        }
        log("tracked screen ${instrumentation.functionName}")
    }

    private fun IrSimpleFunction.isRuntimeHook(): Boolean =
        fqNameWhenAvailable?.asString()?.startsWith(COMPOSE_RUNTIME_PACKAGE) == true

    private fun missingComposeRuntime(feature: String) {
        messages.report(CompilerMessageSeverity.ERROR, "Analytics Compose $feature instrumentation runtime is unavailable")
    }

    private fun log(message: String) {
        if (verbose) messages.report(CompilerMessageSeverity.LOGGING, "Analytics: $message")
    }
}
