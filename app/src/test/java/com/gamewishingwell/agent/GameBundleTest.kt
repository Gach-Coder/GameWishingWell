package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameBundleTest {

    @Test
    fun `本地 script 与 stylesheet 内联为自包含页面`() {
        val entry = """<!DOCTYPE html><html><head>
<link rel="stylesheet" href="css/style.css">
<script src="js/main.js"></script>
</head><body><canvas id="g"></canvas></body></html>"""
        val files = mapOf(
            "css/style.css" to "body{margin:0}",
            "js/main.js" to "var Game = { score: 0 };"
        )
        val out = GameBundle.inline(entry) { files[it] }

        // 外部形态：不再有 src/href 引用，内容以内联形态出现且顺序保持
        assertFalse(out.contains("src=\"js/main.js\""))
        assertFalse(out.contains("href=\"css/style.css\""))
        assertTrue(out.contains("body{margin:0}"))
        assertTrue(out.contains("var Game = { score: 0 };"))
        // 内联脚本在 body 之前（原 script 位置不变——加载顺序语义等价）
        assertTrue(out.indexOf("var Game") in 0 until out.indexOf("<canvas"))
    }

    @Test
    fun `引用的文件缺失时保持原样留给校验器报错`() {
        val entry = "<html><head><script src=\"js/missing.js\"></script></head><body></body></html>"
        val out = GameBundle.inline(entry) { null }
        assertEquals(entry, out)
    }

    @Test
    fun `内联 JS 中的闭合标签字面量被转义`() {
        // JS 字符串里出现 </script 会提前终止标签；替换为 <\/script 在字符串/正则/注释中均合法等价
        val entry = "<html><body><script src=\"a.js\"></script></body></html>"
        val out = GameBundle.inline(entry) { "var s = '</script>';" }
        assertTrue(out.contains("""var s = '<\/script>';"""))
        assertFalse(out.contains("'</script>'"))
    }

    @Test
    fun `外部 URL 与 data 引用不经过文件读取`() {
        val entry = "<html><head>" +
            "<script src=\"https://cdn.example.com/x.js\"></script>" +
            "<script src=\"data:text/javascript,alert(1)\"></script>" +
            "</head><body></body></html>"
        var readCount = 0
        val out = GameBundle.inline(entry) { readCount++; null }
        assertEquals(0, readCount)
        assertTrue(out.contains("https://cdn.example.com/x.js"))
        assertTrue(out.contains("data:text/javascript"))
    }

    @Test
    fun `路径守卫由 read 回调负责-越界路径取不到内容`() {
        // read 回调按 GameFileWorkspace 的守卫语义实现：越界路径返回 null → 引用保持原样
        val entry = "<html><body><script src=\"../../secret.js\"></script></body></html>"
        val out = GameBundle.inline(entry) { rel -> if (rel.contains("..")) null else "ok" }
        assertTrue(out.contains("../../secret.js"))
    }

    @Test
    fun `无本地引用的入口原样返回`() {
        val entry = "<html><body><script>var x=1;</script></body></html>"
        assertEquals(entry, GameBundle.inline(entry) { fail("不应读取任何文件"); @Suppress("UNREACHABLE_CODE") null })
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
