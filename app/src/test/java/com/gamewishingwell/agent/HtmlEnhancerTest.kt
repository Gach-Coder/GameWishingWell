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

    // ---------- 内置引擎注入 ----------

    @Test
    fun `声明 ww-engine 时注入引擎源码且位于视口修复与游戏脚本之前`() {
        val provider = HtmlEnhancer.engineSourceProvider
        try {
            HtmlEnhancer.engineSourceProvider = { name -> if (name == "three") "/*THREE_SOURCE*/" else null }
            val html = "<html><head><meta name=\"ww-engine\" content=\"three\"></head><body><script>var x=1;</script></body></html>"
            val out = HtmlEnhancer.inject(html)
            assertTrue(out.contains("__wwEngineInjected:three"))
            assertTrue(out.contains("/*THREE_SOURCE*/"))
            // 顺序：引擎 → 视口修复 → 游戏脚本
            val engineAt = out.indexOf("/*THREE_SOURCE*/")
            assertTrue(engineAt in 0 until out.indexOf("__wwViewportFixApplied"))
            assertTrue(engineAt < out.indexOf("var x=1;"))
            // 原始 meta 声明保留（磁盘副本不动，注入只影响渲染副本）
            assertTrue(out.contains("ww-engine"))
        } finally {
            HtmlEnhancer.engineSourceProvider = provider
        }
    }

    @Test
    fun `未声明引擎时不注入且白名单外声明被静默跳过`() {
        val provider = HtmlEnhancer.engineSourceProvider
        try {
            HtmlEnhancer.engineSourceProvider = { name -> "/*SRC_$name*/" }
            // 无声明：不注入引擎块
            val plain = HtmlEnhancer.inject(htmlWithHead)
            assertFalse(plain.contains("__wwEngineInjected"))
            // 白名单外声明：渲染层不注入未知代码（由基础校验打回）
            val bad = HtmlEnhancer.inject("<html><head><meta name=\"ww-engine\" content=\"babylon\"></head><body></body></html>")
            assertFalse(bad.contains("__wwEngineInjected"))
            assertFalse(bad.contains("/*SRC_babylon*/"))
        } finally {
            HtmlEnhancer.engineSourceProvider = provider
        }
    }

    @Test
    fun `引擎注入幂等且 provider 缺引擎时不注入`() {
        val provider = HtmlEnhancer.engineSourceProvider
        try {
            HtmlEnhancer.engineSourceProvider = { null }
            val html = "<html><head><meta name=\"ww-engine\" content=\"three\"></head><body></body></html>"
            // provider 拿不到源码（assets 缺失）：不产生空注入块
            val out = HtmlEnhancer.inject(html)
            assertFalse(out.contains("__wwEngineInjected"))

            HtmlEnhancer.engineSourceProvider = { name -> if (name == "three") "/*THREE_SOURCE*/" else null }
            val once = HtmlEnhancer.inject(html)
            val twice = HtmlEnhancer.inject(once)
            assertEquals(1, Regex("__wwEngineInjected:three").findAll(twice).count())
            assertEquals(once, twice)
        } finally {
            HtmlEnhancer.engineSourceProvider = provider
        }
    }
}
