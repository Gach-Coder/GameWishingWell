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
    // 非贪婪逐块匹配：贪婪捕获吞并多个围栏块（```html 后又出现 ```json 等）时，
    // 用它逐块找出第一个完整 HTML。
    private val lazyFenceRegex = Regex("```(?:html)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    // 可选捕获 DOCTYPE，保证抽取结果按标准模式渲染
    private val htmlRegex = Regex("(?:<!DOCTYPE\\s+html[^>]*>\\s*)?<html[\\s\\S]*</html>", RegexOption.IGNORE_CASE)
    private val externalUrlRegex = Regex("""\b(?:https?://|//[a-z][a-z0-9.-]*[/'"])""", RegexOption.IGNORE_CASE)

    /**
     * 生成被用户中断时，从尚未收完的流式文本中抢救出一个“可尝试游玩”的中间版本。
     * 没有完整闭合标签时会补上最小 HTML 收尾；该版本可能运行错误，属于正常现象。
     */
    fun extractPartial(raw: String): String? {
        val complete = runCatching { extract(raw) }.getOrNull()?.html
        if (complete != null) return complete

        val start = raw.indexOf("<html", ignoreCase = true)
        if (start < 0) return null
        var partial = raw.substring(start)
        if (!partial.contains("</html>", ignoreCase = true)) {
            partial = partial.trimEnd() + "\n</body></html>"
        }
        return partial
    }

    fun extract(raw: String): ExtractResult {
        val warnings = mutableListOf<String>()

        val greedyFenced = fenceRegex.find(raw)?.groupValues?.get(1)?.trim()
        // 贪婪捕获可能把多个围栏块连同中间的说明文字一起吞入（如 ```html 游戏代码```
        // 说明 ```json 数据```），带尾巴的 HTML 不是可交付产物：此时逐块找第一个
        // 完整 HTML；都不完整再回退贪婪结果（游戏代码字符串里可能真有 ``` 字面量）。
        val fenced = if (greedyFenced != null && greedyFenced.contains("```")) {
            lazyFenceRegex.findAll(raw)
                .mapNotNull { it.groupValues[1].trim() }
                .firstOrNull { it.contains("</html>", ignoreCase = true) }
                ?: greedyFenced
        } else {
            greedyFenced
        }
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
        // 非贪婪逐块剥离：贪婪会把两个围栏块之间的正文一并删掉（模型给玩家的说明丢失）。
        var t = raw
        t = t.replace(lazyFenceRegex, "")
        t = t.replace(htmlRegex, "")
        return t.trim()
    }
}
