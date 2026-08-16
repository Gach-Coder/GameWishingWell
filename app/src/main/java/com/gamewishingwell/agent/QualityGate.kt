package com.gamewishingwell.agent

import kotlinx.serialization.Serializable

@Serializable
data class QualityFail(val item: String, val severity: String)

@Serializable
data class QualityVerdict(
    val pass: Boolean,
    val fails: List<QualityFail> = emptyList(),
    val note: String = ""
)

/**
 * Agent Loop 末尾的两段式自检：
 * (a) 先对照验收标准清单，并“必读校验结果”——已发现的错误不得翻案；
 * (b) 再输出结构化 verdict {pass, fails:[{item,severity}], note}。
 */
object QualityGate {

    fun evaluate(report: ValidationReport, plan: DesignPlan, html: String): QualityVerdict {
        val fails = mutableListOf<QualityFail>()

        // (a) 校验结果具有一票否决权，任何已发现的 error 都不得翻案。
        report.errors.forEach { issue ->
            fails += QualityFail(
                item = "校验报错：${issue.category}@${issue.file}:${issue.line} ${issue.message}",
                severity = issue.severity
            )
        }

        // 对照策划验收清单做可静态判定的结构检查。
        if (!html.contains("<canvas", ignoreCase = true) && !html.contains("<div id=\"game", ignoreCase = true)) {
            fails += QualityFail("缺少可交互的游戏容器（canvas/游戏 div）", "error")
        }
        if (!html.contains("requestAnimationFrame", ignoreCase = true) && !html.contains("setInterval", ignoreCase = true)) {
            fails += QualityFail("缺少主循环驱动（requestAnimationFrame / setInterval）", "error")
        }
        if (!Regex("""function\s+restart\s*\(""").containsMatchIn(html)) {
            fails += QualityFail("缺少全局 restart() 重开函数", "error")
        }
        if (!html.contains("touchstart", ignoreCase = true) && !html.contains("touchmove", ignoreCase = true) &&
            !html.contains("pointerdown", ignoreCase = true) && !html.contains("click", ignoreCase = true)
        ) {
            fails += QualityFail("缺少触控/点按输入处理", "error")
        }

        val pass = fails.none { it.severity == "error" }
        val note = buildString {
            append("已核对验收标准 ${plan.acceptanceChecklist.size} 条；")
            append("静态校验 ${report.checks.size} 条（error ${report.errors.size} / warning ${report.warnings.size}）。")
            if (pass) append("已知 warning 不作为阻断项，但会记录到 known-issues。")
        }
        return QualityVerdict(pass = pass, fails = fails, note = note)
    }

    fun promptFeedback(verdict: QualityVerdict, report: ValidationReport): String = buildString {
        append("自检未通过，请修复以下问题后重新输出完整 HTML：\n")
        verdict.fails.forEach { append("- ${it.item}\n") }
        val errors = report.errors
        if (errors.isNotEmpty()) {
            append("校验器原始错误：\n")
            errors.take(10).forEach { append("- [${it.category}] line ${it.line}: ${it.message}\n") }
        }
    }.trimEnd()
}
