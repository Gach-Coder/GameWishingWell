package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.ErrorReporter
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Parser as RhinoParser
import org.mozilla.javascript.ast.AstNode
import org.mozilla.javascript.ast.AstRoot
import org.mozilla.javascript.ast.CatchClause
import org.mozilla.javascript.ast.ElementGet
import org.mozilla.javascript.ast.FunctionNode
import org.mozilla.javascript.ast.Name
import org.mozilla.javascript.ast.NodeVisitor
import org.mozilla.javascript.ast.ObjectProperty
import org.mozilla.javascript.ast.PropertyGet
import org.mozilla.javascript.ast.Scope
import org.mozilla.javascript.ast.VariableInitializer

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
 * 基本校验脚本。输出结构化 JSON：
 * {checks:[{category,file,line,message,severity}]}
 *
 * - 语法：Rhino（Mozilla JS parser，Android 端可用的现成解析器）做 acorn 同等职责的 JS 语法检查；
 * - HTML 配对：jsoup HTML parser 的 error tracking（不手写标签栈）；
 * - 静态运行时：基于 parser AST + 作用域符号表实现 no-undef 规则；
 * - DOM id 存在性、资源缺失、占位实现。
 */
object GameValidator {

    const val FILE_INDEX_HTML = "index.html"

    private val scriptRegex = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val externalUrlRegex = Regex("""(?:https?://|//[a-z][a-z0-9.-]*[/'"])""", RegexOption.IGNORE_CASE)
    private val forbiddenJsRegex = Regex("""\beval\s*\(|new\s+Function\s*\(|\brequire\s*\(|\bimport\s*\(""")
    private val domGetRegex = Regex("""getElementById\s*\(\s*['"]([^'"]+)['"]""")
    private val querySelectorRegex = Regex("""querySelector(?:All)?\s*\(\s*['"]#([A-Za-z_][\w-]*)['"]""")
    private val dynamicIdRegex = Regex("""\.id\s*=\s*['"]([^'"]+)['"]|setAttribute\s*\(\s*['"]id['"]\s*,\s*['"]([^'"]+)['"]""")

    fun validate(html: String): ValidationReport {
        if (html.isBlank()) {
            return ValidationReport(
                listOf(
                    ValidationIssue("syntax", FILE_INDEX_HTML, 1, "HTML 内容为空", "error")
                )
            )
        }

        val checks = mutableListOf<ValidationIssue>()
        val doc = try {
            Parser.htmlParser().apply {
                setTrackErrors(64)
            }.parseInput(html, "")
        } catch (e: Exception) {
            checks += ValidationIssue("html", FILE_INDEX_HTML, 1, "HTML 解析失败：${e.message}", "error")
            return ValidationReport(checks)
        }

        // HTML 标签配对（jsoup parser 的 tokenizer 错误）
        try {
            val parser = Parser.htmlParser().apply { setTrackErrors(64) }
            parser.parseInput(html, "")
            parser.errors.forEach { err ->
                checks += ValidationIssue(
                    "html", FILE_INDEX_HTML, 1,
                    "HTML 标签配对异常：${err.errorMessage}", "error"
                )
            }
        } catch (e: Exception) {
            checks += ValidationIssue("html", FILE_INDEX_HTML, 1, "HTML 解析失败：${e.message}", "error")
        }

        val htmlIds = doc.allElements
            .mapNotNull { it.id().takeIf { id -> id.isNotBlank() } }
            .toMutableSet()
        val dynamicIds = dynamicIdRegex.findAll(html).flatMap { match ->
            listOfNotNull(match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }, match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() })
        }.toSet()
        val allowedIds = htmlIds + dynamicIds

        val scriptMatches = scriptRegex.findAll(html).toList()
        if (scriptMatches.isEmpty()) {
            checks += ValidationIssue("syntax", FILE_INDEX_HTML, 1, "未找到 <script> 游戏代码", "error")
        }

        for (match in scriptMatches) {
            val source = match.groupValues[1]
            val scriptLine = html.substring(0, match.range.first).count { it == '\n' } + 1
            if (source.isBlank()) continue
            val (ast, parseErrors) = parseJavaScript(source, scriptLine)
            parseErrors.forEach { (line, message) ->
                checks += ValidationIssue("syntax", FILE_INDEX_HTML, line, "JS 语法错误：$message", "error")
            }
            if (ast != null) {
                checks += checkNoUndef(ast, scriptLine, source)
                checks += checkDomReferences(source, scriptLine, allowedIds)
            }
            checks += checkForbiddenPatterns(source, scriptLine)
            checks += checkPlaceholders(source, scriptLine)
        }

        // 资源缺失 / 外部资源
        doc.getElementsByTag("script").forEach { el ->
            if (el.hasAttr("src")) {
                val src = el.attr("src")
                if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//")) {
                    checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "禁止外部 JS 资源：$src", "error")
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
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "禁止外部图片资源：$src", "error")
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
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "禁止外部音频资源：$src", "error")
                    src.isNotBlank() && !src.startsWith("data:") ->
                        checks += ValidationIssue("resources", FILE_INDEX_HTML, 1, "本地音频资源缺失（自包含游戏禁止引用本地文件）：$src", "error")
                }
            }
        }
        Regex("""url\(\s*['"]?([^)'"\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
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

        return ValidationReport(checks.distinctBy { "${it.category}|${it.line}|${it.message}" })
    }

    private data class ParseError(val line: Int, val message: String)

    private fun parseJavaScript(source: String, startLine: Int): Pair<AstRoot?, List<ParseError>> {
        val env = CompilerEnvirons().apply {
            setLanguageVersion(Context.VERSION_ES6)
            setRecoverFromErrors(true)
            setIdeMode(true)
            setReservedKeywordAsIdentifier(true)
        }
        val errors = mutableListOf<ParseError>()
        val reporter = object : ErrorReporter {
            override fun warning(message: String, sourceName: String, line: Int, lineSource: String, lineOffset: Int) {
                errors += ParseError(line.coerceAtLeast(startLine), message)
            }

            override fun error(message: String, sourceName: String, line: Int, lineSource: String, lineOffset: Int) {
                errors += ParseError(line.coerceAtLeast(startLine), message)
            }

            override fun runtimeError(message: String, sourceName: String, line: Int, lineSource: String, lineOffset: Int): EvaluatorException =
                EvaluatorException(message)
        }
        return try {
            val ast = RhinoParser(env, reporter).parse(source, FILE_INDEX_HTML, startLine)
            ast to errors
        } catch (e: Exception) {
            null to (errors + ParseError(startLine, e.message ?: "JS 解析失败"))
        }
    }

    private fun checkNoUndef(ast: AstRoot, startLine: Int, source: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val declaredByScope = HashMap<Scope, Set<String>>()
        ast.visitAll(object : NodeVisitor {
            override fun visit(node: AstNode): Boolean {
                if (node is Scope && node.symbolTable != null) {
                    declaredByScope[node] = node.symbolTable.keys.toSet()
                }
                return true
            }
        })

        ast.visitAll(object : NodeVisitor {
            override fun visit(node: AstNode): Boolean {
                if (node is Name) {
                    if (isDeclarationOrPropertyName(node)) return true
                    val name = node.identifier
                    if (name.isBlank() || BROWSER_GLOBALS.contains(name) || JS_BUILTINS.contains(name)) return true
                    val scope = nearestScope(node)
                    if (scope != null && isDeclaredInScopeChain(scope, name, declaredByScope)) return true
                    issues += ValidationIssue(
                        "static-runtime", FILE_INDEX_HTML, node.lineno.coerceAtLeast(startLine),
                        "引用未定义变量/函数：$name（no-undef）", "error"
                    )
                }
                return true
            }
        })
        return issues.distinctBy { "${it.line}|${it.message}" }
    }

    private fun isDeclarationOrPropertyName(name: Name): Boolean {
        val parent = name.parent ?: return false
        if (parent is VariableInitializer && parent.target === name) return true
        if (parent is FunctionNode && parent.functionName === name) return true
        if (parent is PropertyGet && parent.property === name) return true
        if (parent is ObjectProperty && (parent.left === name || parent.right === name)) return true
        if (parent is CatchClause && parent.varName === name) return true
        if (parent is ElementGet && parent.element === name) {
            // element 是动态 key，例如 obj[key]；只有字符串字面量会由 Rhino 表达为 StringLiteral，
            // Name 出现在这里时按普通变量引用处理，不能跳过。
            return false
        }
        return false
    }

    private fun nearestScope(node: AstNode): Scope? {
        var current: AstNode? = node.parent
        while (current != null) {
            if (current is Scope) return current
            current = current.parent
        }
        return null
    }

    private fun isDeclaredInScopeChain(scope: Scope, name: String, declared: Map<Scope, Set<String>>): Boolean {
        var current: Scope? = scope
        while (current != null) {
            if (declared[current]?.contains(name) == true) return true
            current = current.parentScope
        }
        return false
    }

    private fun checkDomReferences(source: String, startLine: Int, allowedIds: Set<String>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val missing = linkedSetOf<String>()
        domGetRegex.findAll(source).forEach { missing += it.groupValues[1] }
        querySelectorRegex.findAll(source).forEach { missing += it.groupValues[1] }
        missing.forEach { id ->
            if (id !in allowedIds && !id.startsWith("__ww")) {
                issues += ValidationIssue(
                    "dom-ids", FILE_INDEX_HTML, lineOf(source, id, startLine),
                    "JS 引用的 DOM id 不存在：$id（动态创建 id 请在白名单或动态赋值中声明）", "warning"
                )
            }
        }
        return issues
    }

    private fun checkForbiddenPatterns(source: String, startLine: Int): List<ValidationIssue> =
        forbiddenJsRegex.findAll(source).mapNotNull { m ->
            ValidationIssue(
                "static-runtime", FILE_INDEX_HTML, lineOf(source, m.value, startLine),
                "生成代码契约禁止 eval / new Function / 动态 require / import()：${m.value}", "error"
            )
        }.toList()

    private fun checkPlaceholders(source: String, startLine: Int): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val todo = Regex("""(?:TODO|FIXME|占位|待实现)""", RegexOption.IGNORE_CASE).findAll(source).toList()
        todo.forEach { m ->
            issues += ValidationIssue(
                "placeholders", FILE_INDEX_HTML, lineOf(source, m.value, startLine),
                "发现占位实现标记：${m.value}", "warning"
            )
        }

        // 定义但从未被调用的顶层函数：只报 warning，事件回调等场景会有误报。
        val declared = Regex("""function\s+([A-Za-z_$][\w$]*)\s*\(""").findAll(source).map { it.groupValues[1] }.toSet()
        val called = Regex("""\b([A-Za-z_$][\w$]*)\s*\(""").findAll(source).map { it.groupValues[1] }.toSet()
        val neverCalled = declared - called - setOf("restart")
        neverCalled.forEach { fn ->
            issues += ValidationIssue(
                "placeholders", FILE_INDEX_HTML, lineOf(source, fn, startLine),
                "函数 $fn 定义了但从未被直接调用", "warning"
            )
        }
        return issues
    }

    private fun lineOf(source: String, token: String, startLine: Int): Int {
        val index = source.indexOf(token)
        if (index < 0) return startLine
        return startLine + source.substring(0, index).count { it == '\n' }
    }

    private val BROWSER_GLOBALS: Set<String> = setOf(
        "window", "self", "globalThis", "top", "parent", "frames", "document", "console", "navigator",
        "location", "history", "localStorage", "sessionStorage", "screen", "visualViewport", "devicePixelRatio",
        "innerWidth", "innerHeight", "outerWidth", "outerHeight", "scrollX", "scrollY", "pageXOffset", "pageYOffset",
        "requestAnimationFrame", "cancelAnimationFrame", "setTimeout", "clearTimeout", "setInterval", "clearInterval",
        "requestIdleCallback", "cancelIdleCallback", "fetch", "XMLHttpRequest", "Image", "Audio",
        "AudioContext", "webkitAudioContext", "OfflineAudioContext", "AudioNode", "GainNode", "OscillatorNode",
        "CanvasRenderingContext2D", "WebGLRenderingContext", "Path2D", "DOMParser", "Event", "CustomEvent",
        "KeyboardEvent", "MouseEvent", "TouchEvent", "PointerEvent", "WheelEvent", "IntersectionObserver",
        "MutationObserver", "ResizeObserver", "getComputedStyle", "matchMedia", "URL", "URLSearchParams",
        "Blob", "FileReader", "TextEncoder", "TextDecoder", "atob", "btoa", "crypto", "performance",
        "requestFileSystem", "webkitRequestFileSystem", "Notification", "WebSocket", "Worker", "gamepad",
        "ontouchstart", "ontouchend", "ontouchmove", "orientation"
    )

    private val JS_BUILTINS: Set<String> = setOf(
        "Array", "ArrayBuffer", "BigInt", "Boolean", "DataView", "Date", "Error", "EvalError", "Float32Array",
        "Float64Array", "Function", "Infinity", "Int8Array", "Int16Array", "Int32Array", "JSON", "Map", "Math",
        "NaN", "Number", "Object", "Promise", "Proxy", "RangeError", "ReferenceError", "Reflect", "RegExp",
        "Set", "String", "Symbol", "SyntaxError", "TypeError", "URIError", "Uint8Array", "Uint8ClampedArray",
        "Uint16Array", "Uint32Array", "WeakMap", "WeakSet", "decodeURI", "decodeURIComponent", "encodeURI",
        "encodeURIComponent", "escape", "unescape", "eval", "isFinite", "isNaN", "parseFloat", "parseInt",
        "undefined", "arguments"
    )
}
