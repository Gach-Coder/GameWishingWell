package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeliveryGate 表驱动测试：把我的世界事故（2026-09-06）的每个放行洞固化为
 * 回归用例——测量与裁决分离后，每条规则都是可独立验证的纯函数。
 */
class DeliveryGateTest {

    private fun canvas(w: Int, h: Int, colors: Int, note: String? = null) =
        DeliveryGate.CanvasMeasurement(w, h, colors, note)

    @Test
    fun `canvas游戏初始化崩溃零存活画布不再走文本豁免`() {
        // 事故现场：init 在 Input.setUI 抛 TypeError → 无非零尺寸画布；
        // 旧实现落入 DOM 文本豁免（帮助文案≥30字）放行，colors=0 被当合法值。
        val ev = DeliveryGate.Evidence(
            errors = listOf("uncaught: Input.setUI is not a function"),
            framesRun = 24,
            canvases = emptyList(),
            zeroSizeCanvases = 2,
            domTextLen = 120
        )
        val verdict = DeliveryGate.evaluate(ev, DeliveryGate.Context(canvasDeclared = true))
        assertFalse(verdict.passed)
        assertTrue(verdict.errors.any { it.startsWith("uncaught: Input.setUI") })
        assertEquals(0, verdict.canvasColors)
    }

    @Test
    fun `canvas游戏零存活画布且无报错也失败`() {
        // 更刁钻的变体：错误恰好没被捕获（或已修复但画布从未创建）——
        // 声明了 canvas 的游戏收尾必须存在非零尺寸画布。
        val ev = DeliveryGate.Evidence(framesRun = 24, domTextLen = 200)
        val verdict = DeliveryGate.evaluate(ev, DeliveryGate.Context(canvasDeclared = true))
        assertFalse(verdict.passed)
        assertTrue(verdict.errors.any { it.startsWith("canvas-not-alive") })
    }

    @Test
    fun `canvas游戏画布采样零色等于证据缺失判失败`() {
        // colors=0（像素不可读，如 WebGL 无 preserveDrawingBuffer）≠ 合法通过值
        val ev = DeliveryGate.Evidence(
            framesRun = 24,
            canvases = listOf(canvas(640, 360, 0, "像素不可读"))
        )
        val verdict = DeliveryGate.evaluate(ev, DeliveryGate.Context(canvasDeclared = true))
        assertFalse(verdict.passed)
        assertTrue(verdict.errors.any { it.startsWith("playability-blank-canvas") && it.contains("像素不可读") })
    }

    @Test
    fun `canvas游戏纯色画布失败且错误文本与颜色数同源`() {
        val ev = DeliveryGate.Evidence(
            framesRun = 24,
            canvases = listOf(canvas(640, 360, 1, "最多色约 #000000 占 100%"))
        )
        val verdict = DeliveryGate.evaluate(ev, DeliveryGate.Context(canvasDeclared = true))
        assertFalse(verdict.passed)
        val row = verdict.errors.first { it.startsWith("playability-blank-canvas") }
        assertTrue(row.contains("仅 1 种颜色") && row.contains("#000000"))
        assertEquals(1, verdict.canvasColors) // 历史缺陷：错误文案与上报字段各说各话
    }

    @Test
    fun `canvas游戏多画布取最大色数三色以上通过`() {
        val ev = DeliveryGate.Evidence(
            framesRun = 24,
            canvases = listOf(canvas(640, 360, 2), canvas(800, 600, 7, "多彩"))
        )
        val verdict = DeliveryGate.evaluate(ev, DeliveryGate.Context(canvasDeclared = true))
        assertTrue(verdict.passed)
        assertEquals(7, verdict.canvasColors)
    }

    @Test
    fun `DOM游戏文本豁免保留-短文本白屏失败长文本通过`() {
        val short = DeliveryGate.evaluate(
            DeliveryGate.Evidence(framesRun = 24, domTextLen = 10),
            DeliveryGate.Context(canvasDeclared = false)
        )
        assertFalse(short.passed)
        assertTrue(short.errors.any { it.startsWith("playability-blank-screen") })

        val ok = DeliveryGate.evaluate(
            DeliveryGate.Evidence(framesRun = 24, domTextLen = 60),
            DeliveryGate.Context(canvasDeclared = false)
        )
        assertTrue(ok.passed)
    }

    @Test
    fun `轻量冒烟只裁运行时错误行`() {
        // light（中途冒烟）：画面证据缺失不算失败——咨询式升级语义不变
        val ev = DeliveryGate.Evidence(
            framesRun = 6,
            canvases = emptyList(),
            zeroSizeCanvases = 1,
            domTextLen = 0,
            restartMissing = true,
            debugStateProblems = listOf("debug-state-missing: 未找到 window.__wwDebugState()")
        )
        val clean = DeliveryGate.evaluate(ev, DeliveryGate.Context(light = true, canvasDeclared = true))
        assertTrue(clean.passed)

        val withError = DeliveryGate.evaluate(
            ev.copy(errors = listOf("uncaught: boom")),
            DeliveryGate.Context(light = true, canvasDeclared = true)
        )
        assertFalse(withError.passed)
    }

    @Test
    fun `断言失败格式化回显原文与摘要计数`() {
        val ev = DeliveryGate.Evidence(
            framesRun = 24,
            canvases = listOf(canvas(640, 360, 5)),
            scenarios = listOf(
                DeliveryGate.ScenarioEntry("击杀计数", "战斗", true, "第 3 帧达成", "s.score > 0"),
                DeliveryGate.ScenarioEntry("相机跟随", "相机", false, "期限 300 帧内未达成", "Math.abs(s.camera.yaw - s0.camera.yaw) > 0.05")
            )
        )
        val verdict = DeliveryGate.evaluate(ev, DeliveryGate.Context(canvasDeclared = true))
        assertFalse(verdict.passed)
        assertTrue(verdict.errors.any { it.startsWith("scenario-fail[相机/相机跟随]") && it.contains("（断言：") })
        assertEquals(2, verdict.scenarioTotal)
        assertEquals(1, verdict.scenarioPassed)
        assertTrue(verdict.scenarioResults.any { it.endsWith(" 通过") })
    }

    @Test
    fun `方向与铺满裁决按期望分流`() {
        fun geo(w: Int, h: Int, vw: Int = 640, vh: Int = 360) =
            DeliveryGate.Evidence(
                framesRun = 24,
                canvases = listOf(canvas(w, h, 6)),
                mainCanvas = DeliveryGate.MainCanvasGeometry(w, h, vw, vh)
            )
        // 横板期望 + 竖版画布 → 失败
        val mismatch = DeliveryGate.evaluate(geo(360, 640), DeliveryGate.Context(canvasDeclared = true, landscape = true))
        assertTrue(mismatch.errors.any { it.startsWith("orientation-mismatch") })
        // 横板期望 + 横板画布但黑边（宽仅 60%）→ 失败
        val letterbox = DeliveryGate.evaluate(geo(384, 360), DeliveryGate.Context(canvasDeclared = true, landscape = true))
        assertTrue(letterbox.errors.any { it.startsWith("canvas-not-fullscreen") })
        // 合格横板
        val ok = DeliveryGate.evaluate(geo(640, 360), DeliveryGate.Context(canvasDeclared = true, landscape = true))
        assertTrue(ok.passed)
    }

    @Test
    fun `canvas声明判定-标签与引擎声明`() {
        assertTrue(DeliveryGate.canvasDeclaredIn("<html><body><canvas id=\"g\"></canvas></body></html>"))
        assertTrue(DeliveryGate.canvasDeclaredIn("<meta name=\"ww-engine\" content=\"three\">"))
        assertTrue(DeliveryGate.canvasDeclaredIn("<meta name=\"ww-engine\" content=\"pixi, tween\">"))
        assertFalse(DeliveryGate.canvasDeclaredIn("<div>纯 DOM 游戏</div>"))
        assertFalse(DeliveryGate.canvasDeclaredIn("<meta name=\"ww-engine\" content=\"matter\">"))
    }
}
