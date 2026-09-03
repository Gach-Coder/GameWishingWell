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
    fun `writefile 可覆盖已存在文件且版本递增`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        executor.execute(call(GameTools.WRITE_FILE, """{"content":"v1"}"""))
        val out = executor.execute(call(GameTools.WRITE_FILE, """{"content":"v2"}"""))
        // 一般 Agent 惯例：Write 支持新建与整量覆盖，不做策略门禁
        assertTrue(out.ok)
        assertTrue(out.mutated)
        assertEquals("v2", ws.read("index.html"))
        assertTrue(out.observation.contains("v2"))
        // 内容一致时不产生变更
        val same = executor.execute(call(GameTools.WRITE_FILE, """{"content":"v2"}"""))
        assertTrue(same.ok)
        assertFalse(same.mutated)
        assertTrue(same.observation.contains("完全一致"))
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
    fun `readfile 支持行区间`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val executor = GameToolExecutor(ws)
        ws.writeInitial("index.html", "l1\nl2\nl3\nl4\nl5")
        val full = executor.execute(call(GameTools.READ_FILE, "{}"))
        assertTrue(full.observation.contains("共 5 行"))
        assertTrue(full.observation.contains("l1"))
        val slice = executor.execute(call(GameTools.READ_FILE, """{"start_line":2,"end_line":3}"""))
        assertTrue(slice.ok)
        assertTrue(slice.observation.contains("第 2-3 行（共 5 行）"))
        assertTrue(slice.observation.contains("l2"))
        assertTrue(slice.observation.contains("l3"))
        assertFalse(slice.observation.contains("l5"))
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
