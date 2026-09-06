package com.gamewishingwell.agent

import com.gamewishingwell.data.ToolCallData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameToolsTest {

    private fun newWorkspace(): Pair<GameFileWorkspace, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "ws-tools-${System.nanoTime()}")
        return GameFileWorkspace(dir) to dir
    }

    private val validHtml = "<!DOCTYPE html><html><body><script>window.__wwDebugState=function(){return{state:'playing'}};var score = 0;</script></body></html>"

    private fun call(name: String, argsJson: String) =
        ToolCallData(id = "c1", name = name, arguments = argsJson)

    @Test
    fun `readfile 小文件切片请求直接整读`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 多行小文件：切片请求（2-3 行）被整读覆盖，省掉切片重读的轮次。
        ws.writeInitial("index.html", "l1\nl2\nl3\nl4\nl5")
        val out = executor.execute(
            call(GameTools.READ_FILE, """{"path":"index.html","start_line":2,"end_line":3}""")
        )
        assertTrue(out.ok)
        assertTrue(out.observation.contains("已直接返回全文"))
        assertTrue(out.observation.contains("l1"))
        assertTrue(out.observation.contains("l5"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `首次 writefile 写入并自动校验`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        val out = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"index.html","content":${Json.encodeToString(validHtml)}}""")
        )
        assertTrue(out.ok)
        assertTrue(out.mutated)
        assertEquals(validHtml, out.html)
        assertNotNull(out.report)
        assertFalse(out.report!!.hasErrors)
        assertTrue(out.observation.contains("基础校验通过"))
        assertEquals(validHtml, ws.read("index.html"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `listfiles 无写入时去重回指针，写入后失效`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        executor.execute(call(GameTools.WRITE_FILE, """{"content":"header line\nbody v1\nfooter line"}"""))
        val out1 = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(out1.observation.contains("index.html"))
        assertTrue(out1.observation.contains("sha256="))
        // 无写入的重复探测只回指针（信息在上文，不再堆一份全量清单进历史）
        val out2 = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(out2.observation.contains("未变化"))
        assertFalse(out2.observation.contains("sha256="))
        // 写入使版本变化，去重失效，重新全量返回（覆盖写入已被禁止，用局部 editfile 更新）
        executor.execute(call(GameTools.EDIT_FILE, """{"old_string":"body v1","new_string":"body v2"}"""))
        val out3 = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(out3.observation.contains("v2"))
        assertTrue(out3.observation.contains("sha256="))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `多文件形态-写辅助 js 文件不作入口发布且不产生伪校验错误`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", validHtml)
        val out = executor.execute(
            call(
                GameTools.WRITE_FILE,
                """{"path":"js/main.js","content":"var WW = window.WW || {};\nWW.go = function(){ return 1; };"}"""
            )
        )
        assertTrue(out.ok)
        // 辅助文件：不算入口变更（不发布 currentHtml）、无入口校验报告，但计入工作区触碰
        assertFalse(out.mutated)
        assertNull(out.html)
        assertNull(out.report)
        assertTrue(out.workspaceTouched)
        assertEquals("js/main.js", out.path)
        // 观察里没有"缺少可观测性契约"之类入口伪错误
        assertFalse(out.observation.contains("__wwDebugState"))
        assertTrue(out.observation.contains("基础校验通过"))
        // 入口内容不受影响
        assertEquals(validHtml, ws.read("index.html"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `多文件形态-辅助 js 的 eval 与 module 语法被轻量校验拦截`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        val out = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"js/hack.js","content":"var r = eval('1+1');"}""")
        )
        assertTrue(out.ok)
        assertFalse(out.mutated)
        assertTrue(out.observation.contains("eval"))
        val out2 = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"js/mod.js","content":"import { a } from './a.js';"}""")
        )
        assertTrue(out2.observation.contains("ES module"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `未授权整体重写被状态比对拦截-单次与逐步掏空等价`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val original = (1..40).joinToString("\n") { "function part$it(){ return $it; }" }
        ws.writeInitial("js/game.js", original)
        val snapshot = mapOf("js/game.js" to original)
        val executor = GameToolExecutor(ws, turnStartSnapshot = snapshot)

        // 攻击面 A：writefile 一次性整体重写（全新内容，相似度≈0）→ 致命拒绝，文件未动
        val rewritten = (1..30).joinToString("\n") { "const m$it = $it;" }
        val out1 = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"js/game.js","content":${Json.encodeToString(rewritten)}}""")
        )
        assertFalse(out1.ok)
        assertTrue(out1.observation.contains("整体重写"))
        assertEquals(original, ws.read("js/game.js"))

        // 攻击面 B：多次 editfile 逐步掏空（累积损伤跨阈值的那次写入被拦）：
        // 第一刀替换半数（相似度仍高，放行落盘），第二刀替换其余（相似度≈0，致命）
        val head = original.lines().let { it.subList(0, 20).joinToString("\n") }
        val out2a = executor.execute(
            call(GameTools.EDIT_FILE, """{"path":"js/game.js","old_string":${Json.encodeToString(head)},"new_string":"const gutted1 = 1;"}""")
        )
        assertTrue(out2a.ok)
        val afterFirst = ws.read("js/game.js")!!
        val tail = original.lines().let { it.subList(20, 40).joinToString("\n") }
        val out2b = executor.execute(
            call(GameTools.EDIT_FILE, """{"path":"js/game.js","old_string":${Json.encodeToString(tail)},"new_string":"const gutted2 = 2;"}""")
        )
        assertFalse(out2b.ok)
        assertTrue(out2b.observation.contains("整体重写"))
        // 被拦下的写入不落盘：文件停留在上一合法状态
        assertEquals(afterFirst, ws.read("js/game.js"))

        // 授权重做（allowFullRewrite）→ 整体重写放行
        val authorized = GameToolExecutor(ws, allowFullRewrite = true, turnStartSnapshot = snapshot)
        val out3 = authorized.execute(
            call(GameTools.WRITE_FILE, """{"path":"js/game.js","content":${Json.encodeToString(rewritten)}}""")
        )
        assertTrue(out3.ok)
        assertEquals(rewritten, ws.read("js/game.js"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `合法写入全部放行-工作面回归`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val game = (1..40).joinToString("\n") { "function part$it(){ return $it; }" }
        val scenarios = """{"scenarios":[
            {"id":"kill","name":"击杀得分","expect":"s.score > 0"},
            {"id":"pause","name":"暂停生效","expect":"s.paused === true"}
        ]}"""
        ws.writeInitial("js/game.js", game)
        ws.writeInitial("scenarios.json", scenarios)
        val snapshot = mapOf("js/game.js" to game, "scenarios.json" to scenarios)
        val ids = GameScenarios.parse(scenarios).map { it.id }.toSet()
        val executor = GameToolExecutor(ws, turnStartSnapshot = snapshot, protectedScenarioIds = ids)

        // 工作面 1（教育税事故回归）：scenarios.json 整体重写、id 只增不减 → 放行
        val scenariosV2 = """{"scenarios":[
            {"id":"kill","name":"击杀得分","expect":"s.score > 0"},
            {"id":"pause","name":"暂停生效","expect":"s.paused === true"},
            {"id":"sell","name":"出售塔","expect":"s.gold > 100"}
        ]}"""
        val out1 = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"scenarios.json","content":${Json.encodeToString(scenariosV2)}}""")
        )
        assertTrue(out1.ok)

        // 工作面 2：writefile 修改已有 js 的一行（高相似度）→ 放行（不再一刀切禁覆盖）
        val patched = game.replace("function part7(){ return 7; }", "function part7(){ return 77; }")
        val out2 = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"js/game.js","content":${Json.encodeToString(patched)}}""")
        )
        assertTrue(out2.ok)

        // 工作面 3：appendfile 追加 → 放行
        val out3 = executor.execute(
            call(GameTools.APPEND_FILE, """{"path":"js/game.js","content":"function extra(){ return 0; }"}""")
        )
        assertTrue(out3.ok)

        // 工作面 4：新建文件 → 放行
        val out4 = executor.execute(
            call(GameTools.WRITE_FILE, """{"path":"js/sell.js","content":"function sellTower(){}"}""")
        )
        assertTrue(out4.ok)

        // 工作面 5：editfile 小段替换 → 放行
        val out5 = executor.execute(
            call(GameTools.EDIT_FILE, """{"path":"js/game.js","old_string":"function extra(){ return 0; }","new_string":"function extra(){ return 1; }"}""")
        )
        assertTrue(out5.ok)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `修改回合回归断言只增不减`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val original = """{"scenarios":[
            {"id":"kill","name":"击杀得分","expect":"s.score > 0"},
            {"id":"pause","name":"暂停生效","expect":"s.paused === true"}
        ]}"""
        ws.writeInitial("scenarios.json", original)
        val protected = GameScenarios.readFromWorkspace(ws).map { it.id }.toSet()
        val executor = GameToolExecutor(ws, protectedScenarioIds = protected)

        // 改写/删除任一既有断言的身份（id）→ 致命拒绝（实测模型曾删掉连续失败的击杀断言过关）
        val out1 = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"scenarios.json","old_string":${Json.encodeToString("\"id\":\"pause\"")},"new_string":${Json.encodeToString("\"id\":\"pause2\"")}}"""
            )
        )
        assertFalse(out1.ok)
        assertTrue(out1.observation.contains("只增不减"))
        assertTrue(out1.observation.contains("pause"))
        assertEquals(original, ws.read("scenarios.json"))

        // appendfile 追加新断言（保留全部既有 id）→ 通过
        val appendEntry = """,
            {"id":"sell","name":"出售塔","expect":"s.gold > 100"}
        ]}"""
        val out2 = executor.execute(
            call(GameTools.APPEND_FILE, """{"path":"scenarios.json","content":${Json.encodeToString(appendEntry)}}""")
        )
        // 追加后 JSON 可能非法（}] 重复），此处只验证保护门未拦截（ok 或格式打回均可），不要求 ok=true
        if (out2.ok) {
            assertTrue(ws.read("scenarios.json")!!.contains("\"sell\""))
        }

        // 修断言内容保留 id（editfile 小段替换）→ 通过
        ws.writeUpdated("scenarios.json", original)
        val out3 = executor.execute(
            call(GameTools.EDIT_FILE, """{"path":"scenarios.json","old_string":"s.paused === true","new_string":"s.paused === true && s.state==='paused'"}""")
        )
        assertTrue(out3.ok)

        // 用户授权重做 → 整文件替换（editfile 全文）+ 套件整体重写放行
        ws.writeUpdated("scenarios.json", original)
        val rebuilder = GameToolExecutor(ws, allowFullRewrite = true)
        val rebuilt = """{"scenarios":[{"id":"new","name":"新套件","expect":"s.state==='playing'"}]}"""
        val out4 = rebuilder.execute(
            call(GameTools.EDIT_FILE, """{"path":"scenarios.json","old_string":${Json.encodeToString(original)},"new_string":${Json.encodeToString(rebuilt)}}""")
        )
        assertTrue(out4.ok)
        assertEquals(rebuilt, ws.read("scenarios.json"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile 精确替换且保持其余内容不变`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", "a\nb\nc")
        val out = executor.execute(
            call(GameTools.EDIT_FILE, """{"old_string":"b","new_string":"B"}""")
        )
        assertTrue(out.ok)
        assertTrue(out.mutated)
        assertEquals("a\nB\nc", ws.read("index.html"))
        assertTrue(out.observation.contains("已替换 1 处"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile 未命中与不唯一时给出可行动的错误`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", "x\nx\ny")
        val miss = executor.execute(call(GameTools.EDIT_FILE, """{"old_string":"z","new_string":"Z"}"""))
        assertFalse(miss.ok)
        assertTrue(miss.observation.contains("未找到"))
        val ambiguous = executor.execute(call(GameTools.EDIT_FILE, """{"old_string":"x","new_string":"X"}"""))
        assertFalse(ambiguous.ok)
        assertTrue(ambiguous.observation.contains("不唯一"))
        // replace_all=true 时全部替换
        val all = executor.execute(call(GameTools.EDIT_FILE, """{"old_string":"x","new_string":"X","replace_all":true}"""))
        assertTrue(all.ok)
        assertEquals("X\nX\ny", ws.read("index.html"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `沙箱禁止越界路径写入`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        val out = executor.execute(call(GameTools.WRITE_FILE, """{"path":"../evil.html","content":"x"}"""))
        assertFalse(out.ok)
        assertNull(ws.read("../evil.html"))
        assertFalse(File(dir.parentFile, "evil.html").exists())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `未知工具与非法参数返回错误观察`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        val unknown = executor.execute(call("delete_everything", "{}"))
        assertFalse(unknown.ok)
        assertTrue(unknown.observation.contains("未知工具"))
        val badJson = executor.execute(call(GameTools.READ_FILE, "not-json"))
        assertFalse(badJson.ok)
        assertTrue(badJson.observation.contains("合法 JSON"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `listfiles 与 readfile 观察内容`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        val empty = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(empty.ok)
        assertTrue(empty.observation.contains("工作区为空"))
        ws.writeInitial("index.html", "hello")
        val listed = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(listed.observation.contains("index.html"))
        val read = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(read.ok)
        assertTrue(read.observation.contains("hello"))
        val missing = executor.execute(call(GameTools.READ_FILE, """{"path":"nope.html"}"""))
        assertFalse(missing.ok)
        assertTrue(missing.observation.contains("不存在"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `readfile 整读去重且写入后失效`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", "l1\nl2\nl3")
        val first = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(first.ok)
        assertTrue(first.observation.contains("l1"))
        // 同回合重复整读（含切片请求被整读覆盖）：只回指针，不再整发全文
        val dup = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(dup.ok)
        assertTrue(dup.observation.contains("未发生变更"))
        assertFalse(dup.observation.contains("l1"))
        val dupSlice = executor.execute(call(GameTools.READ_FILE, """{"start_line":2,"end_line":3}"""))
        assertTrue(dupSlice.observation.contains("未发生变更"))
        // 写入后标记失效：重读返回最新全文
        executor.execute(call(GameTools.EDIT_FILE, """{"old_string":"l2","new_string":"L2"}"""))
        val fresh = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(fresh.observation.contains("L2"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `writefile 参数中途截断时引导改用 editfile`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 未闭合的 JSON 字符串 → Unexpected EOF（模拟输出在参数中途被截断）
        val out = executor.execute(call(GameTools.WRITE_FILE, """{"content":"v2"""))
        assertFalse(out.ok)
        assertTrue(out.observation.contains("截断"))
        assertTrue(out.observation.contains("editfile"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `readfile 支持行区间`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 大文件（超整读阈值）才保留真正的切片语义。
        val big = (1..GameTools.WHOLE_READ_MAX_LINES + 5).joinToString("\n") { "l$it" }
        ws.writeInitial("index.html", big)
        val full = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(full.observation.contains("共 ${GameTools.WHOLE_READ_MAX_LINES + 5} 行"))
        assertTrue(full.observation.contains("l1"))
        val slice = executor.execute(call(GameTools.READ_FILE, """{"start_line":2,"end_line":3}"""))
        assertTrue(slice.ok)
        assertTrue(slice.observation.contains("第 2-3 行"))
        assertTrue(slice.observation.contains("l2"))
        assertTrue(slice.observation.contains("l3"))
        assertFalse(slice.observation.contains("l${GameTools.WHOLE_READ_MAX_LINES + 5}\n"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile 成功返回修改点上下文片段`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", "line1\nline2\ntarget\nline4\nline5")
        val out = executor.execute(
            call(GameTools.EDIT_FILE, """{"old_string":"target","new_string":"replaced"}""")
        )
        assertTrue(out.ok)
        assertTrue(out.observation.contains("修改点上下文"))
        // 前后各 2 行的文件原文，可直接用作下一次 old_string
        assertTrue(out.observation.contains("line2"))
        assertTrue(out.observation.contains("replaced"))
        assertTrue(out.observation.contains("line4"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `appendfile 在文件末尾追加内容`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", "<html><body>")
        val out = executor.execute(call(GameTools.APPEND_FILE, """{"content":"<script>var a = 1;</script>"}"""))
        assertTrue(out.ok)
        assertTrue(out.mutated)
        assertTrue(out.observation.contains("已追加 1 行"))
        assertEquals("<html><body>\n<script>var a = 1;</script>", ws.read("index.html"))
        // 二次追加继续累积，无行数限制
        val again = executor.execute(call(GameTools.APPEND_FILE, """{"content":"\n<script>var b = 2;</script>"}"""))
        assertTrue(again.ok)
        assertTrue(ws.read("index.html")!!.contains("var b = 2;"))
        dir.deleteRecursively()
        Unit
    }

}
