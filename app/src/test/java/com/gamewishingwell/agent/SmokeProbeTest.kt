package com.gamewishingwell.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

class SmokeProbeTest {

    /**
     * 探针脚本的语法守护（Rhino 编译，只编译不执行）：探针是 Kotlin 字符串模板，
     * 一次手滑的花括号错位就会让 WebView 里整个 <script> 解析失败、沙箱全瘫——
     * 纯 contains 断言永远发现不了这类错误（历史上真实发生过）。
     */
    @Test
    fun `探针脚本是可解析的合法 JavaScript`() {
        listOf(
            SmokeTestProbe.inject("<html></html>"),
            SmokeTestProbe.inject("<html></html>", deep = true),
            SmokeTestProbe.inject("<html></html>", light = true),
            SmokeTestProbe.inject(
                "<html></html>",
                scenariosJson = """{"scenarios":[{"id":"a","name":"A","steps":[{"tap":[50,80]}],"expect":"s.score > 0"}]}"""
            )
        ).forEach { out ->
            val script = out.substringAfter("<script>\n").substringBefore("</script>")
            val cx = Context.enter()
            try {
                cx.compileString(script, "probe", 1, null)
            } catch (e: Exception) {
                throw AssertionError("探针脚本存在语法错误: ${e.message}", e)
            } finally {
                Context.exit()
            }
        }
    }

    @Test
    fun `探针只测量不判定-证据对象与方向测量`() {
        val out = SmokeTestProbe.inject("<html><body></body></html>", landscape = true)
        // 测量与裁决分离：探针采集 __wwEvidence（含主画布几何），方向期望在宿主
        // DeliveryGate.Context——JS 里不再持有 __landscape 期望值。
        assertTrue(out.contains("window.__wwEvidence"))
        assertTrue(out.contains("measureOrientation"))
        assertTrue(out.contains("__meas.mainCanvas"))
        assertFalse(out.contains("var __landscape"))
    }

    @Test
    fun `默认注入且重复注入不叠加`() {
        val once = SmokeTestProbe.inject("<html><body></body></html>")
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
        // s0 相对断言：求值上下文提供场景起点深拷贝快照
        assertTrue(injected.contains("snap0Copy"))
        assertTrue(injected.contains("new Function('s', 's0'"))
    }

    @Test
    fun `画面证据测量与未捕获错误捕获注入`() {
        val injected = SmokeTestProbe.inject("<html></html>")
        // canvas 像素测量：逐画布采样 colors（0=不可读），裁决在 DeliveryGate
        assertTrue(injected.contains("sampleCanvasColors"))
        assertTrue(injected.contains("measureCanvases"))
        assertTrue(injected.contains("__meas.canvases"))
        assertTrue(injected.contains("zeroSize"))
        // 未捕获异常与 Promise 拒绝一并入 errors（历史盲区：setTimeout 里抛的
        // TypeError 探针看不见，崩溃版钻过交付门）
        assertTrue(injected.contains("addEventListener('error'"))
        assertTrue(injected.contains("'uncaught: '"))
        assertTrue(injected.contains("unhandledrejection"))
        // DOM 文本豁免只属于未声明 canvas 的游戏（canvas 声明分流在宿主裁决）
        assertTrue(injected.contains("domTextLen"))
        assertFalse(injected.contains("toDataURL().length > 3000"))
    }
}
