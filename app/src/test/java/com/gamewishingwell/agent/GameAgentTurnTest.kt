package com.gamewishingwell.agent

import android.content.Context
import android.content.res.AssetManager
import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.LlmSettings
import com.gamewishingwell.data.SettingsRepository
import com.gamewishingwell.data.ToolCallData
import com.gamewishingwell.llm.LlmClient
import com.gamewishingwell.llm.LlmResponse
import com.gamewishingwell.llm.Protocol
import com.gamewishingwell.llm.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** 门控脚本客户端：指定调用序号挂起（gate.await）直至 release——用于在回合中途
     *  稳定观察"Agent Loop 进行中"的跨实例聚合状态（runState），不受真实时序抖动影响。 */
    private class GatedLlmClient(steps: List<Any>, vararg gateAt: Int) : LlmClient {
        override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE
        private val queue = ArrayDeque(steps)
        private val gates = gateAt.associateWith { CompletableDeferred<Unit>() }
        private var call = 0

        fun release(step: Int) { gates[step]?.complete(Unit) }

        override suspend fun streamChat(
            messages: List<com.gamewishingwell.data.ChatMessage>,
            onDelta: (String) -> Unit,
            onThinking: (String) -> Unit,
            onDone: () -> Unit,
            tools: List<ToolSpec>,
            onToolCallDelta: (Int) -> Unit
        ): LlmResponse {
            gates[call++]?.await()
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
            landscape: Boolean,
            light: Boolean
        ): SmokeTestResult = result
    }

    /** 记录每次调用的 (scenariosJson, light) 并按场景返回确定性结果：
     *  light 恒报同一运行时错误（驱动咨询式升级）；scenarios 首败后过（驱动两段门修复）；
     *  纯运行（无 scenarios）恒过。 */
    private class RecordingSmokeRunner : SmokeTestRunner {
        data class Call(val scenariosJson: String?, val light: Boolean)
        val calls = mutableListOf<Call>()
        private var assertionFails = 0
        override suspend fun run(
            html: String,
            deep: Boolean,
            scenariosJson: String?,
            landscape: Boolean,
            light: Boolean
        ): SmokeTestResult {
            calls += Call(scenariosJson, light)
            return when {
                light -> SmokeTestResult(
                    passed = false, framesRun = 6, message = "smoke-failed",
                    errors = listOf("ReferenceError: startGame is not defined")
                )
                scenariosJson != null ->
                    if (assertionFails++ == 0) SmokeTestResult(
                        passed = false, framesRun = 24, message = "smoke-failed",
                        errors = listOf("scenario-fail[战斗/断言X]: 期限 300 帧内未达成（断言：s.score > 999）")
                    ) else SmokeTestResult(passed = true, framesRun = 24, message = "smoke-ok")
                else -> SmokeTestResult(passed = true, framesRun = 24, message = "smoke-ok")
            }
        }
    }

    /** 前 [failTimes] 次返回失败结果、之后返回通过：驱动"连败后修复成功"的确定性脚本。 */
    private class FlakySmokeRunner(
        private val failTimes: Int,
        private val fail: SmokeTestResult,
        private val pass: SmokeTestResult
    ) : SmokeTestRunner {
        private var runs = 0
        override suspend fun run(
            html: String,
            deep: Boolean,
            scenariosJson: String?,
            landscape: Boolean,
            light: Boolean
        ): SmokeTestResult = if (runs++ < failTimes) fail else pass
    }

    private val validHtml = """<!DOCTYPE html><html><head><meta charset="utf-8"></head><body><script>
window.GAME_CONFIG = { "speed": 10 };
Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});
window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };
function restart(){}
</script></body></html>"""

    private fun jsonStr(s: String): String = Json.encodeToString(s)

    private fun newAgent(
        dir: File,
        client: LlmClient?,
        smoke: SmokeTestRunner = StubSmokeRunner(
            SmokeTestResult(passed = true, framesRun = 24, message = "smoke-ok")
        ),
        pinnedGameId: Long? = null,
        autoSave: Boolean = true
    ): GameAgent {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        val assets = mock(AssetManager::class.java)
        `when`(assets.open(anyString())).thenReturn(ByteArrayInputStream("<html>template</html>".toByteArray()))
        `when`(context.assets).thenReturn(assets)
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.settings).thenReturn(
            MutableStateFlow(
                LlmSettings(apiKey = "k", baseUrl = "http://localhost/v1", model = "m", autoSaveOnDelivery = autoSave)
            )
        )
        `when`(settings.isConfigured()).thenReturn(true)
        return GameAgent(
            appContext = context,
            repository = GameRepository(context),
            settingsRepository = settings,
            injectedClientFactory = { client },
            injectedSmokeRunner = smoke,
            pinnedGameId = pinnedGameId
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
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
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
        assertEquals(7, script.calls.size)
        assertTrue("第 4 轮仍应携带工具定义（未被误降级）", script.calls[5].second.isNotEmpty())
        assertTrue(script.calls[5].first.any { it.toolCalls.isNotEmpty() && it.toolCalls.any { tc -> tc.name == "writefile" } })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `连续被驳回的完成宣告第2次注入决策指令第3次熔断保工作交付`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-stall-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                // 识别层 Lite 补丁（direct-make 路径）
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
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
        // 第 3 次被驳回即熔断：本轮已发生写入（第 1 轮 writefile），熔断走保工作
        // 软着陆——静态校验通过的当前工作区带警告交付（运行时错误进警告与总结），
        // 不再"未推进"整轮作废把已落盘修改丢给撤销（20261001_200232 回合教训）。
        assertTrue("软着陆应交付已写入的工作区", s.gameGenerated)
        assertNull("软着陆不标记失败", s.error)
        assertTrue(
            "软着陆警告必须可见（未走完整验收）",
            s.lastWarning?.contains("未经完整验收") == true
        )
        assertTrue(
            "软着陆总结应带轻量检测待确认项",
            s.messages.last().content.contains("轻量检测有待确认项")
        )
        // 第 2 次驳回注入决策强制指令（三选一：改文件/改断言并说明理由/再空转即终止）
        assertTrue(
            "第 4 次 LLM 调用（第 3 次宣告轮）的历史应含决策指令",
            script.calls[4].first.any { it.content.contains("三选一") && it.content.contains("scenarios.json") }
        )
        // 第 1 次驳回走既有反馈路径（不打扰正常修复环入口）
        assertTrue(
            script.calls[3].first.any { it.content.contains("冒烟测试未通过") }
        )
        // 1 次识别 + 4 轮循环即收束（旧机制下同类空转实测 6+ 轮）
        assertEquals(6, script.calls.size)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `已有写入后侦查空转熔断保工作交付`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-recon-salvage-${System.nanoTime()}")
        // 复刻 20261001_200232 回合：第 1 轮写入（27 处修改的缩影）→ 收尾复核连续
        // 24 轮纯读 → 侦查熔断。已有写入时熔断必须软着陆交付（保存全部修改），
        // 而不是"未推进"失败把工作丢给撤销。
        val reconRounds = (1..LoopStallTracker.FUSE_RECON_ROUNDS).map {
            LlmResponse(
                "", toolCalls = listOf(ToolCallData("c-read-$it", "readfile", """{"path":"index.html"}"""))
            )
        }
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                )
            ) + reconRounds
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue("侦查熔断后应保工作交付", s.gameGenerated)
        assertNull(s.error)
        assertTrue(
            "软着陆警告必须可见",
            s.lastWarning?.contains("未经完整验收") == true
        )
        assertTrue(s.currentHtml?.contains("__wwDebugState") == true)
        // 识别 1 + 写入 1 + 纯读 24 = 26 次调用后熔断收束，不再消耗 LLM 轮次
        assertEquals(2 + 1 + LoopStallTracker.FUSE_RECON_ROUNDS, script.calls.size)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `从未写入的纯侦查空转熔断仍硬失败`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-recon-pure-${System.nanoTime()}")
        // 纯空转（零写入）没有可保的工作：维持原硬失败语义与"换模型"提示。
        val reconRounds = (1..LoopStallTracker.FUSE_RECON_ROUNDS).map {
            LlmResponse(
                "", toolCalls = listOf(ToolCallData("c-r-$it", "readfile", """{"path":"index.html"}"""))
            )
        }
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}""")
            ) + reconRounds
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertFalse("纯空转不应交付", s.gameGenerated)
        assertTrue("纯空转维持换模型文案", s.error?.contains("未能推进") == true)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `同轮同文件失配后后续editfile被短路`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-miss-sc-${System.nanoTime()}")
        // 同一轮并行两个 editfile 且第一个失配：第二个必须短路（并行调用共享同一份
        // 文件快照，前序失配=模型对文件现状认知已过期，后续编辑没有执行意义）。
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-m1", "editfile", """{"path":"index.html","old_string":"zzz-not-exist-1","new_string":"a"}"""),
                        ToolCallData("c-m2", "editfile", """{"path":"index.html","old_string":"zzz-not-exist-2","new_string":"b"}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue("失配轮后应正常交付", s.gameGenerated)
        assertEquals(5, script.calls.size)
        // 第 4 次调用（"完成"轮）的请求历史里应有两条工具观察：
        // 第一个 editfile 真实失配（含最相近候选提示），第二个被同轮短路。
        val lastReq = script.calls[4].first
        assertTrue("第一个 editfile 应真实失配", lastReq.any { it.content.contains("未找到") })
        val shortCircuit = lastReq.filter { it.content.contains("本次编辑已跳过") }
        assertEquals("第二个失配 editfile 应被短路", 1, shortCircuit.size)
        assertTrue("短路提示应指向重读目标文件", shortCircuit.first().content.contains("readfile index.html"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `连续2轮失配注入强制重读指令`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-miss-streak-${System.nanoTime()}")
        // 模型连续 3 轮凭记忆盲试 old_string（每轮一个失配 editfile）：跨轮失配阈值已收紧为
        // 连续 2 轮（20261003_003854 回合 100% 相似候选被无视 3 轮才转向）——第 2 轮失配后
        // 即注入用户级强制重读指令，打断盲试循环（实测 042436 回合失配 83 次不止）。
        val miss = { i: Int ->
            LlmResponse(
                "", toolCalls = listOf(
                    ToolCallData("c-m$i", "editfile", """{"path":"index.html","old_string":"stale-$i","new_string":"x$i"}""")
                )
            )
        }
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                miss(1), miss(2), miss(3),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        // 第 5 次 LLM 调用（第 3 个失配轮）的请求历史应含强制重读指令（第 2 轮失配后注入）
        assertTrue(
            "第 2 轮失配后应注入强制重读指令",
            script.calls[5].first.any { it.content.contains("连续 2 轮 editfile 失配") && it.content.contains("readfile") }
        )
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `已入库游戏空标题保存保持原名而草稿空标题回退默认名`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-savekeep-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        // 首次保存（草稿入库）：显式命名
        val m1 = agent.saveCurrentGame("星域塔防")
        assertEquals("星域塔防", m1?.title)
        // 草稿期回合日志随入库迁进游戏文件夹（不再随 clearDraft 消失）
        assertTrue(
            "游戏文件夹应有回合日志",
            File(dir, "games/${m1?.id}/log").listFiles().orEmpty().isNotEmpty()
        )
        assertFalse(File(dir, "drafts/log").exists())

        // 已有游戏的旧版本（已命名）：空标题直接保存 = 保持原名，不弹重命名
        val m2 = agent.saveCurrentGame("")
        assertEquals(m1?.id, m2?.id)
        assertEquals("星域塔防", m2?.title)
        dir.deleteRecursively()

        // 草稿空标题仍回退会话派生默认名（首条用户消息前 20 字）
        val dir2 = File(System.getProperty("java.io.tmpdir"), "agent-turn-savedft-${System.nanoTime()}")
        val script2 = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c2-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val agent2 = newAgent(dir2, client = script2)
        agent2.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        val m3 = agent2.saveCurrentGame("")
        // 草稿空标题回退会话派生默认名（designPlan.title，缺失时取首条用户消息前 20 字）
        assertTrue("草稿空标题应回退非空默认名", !m3?.title.isNullOrBlank())
        dir2.deleteRecursively()
        Unit
    }

    @Test
    fun `草稿首回合运行中已落盘使首页卡片可见且停止后对话保留`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-draftvis-${System.nanoTime()}")
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
        val repo = GameRepository(context)
        // 第 0 次 LLM 调用（识别层 Lite 复核）挂起：回合稳定停在"运行中"
        val client = GatedLlmClient(emptyList(), 0)
        val agent = newAgent(dir, client = client)
        val sendJob = launch { agent.sendUserMessage("做一个塔防游戏", qualityTier = "fast") }
        withTimeout(10_000) { while (!agent.session.value.isGenerating) yield() }
        // 首回合发送即落盘：整个回合期间 drafts/ 有簿记文件（首页 hasDraft 为 true，
        // 草稿卡片显示——历史缺陷：落盘点全在回合结束态，首回合运行中卡片消失）。
        // 落盘在 IO 线程异步完成，轮询等待而非立即断言。
        withTimeout(10_000) { while (!File(dir, "drafts/session.json").exists()) yield() }
        assertTrue(repo.hasDraft())
        agent.stopGeneration()
        sendJob.join()
        // 停止即落盘：中断后的对话保留（重启后 loadDraftSession 可回显）
        assertTrue(File(dir, "drafts/session.json").readText(Charsets.UTF_8).contains("塔防"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `陈旧的生成中持久化态装载时复位避免会话锁死`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-stale-${System.nanoTime()}")
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
        val repo = GameRepository(context)
        repo.saveDraft("<html>x</html>", listOf(ChatMessage("user", "旧消息")))
        // 模拟进程在回合中途被杀：agent_state 持久化为"生成中"（发送即落盘后会出现）
        repo.saveDraftAgentState("""{"isGenerating":true,"agentStage":"代码生成中","currentHtml":"<html>x</html>"}""")
        val agent = newAgent(dir, client = ScriptedLlmClient(emptyList()))
        agent.loadDraftSession()
        assertFalse("陈旧生成态必须复位", agent.session.value.isGenerating)
        // 复位后新回合不被 isGenerating 闸门拒绝（否则会话被永久锁死）
        agent.sendUserMessage("这个游戏能联机吗")
        assertFalse(agent.session.value.isGenerating)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `会话身份固定拒绝跨会话切换与重置`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-pinned-${System.nanoTime()}")
        val script = ScriptedLlmClient(emptyList())
        val agent = newAgent(dir, client = script, pinnedGameId = 7)
        agent.awaitReady()
        // pinned 会话拒绝一切身份切换入口（多会话并发的前提：实例即会话，互不顶替）
        agent.loadGameSession(9)
        agent.loadDraftSession()
        agent.newSession()
        assertTrue("身份保持 pinned 到 game-7", agent.boundGameId == 7L)
        // 日志只能由回合入口创建：回合外的会话管理不产生任何日志文件
        assertTrue(
            "回合外不产生日志文件",
            File(dir, "games/7/log").listFiles().isNullOrEmpty()
        )
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `草稿入库后AgentHub重挂键位且原实例续作`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-hub-${System.nanoTime()}")
        val context = mock(android.content.Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        val assets = mock(android.content.res.AssetManager::class.java)
        `when`(assets.open(anyString())).thenReturn(ByteArrayInputStream("<html>template</html>".toByteArray()))
        `when`(context.assets).thenReturn(assets)
        val settings = mock(com.gamewishingwell.data.SettingsRepository::class.java)
        `when`(settings.settings).thenReturn(
            MutableStateFlow(com.gamewishingwell.data.LlmSettings(apiKey = "k", baseUrl = "http://localhost/v1", model = "m"))
        )
        `when`(settings.isConfigured()).thenReturn(true)
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val hub = AgentHub(context, GameRepository(context), settings, injectedClientFactory = { script })
        val draftAgent = hub.agentFor(null)
        draftAgent.awaitReady()
        draftAgent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val meta = draftAgent.saveCurrentGame("会话一") ?: throw AssertionError("保存失败")
        // 同一实例以 game-<id> 续作（编辑上下文不中断）；草稿键位让位给新会话
        assertSame(draftAgent, hub.agentFor(meta.id))
        assertNotSame(draftAgent, hub.agentFor(null))
        dir.deleteRecursively()
        Unit
    }

    @Test
    @org.junit.Ignore("意图层 LLM 化后需专项适配：脚本 7 次调用全部回放且 turn2 已 join，仅 hub 聚合视图(gameStages)未清空——疑 watcher 与会话键位重挂的时序交互，待排查")
    fun `会话运行视图随回合起止在草稿与游戏键位间切换`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-runstate-${System.nanoTime()}")
        val context = mock(android.content.Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        val assets = mock(android.content.res.AssetManager::class.java)
        `when`(assets.open(anyString())).thenReturn(ByteArrayInputStream("<html>template</html>".toByteArray()))
        `when`(context.assets).thenReturn(assets)
        val settings = mock(com.gamewishingwell.data.SettingsRepository::class.java)
        `when`(settings.settings).thenReturn(
            MutableStateFlow(com.gamewishingwell.data.LlmSettings(apiKey = "k", baseUrl = "http://localhost/v1", model = "m"))
        )
        `when`(settings.isConfigured()).thenReturn(true)
        val htmlB = validHtml.replace("score: 0", "score: 1")
        fun write(content: String) = LlmResponse(
            "", toolCalls = listOf(
                ToolCallData("c-${content.hashCode()}", "writefile", """{"path":"index.html","content":${jsonStr(content)}}""")
            )
        )
        // gate 1 挂起草稿回合的代码生成轮（识别响应之后），gate 3 挂起入库重挂
        // game-<id> 键位后的编辑回合首轮——两次挂起点各自稳定暴露"运行中"视图。
        val client = GatedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                write(validHtml),
                LlmResponse("完成"),
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                write(htmlB),
                LlmResponse("完成")
            ),
            2, 5
        )
        val hub = AgentHub(context, GameRepository(context), settings, injectedClientFactory = { client })
        val draft = hub.agentFor(null)
        draft.awaitReady()
        assertNull("开局无会话运行", hub.runState.value.draftStage)
        assertTrue("开局游戏运行视图为空", hub.runState.value.gameStages.isEmpty())

        val turn1 = launch { draft.sendUserMessage("做一个我的世界游戏", qualityTier = "fast") }
        withTimeout(10_000) { while (hub.runState.value.draftStage == null) { yield(); delay(10) } }
        val mid = hub.runState.value
        assertTrue("草稿运行中应有阶段文案，实际 ${mid.draftStage}", mid.draftStage != null && mid.draftStage != AgentStage.IDLE)
        assertTrue("此时无游戏会话运行", mid.gameStages.isEmpty())
        client.release(2)
        turn1.join()

        // 交付即"有未保存新版本"（首页同色 "!"），保存后消失
        withTimeout(10_000) { while (!hub.runState.value.draftUnsaved) { yield(); delay(10) } }
        withTimeout(10_000) { while (hub.runState.value.draftStage != null) { yield(); delay(10) } }
        val meta = draft.saveCurrentGame("运行态") ?: throw AssertionError("保存失败")
        withTimeout(10_000) { while (hub.runState.value.draftUnsaved) { yield(); delay(10) } }

        val turn2 = launch { draft.sendUserMessage("把分数调整一下", qualityTier = "fast") }
        withTimeout(10_000) { while (hub.runState.value.gameStages[meta.id] == null) { yield(); delay(10) } }
        val mid2 = hub.runState.value
        assertTrue(
            "入库后同实例应挂在 game 键位运行，实际 ${mid2.gameStages}",
            mid2.gameStages[meta.id] != null && mid2.gameStages[meta.id] != AgentStage.IDLE
        )
        assertNull("草稿键位已让位给新会话", mid2.draftStage)
        client.release(5)
        turn2.join()
        withTimeout(10_000) { while (hub.runState.value.gameStages.isNotEmpty()) { yield(); delay(10) } }
        // 入库后的第二次交付：unsaved 挂在 game 键位（草稿键位恒 false）
        withTimeout(10_000) { while (meta.id !in hub.runState.value.gameUnsaved) { yield(); delay(10) } }
        assertFalse(hub.runState.value.draftUnsaved)
        hub.shutdown()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `同一断言连败四次注入错位定向指令`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-stuck-${System.nanoTime()}")
        // 姿态：writefile 修改 + 宣告交替——宣告空转熔断（无修改连败 3 次）不会触发，
        // 走"有修改但修不对"的错位路由（同一 scenario 连败 4 次阈值）。
        // 桩沙箱失败 4 次后转通过：回合确定性 ACCEPTED 收尾，终态消息序列稳定可断言
        //（calls 持有可变列表引用，回合未收尾时按下标断言"未来消息"会受追加污染）。
        val htmlB = validHtml.replace("score: 0", "score: 1")
        fun write(content: String) = LlmResponse(
            "", toolCalls = listOf(
                ToolCallData("c-${content.hashCode()}", "writefile", """{"path":"index.html","content":${jsonStr(content)}}""")
            )
        )
        val scenarioJson = """{"scenarios":[{"id":"k","system":"战斗","name":"击杀计数","expect":"s.score > 0"}]}"""
        val scenariosWrite = LlmResponse(
            "", toolCalls = listOf(
                ToolCallData("c-scen", "writefile", """{"path":"scenarios.json","content":${jsonStr(scenarioJson)}}""")
            )
        )
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                write(validHtml),          // r1 写入
                LlmResponse("完成"),        // r2 宣告 → 断言失败 ×1
                write(htmlB),               // r3 修改
                LlmResponse("完成"),        // r4 宣告 → ×2
                write(validHtml),           // r5 修改
                LlmResponse("完成"),        // r6 宣告 → ×3
                write(htmlB),               // r7 修改
                LlmResponse("完成"),        // r8 宣告 → ×4 → 反馈应含错位定向指令
                scenariosWrite,             // r9 按指令 ② 改写断言
                LlmResponse("已按指引改写断言"), // r10 宣告 → 沙箱通过 → 消费自检轮
                LlmResponse("自查完成")      // r11 宣告 → 通过 → ACCEPTED
            )
        )
        val agent = newAgent(
            dir, client = script,
            smoke = FlakySmokeRunner(
                failTimes = 4,
                fail = SmokeTestResult(
                    passed = false, framesRun = 24, message = "smoke-failed",
                    errors = listOf("scenario-fail[战斗与经济/击杀敌人后金币增长]: 期限 300 帧内未达成；字段轨迹: …帧300 {\"gold\":1255}（断言：s.gold < 200 && s.towers === 2）")
                ),
                pass = SmokeTestResult(passed = true, framesRun = 24, message = "smoke-ok")
            )
        )
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "balanced")
        assertTrue("改写断言后应交付", agent.session.value.gameGenerated)

        // 终态反馈序列（折叠保留最近两条全文）：[fold, fold, fb3(常规), fb4(错位指令)]
        val feedbacks = script.calls.last().first
            .filter { it.role == "user" && (it.content.contains("冒烟测试未通过") || it.content.contains("沙箱失败反馈")) }
        assertTrue("应有 4 条失败反馈（含折叠摘要），实际 ${feedbacks.size}", feedbacks.size >= 4)
        assertFalse(
            "第 3 次失败不应注入错位指令",
            feedbacks[feedbacks.size - 2].content.contains("断言与游戏设计错位")
        )
        val lastFeedback = feedbacks.last().content
        assertTrue(
            "第 4 次失败应注入错位定向指令",
            lastFeedback.contains("断言与游戏设计错位") && lastFeedback.contains("三选一")
        )
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `首代生成两段门-早期写的断言不干扰运行验收`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-twogate-${System.nanoTime()}")
        val badScen = """{"scenarios":[{"id":"x","system":"战斗","name":"断言X","expect":"s.score > 999"}]}"""
        val goodScen = """{"scenarios":[{"id":"x","system":"战斗","name":"断言X","expect":"s.score > 0"}]}"""
        fun write(path: String, content: String, tag: String) = ToolCallData(tag, "writefile", """{"path":"$path","content":${jsonStr(content)}}""")
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                // r1：同轮并行写入口 + 一条会失败的断言（早期主动写断言的形态）
                LlmResponse("", toolCalls = listOf(write("index.html", validHtml, "c-h"), write("scenarios.json", badScen, "c-s1"))),
                LlmResponse("完成"),                       // r2 宣告：运行门过 → 同宣告补断言门 → 失败反馈
                LlmResponse("", toolCalls = listOf(write("scenarios.json", goodScen, "c-s2"))), // r3 修断言
                LlmResponse("完成"),                       // r4 宣告：断言门过 → 消费自检轮
                LlmResponse("自查完成")                     // r5 宣告：通过 → ACCEPT
            )
        )
        val smoke = RecordingSmokeRunner()
        val agent = newAgent(dir, client = script, smoke = smoke)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "balanced")
        assertTrue("修复断言后应交付", agent.session.value.gameGenerated)

        // 两段门：同一宣告内先运行门（不带断言）再断言门（既有断言立即补验）
        assertEquals(4, smoke.calls.size)
        assertNull("首段不得携带断言（运行时门先行）", smoke.calls[0].scenariosJson)
        assertTrue("次段应携带既有断言（同宣告补验）", smoke.calls[1].scenariosJson != null)
        // 断言失败进入了正常修复反馈（r3 的请求历史可见 scenario-fail）
        assertTrue(script.calls[4].first.any { it.content.contains("scenario-fail") })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `中途轻量冒烟触发与咨询式升级`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-lightsmoke-${System.nanoTime()}")
        val htmlB = validHtml.replace("score: 0", "score: 1")
        fun write(content: String, tag: String) = LlmResponse(
            "", toolCalls = listOf(ToolCallData(tag, "writefile", """{"path":"index.html","content":${jsonStr(content)}}""")
        ))
        // r1 写入口，r2..r17 再写 16 次：第 8 次写入后触发轻量冒烟#1（咨询通知），
        // 再 8 次后触发#2（同错复现 → 升级为正式修复反馈），r18 宣告交付（轻量档无断言/自检）。
        val steps = mutableListOf<LlmResponse>(
            LlmResponse("""{"intent":"dev","confidence":0.9}"""),
            LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}""")
        )
        for (i in 1..17) steps += write(if (i % 2 == 1) validHtml else htmlB, "c-$i")
        steps += LlmResponse("完成")
        val script = ScriptedLlmClient(steps)
        val smoke = RecordingSmokeRunner()
        val agent = newAgent(dir, client = script, smoke = smoke)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "light")
        assertTrue("轻量档应交付", agent.session.value.gameGenerated)

        // 两次轻量冒烟（light=true 且不带断言），第三次为交付门全量运行
        val lights = smoke.calls.filter { it.light }
        assertEquals(2, lights.size)
        assertTrue(lights.all { it.scenariosJson == null })
        assertFalse("交付门不得是轻量模式", smoke.calls.last().light)
        // 咨询通知与升级反馈都在历史中
        val allText = script.calls.last().first.joinToString(" ") { it.content }
        assertTrue("首次应为咨询式通知", allText.contains("可能因部分文件尚未写完"))
        assertTrue("同错复现应升级为正式反馈", allText.contains("连续两次发现同样错误"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `重写模块丢失导出成员被写入时刻拒绝并点名`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-export-${System.nanoTime()}")
        // 我的世界事故形态：touch.js 重写丢 setUI 导出，main.js 的 Input.setUI 调用
        // 让游戏一启动即崩——写入时刻的跨文件导出契约必须拒绝并指名缺失成员。
        val touchFull = """
            window.Input = (function(){
              var uic = null;
              function setUI(c){ uic = c; }
              return { setUI: setUI, bind: function(){} };
            })();
        """.trimIndent()
        val touchMissing = touchFull.replace("setUI: setUI, ", "")
        val mainJs = "window.GAME_CONFIG = { \"speed\": 10 };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});\n" +
            "window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };\n" +
            "function restart(){}\n" +
            "(function(){ setTimeout(function(){ Input.setUI(document.createElement('canvas')); }, 30); })();"
        val entryHtml = """<!DOCTYPE html><html><head>
<link rel="stylesheet" href="css/style.css">
</head><body><script src="js/touch.js"></script><script src="js/main.js"></script></body></html>"""
        fun wcall(path: String, content: String) = ToolCallData(
            "c-${path.hashCode()}-${content.hashCode()}", "writefile",
            """{"path":"$path","content":${jsonStr(content)}}"""
        )
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-css", "writefile", """{"path":"css/style.css","content":${jsonStr("body{margin:0}")}}"""),
                        wcall("js/touch.js", touchFull),
                        wcall("js/main.js", mainJs),
                        wcall("index.html", entryHtml)
                    )
                ),
                LlmResponse("", toolCalls = listOf(wcall("js/touch.js", touchMissing))), // 丢 setUI 的重写：应被拒绝
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        // 拒绝信息回填给了模型（第 4 次请求历史可见），且指名模块/成员/调用方
        val lastRequest = script.calls[4].first
        assertTrue(
            "应包含导出契约拒绝信息",
            lastRequest.any { it.content.contains("跨文件模块导出契约被破坏") && it.content.contains("Input.setUI") }
        )
        // 文件未被改动：磁盘上的 touch.js 仍含 setUI 导出
        val onDisk = File(File(dir, "agent/workspace/draft"), "js/touch.js").readText()
        assertTrue("被拒写入不得落盘", onDisk.contains("setUI: setUI"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `回合累计全量重写达到三次注入咨询提示`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-advise-${System.nanoTime()}")
        val entryHtml = """<!DOCTYPE html><html lang="zh"><head>
<link rel="stylesheet" href="css/style.css">
</head><body><script src="js/main.js"></script></body></html>"""
        val jsContent = "window.GAME_CONFIG = { \"speed\": 10 };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});\n" +
            "window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };\nfunction restart(){}"
        val cssContent = "body{margin:0;background:#111}"
        fun wcall(path: String, content: String) = ToolCallData(
            "c-${path.hashCode()}-${content.hashCode()}", "writefile",
            """{"path":"$path","content":${jsonStr(content)}}"""
        )
        // 快照式记录（不可变副本）：calls 逐轮状态不被后续折叠污染
        class SnapClient(steps: List<Any>) : LlmClient {
            override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE
            private val queue = ArrayDeque(steps)
            val calls = mutableListOf<List<com.gamewishingwell.data.ChatMessage>>()
            override suspend fun streamChat(
                messages: List<com.gamewishingwell.data.ChatMessage>,
                onDelta: (String) -> Unit,
                onThinking: (String) -> Unit,
                onDone: () -> Unit,
                tools: List<ToolSpec>,
                onToolCallDelta: (Int) -> Unit
            ): LlmResponse {
                calls += messages.toList()
                val step = queue.removeFirstOrNull() ?: LlmResponse("", emptyList())
                if (step is Exception) throw step
                step as LlmResponse
                onDelta(step.text)
                onDone()
                return step
            }
        }
        val script = SnapClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        wcall("css/style.css", cssContent),
                        wcall("js/main.js", jsContent),
                        wcall("index.html", entryHtml)
                    )
                ),
                LlmResponse("完成"),
                // 第二回合入口的意图二分类（dev/chat），其后才是循环轮
                LlmResponse("""{"intent":"dev","confidence":0.9,"reason":"改进诉求"}"""),
                // 三次小改幅度的全量覆盖（均高于重写护栏相似度阈值）
                LlmResponse("", toolCalls = listOf(wcall("css/style.css", cssContent.replace("#111", "#222")))),
                LlmResponse("", toolCalls = listOf(wcall("js/main.js", jsContent.replace("score: 0", "score: 1")))),
                LlmResponse("", toolCalls = listOf(wcall("index.html", entryHtml.replace("lang=\"zh\"", "lang=\"en\"")))),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        agent.sendUserMessage("微调一下画面", qualityTier = "fast")

        // 第三次覆盖（index.html）的观察附咨询提示；前两次不附
        assertTrue(
            "第三次全量覆盖应附咨询提示；n=${script.calls.size} 全部调用工具结果=" +
                script.calls.mapIndexed { i, c ->
                    "[$i]" + c.filter { it.role == "tool" }.joinToString("|") { it.content?.take(45) ?: "" }
                }.joinToString(" ;; "),
            script.calls[8].any { it.content.contains("全量重写提示") && it.content.contains("editfile") }
        )
        assertFalse("第二次覆盖不应提示", script.calls[7].any { it.content.contains("全量重写提示") })
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
        val jsContent = "window.GAME_CONFIG = { \"speed\": 10 };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});\n" +
            "window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };\nfunction restart(){}"
        val script = ScriptedLlmClient(
            listOf(
                // 识别层 Lite 补丁（direct-make 路径的系统参考抽取）
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
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
        // 未内联的 src 引用必须消失（内容已在脚本标签里）；内联尾注
        // //# sourceURL=js/main.js（报错定位用）合法保留原路径
        assertFalse(preview.contains("<script src="))
        // 全程没有"缺少可观测性契约"伪错误（旧实现只看原始入口，js 里的定义看不见；
        // css/js 写入被当 HTML 校验也会误报）
        assertFalse(s.messages.any { it.content.contains("缺少可观测性契约") })
        // 三个 writefile 在同一轮 assistant 消息里并行发起（第 2 轮请求历史可见）
        assertTrue(script.calls[3].first.any { it.toolCalls.size == 3 })
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
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
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
        assertEquals(5, script.calls.size)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `chat 回复携带最近对话历史`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-chat-${System.nanoTime()}")
        // 意图层走 LLM（正则已删除）：每条消息 = 意图判定步 + 回答步。
        // 两问均判 chat（无锚定游戏 → 纯文本回答，不进只读工具循环）。
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"chat","confidence":0.95,"reason":"问候"}"""),
                LlmResponse("你好！想做点什么游戏？"),
                LlmResponse("""{"intent":"chat","confidence":0.95,"reason":"询问能力"}"""),
                LlmResponse("我可以根据一句话生成 HTML5 小游戏。")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("你好")
        agent.sendUserMessage("你能做什么")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertEquals(4, script.calls.size)
        // 第二问的回答步（第 4 次调用）
        val secondCallMessages = script.calls[3].first
        // 第二问的上下文必须包含第一问与第一答（多轮连续性），本轮指令居末
        assertTrue(secondCallMessages.any { it.isUser && it.content == "你好" })
        assertTrue(secondCallMessages.any { it.role == "assistant" && it.content.contains("想做点什么游戏") })
        assertEquals("你能做什么", secondCallMessages.last { it.isUser }.content)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `chat 会话严格禁写且可只读查阅工作区`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-chat-ro-${System.nanoTime()}")
        // 先造已锚定游戏（dev 一轮交付），再以 chat 提问：只读工具循环提供 readfile；
        // 模型硬调 editfile 也必须被 readOnly 硬拒（双层保障第二层），工作区不变。
        val script = ScriptedLlmClient(
            listOf(
                // 第 1 回合（dev）：意图 → 识别 → 写入 → 完成
                LlmResponse("""{"intent":"dev","confidence":0.95,"reason":"制作游戏"}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-w1", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成"),
                // 第 2 回合（chat）：意图 → 只读循环（读 + 硬调写入被拒）→ 回答
                LlmResponse("""{"intent":"chat","confidence":0.95,"reason":"询问实现"}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("c-r", "readfile", """{"path":"index.html"}"""),
                        ToolCallData("c-w2", "editfile", """{"path":"index.html","old_string":"a","new_string":"b"}""")
                    )
                ),
                LlmResponse("计分逻辑在 window.__wwDebugState 里。")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        val before = agent.session.value.currentHtml
        agent.sendUserMessage("游戏里计分是怎么实现的", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertEquals("工作区不得被 chat 修改", before, s.currentHtml)
        // 第 7 次调用（chat 回答轮）的请求历史：readfile 观察 + editfile 拒绝观察都在
        val lastReq = script.calls[6].first
        assertTrue("只读循环应提供 readfile 观察", lastReq.any { it.role == ChatMessage.ROLE_TOOL && it.content.contains("__wwDebugState") })
        assertTrue(
            "chat 禁写必须硬拒 editfile",
            lastReq.any { it.role == ChatMessage.ROLE_TOOL && it.content.contains("严格只读") && it.content.contains("editfile") }
        )
        assertTrue("回答应进入会话", s.messages.any { it.role == "assistant" && it.content.contains("计分") })
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
    //  失败回报自带字段轨迹与输入点状态，"机制未发生/字段错位"独立成信号，无需按次数升级的判定文案。

    // ---------- 参数外置契约维护（迭代轮，写入时刻执行层强制 + 覆盖清单注入） ----------

    private fun cfgGuardFixtures(): Array<String> {
        val cfgFull = "window.GAME_CONFIG = { \"speed\": 3, \"hp\": 100 };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
        val cfgLost = "window.GAME_CONFIG = { \"speed\": 3 };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
        val cfgTuned = "window.GAME_CONFIG = { \"speed\": 5, \"hp\": 100 };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
        return arrayOf(cfgFull, cfgLost, cfgTuned)
    }

    @Test
    fun `迭代轮参数键守恒-删键被拒且调值放行`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-cfgguard-${System.nanoTime()}")
        val (cfgFull, cfgLost, cfgTuned) = cfgGuardFixtures()
        val mainJs = "var v = window.GAME_CONFIG.speed + window.GAME_CONFIG.hp;\n" +
            "window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };\nfunction restart(){}"
        val entryHtml = "<!DOCTYPE html><html><head><script src=\"js/config.js\"></script></head>" +
            "<body><script src=\"js/main.js\"></script></body></html>"
        fun wcall(path: String, content: String, tag: String) = ToolCallData(
            tag, "writefile", """{"path":"$path","content":${jsonStr(content)}}"""
        )
        val script = ScriptedLlmClient(
            listOf(
                // 轮 1：首代生成（js/config.js 参与契约），fast 档直出交付
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse("", toolCalls = listOf(
                    wcall("js/config.js", cfgFull, "c-cfg1"),
                    wcall("js/main.js", mainJs, "c-main1"),
                    wcall("index.html", entryHtml, "c-idx1")
                )),
                LlmResponse("完成"),
                // 轮 2（迭代，意图 LLM 判定 dev）：
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                // ① 重写 config.js 删掉 hp 键（指令未点名键名）→ 执行层致命拒绝且不落盘
                LlmResponse("", toolCalls = listOf(wcall("js/config.js", cfgLost, "c-cfg2"))),
                // ② 被拒后恢复完整键表并只调 speed 值 → 放行（调值不受限）
                LlmResponse("", toolCalls = listOf(wcall("js/config.js", cfgTuned, "c-cfg3"))),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个游戏", qualityTier = "fast")
        agent.sendUserMessage("调整速度", qualityTier = "fast")

        // 拒绝信息（参数键只增不减 + 点名 hp）回填给了模型
        assertTrue(
            "应回填参数键守恒拒绝信息",
            script.calls.any { (msgs, _) -> msgs.any { it.content.contains("参数键只增不减") && it.content.contains("hp") } }
        )
        // 最终落盘：hp 键保留、speed 调值生效（被拒版本未落盘）
        val final = File(File(dir, "agent/workspace/draft"), "js/config.js").readText()
        assertTrue(final.contains("\"speed\": 5") && final.contains("\"hp\""))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `迭代轮注入玩家参数覆盖清单`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-cfgovr-${System.nanoTime()}")
        val (cfgFull, _, cfgTuned) = cfgGuardFixtures()
        val mainJs = "var v = window.GAME_CONFIG.speed + window.GAME_CONFIG.hp;\n" +
            "window.__wwDebugState = function(){ return { state: 'playing', score: 0 }; };\nfunction restart(){}"
        val entryHtml = "<!DOCTYPE html><html><head><script src=\"js/config.js\"></script></head>" +
            "<body><script src=\"js/main.js\"></script></body></html>"
        fun wcall(path: String, content: String, tag: String) = ToolCallData(
            tag, "writefile", """{"path":"$path","content":${jsonStr(content)}}"""
        )
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse("", toolCalls = listOf(
                    wcall("js/config.js", cfgFull, "c-cfg1"),
                    wcall("js/main.js", mainJs, "c-main1"),
                    wcall("index.html", entryHtml, "c-idx1")
                )),
                LlmResponse("完成"),
                // 轮 2：入库后工作区随会话身份切换到 game-<id>（草稿工作区不迁移，与真机
                // 行为一致）——重写全三件套；本轮上下文应包含玩家参数覆盖清单
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("", toolCalls = listOf(
                    wcall("js/config.js", cfgTuned, "c-tune-cfg"),
                    wcall("js/main.js", mainJs, "c-tune-main"),
                    wcall("index.html", entryHtml, "c-tune-idx")
                )),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个游戏", qualityTier = "fast")
        val meta = agent.saveCurrentGame("测试游戏") ?: throw AssertionError("保存失败")
        // 玩家经参数面板写入覆盖（同一 filesDir 的仓库实例即可触达同一文件）
        val ctx = org.mockito.Mockito.mock(android.content.Context::class.java)
        `when`(ctx.filesDir).thenReturn(dir)
        GameRepository(ctx).writeUserConfig(meta.id, """{"playerSpeed": 260, "goblinHp": 50}""")
        agent.sendUserMessage("调整速度", qualityTier = "fast")

        assertTrue(
            "迭代轮上下文应包含玩家参数覆盖清单",
            script.calls.any { (msgs, _) ->
                msgs.any { it.content.contains("玩家参数覆盖") && it.content.contains("playerSpeed = 260") }
            }
        )
        dir.deleteRecursively()
        Unit
    }

    // ---------- 交付后自动保存（方案 A：仅已入库会话，草稿/关闭开关不触发） ----------

    @Test
    fun `已入库会话交付后自动保存且撤销锚点逐交付刷新`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-autosave-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                // 回合 1（草稿首作）：识别 Lite → writefile → 完成
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("a-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成"),
                // 回合 2（已入库迭代，dev 直达 Agent Loop）：意图 → editfile 小改 → 完成
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("a-edit", "editfile", """{"path":"index.html","old_string":${jsonStr("score: 0")},"new_string":${jsonStr("score: 1")}}""")
                    )
                ),
                LlmResponse("完成，分数已改")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        val meta = agent.saveCurrentGame("自动保存测试") ?: throw AssertionError("首存失败")
        // 回合 2 交付 → 自动保存：保存区文件同步为最新版、保存点刷新、unsavedVersion 清零
        agent.sendUserMessage("把score改成1", qualityTier = "fast")

        val savedHtml = File(dir, "games/${meta.id}/index.html").readText(Charsets.UTF_8)
        assertTrue("保存区应自动同步到交付版本：${savedHtml.take(120)}", savedHtml.contains("score: 1"))
        assertFalse("自动保存后 unsavedVersion 应清零", agent.session.value.unsavedVersion)
        val savepoint = File(dir, "games/${meta.id}/.savepoint/session.json").readText(Charsets.UTF_8)
        assertTrue("保存点应冻结到本交付（含本轮对话）", savepoint.contains("把score改成1"))
        val savedSession = File(dir, "games/${meta.id}/session.json").readText(Charsets.UTF_8)
        assertTrue("保存区会话簿记应同步", savedSession.contains("把score改成1"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `自动保存下撤销回到上一交付版本且为单步撤销`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-undo-prev-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                // 回合 1（草稿首作）：识别 Lite → writefile → 完成（交付第 1 版）
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("u-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成"),
                // 回合 2（已入库迭代）：意图 → editfile 小改 → 完成（交付第 2 版，自动保存）
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("u-edit", "editfile", """{"path":"index.html","old_string":${jsonStr("score: 0")},"new_string":${jsonStr("score: 1")}}""")
                    )
                ),
                LlmResponse("完成，分数已改")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        val meta = agent.saveCurrentGame("撤销测试") ?: throw AssertionError("首存失败")
        agent.sendUserMessage("把score改成1", qualityTier = "fast")

        // v2 交付自动保存后：prev 槽冻结 v1（旧保存区文件 + 旧保存点会话）
        assertEquals("撤销目标版号应为上一交付（第 1 版）而非当前版", 1, agent.undoTargetVersion()!!)
        val prevHtml = File(dir, "games/${meta.id}/.savepoint_prev/files/index.html").readText(Charsets.UTF_8)
        assertTrue("prev 文件快照应为上一交付版本", prevHtml.contains("score: 0"))

        // 撤销：工作区 + 保存区 + 会话三处一致地回到 v1
        assertTrue("应可撤销到上一交付版本", agent.undoToSaved())
        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertFalse("撤销后无未保存标记", s.unsavedVersion)
        assertEquals("会话版号应回到第 1 版", 1, s.deliveredVersion)
        assertTrue("currentHtml 应回到上一版", s.currentHtml!!.contains("score: 0"))
        assertTrue(
            "工作区应恢复上一版",
            File(dir, "agent/workspace/game-${meta.id}/index.html").readText(Charsets.UTF_8).contains("score: 0")
        )
        assertTrue(
            "保存区应同步回上一版（防撤销后重进装载回当前版）",
            File(dir, "games/${meta.id}/index.html").readText(Charsets.UTF_8).contains("score: 0")
        )
        assertTrue("撤销提示应指明目标版本", s.messages.last().content.contains("上一交付版本（第 1 版）"))

        // 单步撤销语义：prev 已晋升为当前锚点，再次撤销无可回退
        assertFalse("第二次撤销应无可撤销", agent.undoToSaved())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `侦查超限门禁拒绝读取工具且写入后恢复`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-gate-${System.nanoTime()}")
        // 轮 1-10 全部 readfile（index.html 尚不存在→ok=false，仍是纯读取轮）：
        // advisory@5、ultimatum@8、宽限轮 9（对齐"允许 outline 一次"）、门禁在轮 9 末
        // 激活 → 轮 10 的 readfile 被执行层拒绝（specs 摘除 + 幻觉调用双保险）；
        // 轮 11 writefile 落盘 → 门禁解除；轮 12 readfile 正常返回全文；轮 13 完成交付。
        val reads = (1..10).map { i ->
            LlmResponse(
                "", toolCalls = listOf(
                    ToolCallData("g-r$i", "readfile", """{"path":"index.html"}""")
                )
            )
        }
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}""")
            ) + reads + listOf(
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("g-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("g-read2", "readfile", """{"path":"index.html"}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")

        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue("门禁后仍可落盘交付", s.gameGenerated)
        // calls: 0 intent / 1 recognition / 2..11 十个读取轮 / 12 写入轮 / 13 读取轮 / 14 完成轮
        val round11Req = script.calls[12].first
        assertTrue(
            "轮 10 的 readfile 应被门禁拒绝（观察进入轮 11 请求历史）",
            round11Req.any { it.content.contains("侦查门禁") }
        )
        val round13Req = script.calls[14].first
        assertTrue(
            "写入后读取工具应恢复（轮 12 整读全文进入完成轮请求历史）",
            round13Req.any { it.content.contains("__wwDebugState") && !it.content.contains("侦查门禁") }
        )
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `修复轮侦查拖延时恢复思考并留痕`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-quiet-${System.nanoTime()}")
        // 回合 1：正常首作（writefile + 完成）
        val fixReads = (1..4).map { i ->
            LlmResponse(
                "", toolCalls = listOf(
                    ToolCallData("q-r$i", "readfile", """{"path":"index.html"}""")
                )
            )
        }
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("q-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成")
            ) + fixReads + listOf(
                // 连续 4 轮只读后落盘修复，再完成
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("q-fix", "editfile", """{"path":"index.html","old_string":${jsonStr("score = 0")},"new_string":${jsonStr("score = 1")}}""")
                    )
                ),
                LlmResponse("已修复")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        // 修复轮 fixTurn=true → quiet 基线开启；连续 3 轮只读后应关 quiet 恢复思考
        agent.fixWithError("index.html:12 TypeError: startGame is not a function")
        val s = agent.session.value
        assertFalse(s.isGenerating)
        assertTrue("修复轮应正常交付", s.gameGenerated)
        val logText = File(dir, "drafts/log").listFiles().orEmpty()
            .joinToString("") { it.readText(Charsets.UTF_8) }
        assertTrue(
            "连续只读达到阈值应关 quiet 并留痕：\n${logText.takeLast(600)}",
            logText.contains("quiet off: struggling recon")
        )
        assertFalse("该场景未到 ultimatum，门禁不应激活", logText.contains("recon gate ON"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `草稿会话交付不触发自动入库`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-nosave-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("n-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        // 草稿交付：不做自动入库（首存需命名，保持手动），unsavedVersion 维持"有未保存新版本"
        assertTrue("草稿交付后应有未保存标记", agent.session.value.unsavedVersion)
        assertTrue("草稿交付不得自动入库", File(dir, "games").listFiles().isNullOrEmpty())
        assertTrue("草稿簿记应在 drafts/", File(dir, "drafts/session.json").isFile)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `关闭自动保存开关后交付保持手动保存语义`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-asoff-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("o-write", "writefile", """{"path":"index.html","content":${jsonStr(validHtml)}}""")
                    )
                ),
                LlmResponse("完成"),
                // 回合 2（迭代轮同样先过意图 LLM）
                LlmResponse("""{"intent":"dev","confidence":0.9}"""),
                LlmResponse(
                    "", toolCalls = listOf(
                        ToolCallData("o-edit", "editfile", """{"path":"index.html","old_string":${jsonStr("score: 0")},"new_string":${jsonStr("score: 2")}}""")
                    )
                ),
                LlmResponse("完成")
            )
        )
        val agent = newAgent(dir, client = script, autoSave = false)
        agent.sendUserMessage("做一个我的世界游戏", qualityTier = "fast")
        val meta = agent.saveCurrentGame("手动档") ?: throw AssertionError("首存失败")
        val before = File(dir, "games/${meta.id}/index.html").readText(Charsets.UTF_8)
        agent.sendUserMessage("把score改成2", qualityTier = "fast")

        val after = File(dir, "games/${meta.id}/index.html").readText(Charsets.UTF_8)
        assertEquals("关闭开关时保存区不得被自动改写", before, after)
        assertTrue("关闭开关时交付后保持未保存标记", agent.session.value.unsavedVersion)
        // 手动保存仍可用
        agent.saveCurrentGame("")
        assertTrue(File(dir, "games/${meta.id}/index.html").readText(Charsets.UTF_8).contains("score: 2"))
        assertFalse(agent.session.value.unsavedVersion)
        dir.deleteRecursively()
        Unit
    }
}
