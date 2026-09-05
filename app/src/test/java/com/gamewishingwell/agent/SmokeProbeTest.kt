package com.gamewishingwell.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeProbeTest {

    @Test
    fun `横板游戏探针注入横屏期望与方向断言脚本`() {
        val out = SmokeTestProbe.inject("<html><body></body></html>", landscape = true)
        // 宿主按 Game Schema 注入方向期望：横板 = 主画布宽>高
        assertTrue(out.contains("var __landscape = true;"))
        assertTrue(out.contains("orientation-mismatch"))
        // 铺满断言：拦固定逻辑分辨率 + 等比缩放黑边适配
        assertTrue(out.contains("canvas-not-fullscreen"))
        // 定稿标志：宿主完成判定以探针 finalize 为准（deep 复跑中途不算完成）
        assertTrue(out.contains("__wwSmokeFinalized"))
        assertFalse(out.contains("var __landscape = false;"))
    }

    @Test
    fun `默认按竖版期望注入且重复注入不叠加`() {
        val once = SmokeTestProbe.inject("<html><body></body></html>")
        assertTrue(once.contains("var __landscape = false;"))
        // 已注入（含探针标记）的 HTML 原样返回，不重复注入
        assertTrue(once == SmokeTestProbe.inject(once, landscape = true))
    }

    @Test
    fun `无 head 与 body 的兜底注入不破坏 DOCTYPE 标准模式`() {
        val html = "<!DOCTYPE html>\n<html lang=\"zh\">\n<div>游戏</div>\n</html>"
        val out = SmokeTestProbe.inject(html)
        // DOCTYPE 必须仍是文件首个标签：脚本前插到 DOCTYPE 之前会触发 quirks 模式，
        // 视口/布局行为与真机失真，沙箱结论不可信。
        assertTrue(out.trimStart().startsWith("<!DOCTYPE"))
        assertTrue(out.indexOf("__wwSmokeInstalled") > out.indexOf("<html"))
        assertTrue(out.contains("</html>"))
    }
}
