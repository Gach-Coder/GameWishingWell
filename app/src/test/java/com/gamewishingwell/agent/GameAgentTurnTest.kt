package com.gamewishingwell.agent

import android.content.Context
import android.content.res.AssetManager
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.LlmSettings
import com.gamewishingwell.data.SettingsRepository
import com.gamewishingwell.data.ToolCallData
import com.gamewishingwell.llm.LlmClient
import com.gamewishingwell.llm.LlmResponse
import com.gamewishingwell.llm.Protocol
import com.gamewishingwell.llm.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File

/**
 * GameAgent 回合级集成测试：脚本化 LLM（按调用序回放响应/异常）+ 桩沙箱，
 * 驱动完整回合管线（意图→识别→Agent Loop→验收→交付），验证回合所有权、
 * 异常兜底、工具降级判定与聊天历史等核心行为——这些逻辑此前只靠真机手测。
 */
class GameAgentTurnTest {

    /** 按调用序回放的脚本客户端：每个元素为一次响应（LlmResponse）或一个待抛异常。 */
    private class ScriptedLlmClient(steps: List<Any>) : LlmClient {
        override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE
        private val queue = ArrayDeque(steps)
        val calls = mutableListOf<Pair<List<com.gamewishingwell.data.ChatMessage>, List<ToolSpec>>>()

        override suspend fun streamChat(
            messages: List<com.gamewishingwell.data.ChatMessage>,
            onDelta: (String) -> Unit,
            onThinking: (String) -> Unit,
            onDone: () -> Unit,
            tools: List<ToolSpec>,
            onToolCallDelta: (Int) -> Unit
        ): LlmResponse {
            calls += messages to tools
            val step = queue.removeFirstOrNull() ?: LlmResponse("", emptyList())
            if (step is Exception) throw step
            step as LlmResponse
            onDelta(step.text)
            onDone()
            return step
        }
    }

    private class StubSmokeRunner(private val result: SmokeTestResult) : SmokeTestRunner {
        override suspend fun run(
            html: String,
            deep: Boolean,
            scenariosJson: String?,
            landscape: Boolean
        ): SmokeTestResult = result
    }

    private val validHtml = """<!DOCTYPE html><html><head><meta charset="utf-8"></head><body><script>
window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };
function restart(){}
</script></body></html>"""

    private fun jsonStr(s: String): String = Json.encodeToString(s)

    private fun newAgent(
        dir: File,
        client: LlmClient?,
        smoke: SmokeTestRunner = StubSmokeRunner(
            SmokeTestResult(passed = true, framesRun = 24, message = "smoke-ok")
        )
    ): GameAgent {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        val assets = mock(AssetManager::class.java)
        `when`(assets.open(anyString())).thenReturn(ByteArrayInputStream("<html>template</html>".toByteArray()))
        `when`(context.assets).thenReturn(assets)
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.settings).thenReturn(
            MutableStateFlow(LlmSettings(apiKey = "k", baseUrl = "http://localhost/v1", model = "m"))
        )
        `when`(settings.isConfigured()).thenReturn(true)
        AgentLog.initDir(File(dir, "logs"))
        return GameAgent(
            appContext = context,
            repository = GameRepository(context),
            settingsRepository = settings,
            injectedClientFactory = { client },
            injectedSmokeRunner = smoke
        )
    }

    @Test
    fun `回合内意外异常被兜底为如实失败而非崩溃`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-crash-${System.nanoTime()}")
        // 客户端工厂抛出非预期异常（模拟文件 IO/序列化等未覆盖路径）：
        // runTurnSafely 必须接住并 failTurn，进程不崩、会话正确收尾。
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        val assets = mock(AssetManager::class.java)
        `when`(assets.open(anyString())).thenReturn(ByteArrayInputStream("<html>t</html>".toByteArray()))
        `when`(context.assets).thenReturn(assets)
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.settings).thenReturn(
            MutableStateFlow(LlmSettings(apiKey = "k", baseUrl = "http://x", model = "m"))
        )
        `when`(settings.isConfigured()).thenReturn(true)
        val agent = GameAgent(
            appContext = context,
            repository = GameRepository(context),
            settingsRepository = settings,
            injectedClientFactory = { throw RuntimeException("client factory boom") },
            injectedSmokeRunner = StubSmokeRunner(SmokeTestResult(passed = true))
        )
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue(s.error?.contains("意外错误") == true)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `夹在纯文本轮之间的工具轮不触发兼容降级`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-b1-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                // 识别层 Lite 补丁（direct-make 路径的系统参考抽取）
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                // 第 1 轮：纯文本无代码 → nudge
                LlmResponse("我先想想"),
                // 第 2 轮：readfile（真实工具调用，但未写入）→ 无工具轮计数必须被重置
                LlmResponse(
                    "", toolCalls = listOf(ToolCallData("c-read", "readfile", """{"path":"index.html"}"""))
                ),
                // 第 3 轮：又是纯文本 → 计数只应为 1（旧实现为 2，会误判降级）
                LlmResponse("还在构思"),
                // 第 4 轮：writefile 写入合法游戏（此轮请求必须仍携带 tools——
                // 若被误降级到兼容回环，这次调用将不带任何工具）
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                // 第 5 轮：声明完成（快速档跳过沙箱/断言/自检，直接验收）
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue(s.gameGenerated)
        assertTrue(s.currentHtml?.contains("__wwDebugState") == true)
        assertTrue(s.messages.last().content.contains("共 5 轮"))
        // writefile 那次调用（第 5 个调用，0 基下标 4：识别/纯文本/readfile/纯文本/写入）
        // 仍处于工具模式——工具轮重置无工具计数是判定"模型会 function calling"的前提。
        assertEquals(6, script.calls.size)
        assertTrue("第 4 轮仍应携带工具定义（未被误降级）", script.calls[4].second.isNotEmpty())
        assertTrue(script.calls[4].first.any { it.toolCalls.isNotEmpty() && it.toolCalls.any { tc -> tc.name == "writefile" } })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `chat 回复携带最近对话历史`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-chat-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("你好！想做点什么游戏？"),
                LlmResponse("我可以根据一句话生成 HTML5 小游戏。")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("你好")
        agent.sendUserMessage("你能做什么")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertEquals(2, script.calls.size)
        val secondCallMessages = script.calls[1].first
        // 第二问的上下文必须包含第一问与第一答（多轮连续性），本轮指令居末
        assertTrue(secondCallMessages.any { it.isUser && it.content == "你好" })
        assertTrue(secondCallMessages.any { it.role == "assistant" && it.content.contains("想做点什么游戏") })
        assertEquals("你能做什么", secondCallMessages.last { it.isUser }.content)
        dir.deleteRecursively()
        Unit
    }
    @Test
    fun `正文流预览取末三行非空行并截断长行`() {
        // 常规：末三行非空行（空行剔除后）
        assertEquals("第一行\n第二行还在写\n第三行", formatStreamPreview("第一行\n\n第二行还在写\n第三行"))
        // 单行
        assertEquals("只有一行", formatStreamPreview("只有一行"))
        // 空白输入返回 null（无可见内容不占 UI 空间）
        assertNull(formatStreamPreview(""))
        assertNull(formatStreamPreview("   \n  "))
        // 超长行截断到上限
        assertEquals(96, formatStreamPreview("x".repeat(300))!!.length)
        // 超出尾部窗口只看末尾
        val long = (1..100).joinToString("\n") { "行$it" }
        assertTrue(formatStreamPreview(long)!!.contains("行100"))
        assertFalse(formatStreamPreview(long)!!.contains("行1\n"))
    }
    @Test
    fun `流预览三级优先-正文优先于思考-思考优先于参数计数`() {
        // 三者皆有：正文优先
        assertEquals("正文一行", pickStreamPreview("正文一行", "思考中", 999))
        // 无正文有思考：思考滚动窗口（不足三行时全显）
        assertEquals("思考第一行\n思考第两行", pickStreamPreview("", "思考第一行\n思考第两行", 500))
        // 仅参数流：只报进度计数（参数是 JSON+代码，不回显内容）
        val argOnly = pickStreamPreview("", "", 4321)
        assertTrue(argOnly!!.contains("4321"))
        // 全空：null（不占 UI 空间）
        assertNull(pickStreamPreview("", "", 0))
    }
    @Test
    fun `参数流预览显示纯数字字符数`() {
        val p = pickStreamPreview("", "", 62_300)
        assertTrue(p!!.contains("62300"))
    }
    @Test
    fun `断言对齐提示分档-3轮判定5轮强制仲裁`() {
        // <3 轮：不注入提示
        assertEquals("", alignmentHintFor(mapOf("战斗/击杀" to 2), null))
        // ≥3 轮：判定提示（对照快照二选一）
        val hint3 = alignmentHintFor(mapOf("塔防/波次" to 3), null)
        assertTrue(hint3.contains("断言对齐判定"))
        assertTrue(hint3.contains("3 轮"))
        assertTrue(hint3.contains("scenarios.json"))
        // ≥5 轮：强制仲裁，压过重写建议并禁改逻辑
        val hint5 = alignmentHintFor(mapOf("塔防/基地" to 6), null)
        assertTrue(hint5.contains("仲裁"))
        assertTrue(hint5.contains("优先于"))
        assertTrue(hint5.contains("禁止再修改 index.html"))
        // 零活动快照（state=playing 且 enemyCount=0/entities 空）→ 帧预算定向线索
        val snap = """scenario-fail[塔防/基地]: 断言不满足；实际快照 {"state":"playing","enemyCount":0,"entities":[]}"""
        assertTrue(alignmentHintFor(mapOf("塔防/基地" to 6), snap).contains("大概率是帧预算不足"))
        // 非零活动快照不给该线索（避免误导）
        val activeSnap = """scenario-fail[x]: 实际快照 {"state":"playing","enemyCount":5,"entities":[{"type":"enemy"}]}"""
        assertFalse(alignmentHintFor(mapOf("塔防/基地" to 6), activeSnap).contains("大概率是帧预算不足"))
    }
}
