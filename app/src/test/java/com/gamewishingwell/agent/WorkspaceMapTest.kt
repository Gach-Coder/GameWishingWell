package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作区文件地图（确定性符号索引）测试。 */
class WorkspaceMapTest {

    private fun bigJs(): String = buildString {
        repeat(WorkspaceMap.MIN_LINES_FOR_MAP) { append("// line $it\n") }
        append("function boot() { startMenu(); }\n")
        append("function startMenu(){ var menuOpen = true; }\n")
        append("window.WW = window.WW || {};\n")
    }

    @Test
    fun `大文件绘出符号行号-函数与window模块`() {
        val map = WorkspaceMap.render(mapOf("js/main.js" to bigJs()))
        assertNotNull(map)
        assertTrue(map!!.contains("js/main.js"))
        assertTrue("函数应带行号", map.contains("boot()@"))
        assertTrue(map.contains("startMenu()@"))
        assertTrue(map.contains("window.WW@"))
        assertTrue("应引导 grep 定位未尽符号", map.contains("grepfile"))
    }

    @Test
    fun `入口html绘出元素id行号`() {
        val html = buildString {
            repeat(WorkspaceMap.MIN_LINES_FOR_MAP) { append("<!-- padding $it -->\n") }
            append("<div id=\"menu\" class=\"overlay\"><button id=\"btnStart\">开始战斗</button></div>\n")
        }
        val map = WorkspaceMap.render(mapOf("index.html" to html))
        assertNotNull(map)
        assertTrue(map!!.contains("#menu@"))
        assertTrue(map.contains("#btnStart@"))
    }

    @Test
    fun `小工作区不注入地图`() {
        val small = mapOf(
            "index.html" to "<html><body><script>var a = 1;</script></body></html>",
            "js/main.js" to "function boot(){}\nfunction tick(){}"
        )
        assertNull(WorkspaceMap.render(small))
    }
}
