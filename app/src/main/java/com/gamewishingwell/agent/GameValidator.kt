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
 * - 自包含：禁止外部网络资源（离线可玩是产品承诺）；本地文本资产（JS/CSS）允许
 *   多文件相对引用，但引用的文件必须存在（运行前由 GameBundle 内联合并）；
 *   二进制（图片/音频）本地引用仍然禁止（无法文本内联）；
 * - 安全契约：禁止 eval / new Function / 动态 require / import()，并禁止
 *   ES module 的静态 import/export（内联管道不做模块依赖图解析，多文件请用
 *   普通 script 顺序加载）。
 */
object GameValidator {

    const val FILE_INDEX_HTML = "index.html"

    /**
     * 多文件模式的结构建议阈值：入口完全内联（无本地 js/css 引用）且
     * 内联脚本+样式超过该行数时提示拆分——纯建议（warning），
     * 提示文案明确"修复报障回合无需处理"，避免与修改范围契约打架。
     */
    const val INLINE_BLOAT_WARN_LINES = 600

    private val forbiddenJsRegex = Regex("""\beval\s*\(|new\s+Function\s*\(|\brequire\s*\(|\bimport\s*\(""")
    private val moduleSyntaxRegex = Regex(
        """\bimport\s*(\{|\*)|\bimport\s+['"]|\bimport\s+[\w$]+\s+from\b|\bexport\s+(default\s+)?(function\b|class\b|const\b|let\b|var\b|\{)"""
    )
    private val cssUrlRegex = Regex("""url\(\s*['"]?([^)'"\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val externalUrlRegex = Regex("""(?:https?://|//[a-z][a-z0-9.-]*[/'"])""", RegexOption.IGNORE_CASE)

    fun validate(
        html: String,
        /** 本轮条件允许的引擎集合（条件引入机制）；缺省=全部内置引擎。 */
        allowedEngines: Set<String> = GameEngines.BUNDLED.keys,
        /**
         * 工作区文件存在性回调（多文件契约的裁决点）：非 null 时，本地 script/link
         * 引用改为存在性校验（存在=合法多文件组织，缺失=error 引导模型先创建）；
         * null（兼容回环等无工作区场景）维持单文件禁令——本地引用一律 error。
         */
        localFileExists: ((String) -> Boolean)? = null,
        /**
         * 入参是否为内联合并后的视图：是则跳过"超大内联建议拆分"结构提示
         * （合并视图天然全内联，该提示只对原始入口有意义）。
         */
        inlineView: Boolean = false
    ): ValidationReport {
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

        /** 本地引用分流：多文件模式查存在性，单文件模式一律禁止。 */
        fun localRefIssue(src: String, what: String, hint: String): ValidationIssue? = when {
            localFileExists == null ->
                ValidationIssue(
                    "resources", FILE_INDEX_HTML, 1,
                    "本地$what 资源缺失（本模式要求自包含单文件，代码请直接写在 <script>/<style> 内联）：$src", "error"
                )
            !localFileExists(src) ->
                ValidationIssue(
                    "resources", FILE_INDEX_HTML, 1,
                    "引用的本地$what 文件不存在：$src（$hint）", "error"
                )
            else -> null
        }

        doc.getElementsByTag("script").forEach { el ->
            if (el.hasAttr("src")) {
                val src = el.attr("src")
                if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//")) {
                    externalError("script", src, "JS")
                } else {
                    localRefIssue(src, "JS", "请先 writefile 创建该文件并核对相对路径；入口 index.html 与其同目录")?.let { checks += it }
                }
            }
        }
        doc.select("link[rel=stylesheet][href]").forEach { el ->
            val href = el.attr("href").trim()
            when {
                href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//") ->
                    externalError("css", href, "CSS")
                GameBundle.isLocalRef(href) ->
                    localRefIssue(href, "CSS", "请先 writefile 创建该样式文件并核对相对路径")?.let { checks += it }
                else -> Unit
            }
        }
        doc.getElementsByTag("img").forEach { el ->
            if (el.hasAttr("src")) {
                val src = el.attr("src").trim()
                when {
                    src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//") ->
                        externalError("img", src, "图片")
                    src.isNotBlank() && !src.startsWith("data:") ->
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "本地图片资源缺失（二进制无法内联，图形请用 Canvas/矢量绘制）：$src", "error")
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
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "本地音频资源缺失（音频请用 WebAudio 振荡器程序化生成）：$src", "error")
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
        moduleSyntaxRegex.findAll(html).forEach { m ->
            checks += ValidationIssue(
                "static-runtime", FILE_INDEX_HTML, 1,
                "多文件游戏禁用 ES module 的 import/export（平台按普通 script 顺序内联合并，" +
                    "跨文件请用全局变量/命名空间协作，不建依赖图）：${m.value.take(60)}", "error"
            )
        }

        // 结构建议（仅多文件模式、纯内联且体量很大时）：引导拆分为 js/*.js + css/*.css。
        // 不构成交付门槛；文案声明修复回合可忽略，模型在生成/重构时机自行采纳。
        // inlineView（内联合并视图）天然全内联，不适用本提示。
        if (localFileExists != null && !inlineView) {
            val hasLocalRef =
                doc.select("script[src]").any { GameBundle.isLocalRef(it.attr("src").trim()) } ||
                    doc.select("link[rel=stylesheet][href]").any { GameBundle.isLocalRef(it.attr("href").trim()) }
            if (!hasLocalRef) {
                fun lineCount(content: String): Int =
                    if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
                val inlineLines = doc.getElementsByTag("script").filter { !it.hasAttr("src") }
                    .sumOf { lineCount(it.data()) } +
                    doc.getElementsByTag("style").sumOf { lineCount(it.data()) }
                if (inlineLines > INLINE_BLOAT_WARN_LINES) {
                    checks += ValidationIssue(
                        "structure", FILE_INDEX_HTML, 1,
                        "index.html 内联脚本/样式约 $inlineLines 行：建议拆分为 js/*.js 与 css/*.css 多文件组织" +
                            "（平台运行前自动内联合并，离线不变；本条为结构建议非错误，修复用户报障的回合无需处理）",
                        "warning"
                    )
                }
            }
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

        // 内置引擎声明白名单（条件引入机制）：ww-engine 只允许平台已内置且本轮
        // 条件允许的引擎（GameAgent 按 design_schema 维度/系统/档位算出 allowedEngines
        // 传入；默认全部内置引擎，供 JVM 单测与文件级校验使用）。
        GameEngines.declaredEngines(html)
            .filterNot { it in GameEngines.BUNDLED.keys }
            .forEach { engine ->
                checks += ValidationIssue(
                    "resources", FILE_INDEX_HTML, 1,
                    "不支持的引擎声明：$engine（平台未内置该引擎；ww-engine 只能声明内置引擎：" +
                        "${GameEngines.BUNDLED.keys.joinToString("/")}，由平台渲染时自动注入源码）",
                    "error"
                )
            }
        val bundledDeclared = GameEngines.declaredEngines(html).filter { it in GameEngines.BUNDLED.keys }
        bundledDeclared
            .filterNot { it in allowedEngines }
            .forEach { engine ->
                checks += ValidationIssue(
                    "resources", FILE_INDEX_HTML, 1,
                    "引擎 $engine 不适用于本游戏（适用条件：${GameEngines.BUNDLED[engine]?.condition}；" +
                        "本轮可用引擎：${if (allowedEngines.isEmpty()) "无——本游戏类型不需要引擎" else allowedEngines.joinToString("/")}）。" +
                        "请移除该声明并按当前技术方案实现",
                    "error"
                )
            }

        return ValidationReport(checks.distinctBy { "${it.category}|${it.message}" })
    }

    /**
     * 辅助文件（js 与 css 等非入口文件）的轻量契约检查：
     * 入口级校验（HTML 结构/引用存在性/可观测性契约）不适用于纯 JS/CSS 文本——
     * jsoup 会把它们当 HTML 解析，产生"缺少 __wwDebugState"之类伪错误。
     * 这里只查与文件类型相关的硬约束：js 查安全禁令与 ES module 语法；
     * css 查 url() 外链（error）与无法内联的本地引用（warning）。
     */
    fun validateAuxFile(path: String, content: String): ValidationReport {
        if (content.isBlank()) return ValidationReport()
        val checks = mutableListOf<ValidationIssue>()
        when {
            path.endsWith(".js", ignoreCase = true) -> {
                forbiddenJsRegex.findAll(content).forEach { m ->
                    checks += ValidationIssue(
                        "static-runtime", path, 1,
                        "安全契约禁止 eval / new Function / 动态 require / import()：${m.value}", "error"
                    )
                }
                moduleSyntaxRegex.findAll(content).forEach { m ->
                    checks += ValidationIssue(
                        "static-runtime", path, 1,
                        "多文件游戏禁用 ES module 的 import/export（平台按普通 script 顺序内联合并，" +
                            "跨文件请用全局变量/命名空间协作）：${m.value.take(60)}", "error"
                    )
                }
            }
            path.endsWith(".css", ignoreCase = true) -> {
                cssUrlRegex.findAll(content).forEach { match ->
                    val url = match.groupValues[1]
                    when {
                        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ||
                            url.startsWith("//") ->
                            checks += ValidationIssue(
                                "resources", path, 1,
                                "CSS 引用外部资源（离线可玩承诺）：$url", "error"
                            )
                        !url.startsWith("data:", ignoreCase = true) && !url.startsWith("#") ->
                            checks += ValidationIssue(
                                "resources", path, 1,
                                "CSS 引用的本地资源缺失（二进制无法内联）：$url", "warning"
                            )
                    }
                }
            }
        }
        return ValidationReport(checks.distinctBy { "${it.category}|${it.message}" })
    }
}
