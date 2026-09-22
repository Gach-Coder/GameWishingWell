package com.gamewishingwell.agent

/**
 * 交付门（声明式裁决表）——测量与裁决分离架构的裁决侧：
 * - JS 探针（SmokeTestProbe）只做测量，收尾回报规范化 [Evidence]；
 * - 本对象按检查表逐行裁决出 [SmokeTestResult]，判定全部在 JVM 侧纯函数化，
 *   每一行都可表驱动单测（此前判定藏在一段内嵌 JS 里，模式边界上反复开洞）。
 *
 * 历史事故（2026-09-06 我的世界，两连放行）的根除点：
 * - canvas-not-alive：html 声明了 `<canvas>`（或 ww-engine=three/pixi）的游戏，
 *   收尾时必须存在非零尺寸画布——此前"无存活画布"落入 DOM 文本豁免（帮助文案
 *   ≥30 字即放行），初始化崩溃的交付版正是从这里钻过（colors=0 被当作合法值）；
 * - pixel-diversity：canvas 游戏的画布采样 colors=0（像素不可读）= 画面证据缺失
 *   = 失败，不再是"无 canvas 的 DOM 游戏"的同义词——两类游戏按 html 声明分流。
 *
 * light 模式（中途冒烟）只评估运行时错误行；其余行（画布/方向/角落/可观测性/
 * 断言）只在交付门评估——与旧探针的检查组划分语义一致，但不再依赖 JS 侧条件分支。
 */
object DeliveryGate {

    /** 单画布测量：w/h 为画布属性尺寸，colors 为降采样量化色数（0=采样失败/不可读）。 */
    data class CanvasMeasurement(val w: Int, val h: Int, val colors: Int, val note: String?)

    /** 主画布几何测量（方向与铺满裁决的输入）：null = 无可见主画布。 */
    data class MainCanvasGeometry(val w: Int, val h: Int, val vw: Int, val vh: Int)

    /** 探针收尾回报的规范化证据（JS 只采集不判定）。 */
    data class Evidence(
        val errors: List<String> = emptyList(),
        val framesRun: Int = 0,
        val canvases: List<CanvasMeasurement> = emptyList(),
        val zeroSizeCanvases: Int = 0,
        val domTextLen: Int = 0,
        val restartMissing: Boolean = false,
        val cornerViolations: List<String> = emptyList(),
        val mainCanvas: MainCanvasGeometry? = null,
        val debugStateProblems: List<String> = emptyList(),
        val scenarios: List<ScenarioEntry> = emptyList()
    )

    /** 断言测量条目：ok/reason 由执行器记录，expect 供失败行回显原文。 */
    data class ScenarioEntry(
        val name: String,
        val system: String,
        val ok: Boolean,
        val reason: String,
        val expect: String
    )

    /**
     * 裁决上下文：
     * - [light] 中途轻量冒烟（仅运行时错误行）；
     * - [canvasDeclared] html 声明了 canvas（`<canvas` 标签或 ww-engine=three/pixi）
     *   ——canvas 游戏与 DOM 游戏按此分流，废除前者的文本豁免；
     * - [landscape] Game Schema 期望的画面方向（横板=true）。
     */
    data class Context(val light: Boolean = false, val canvasDeclared: Boolean = false, val landscape: Boolean = false)

    fun evaluate(evidence: Evidence, ctx: Context): SmokeTestResult {
        // 行 1（恒评估，light 也含）：运行时错误——console.error、未捕获异常
        // （window error/unhandledrejection）与帧内异常一视同仁，任何一条即失败。
        // 浏览器干预告警（passive preventDefault 等）不算游戏缺陷——采集侧已分流，
        // 这里兜底过滤覆盖探针页面内自采通道（详见 BrowserInterventions 历史缺陷说明）。
        val rows = mutableListOf<String>()
        rows += evidence.errors.filter { !BrowserInterventions.isIntervention(it) }

        // 画面证据行（light 跳过——中途冒烟只回答"有没有报错"）
        var canvasColors = 0
        if (!ctx.light) {
            val live = evidence.canvases
            canvasColors = live.maxOfOrNull { it.colors } ?: 0
            if (ctx.canvasDeclared) {
                if (live.isEmpty()) {
                    rows += canvasNotAliveMessage(evidence.zeroSizeCanvases)
                } else if (canvasColors < 3) {
                    rows += if (canvasColors == 0 && live.all { it.colors == 0 }) {
                        "playability-blank-canvas: 画布像素不可读（colors=0，画面证据缺失按失败处理）。" +
                            "WebGL/three 游戏创建 renderer 时必须带 preserveDrawingBuffer:true，" +
                            "否则离屏读取画布只能得到空数据；纯 DOM 游戏请移除无用的 canvas 声明。"
                    } else {
                        val best = live.maxByOrNull { it.colors }!!
                        "playability-blank-canvas: 画面像素仅 ${best.colors} 种颜色" +
                            "（${best.note ?: "最大画布 ${best.w}x${best.h}"}）" +
                            "——画布疑似只有背景/天空清除色的纯色填充，无任何实际渲染内容。请排查：" +
                            "①几何体是否真正加入场景且含有效顶点（顶点表结构与索引方式必须匹配，NaN 顶点会被 GPU 全部裁剪导致只剩背景色）；" +
                            "②渲染循环是否持续执行 renderer.render；③相机位置是否在场景内朝向内容" +
                            "（相机低于地面且画布单面渲染时地面会整体消失）；" +
                            "④WebGL 画布若实际有内容但此处读不到，创建 renderer 时必须带 preserveDrawingBuffer:true"
                    }
                }
            } else {
                // DOM 游戏（html 未声明 canvas）：保留文本规则——可读文本≥30 字视为有内容。
                if (evidence.domTextLen < 30) {
                    rows += "playability-blank-screen: 画面检测未发现任何渲染内容（白屏），游戏未实际运行或未绘制"
                }
            }

            if (evidence.restartMissing) {
                rows += "restart-missing: 未找到全局 restart() 函数（契约要求提供完整重开）"
            }
            if (evidence.cornerViolations.isNotEmpty()) {
                rows += "top-corner-reserved: 左上/右上角平台按钮悬浮区出现可交互元素（会被平台返回/设置按钮遮挡无法点击）：" +
                    evidence.cornerViolations.take(3).joinToString(" | ") +
                    "；请将这些元素移出两个角落，顶部其余区域可自由布局"
            }
            evidence.mainCanvas?.let { main ->
                if (ctx.landscape && main.w <= main.h) {
                    rows += "orientation-mismatch: 画面方向应为横板（主画布宽>高），实际主画布为 ${main.w}x${main.h}。" +
                        "横板游戏不能只把关卡做成横向卷轴而保持竖版/窄幅画面：平台会以横屏视口加载，" +
                        "直接以窗口实际尺寸为逻辑分辨率全屏铺满即可，不要旋转画布"
                } else if (!ctx.landscape && main.h <= main.w) {
                    rows += "orientation-mismatch: 画面方向应为竖版（主画布高>宽），实际主画布为 ${main.w}x${main.h}；请按竖屏视口布局"
                }
                if (main.vw > 0 && main.vh > 0 && main.w >= main.vw * 0.5 &&
                    (main.w < main.vw * 0.85 || main.h < main.vh * 0.6)
                ) {
                    rows += "canvas-not-fullscreen: 主画布 ${main.w}x${main.h} 未铺满视口 ${main.vw}x${main.vh}" +
                        "（要求宽≥85%、高≥60%）。禁止固定逻辑分辨率后等比缩放居中留黑边——" +
                        "应以窗口实际尺寸为逻辑分辨率全屏自适应"
                }
            }
            rows += evidence.debugStateProblems
            // 断言行：失败条目格式化回显（断言原文随行，修复不靠猜）。
            evidence.scenarios.filter { !it.ok }.forEach { sc ->
                val label = if (sc.system.isBlank()) sc.name else "${sc.system}/${sc.name}"
                rows += "scenario-fail[$label]: ${sc.reason}（断言：${sc.expect}）"
            }
        }

        val scenarioResults = evidence.scenarios.map { sc ->
            val label = if (sc.system.isBlank()) sc.name else "${sc.system}/${sc.name}"
            if (sc.ok) "$label 通过" else "$label 失败:${sc.reason.take(160)}"
        }
        return SmokeTestResult(
            passed = rows.isEmpty(),
            framesRun = evidence.framesRun,
            errors = rows,
            message = if (rows.isEmpty()) "smoke-ok" else "smoke-failed",
            scenarioTotal = evidence.scenarios.size,
            scenarioPassed = scenarioResults.count { it.endsWith(" 通过") },
            scenarioResults = scenarioResults,
            finalized = true,
            canvasColors = canvasColors
        )
    }

    private fun canvasNotAliveMessage(zeroSize: Int): String =
        "canvas-not-alive: 页面声明了 <canvas> 但收尾时不存在任何非零尺寸画布" +
            (if (zeroSize > 0) "（存在 $zeroSize 个 0 尺寸画布）" else "") +
            "——初始化大概率在渲染启动前就崩溃了。请优先核对运行时报错（errors）与跨文件导出完整性：" +
            "入口 main.js 调用的 Module.fn() 必须在该模块 js 文件的 return 导出表中，" +
            "重写文件时丢失导出成员会让 init 直接抛 TypeError；" +
            "画布的 width/height 应在初始化末尾按视口设置，不得停留在 0"

    /**
     * html 是否声明了 canvas 游戏：`<canvas` 标签或 three/pixi 引擎声明
     * （对 prepared 前的原始入口判定，避免探针/引擎注入文本的干扰）。
     */
    fun canvasDeclaredIn(html: String): Boolean {
        if (Regex("<canvas\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) return true
        val engine = Regex("""<meta[^>]+name=["']?ww-engine["']?[^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return false
        return engine.split(',').any { it.trim().equals("three", true) || it.trim().equals("pixi", true) }
    }
}
