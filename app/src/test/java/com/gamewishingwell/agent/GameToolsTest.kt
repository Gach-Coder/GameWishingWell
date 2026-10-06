package com.gamewishingwell.agent

import com.gamewishingwell.data.ToolCallData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
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
        // 写入使清单变化，去重失效，重新全量返回（覆盖写入已被禁止，用局部 editfile 更新）
        executor.execute(call(GameTools.EDIT_FILE, """{"old_string":"body v1","new_string":"body v2"}"""))
        val out3 = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(out3.observation.contains("index.html"))
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
        // 空工作区指引与多文件契约同向：先写被引用的辅助文件、最后写入口
        // （历史缺陷：文案喊"先写 index.html"，与"先 js/css 后入口"的契约打架，
        //  实测 round 1 先写入口触发引用缺失报错白费一轮）
        assertTrue(empty.observation.contains("先写被引用的 js/css"))
        assertTrue(empty.observation.contains("最后写入口 index.html"))
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
        // 未闭合的 JSON 字符串 → Unexpected EOF（模拟输出在参数中途被截断）；
        // 截断不可修复（内容不完整），仍走截断指引
        val out = executor.execute(call(GameTools.WRITE_FILE, """{"content":"v2"""))
        assertFalse(out.ok)
        assertTrue(out.observation.contains("截断"))
        assertTrue(out.observation.contains("editfile"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `writefile 参数字符串内未转义换行制表被宽容修复后照常写入`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 模型/网关高发损坏（2026-09-07 真机实测，大文件参数高频）：代码换行/制表符按
        // 原始字符进 JSON 字符串（未转义 \n/\t），严格解析必失败而内容本身完好
        val args = "{\"path\":\"js/main.js\",\"content\":\"line1\nline2\tline3\"}"
        val out = executor.execute(call(GameTools.WRITE_FILE, args))
        assertTrue("宽容修复后应写入成功：${out.observation}", out.ok)
        assertEquals("line1\nline2\tline3", ws.read("js/main.js"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `控制字符转义只作用于字符串字面量内`() {
        // 字符串外的原始换行是合法空白（保留）；字符串内才转义；合法转义序列不被二次转义
        val src = "{\n  \"a\": \"x\\\"y\nz\",\n  \"b\": 1\n}"
        val fixed = escapeRawControlCharsInStrings(src)
        assertNotNull(fixed)
        val obj = Json.parseToJsonElement(fixed!!).jsonObject
        assertEquals("x\"y\nz", (obj["a"] as JsonPrimitive).content)
        assertEquals(1, (obj["b"] as JsonPrimitive).int)
        // 没有任何可修内容（参数本就合法）返回 null，不产生等价副本
        assertNull(escapeRawControlCharsInStrings("""{"a":"b"}"""))
    }

    @Test
    fun `参数 token 间的全角空格被归一为半角空白后照常写入`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 另一族高频损坏（真机实测签名 Expected quotation mark '"', but had ' '）：
        // 全角空格/不换行空格落在 JSON token 之间——严格 JSON 只认半角空白
        val fullWidthSpace = "\u3000"
        val nbsp = "\u00A0"
        val args = "{\"path\": \"js/iso.js\",$fullWidthSpace\"content\":$nbsp\"line1\nline2\"}"
        val out = executor.execute(call(GameTools.WRITE_FILE, args))
        assertTrue("归一后应写入成功：${out.observation}", out.ok)
        assertEquals("line1\nline2", ws.read("js/iso.js"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `readfile 支持行区间`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 超大文件（超出放宽后的整读预算）才保留真正的切片语义。
        val hugeN = GameTools.WHOLE_READ_MAX_LINES_LARGE + 10
        val huge = (1..hugeN).joinToString("\n") { "l$it" }
        ws.writeInitial("index.html", huge)
        val full = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(full.observation.contains("共 ${hugeN} 行"))
        assertTrue(full.observation.contains("l1"))
        val slice = executor.execute(call(GameTools.READ_FILE, """{"start_line":2,"end_line":3}"""))
        assertTrue(slice.ok)
        assertTrue(slice.observation.contains("第 2-3 行"))
        assertTrue(slice.observation.contains("l2"))
        assertTrue(slice.observation.contains("l3"))
        assertFalse(slice.observation.contains("l$hugeN\n"))
        assertTrue("超大文件切片应附定位提示", slice.observation.contains("outline"))
        // 行数超小阈值但在放宽预算内（行多内容小）的文件：切片请求仍整读（省往返）。
        val (ws2, dir2) = newWorkspace()
        val executor2 = GameToolExecutor(ws2)
        val wide = (1..GameTools.WHOLE_READ_MAX_LINES + 5).joinToString("\n") { "w$it" }
        ws2.writeInitial("index.html", wide)
        val relaxed = executor2.execute(call(GameTools.READ_FILE, """{"start_line":2,"end_line":3}"""))
        assertTrue(relaxed.ok)
        assertTrue("预算内大文件切片应被整读放宽", relaxed.observation.contains("已直接返回全文"))
        dir.deleteRecursively(); dir2.deleteRecursively()
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

    // ---------- grepfile（定位专用读取工具） ----------

    @Test
    fun `grepfile 正则与文本命中文件行号`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("js/main.js", "var menuEl = null;\nfunction boot() {\n  startMenu();\n}\nfunction startMenu(){\n  // 开始游戏弹窗\n}")
        ws.writeInitial("index.html", "<div id=\"menu\">开始游戏</div>")
        // 文本命中两个文件，带行号（js/main.js 第 1 行 menuEl + index.html 第 1 行 id="menu"）
        val out1 = executor.execute(call(GameTools.GREP_FILE, """{"pattern":"menu"}"""))
        assertTrue(out1.ok)
        assertTrue("grep输出：${out1.observation.take(300)}", out1.observation.contains("js/main.js"))
        assertTrue(out1.observation.contains("index.html"))
        assertTrue(out1.observation.contains("1: var menuEl = null;"))
        // path 限定单文件 + 中文文案
        val out2 = executor.execute(call(GameTools.GREP_FILE, """{"pattern":"开始游戏","path":"index.html"}"""))
        assertTrue(out2.ok)
        assertTrue(out2.observation.contains("1:"))
        assertFalse(out2.observation.contains("js/main.js"))
        // 无命中如实反馈
        val out3 = executor.execute(call(GameTools.GREP_FILE, """{"pattern":"不存在的东西"}"""))
        assertTrue(out3.ok)
        assertTrue(out3.observation.contains("未命中"))
        // 非法正则退回普通文本匹配
        val out4 = executor.execute(call(GameTools.GREP_FILE, """{"pattern":"boot("}"""))
        assertTrue(out4.ok)
        assertTrue(out4.observation.contains("2: function boot() {"))
        // 正常正则按正则语义匹配（raw string 单反斜杠即 JSON 转义，解码为 \s）
        val out5 = executor.execute(call(GameTools.GREP_FILE, """{"pattern":"function\\s+boot"}"""))
        assertTrue(out5.ok)
        assertTrue(out5.observation.contains("2: function boot() {"))
        dir.deleteRecursively()
        Unit
    }

    // ---------- 删除残留预检（写入时刻致命拦截） ----------

    @Test
    fun `editfile 删除声明留裸引用被致命拒绝且不落盘`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val start = "'use strict';\nvar bashQ = null;\nfunction newGame(){ bashQ = null; }\nfunction boot(){ newGame(); }"
        val executor = GameToolExecutor(ws, turnStartSnapshot = mapOf("js/main.js" to start))
        ws.writeInitial("js/main.js", start)
        // 多行 old_string 经 Json.encodeToString 编码（raw string 内 \\n 不构成换行转义）
        val out = executor.execute(
            call(GameTools.EDIT_FILE, """{"path":"js/main.js","old_string":${Json.encodeToString("var bashQ = null;\n")},"new_string":""}""")
        )
        assertFalse(out.ok)
        assertTrue(out.observation.contains("删除残留"))
        assertTrue(out.observation.contains("bashQ"))
        assertEquals("致命拒绝不得落盘", start, ws.read("js/main.js"))
        // 声明与引用一并删除则放行
        val okOut = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"js/main.js","old_string":${Json.encodeToString("var bashQ = null;\nfunction newGame(){ bashQ = null; }")},"new_string":"function newGame(){ }"}"""
            )
        )
        assertTrue(okOut.ok)
        dir.deleteRecursively()
        Unit
    }

    // ---------- 大幅删除咨询信号（非硬门） ----------

    @Test
    fun `大幅删除触发咨询但写入仍成功`() = runBlocking {
        val (ws, dir) = newWorkspace()
        // 20 个非空行起点；删掉 8 行（40%）——行相似度 2*12/(20+12)=0.75 仍过 0.35 硬门
        val origin = (1..20).joinToString("\n") { "line$it keep" }
        val cut = (13..20).joinToString("\n") { "line$it keep" }
        val executor = GameToolExecutor(ws, turnStartSnapshot = mapOf("index.html" to origin))
        ws.writeInitial("index.html", origin)
        val out = executor.execute(
            call(GameTools.EDIT_FILE, """{"old_string":${Json.encodeToString(cut)},"new_string":""}""")
        )
        assertTrue("编辑结果：${out.observation.take(300)}", out.ok)
        assertTrue("应附大幅删除提醒：${out.observation.take(200)}", out.observation.contains("大幅删除提醒"))
        assertTrue(out.observation.contains("40%"))
        dir.deleteRecursively()
        Unit
    }

    // ---------- 回合改动摘要（沙箱失败反馈附带） ----------

    @Test
    fun `回合改动摘要列出增删行数`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val origin = "a\nb\nc"
        val executor = GameToolExecutor(ws, turnStartSnapshot = mapOf("js/main.js" to origin))
        ws.writeInitial("js/main.js", origin)
        executor.execute(call(GameTools.EDIT_FILE, """{"path":"js/main.js","old_string":"c","new_string":"c2\\nd"}"""))
        val summary = executor.turnChangeSummary()
        assertTrue(summary.contains("js/main.js"))
        assertTrue(summary.contains("+1"))
        assertTrue(summary.contains("-1"))
        // 无改动的执行器摘要为空
        val (ws2, dir2) = newWorkspace()
        ws2.writeInitial("js/main.js", origin)
        val executor2 = GameToolExecutor(ws2, turnStartSnapshot = mapOf("js/main.js" to origin))
        assertTrue(executor2.turnChangeSummary().isEmpty())
        dir.deleteRecursively(); dir2.deleteRecursively()
        Unit
    }

    // ---------- 新增工具：outline 符号地图 / revertfile 单文件回滚 / 失配候选 ----------

    @Test
    fun `outline 返回符号地图与行号区间`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial(
            "js/main.js",
            "var CFGX = 1;\nfunction alpha() {\n  return 1;\n}\nclass Beta {\n  run() {}\n}\nconst GAMMA = 2;\n"
        )
        val out = executor.execute(call(GameTools.OUTLINE, """{"path":"js/main.js"}"""))
        assertTrue(out.ok)
        assertFalse(out.workspaceTouched)
        assertTrue(out.observation.contains("1-1 const CFGX"))
        assertTrue(out.observation.contains("2-4 function alpha"))
        assertTrue(out.observation.contains("5-7 class Beta"))
        assertTrue(out.observation.contains("8-9 const GAMMA"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile 失配时返回最相近候选段`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("js/main.js", "function f() {\n  var a = 1;\n  return a;\n}\n")
        // old_string 与原文高度相似但逐字符不一致（return 行少了分号）
        val miss = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"js/main.js","old_string":${Json.encodeToString("function f() {\n  var a = 1;\n  return a\n}")},"new_string":"g"}"""
            )
        )
        assertFalse(miss.ok)
        assertTrue(miss.observation.contains("未找到"))
        assertTrue("应给出最相近候选与行号：${miss.observation.take(200)}",
            miss.observation.contains("最相近") && miss.observation.contains("第 1 行"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `grepfile 同pattern同范围去重回指针，写入后失效`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("js/main.js", "function a(){}\nfunction b(){}\n")
        val q = """{"pattern":"function","path":"js/main.js"}"""
        val out1 = executor.execute(call(GameTools.GREP_FILE, q))
        assertTrue(out1.observation.contains("命中 2 行"))
        // 无写入时完全相同的重复搜索只回指针（结果在上文，不重发）
        val out2 = executor.execute(call(GameTools.GREP_FILE, q))
        assertTrue("重复搜索应回指针", out2.observation.contains("已在相同范围") && out2.observation.contains("搜索过"))
        assertFalse("指针不应重发命中清单", out2.observation.contains("命中 2 行"))
        // 不同范围是不同键：不受去重影响
        val out3 = executor.execute(call(GameTools.GREP_FILE, """{"pattern":"function"}"""))
        assertTrue(out3.observation.contains("命中"))
        // 写入使 grep 结果过期：去重清空，同 pattern 可重新搜索
        executor.execute(
            call(GameTools.EDIT_FILE, """{"path":"js/main.js","old_string":"function a(){}","new_string":"function a(x){}"}""")
        )
        val out4 = executor.execute(call(GameTools.GREP_FILE, q))
        assertTrue("写入后应可重新搜索", out4.observation.contains("命中 2 行"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `revertfile 单文件回滚到回合起点`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val origin = "function f() {\n  return 1;\n}\n"
        val executor = GameToolExecutor(ws, turnStartSnapshot = mapOf("js/main.js" to origin))
        ws.writeInitial("js/main.js", origin)
        // 本回合内修改该文件后回滚：内容恢复、计入工作区写入（读过期/空转清零同编辑）
        executor.execute(call(GameTools.EDIT_FILE, """{"path":"js/main.js","old_string":"return 1;","new_string":"return 2;"}"""))
        val out = executor.execute(call(GameTools.REVERT_FILE, """{"path":"js/main.js"}"""))
        assertTrue(out.ok)
        assertTrue(out.workspaceTouched)
        assertEquals(origin, ws.read("js/main.js"))
        assertTrue(out.observation.contains("回滚到本回合开始"))
        // 未修改过的文件：无操作
        val noop = executor.execute(call(GameTools.REVERT_FILE, """{"path":"index.html"}"""))
        assertTrue(noop.ok)
        assertFalse(noop.workspaceTouched)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile 空行与行尾空白差异唯一命中自动归一化应用`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 原文：块内多一个空行、第二行有行尾空格；old_string 空行位置不同且无行尾空格
        val origin = "function f() {\n  var a = 1;   \n\n  return a;\n}\nfunction g() {\n  return 2;\n}\n"
        ws.writeInitial("js/main.js", origin)
        val out = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"js/main.js","old_string":${Json.encodeToString("function f() {\n  var a = 1;\n  return a;\n}")},"new_string":"function f() {\n  var a = 2;\n  return a;\n}"}"""
            )
        )
        assertTrue("应自动归一化应用：${out.observation.take(200)}", out.ok)
        assertTrue(out.observation.contains("空白归一化"))
        // 区间外逐字保留（g 函数与原空行结构不变），区间内被 new_string 整段替换
        assertEquals(
            "function f() {\n  var a = 2;\n  return a;\n}\nfunction g() {\n  return 2;\n}\n",
            ws.read("js/main.js")
        )
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile 归一化后多处同形不自动应用回退候选提示`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        val origin = "function tick() {\n  step();\n}\n\nfunction draw() {\n  step();\n}\n"
        ws.writeInitial("js/main.js", origin)
        // "step();" 在两处出现，且带不同空白包装——归一化同形，绝不猜
        val out = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"js/main.js","old_string":${Json.encodeToString("\n  step();\n\n")},"new_string":"  step(2);\n"}"""
            )
        )
        assertFalse(out.ok)
        assertTrue(out.observation.contains("未找到"))
        assertTrue("多处同形应维持失败提示而非自动应用", !out.observation.contains("已按空白归一化"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile anchor_line 窗口消歧同文多处`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 同一段代码出现在第 2 行与第 102 行（相距超过 ±40 窗口），anchor 指向后者
        val block = "function dup() {\n  return 1;\n}\n"
        val filler = (1..97).joinToString("\n") { "// filler $it" }
        val origin = block + "\n" + filler + "\n" + block
        ws.writeInitial("js/main.js", origin)
        val out = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"js/main.js","old_string":${Json.encodeToString(block)},"new_string":${Json.encodeToString("function dup() {\n  return 2;\n}\n")},"anchor_line":101}"""
            )
        )
        assertTrue("锚定窗口应消歧并应用：${out.observation.take(200)}", out.ok)
        assertTrue(out.observation.contains("窗口匹配应用"))
        val updated = ws.read("js/main.js")!!
        assertTrue("第一处保持原样", updated.contains("return 1;"))
        assertTrue("第二处被替换", updated.contains("return 2;"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `editfile anchor_line 窗口未命中回退全文唯一匹配`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 目标在 101 行，anchor 指向第 1 行（±40 窗口够不到）；全文唯一命中仍应应用并注明回退
        val filler = (1..99).joinToString("\n") { "// pad $it" }
        val origin = "$filler\nvar b = 2;\n"
        ws.writeInitial("js/main.js", origin)
        val out = executor.execute(
            call(
                GameTools.EDIT_FILE,
                """{"path":"js/main.js","old_string":"var b = 2;","new_string":"var b = 20;","anchor_line":1}"""
            )
        )
        assertTrue(out.ok)
        assertTrue(out.observation.contains("回退全文匹配"))
        assertTrue(ws.read("js/main.js")!!.contains("var b = 20;"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `listfiles 超阈值 js 文件附一次性拆分建议`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        // 4200 行 × 每行 ~27 字节 ≈ 113KB，同时越过行数与字节双门槛
        val bigJs = (1..4200).joinToString("\n") { "var pad$it = 1111111111111;" } + "\n"
        ws.writeInitial("js/main.js", bigJs)
        ws.writeInitial("index.html", validHtml)
        val out1 = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertTrue(out1.observation.contains("拆分"))
        assertTrue(out1.observation.contains("4000"))
        // 同回合重复 listfiles（清单未变走指针前）：建议只给一次——此处验证去重指针路径不含建议
        val out2 = executor.execute(call(GameTools.LIST_FILES, "{}"))
        assertFalse(out2.observation.contains("⚠"))
        dir.deleteRecursively()
        Unit
    }

}
