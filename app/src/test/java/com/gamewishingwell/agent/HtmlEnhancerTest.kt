package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlEnhancerTest {

    private val htmlWithHead = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><script>var x=1;</script></body></html>"
    private val htmlWithoutHead = "<!DOCTYPE html><html><body><canvas id=\"g\"></canvas><script>game()</script></body></html>"
    private val bareFragment = "<script>game()</script>"

    @Test
    fun `有 head 时注入到 head 之后`() {
        val out = HtmlEnhancer.inject(htmlWithHead)
        assertTrue(out.contains("unhandledrejection"))
        assertTrue(out.indexOf("unhandledrejection") > out.indexOf("<head>"))
        assertTrue(out.indexOf("unhandledrejection") < out.indexOf("<body>"))
    }

    @Test
    fun `无 head 时注入到 html 标签之后`() {
        val out = HtmlEnhancer.inject(htmlWithoutHead)
        assertTrue(out.contains("unhandledrejection"))
        assertTrue(out.indexOf("unhandledrejection") > out.indexOf("<html>"))
        assertTrue(out.indexOf("unhandledrejection") < out.indexOf("<body>"))
    }

    @Test
    fun `无完整结构时前置注入`() {
        val out = HtmlEnhancer.inject(bareFragment)
        assertTrue(out.startsWith("<script>"))
        assertTrue(out.contains("unhandledrejection"))
        assertTrue(out.endsWith("</script>"))
    }

    @Test
    fun `已有捕获器时不重复注入`() {
        val already = "<html><head><script>window.addEventListener('unhandledrejection', function(){});</script></head><body></body></html>"
        val out = HtmlEnhancer.inject(already)
        assertEquals(1, Regex("unhandledrejection").findAll(out).count())
        assertTrue(out.contains("__wwViewportFixApplied"))
    }

    @Test
    fun `空输入原样返回`() {
        assertEquals("", HtmlEnhancer.inject(""))
        assertEquals("   ", HtmlEnhancer.inject("   "))
    }

    @Test
    fun `注入不破坏原有脚本`() {
        val out = HtmlEnhancer.inject(htmlWithHead)
        // 原脚本内容完整保留，捕获器只注入一份
        assertTrue(out.contains("<script>var x=1;"))
        assertEquals(1, Regex("unhandledrejection").findAll(out).count())
    }
}
