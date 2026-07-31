package com.gamewishingwell.agent

data class ExtractResult(
    val html: String?,
    val warnings: List<String>,
    val error: String?
)

/**
 * 从模型回复中抽取可运行的 HTML，并做基本校验。
 * 纯 JVM 逻辑，方便单元测试。
 */
object HtmlExtractor {

    // 使用贪婪匹配（到最后一个闭合标记），避免代码里的字符串中出现 </html> 或 ``` 时被提前截断
    private val fenceRegex = Regex("```(?:html)?\\s*([\\s\\S]*)```", RegexOption.IGNORE_CASE)
    // 可选捕获 DOCTYPE，保证抽取结果按标准模式渲染
    private val htmlRegex = Regex("(?:<!DOCTYPE\\s+html[^>]*>\\s*)?<html[\\s\\S]*</html>", RegexOption.IGNORE_CASE)
    private val externalUrlRegex = Regex("""\b(?:https?://|//[a-z][a-z0-9.-]*[/'"])""", RegexOption.IGNORE_CASE)

    fun extract(raw: String): ExtractResult {
        val warnings = mutableListOf<String>()

        val fenced = fenceRegex.find(raw)?.groupValues?.get(1)?.trim()
        val rawMatch = htmlRegex.find(raw)?.value

        val candidate = when {
            fenced != null && fenced.contains("</html>", ignoreCase = true) -> fenced
            rawMatch != null -> rawMatch
            else -> null
        }

        if (candidate == null) {
            return ExtractResult(
                html = null,
                warnings = warnings,
                error = "未能从回复中提取到完整的 HTML 代码"
            )
        }
        if (!candidate.contains("<!DOCTYPE", ignoreCase = true)) {
            warnings.add("缺少 DOCTYPE 声明")
        }
        if (externalUrlRegex.containsMatchIn(candidate)) {
            warnings.add("游戏代码包含外部链接，可能无法离线运行")
        }
        return ExtractResult(html = candidate, warnings = warnings, error = null)
    }

    /** 从回复中剥离代码块，得到模型说的话（用于对话展示）。 */
    fun nonCodeText(raw: String): String {
        var t = raw
        t = t.replace(fenceRegex, "")
        t = t.replace(htmlRegex, "")
        return t.trim()
    }
}
