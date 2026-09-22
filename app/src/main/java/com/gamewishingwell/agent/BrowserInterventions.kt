package com.gamewishingwell.agent

/**
 * 浏览器干预/告警识别（共享分类器）——这些日志由 Chromium 生成并以 ERROR 级别
 * 经 onConsoleMessage 上报，与页面真实 JS 异常同通道，但语义完全不同：
 * 它们是浏览器对页面某些写法的优化提示，不中断执行、不影响功能。
 *
 * 历史缺陷（2026-09-19 实测）：交付门与用户报错弹层把它们当游戏缺陷——
 * "Ignored attempt to cancel a touchend event with cancelable=false" /
 * "Unable to preventDefault inside passive event listener invocation" 两条干预
 * 告警驱动了 61 轮修复回合：模型为消告警把全部 touch 监听改 passive、
 * preventDefault 语义整体禁用、追加大范围坐标兜底区，真实手感反而劣化
 * （选人卡片列表无法滚动、底部误触区一键开战）。风险与收益完全倒挂的根源
 * 是错误不分级。此分类器供沙箱采集、交付门裁决与真机报错弹层三处同源使用：
 * 命中=记录（AgentLog）但不进失败裁决、不弹给用户。
 */
object BrowserInterventions {

    private val patterns = listOf(
        // 被动监听器内调用 preventDefault（干预提示，不抛异常）
        Regex("Unable to preventDefault inside passive event listener", RegexOption.IGNORE_CASE),
        // 对不可取消事件调用 preventDefault（如滚动进行中的 touchend）
        Regex("Ignored attempt to cancel a? ?\\w* ?event with cancelable=false", RegexOption.IGNORE_CASE),
        // Chromium 干预/弃用日志的通用前缀形态
        Regex("^(\\[Intervention\\]|\\[Deprecation\\]|\\[Violation\\])", RegexOption.IGNORE_CASE)
    )

    /** 该消息是否为浏览器干预/告警（非游戏缺陷）。 */
    fun isIntervention(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return patterns.any { it.containsMatchIn(message) }
    }
}
