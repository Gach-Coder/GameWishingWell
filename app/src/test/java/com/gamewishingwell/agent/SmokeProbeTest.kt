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

    @Test
    fun `场景执行器注入 eventually 语义与轨迹定位机制`() {
        val injected = SmokeTestProbe.inject("<html></html>", scenariosJson = "[]")
        // eventually：期限内逐帧求值、任一帧为真即通过（时间归执行器，作者不预言帧数）
        assertTrue(injected.contains("horizon"))
        assertTrue(injected.contains("任一帧为真即通过"))
        assertTrue(injected.contains(GameScenarios.HORIZON_DEFAULT_FRAMES.toString()))
        assertTrue(injected.contains(GameScenarios.HORIZON_MAX_FRAMES.toString()))
        // 翻真即停 + 逐帧求值
        assertTrue(injected.contains("passFrame"))
        assertTrue(injected.contains("probeOnce"))
        // 失败回报自带字段轨迹与输入派发点状态（时间维度信息）
        assertTrue(injected.contains("字段轨迹"))
        assertTrue(injected.contains("state="))
        // 字段发现：expect 引用字段第 0 帧确定性校验、缺失即回报可用字段清单
        assertTrue(injected.contains("不在快照中"))
        assertTrue(injected.contains("可用字段"))
        // 输入步骤间默认推进（帧预算由执行器承担）
        assertTrue(injected.contains("advance(${GameScenarios.INTER_STEP_FRAMES})"))
        // 心跳保活：帧边界外的 rAF 注册暂存并在阶段重置后复活——
        // 否则场景阶段游戏冻结、断言只能对初始状态求值（历史死循环的隐藏根因）
        assertTrue(injected.contains("__parkedRaf"))
        assertTrue(injected.contains("reviveRafQueue()"))
    }

    @Test
    fun `画面检测注入像素多样性裁决与颜色数回传`() {
        val injected = SmokeTestProbe.inject("<html></html>")
        // canvas 像素多样性：纯色画布（<3 种量化颜色）判回炉，杀"零三角形只剩背景色"的假通过
        assertTrue(injected.contains("sampleCanvasColors"))
        assertTrue(injected.contains("playability-blank-canvas"))
        assertTrue(injected.contains("preserveDrawingBuffer:true"))
        // 颜色数写入结果对象并在 finalize 重建后保留（供宿主读取注入自检证据）
        assertTrue(injected.contains("canvasColors: window.__wwSmokeResult.canvasColors"))
        // 无 canvas 的 DOM 游戏保留文本规则；旧"文本≥30 即豁免 canvas 游戏"的通道已移除
        assertTrue(injected.contains("playability-blank-screen"))
        assertFalse(injected.contains("toDataURL().length > 3000"))
    }
}
