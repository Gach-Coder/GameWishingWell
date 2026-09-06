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
    fun `连续被驳回的完成宣告第2次注入决策指令第3次熔断`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-stall-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                // 识别层 Lite 补丁（direct-make 路径）
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                // 第 1 轮：写入合法游戏（mutatedOnce 置位，沙箱按均衡档运行）
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                // 第 2~4 轮：零工具宣告完成——沙箱恒败（确定性），复刻 08:40 回合病理
                LlmResponse("我认为已经完成了"),
                LlmResponse("再确认一遍，确实完成了"),
                LlmResponse("以上就是最终版本")
            )
        )
        val agent = newAgent(
            dir, client = script,
            smoke = StubSmokeRunner(
                SmokeTestResult(
                    passed = false, framesRun = 24, message = "smoke-failed",
                    errors = listOf("scenario-fail[塔防/开局进入 playing 状态]: 期限 300 帧内未达成（断言在期限内任一帧为真即通过）")
                )
            )
        )
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "balanced")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        // 第 3 次被驳回即熔断：如实失败（无法推进口径，failTurn 写入 error 字段），不带错交付
        assertFalse(s.gameGenerated)
        assertTrue("熔断应给出无法推进的用户文案", s.error?.contains("未能推进") == true)
        // 第 2 次驳回注入决策强制指令（三选一：改文件/改断言并说明理由/再空转即终止）
        assertTrue(
            "第 4 次 LLM 调用（第 3 次宣告轮）的历史应含决策指令",
            script.calls[3].first.any { it.content.contains("三选一") && it.content.contains("scenarios.json") }
        )
        // 第 1 次驳回走既有反馈路径（不打扰正常修复环入口）
        assertTrue(
            script.calls[2].first.any { it.content.contains("冒烟测试未通过") }
        )
        // 1 次识别 + 4 轮循环即收束（旧机制下同类空转实测 6+ 轮）
        assertEquals(5, script.calls.size)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `多文件生成回合-同轮并行写 css js 与入口并正确验收发布`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-multi-${System.nanoTime()}")
        // __wwDebugState 定义在 js 文件里（多文件组织的常态）：验收必须校验内联合并视图
        val entryHtml = """<!DOCTYPE html><html><head>
<link rel="stylesheet" href="css/style.css">
</head><body><script src="js/main.js"></script></body></html>"""
        val jsContent = "window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };\nfunction restart(){}"
        val script = ScriptedLlmClient(
            listOf(
                // 识别层 Lite 补丁（direct-make 路径的系统参考抽取）
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                // 第 1 轮：同轮并行写入三个文件（先被引用的 js/css、最后入口）
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-css", "writefile", """{"path":"css/style.css","content":${jsonStr("body{margin:0;background:#111}")}}"""),
                        ToolCallData("c-js", "writefile", """{"path":"js/main.js","content":${jsonStr(jsContent)}}"""),
                        ToolCallData("c-html", "writefile", """{"path":"index.html","content":${jsonStr(entryHtml)}}""")
                    )
                ),
                // 第 2 轮：声明完成（快速档跳过沙箱/断言/自检，直接验收）
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue(s.gameGenerated)
        // 发布的 currentHtml 是入口本身——不是同批最后写入的 js/css 内容（旧 bug 会误发布）
        assertEquals(entryHtml, s.currentHtml)
        // 三个文件都真实落盘
        val root = File(dir, "agent/workspace/draft")
        assertTrue(File(root, "index.html").isFile)
        assertTrue(File(root, "js/main.js").isFile)
        assertTrue(File(root, "css/style.css").isFile)
        // 预览副本把 js/css 内联合并进入口（真机游玩看到的就是这份）
        val preview = agent.runnablePreviewHtml() ?: ""
        assertTrue(preview.contains("__wwDebugState"))
        assertTrue(preview.contains("background:#111"))
        assertFalse(preview.contains("js/main.js"))
        // 全程没有"缺少可观测性契约"伪错误（旧实现只看原始入口，js 里的定义看不见；
        // css/js 写入被当 HTML 校验也会误报）
        assertFalse(s.messages.any { it.content.contains("缺少可观测性契约") })
        // 三个 writefile 在同一轮 assistant 消息里并行发起（第 2 轮请求历史可见）
        assertTrue(script.calls[2].first.any { it.toolCalls.size == 3 })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `工作区指纹覆盖辅助文件修改`() = runBlocking {
        // "防空口完成"门的度量对象必须是工作区整体而非仅入口哈希：
        // 纯 js 修复（多文件时代的常态修法）与纯断言修复都必须算作变化，
        // 否则正确修改被门拒绝、nudge 反而引导模型去改 index.html（与 40 帧硬顶同族）。
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-fp-${System.nanoTime()}")
        val agent = newAgent(dir, client = null)
        val ws = GameFileWorkspace(File(dir, "agent/workspace/draft"))
        ws.writeInitial("index.html", "<html>a</html>")
        ws.writeInitial("js/main.js", "var a = 1;")
        val fp1 = agent.workspaceFingerprint(ws)
        // 修改辅助文件：指纹必须变化
        ws.writeUpdated("js/main.js", "var a = 2;")
        assertTrue(fp1 != agent.workspaceFingerprint(ws))
        // 改回原状：净零修改，指纹复原（空口完成仍被拒）
        ws.writeUpdated("js/main.js", "var a = 1;")
        assertEquals(fp1, agent.workspaceFingerprint(ws))
        // 断言文件（scenarios.json）同样计入
        ws.writeInitial("scenarios.json", "{}")
        assertTrue(fp1 != agent.workspaceFingerprint(ws))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `正文 HTML 在工具已工作后被忽略不覆写工作区`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-prose-${System.nanoTime()}")
        val proseHtml = validHtml.replace("score: 0", "score: 1")
        val script = ScriptedLlmClient(
            listOf(
                // 识别层 Lite 补丁
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                // 第 1 轮：正常工具写入（工具通路已建立）
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                // 第 2 轮：正文夹带另一份完整 HTML——工具已工作，必须忽略而非覆写
                LlmResponse("我做完了：\n```html\n$proseHtml\n```"),
                // 第 3 轮：纯文本声明完成 → 正常验收
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertTrue(s.gameGenerated)
        // 工作区未被正文 HTML 覆写（旧实现会把 proseHtml 写进入口并以其交付）
        assertEquals(validHtml, s.currentHtml)
        // 第 2 轮的违规被纠正而非静默吞掉：第 3 轮照常发生（共 4 次 LLM 调用）
        assertEquals(4, script.calls.size)
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
    // 断言对齐提示（alignmentHintFor 3/5 轮阶梯）已随 eventually 断言语义根治而删除：
    // 失败回报自带字段轨迹与输入点状态，"机制未发生/字段错位"独立成信号，无需按次数升级的判定文案。
}
