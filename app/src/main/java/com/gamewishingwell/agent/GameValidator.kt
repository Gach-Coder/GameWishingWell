package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

@Serializable
data class ValidationIssue(
    val category: String,
    val file: String,
    val line: Int,
    val message: String,
    val severity: String // error | warning
)

@Serializable
data class ValidationReport(
    val checks: List<ValidationIssue> = emptyList()
) {
    val hasErrors: Boolean get() = checks.any { it.severity == "error" }
    val errors: List<ValidationIssue> get() = checks.filter { it.severity == "error" }
    val warnings: List<ValidationIssue> get() = checks.filter { it.severity == "warning" }

    fun toJsonString(): String = json.encodeToString(this)

    companion object {
        private val json = Json { prettyPrint = false }
    }
}

/**
 * 轻量产品契约检查（非代码校验）。
 *
 * 运行正确性的唯一校验来源是沙箱（GameSmokeTest：同内核 WebView 确定性 tick +
 * 异常/console 采集）——静态解析器（Rhino/jsoup error tracking）与 Chromium 存在
 * 语法代差，class/可选链/spread/for-of 等合法写法会被误判，模型照假报错去修
 * 只会烧轮次。这里只保留没有代差问题的产品硬约束：
 * - 自包含：禁止外部/本地资源引用（离线可玩是产品承诺）；
 * - 安全契约：禁止 eval / new Function / 动态 require / import()。
 */
object GameValidator {

    const val FILE_INDEX_HTML = "index.html"

    private val forbiddenJsRegex = Regex("""\beval\s*\(|new\s+Function\s*\(|\brequire\s*\(|\bimport\s*\(""")
    private val cssUrlRegex = Regex("""url\(\s*['"]?([^)'"\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val externalUrlRegex = Regex("""(?:https?://|//[a-z][a-z0-9.-]*[/'"])""", RegexOption.IGNORE_CASE)

    fun validate(html: String): ValidationReport {
        if (html.isBlank()) {
            return ValidationReport(
                listOf(ValidationIssue("resources", FILE_INDEX_HTML, 1, "HTML 内容为空", "error"))
            )
        }

        val checks = mutableListOf<ValidationIssue>()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
        if (doc == null) {
            checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "HTML 无法解析", "error")
            return ValidationReport(checks)
        }

        fun externalError(tag: String, src: String, what: String) {
            checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "禁止外部$what 资源：$src", "error")
        }

        doc.getElementsByTag("script").forEach { el ->
            if (el.hasAttr("src")) {
                val src = el.attr("src")
                if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//")) {
                    externalError("script", src, "JS")
                } else {
                    checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "本地 JS 资源无法内联校验，可能缺失：$src", "warning")
                }
            }
        }
        doc.getElementsByTag("img").forEach { el ->
            if (el.hasAttr("src")) {
                val src = el.attr("src").trim()
                when {
                    src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//") ->
                        externalError("img", src, "图片")
                    src.isNotBlank() && !src.startsWith("data:") ->
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "本地图片资源缺失（自包含游戏禁止引用本地文件）：$src", "error")
                }
            }
        }
        doc.getElementsByTag("audio").forEach { el ->
            if (el.hasAttr("src")) {
                val src = el.attr("src").trim()
                when {
                    src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//") ->
                        externalError("audio", src, "音频")
                    src.isNotBlank() && !src.startsWith("data:") ->
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "本地音频资源缺失（自包含游戏禁止引用本地文件）：$src", "error")
                }
            }
        }
        cssUrlRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (!url.startsWith("data:", ignoreCase = true) && !url.startsWith("#") &&
                !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true) &&
                !url.startsWith("//")
            ) {
                checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "CSS 引用的本地资源缺失：$url", "warning")
            }
        }
        externalUrlRegex.findAll(html).forEach { match ->
            if (!checks.any { it.message.contains(match.value) }) {
                checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "包含外部链接：${match.value.take(80)}", "warning")
            }
        }
        forbiddenJsRegex.findAll(html).forEach { m ->
            checks += ValidationIssue(
                "static-runtime", FILE_INDEX_HTML, 1,
                "安全契约禁止 eval / new Function / 动态 require / import()：${m.value}", "error"
            )
        }

        // 可观测性契约：必须暴露 window.__wwDebugState() 供沙箱做不变量断言
        //（负血量实体未移除 / NaN 数值 / 实体泄漏等"不抛错但明显不对"的低级 bug）。
        if (!html.contains("__wwDebugState")) {
            checks += ValidationIssue(
                "observability", FILE_INDEX_HTML, 1,
                "缺少可观测性契约：必须提供全局函数 window.__wwDebugState = function(){...}，" +
                    "返回 { state, score, entities:[{type,hp,x,y}], player:{...} } 状态快照（沙箱据此做自动不变量检查）",
                "error"
            )
        }

        return ValidationReport(checks.distinctBy { "${it.category}|${it.message}" })
    }
}
