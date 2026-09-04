package com.gamewishingwell.agent

import com.gamewishingwell.data.ToolCallData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 功能断言（scenarios.json）：解析清洗、档位门控、探针注入与工具执行分流。 */
class GameScenarioTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 解析与清洗 ----------

    @Test
    fun `parse accepts wrapper object`() {
        val raw = """
            {"scenarios":[
              {"id":"score","system":"战斗","name":"击杀得分","steps":[{"tap":[50,80]},{"frames":24}],"expect":"s.score > 0"},
              {"id":"state","name":"开局进入运行态","expect":"s.state === 'playing'"}
            ]}
        """.trimIndent()
        val list = GameScenarios.parse(raw)
        assertEquals(2, list.size)
        assertEquals("战斗", list[0].system)
        assertEquals(listOf(ScenarioStep(tap = listOf(50.0, 80.0)), ScenarioStep(frames = 24)), list[0].steps)
        assertEquals("s.score > 0", list[0].expect)
        // 无 steps 时默认推进 DEFAULT_FRAMES 帧。
        assertEquals(listOf(ScenarioStep(frames = GameScenarios.DEFAULT_FRAMES)), list[1].steps)
    }

    @Test
    fun `parse accepts bare array and code fence`() {
        val raw = """
            ```json
            [{"id":"a","name":"A","expect":"s.entities.length >= 1"}]
            ```
        """.trimIndent()
        val list = GameScenarios.parse(raw)
        assertEquals(1, list.size)
        assertEquals("a", list[0].id)
    }

    @Test
    fun `parse caps scenarios steps frames and coordinates`() {
        val many = (1..8).joinToString(",") { i ->
            """{"id":"s$i","name":"S$i","expect":"s.score > $i"}"""
        }
        assertEquals(GameScenarios.MAX_SCENARIOS, GameScenarios.parse("[$many]").size)

        val longSteps = (1..3).joinToString(",") { """{"frames":1}""" }
        val list = GameScenarios.parse(
            """[{"id":"x","name":"X","steps":[$longSteps,{"tap":[200,-5]},{"frames":9999}],"expect":"s.score>0"}]"""
        )
        assertEquals(1, list.size)
        assertEquals(5, list[0].steps.size)
        // 超界百分比被钳制到 0-100，帧数钳制到每断言预算。
        assertEquals(listOf(100.0, 0.0), list[0].steps[3].tap)
        assertEquals(GameScenarios.MAX_FRAMES_PER_SCENARIO, list[0].steps[4].frames)
    }

    @Test
    fun `parse drops entries with missing expect name or oversized expect`() {
        val tooLong = "s.a" + " + 1".repeat(200)
        val list = GameScenarios.parse(
            """[
              {"id":"no-expect","name":"E","expect":""},
              {"id":"too-long","name":"T","expect":"$tooLong"},
              {"id":"ok","name":"OK","expect":"s.score>0"}
            ]"""
        )
        // 无 expect/超长被丢弃；无 name 时回退用 id 作标签（可定位，不丢弃）。
        assertEquals(listOf("ok"), list.map { it.id })
        assertEquals(
            listOf("fallback-id"),
            GameScenarios.parse("""[{"id":"fallback-id","expect":"s.score>0"}]""").map { it.name }
        )
    }

    @Test
    fun `parse returns empty on garbage`() {
        assertTrue(GameScenarios.parse(null).isEmpty())
        assertTrue(GameScenarios.parse("").isEmpty())
        assertTrue(GameScenarios.parse("not json at all").isEmpty())
        assertTrue(GameScenarios.parse("""{"scenarios":[]}""").isEmpty())
        assertTrue(GameScenarios.parse("""[{"broken":}]""").isEmpty())
    }

    @Test
    fun `toJson round trips through parse`() {
        val original = listOf(
            GameScenario(
                id = "r1", system = "收集", name = "拾取计数",
                steps = listOf(ScenarioStep(tap = listOf(30.0, 60.0)), ScenarioStep(frames = 12)),
                expect = "s.coins >= 3"
            )
        )
        val parsed = GameScenarios.parse(GameScenarios.toJson(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `checkObservation reports parse result`() {
        val (okGood, textGood) = GameScenarios.checkObservation(
            """{"scenarios":[{"id":"a","system":"战斗","name":"A","expect":"s.score>0"}]}"""
        )
        assertTrue(okGood)
        assertTrue(textGood.contains("共 1 条"))

        val (okBad, textBad) = GameScenarios.checkObservation("{broken")
        assertFalse(okBad)
        assertTrue(textBad.contains("必须输出合法 JSON"))
    }

    @Test
    fun `checkObservation rejects tautological expects`() {
        // >=0 对计数/长度恒真；!== undefined/null 是存在性检查，都等于没测。
        val (ok1, text1) = GameScenarios.checkObservation(
            """{"scenarios":[{"id":"a","name":"A","expect":"s.entities.length >= 0 && s.score > 0"}]}"""
        )
        assertFalse(ok1)
        assertTrue(text1.contains("恒真"))

        val (ok2, _) = GameScenarios.checkObservation(
            """{"scenarios":[{"id":"c","name":"C","expect":"s.player !== null"}]}"""
        )
        assertFalse(ok2)

        val (ok3, _) = GameScenarios.checkObservation(
            """{"scenarios":[{"id":"d","name":"D","expect":"typeof s.score !== 'undefined'"}]}"""
        )
        assertFalse(ok3)

        // 合法阈值不受影响：>=1、>0、>=0.5（小数边界）都不是恒真。
        val (ok4, _) = GameScenarios.checkObservation(
            """{"scenarios":[{"id":"b","name":"B","expect":"s.wave >= 1 || s.player.hp >= 0.5"}]}"""
        )
        assertTrue(ok4)
    }

    @Test
    fun `scenario tier gating is balanced and premium only`() {
        assertFalse(GameScenarios.enabledForTier(QualityTier.FAST))
        assertFalse(GameScenarios.enabledForTier(QualityTier.LIGHT))
        assertTrue(GameScenarios.enabledForTier(QualityTier.BALANCED))
        assertTrue(GameScenarios.enabledForTier(QualityTier.PREMIUM))
    }

    // ---------- 探针注入 ----------

    @Test
    fun `probe inject embeds scenario data tag only when provided`() {
        val html = "<html><head><title>t</title></head><body></body></html>"
        val dataTagMarker = """<script type="application/json" id="__wwScenarioData">"""
        // 无场景时不注入数据标签（探针脚本里的读取代码仍会引用该 id，属正常）。
        val noScen = SmokeTestProbe.inject(html)
        assertFalse(noScen.contains(dataTagMarker))

        val withScen = SmokeTestProbe.inject(
            html,
            scenariosJson = """[{"id":"a","name":"A","expect":"s.score>0"}]"""
        )
        assertTrue(withScen.contains(dataTagMarker))
        assertTrue(withScen.contains(""""id":"a""""))
        // 数据标签在探针脚本之前（探针执行时元素已可读）。
        assertTrue(withScen.indexOf(dataTagMarker) < withScen.indexOf("__wwSmokeInstalled"))

        // 幂等：已注入的 HTML 不重复注入。
        assertEquals(withScen, SmokeTestProbe.inject(withScen, scenariosJson = "[]"))
    }

    @Test
    fun `probe scenario phase and dynamic limit are present in injected script`() {
        val injected = SmokeTestProbe.inject("<html></html>", scenariosJson = "[]")
        assertTrue(injected.contains("runScenarioPhase"))
        assertTrue(injected.contains("scenStep"))
        // rAF 队列阈值改为动态 limit（场景阶段/deep 复跑各有独立帧预算）。
        assertTrue(injected.contains("if (ticked < limit)"))
        assertFalse(injected.contains("if (ticked < 24)"))
    }

    // ---------- 工作区与工具分流 ----------

    @Test
    fun `executor routes scenarios json write away from html publish`(): Unit = runBlocking {
        val workspace = GameFileWorkspace(tmp.newFolder())
        val executor = GameToolExecutor(workspace)

        // 先写入口文件。
        val htmlOutcome = executor.execute(
            ToolCallData("t1", GameTools.WRITE_FILE, """{"path":"index.html","content":"<html>__wwDebugState</html>"}""")
        )
        assertTrue(htmlOutcome.mutated)
        assertEquals("<html>__wwDebugState</html>", htmlOutcome.html)

        // scenarios.json：写入成功但不作为 currentHtml 发布，附断言解析观察。
        val scenOutcome = executor.execute(
            ToolCallData(
                "t2", GameTools.WRITE_FILE,
                """{"path":"scenarios.json","content":"{\"scenarios\":[{\"id\":\"a\",\"system\":\"战斗\",\"name\":\"A\",\"expect\":\"s.score>0\"}]}"}"""
            )
        )
        assertTrue(scenOutcome.workspaceTouched)
        assertFalse(scenOutcome.mutated)
        assertNull(scenOutcome.html)
        assertTrue(scenOutcome.observation.contains("共 1 条"))

        // 非法断言文件：ok=false 反馈模型重写。
        val badOutcome = executor.execute(
            ToolCallData("t3", GameTools.WRITE_FILE, """{"path":"scenarios.json","content":"{broken"}""")
        )
        assertFalse(badOutcome.ok)
        assertTrue(badOutcome.observation.contains("合法 JSON"))

        // 入口指针不被 scenarios.json 写入带偏。
        assertEquals("index.html", workspace.manifest().pointer)
    }

    @Test
    fun `workspace read yields scenarios and fresh seed clears them`(): Unit = runBlocking {
        val workspace = GameFileWorkspace(tmp.newFolder())
        assertNull(workspace.read(GameScenarios.FILE))
        assertTrue(GameScenarios.readFromWorkspace(workspace).isEmpty())

        workspace.writeInitial(
            GameScenarios.FILE,
            """{"scenarios":[{"id":"a","name":"A","expect":"s.score>0"}]}"""
        )
        assertEquals(1, GameScenarios.readFromWorkspace(workspace).size)
    }
}
