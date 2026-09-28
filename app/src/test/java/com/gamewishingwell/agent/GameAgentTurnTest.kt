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
        pinnedGameId: Long? = null
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
    fun `已入库游戏空标题保存保持原名而草稿空标题回退默认名`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "agent-turn-savekeep-${System.nanoTime()}")
        val script = ScriptedLlmClient(
            listOf(
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
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                write(validHtml),
                LlmResponse("完成"),
                write(htmlB),
                LlmResponse("完成")
            ),
            1, 3
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
        client.release(1)
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
        client.release(3)
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
        assertTrue(script.calls[3].first.any { it.content.contains("scenario-fail") })
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
        val lastRequest = script.calls[3].first
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
            script.calls[7].any { it.content.contains("全量重写提示") && it.content.contains("editfile") }
        )
        assertFalse("第二次覆盖不应提示", script.calls[6].any { it.content.contains("全量重写提示") })
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
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse("", toolCalls = listOf(
                    wcall("js/config.js", cfgFull, "c-cfg1"),
                    wcall("js/main.js", mainJs, "c-main1"),
                    wcall("index.html", entryHtml, "c-idx1")
                )),
                LlmResponse("完成"),
                // 轮 2（迭代："调整速度"本地正则 dev 0.9，无 Lite 消耗）：
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
                LlmResponse("""{"visualDimension":null,"screenOrientation":null,"gameSystems":[],"confidence":0.9,"hasGameCommand":true}"""),
                LlmResponse("", toolCalls = listOf(
                    wcall("js/config.js", cfgFull, "c-cfg1"),
                    wcall("js/main.js", mainJs, "c-main1"),
                    wcall("index.html", entryHtml, "c-idx1")
                )),
                LlmResponse("完成"),
                // 轮 2：入库后工作区随会话身份切换到 game-<id>（草稿工作区不迁移，与真机
                // 行为一致）——重写全三件套；本轮上下文应包含玩家参数覆盖清单
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
}
