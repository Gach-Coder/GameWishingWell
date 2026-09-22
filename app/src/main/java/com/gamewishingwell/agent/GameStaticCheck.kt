package com.gamewishingwell.agent

/**
 * 跨文件模块导出契约静态预检（写入时刻，A1 家族）：
 * 生成的多文件/单文件游戏普遍采用 `window.X = (function(){ ... return {...} })()`
 * 模块模式，入口与兄弟模块通过 `X.fn(...)` 调用——重写模块文件时丢失导出成员会让
 * init 直接抛 TypeError（历史事故：touch.js 重写丢 setUI 导出，main.js 的
 * Input.setUI 调用使游戏一启动即崩溃，且当时无任何写入时刻拦截）。
 *
 * 规则（只拦确定性证据，宁可漏报不误伤）：
 * - 模块识别：`window.X = (function` 开头的 IIFE 赋值（plain 对象 `window.G = {...}`
 *   不匹配——其成员访问不是函数调用契约）；
 * - 导出表：文件内所有 `return {` 平衡花括号块的键并集（嵌套键一并计入＝导出面
 *   高估，方向性安全）；无任何 return 块＝导出表不可解析，跳过该模块；
 * - 调用点：全部工作区文件（含入口 index.html 的内联脚本）里的 `X.member(`；
 *   member 不在导出表 → 违规。
 * - 动态访问（X['fn']）、引擎全局（THREE/Matter 等非工作区定义）不参与判定。
 */
object GameStaticCheck {

    data class Violation(val module: String, val member: String, val callers: List<String>)

    private val moduleStartRegex = Regex("""window\.([A-Za-z_$][\w$]*)\s*=\s*\(\s*function""")
    private val returnBlockStartRegex = Regex("""return\s*\{""")
    private val keyRegex = Regex("""([A-Za-z_$][\w$]*)\s*:""")

    /** files：写入后的工作区全量视图（相对路径 → 内容，含 index.html）。 */
    fun moduleExportViolations(files: Map<String, String>): List<Violation> {
        // 1) 模块 → 导出表（跨全部文件：模块定义多在 js/，调用方含入口内联脚本；
        //    同名模块多文件声明取并集——导出面只增不减，方向性安全）
        val exports = HashMap<String, MutableSet<String>>()
        files.forEach { (path, content) ->
            moduleStartRegex.findAll(content).forEach { m ->
                exports.getOrPut(m.groupValues[1]) { mutableSetOf() } += collectReturnBlockKeys(content)
            }
        }
        // 2) 调用点核对：X.member( 缺席即违规
        val violations = mutableListOf<Violation>()
        exports.keys.forEach { name ->
            val table = exports[name]!!
            if (table.isEmpty()) return@forEach // 导出表不可解析：跳过（不误伤）
            val callRegex = Regex("""\b${Regex.escape(name)}\s*\.\s*([A-Za-z_$][\w$]*)\s*\(""")
            val missing = LinkedHashMap<String, MutableList<String>>()
            files.forEach { (path, content) ->
                callRegex.findAll(content).forEach { cm ->
                    val member = cm.groupValues[1]
                    if (member !in table) {
                        missing.getOrPut(member) { mutableListOf() }.add(path)
                    }
                }
            }
            missing.forEach { (member, callers) ->
                violations += Violation(name, member, callers)
            }
        }
        return violations
    }

    /** 提取文件内全部 `return { ... }` 平衡块的对象键（导出面高估方向）。 */
    private fun collectReturnBlockKeys(content: String): Set<String> {
        val keys = mutableSetOf<String>()
        var searchFrom = 0
        while (true) {
            val start = returnBlockStartRegex.find(content, searchFrom) ?: break
            val openBrace = content.indexOf('{', start.range.first)
            if (openBrace < 0) break
            val close = findBalancedClose(content, openBrace) ?: break
            keyRegex.findAll(content.substring(openBrace + 1, close)).forEach { km ->
                keys += km.groupValues[1]
            }
            searchFrom = close + 1
        }
        return keys
    }

    /** 从 openBrace（'{' 下标）走平衡花括号，返回匹配 '}' 下标；中途耗尽返回 null。 */
    private fun findBalancedClose(content: String, openBrace: Int): Int? {
        var depth = 0
        var i = openBrace
        var inStr: Char? = null
        while (i < content.length) {
            val c = content[i]
            if (inStr != null) {
                if (c == '\\') i++ // 转义字符整体跳过
                else if (c == inStr) inStr = null
            } else when (c) {
                '\'', '"', '`' -> inStr = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}
