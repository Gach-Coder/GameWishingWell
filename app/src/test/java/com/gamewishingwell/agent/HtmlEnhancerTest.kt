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
    fun `注入平台设置桥接接口且不再注入页面内面板`() {
        val out = HtmlEnhancer.inject(htmlWithHead)
        // 原生顶栏"设置"面板的页面侧接口
        assertTrue(out.contains("window.__wwSetVolume = __wwSetVolume"))
        assertTrue(out.contains("window.__wwSetPaused ="))
        // 默认音量 0.8（原生滑条可放大到 1.5）
        assertTrue(out.contains("var __wwVolume = 0.8"))
        // 页面内不再注入任何设置按钮/面板
        assertFalse(out.contains("__ww_game_settings_btn"))
        assertFalse(out.contains("__ww_game_settings_overlay"))
    }

    @Test
    fun `注入不破坏原有脚本`() {
        val out = HtmlEnhancer.inject(htmlWithHead)
        // 原脚本内容完整保留，捕获器只注入一份
        assertTrue(out.contains("<script>var x=1;"))
        assertEquals(1, Regex("unhandledrejection").findAll(out).count())
    }
}
