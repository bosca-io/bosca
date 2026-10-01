package bosca.ai.kit.tools

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.agents.testing.tools.mockLLMAnswer
import ai.koog.agents.testing.tools.mockLLMToolCall
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.security.service.AuthenticationContext
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * DECISIVE experiment: does a [KitToolContext] coroutine element set around `agent.run(...)`
 * actually reach a real [KitTool.execute] inside the agent's tool-calling loop? Koog dispatches
 * the run onto an LLM dispatcher and executes tools in a supervisorScope/async — both of which
 * preserve coroutine-context elements unless Koog detaches the scope. This proves it one way or
 * the other, so the orchestrator's identity threading rests on fact, not assumption.
 */
class ContextPropagationTest {

    /** A real KitTool (not mocked) that records the [AuthenticationContext] it resolved. */
    class ProbeTool : KitTool<ProbeTool.Args, String>(
        Args.serializer(), String.serializer(), name = "probe", description = "records the resolved auth",
    ) {
        @Serializable
        data class Args(val q: String = "")

        @Volatile
        var seen: AuthenticationContext? = null

        override suspend fun execute(authentication: AuthenticationContext, input: Args): String {
            seen = authentication
            return "probed-ok"
        }
    }

    @Test
    fun `KitToolContext propagates through agent run into a real tool execute`() = runTest {
        val tool = ProbeTool()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Tools, LLMCapability.Completion))

        // Mock ONLY the LLM's decisions (call the tool, then answer). The tool itself runs for real,
        // so its execute() must resolve auth from the ambient KitToolContext.
        val executor = getMockExecutor {
            mockLLMToolCall(tool, ProbeTool.Args("x")) onRequestContains "go"
            mockLLMAnswer("finished") onRequestContains "probed-ok"
        }

        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(prompt = prompt("probe") { system("you are a probe") }, model = model, maxAgentIterations = 10),
            strategy = singleRunStrategy(),
            toolRegistry = ToolRegistry { tool(tool) },
        )

        withContext(KitToolContext(auth)) { agent.run("go") }

        assertSame(auth, tool.seen, "the tool must have resolved the caller's auth from KitToolContext")
    }
}
