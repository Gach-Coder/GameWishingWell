package com.gamewishingwell.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 参数面板的参数档案：合法键集（GAME_CONFIG 顶层键）+ 键 → 行尾注释（检测与默认参数视图用）。 */
data class ConfigParamInfo(val keys: Set<String>, val docs: Map<String, String>)

/**
 * 参数外置契约（平台侧共享常量与解析器，校验器/注入器/参数面板三方同源）：
 * - 生成侧（GamePrompt 契约）：游戏把全部可调参数集中定义在 window.GAME_CONFIG
 *   （工具模式放 js/config.js、单文件直出放脚本最前），值为纯 JSON 类型，
 *   定义后紧跟 Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {})；
 * - 运行侧（HtmlEnhancer）：加载时在游戏脚本之前注入
 *   window.__wwConfigOverride = {...}——内容来自 games/<id>/user_config.json
 *   （平台自有 JSON，玩家经游戏页"参数调整"面板免 LLM 修改，仅校验合法才注入）；
 * - 面板侧（GameViewModel）：从合并视图提取 GAME_CONFIG 作为默认参数表展示，
 *   "填充全部"模板＝默认值逐键叠加当前覆盖。
 */
object GameConfigContract {

    /** 参数对象全局名（生成契约要求游戏定义；未定义的游戏不显示参数面板入口）。 */
    const val CONFIG_GLOBAL = "GAME_CONFIG"

    /** 平台覆盖桥全局名（HtmlEnhancer 注入，GAME_CONFIG 的合并行消费）。 */
    const val OVERRIDE_BRIDGE = "__wwConfigOverride"

    /** 契约要求的平台覆盖合并行（提示词与校验错误文案共用的标准写法）。 */
    const val MERGE_LINE = "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {})"

    /**
     * GAME_CONFIG 对象赋值探测：var/let/const/window. 前缀均可；
     * Object.assign(window.GAME_CONFIG, ...) 调用形不含 "=" 不误匹配。
     */
    private val configAssignmentRegex = Regex("""(?:window\.)?GAME_CONFIG\s*=\s*\{""")

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }

    /** 合并视图（或单文件 HTML）中是否定义了 GAME_CONFIG。 */
    fun hasConfigObject(html: String?): Boolean =
        html != null && configAssignmentRegex.containsMatchIn(html)

    /**
     * 玩家覆盖文件（user_config.json）合法性：必须是非空 JSON 对象。
     * 写入前与注入前都以此为闸——坏 JSON 不落盘、不进页面。
     */
    fun isValidOverrideJson(text: String): Boolean =
        text.isNotBlank() && runCatching { json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)

    /**
     * 从 HTML 提取 GAME_CONFIG 的对象字面量原文（字符串感知的花括号配对，
     * 值里出现 "}" 或注释符不影响配对）。找不到赋值或括号不闭合返回 null。
     */
    fun extractConfigLiteral(html: String): String? {
        val m = configAssignmentRegex.find(html) ?: return null
        val open = m.range.last // 匹配尾部的 '{'
        var depth = 0
        var i = open
        while (i < html.length) {
            when (val c = html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(open, i + 1)
                }
                '"', '\'', '`' -> i = skipString(html, i)
            }
            i++
        }
        return null
    }

    /** 跳过 [start] 处引号开头的字符串字面量，返回闭引号下标（无闭引号返回末尾）。 */
    private fun skipString(s: String, start: Int): Int {
        val quote = s[start]
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i
            i++
        }
        return s.length - 1
    }

    /**
     * JS 对象字面量 → 严格 JSON 的容错规范化（同一遍扫描，全程字符串感知）：
     * 去 // 与 块注释、去尾逗号、未引号键补引号（仅 { 或 , 后随冒号的标识符）、
     * 单引号字符串转双引号。契约要求值为纯 JSON 类型，这里只兜常见书写偏差；
     * 规范化后仍解析失败的情况由调用方按 null 降级（宁可放弃解析不误产）。
     */
    fun normalizeToJson(literal: String): String? {
        val sb = StringBuilder(literal.length + 32)
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            when {
                c == '/' && i + 1 < literal.length && literal[i + 1] == '/' -> {
                    while (i < literal.length && literal[i] != '\n') i++
                }
                c == '/' && i + 1 < literal.length && literal[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < literal.length && !(literal[i] == '*' && literal[i + 1] == '/')) i++
                    i = minOf(i + 2, literal.length)
                }
                c == '"' -> {
                    val end = skipString(literal, i)
                    sb.append(literal, i, end + 1)
                    i = end + 1
                }
                c == '\'' -> {
                    val end = skipString(literal, i)
                    sb.append('"')
                        .append(literal.substring(i + 1, end).replace("\"", "\\\""))
                        .append('"')
                    i = end + 1
                }
                // 尾逗号：逗号后下一个有效字符是 } 或 ] 时丢弃
                c == ',' && nextSignificantIn(literal, i + 1, "}]") -> i++
                // 未引号键：{ 或 , 之后的标识符且后随冒号 → 补双引号；
                // 其余裸标识符（true/false/null）是合法 JSON 值，原样保留
                (c == '_' || c == '$' || c.isLetter()) && run {
                    var j = sb.length - 1
                    while (j >= 0 && sb[j].isWhitespace()) j--
                    j >= 0 && (sb[j] == '{' || sb[j] == ',')
                } -> {
                    var j = i
                    while (j < literal.length && (literal[j].isLetterOrDigit() || literal[j] == '_' || literal[j] == '$')) j++
                    var k = j
                    while (k < literal.length && literal[k].isWhitespace()) k++
                    if (k < literal.length && literal[k] == ':') {
                        sb.append('"').append(literal, i, j).append('"')
                        i = j
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun nextSignificantIn(s: String, from: Int, chars: String): Boolean {
        var i = from
        while (i < s.length) {
            if (!s[i].isWhitespace()) return chars.indexOf(s[i]) >= 0
            i++
        }
        return false
    }

    /** 从合并视图提取默认参数表（规范化后按严格 JSON 解析；失败返回 null）。 */
    fun extractDefaults(html: String): JsonObject? {
        val literal = extractConfigLiteral(html) ?: return null
        val normalized = normalizeToJson(literal) ?: return null
        return runCatching { json.parseToJsonElement(normalized) as? JsonObject }.getOrNull()
    }

    /**
     * 提取单文件内容中 GAME_CONFIG 的键集（写入护栏的度量基准）。
     * 无 GAME_CONFIG 赋值或字面量不可解析返回 null（不可解析=无法度量，护栏跳过）。
     */
    fun extractConfigKeys(content: String): Set<String>? {
        val literal = extractConfigLiteral(content) ?: return null
        val normalized = normalizeToJson(literal) ?: return null
        return runCatching { (json.parseToJsonElement(normalized) as? JsonObject)?.keys }.getOrNull()
    }

    /**
     * GAME_CONFIG 字面量值是否纯 JSON 兼容（写入护栏的值类型检查）：
     * 无赋值返回 null（不参与）；有赋值时规范化后仍解析失败（函数/表达式值）= false。
     */
    fun configLiteralIsJsonCompatible(content: String): Boolean? {
        val literal = extractConfigLiteral(content) ?: return null
        val normalized = normalizeToJson(literal) ?: return false
        return runCatching { json.parseToJsonElement(normalized) is JsonObject }.getOrDefault(false)
    }

    /**
     * 注释移除 + 字符串内容置空（保留成对引号占位）：引用扫描的预处理——
     * 注释里出现的"经 CFG.xxx 引用"与文案里的点号不是真实引用（实测注释会误报）。
     * 误扫正则字面量只可能少报（漏报方向，安全）。
     */
    fun stripCommentsAndStrings(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = minOf(i + 2, src.length)
                    sb.append(' ')
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val end = skipString(src, i)
                    sb.append(c).append(c)
                    i = end + 1
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private val aliasRegex = Regex("""(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:window\.)?GAME_CONFIG\b""")

    /**
     * 全工作区 GAME_CONFIG 参数引用扫描：键 → 引用它的文件集。
     * 识别 `window.GAME_CONFIG.key` / `GAME_CONFIG.key` 直引与
     * `var X = window.GAME_CONFIG` 别名（`X.key`，别名按文件局部生效）；
     * 扫描前剥离注释与字符串内容——注释/文案里的点访问不算引用。
     */
    fun collectConfigRefs(files: Map<String, String>): Map<String, Set<String>> {
        val refs = HashMap<String, MutableSet<String>>()
        files.forEach { (path, content) ->
            val code = stripCommentsAndStrings(content)
            val names = mutableSetOf(CONFIG_GLOBAL)
            aliasRegex.findAll(code).forEach { names += it.groupValues[1] }
            names.forEach { name ->
                val re = Regex("""\b${Regex.escape(name)}\s*\.\s*([A-Za-z_$][\w$]*)""")
                re.findAll(code).forEach { m ->
                    refs.getOrPut(m.groupValues[1]) { mutableSetOf() } += path
                }
            }
        }
        return refs
    }

    /**
     * Agent 上下文的玩家参数覆盖清单（参数面板 user_config.json 的渲染）：
     * 让模型看见玩家已把哪些参数调成了什么值——覆盖优先于 config.js 默认值，
     * 看不见会让"调简单点"类修改被覆盖静默吞掉。非法/空覆盖返回 null。
     */
    fun renderOverrideNote(overrideJson: String?): String? {
        if (overrideJson.isNullOrBlank() || !isValidOverrideJson(overrideJson)) return null
        val obj = runCatching { json.parseToJsonElement(overrideJson) as? JsonObject }.getOrNull() ?: return null
        if (obj.isEmpty()) return null
        val shown = obj.entries.sortedBy { it.key }.take(MAX_OVERRIDE_NOTE_KEYS)
        val lines = shown.joinToString("\n") { "- ${it.key} = ${it.value}" }
        return buildString {
            append("【玩家参数覆盖（平台参数调整面板，优先于 config.js 默认值）】\n")
            append("玩家已手动覆盖以下参数（经 window.__wwConfigOverride 注入生效）：\n")
            append(lines)
            if (obj.size > MAX_OVERRIDE_NOTE_KEYS) append("\n（另有 ${obj.size - MAX_OVERRIDE_NOTE_KEYS} 项略）")
            append("\n修改这些参数的默认值不会改变玩家的实际体验（覆盖仍优先）；调整相关玩法时以覆盖值为准评估；")
            append("改名或删除参数键时必须同步提醒玩家更新参数覆盖。")
        }
    }

    private const val MAX_OVERRIDE_NOTE_KEYS = 40

    /** 默认参数表的展示用形态（面板"默认参数"只读视图）。 */
    fun prettyDefaults(html: String): String? =
        extractDefaults(html)?.let { prettyJson.encodeToString(JsonObject.serializer(), it) }

    /**
     * 逐行提取 GAME_CONFIG 参数的行尾注释：键 → 注释文本（`"key": 值, // 注释` 形态；
     * 无注释的键缺席）。数组内层键行无注释不误收；值内含 // 时注释从首个 // 截取（仅展示用，
     * 容差可接受）。
     */
    private val paramDocRegex = Regex(
        """^[ \t]*"([A-Za-z_$][\w$]*)"[ \t]*:.*?//[ \t]?(.*)$""",
        RegexOption.MULTILINE
    )

    fun extractParamDocs(content: String): Map<String, String> =
        paramDocRegex.findAll(content).associate { it.groupValues[1] to it.groupValues[2].trim() }

    /** 默认参数视图渲染：`"键": 值    // 注释`（保留 config.js 声明序，无注释键省略注释段）。 */
    fun renderDefaults(defaults: JsonObject, docs: Map<String, String>): String =
        defaults.entries.joinToString("\n") { (k, v) ->
            val doc = docs[k]
            "\"$k\": $v" + if (doc.isNullOrEmpty()) "" else "    // $doc"
        }

    /**
     * 参数值输入的宽松解析：先按 JSON 解析（数字/布尔/null/数组/对象都接受），
     * 解析失败按字符串字面量处理（参数面板输入框免引号——填 #ffb034 即字符串色值）。
     */
    fun parseValueOrString(text: String): JsonElement =
        runCatching { json.parseToJsonElement(text.trim()) }.getOrElse { JsonPrimitive(text) }

    /**
     * 面板"填充全部"模板已随参数调整编辑器结构化改版移除（检测/逐项添加取代全量铺底）。
     */
}
