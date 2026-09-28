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

    /**
     * 参数外置契约维护（写入时刻；修复/迭代轮生效——回合起点已定义 GAME_CONFIG 的游戏）：
     * - 悬空引用：工作区任何 `GAME_CONFIG.key`（或别名 `CFG.key`）引用的键不在定义里。
     *   丢键 = 运行时 undefined（NaN 类症状只能靠沙箱事后兜底，像素/颜色类甚至无症状），
     *   写入时刻精准拦截（与模块导出契约同族：调用存在而定义缺失）；
     * - 参数键守恒：以起点键集为基准，删除/改名需用户指令点名键名或授权重做
     *   （[isKeyDeletionAuthorized]——与断言 id 守恒同语义：玩家参数面板与
     *   user_config 覆盖依赖键名稳定）；
     * - 值类型纯 JSON：GAME_CONFIG 字面量规范化后仍解析失败（函数/表达式值）
     *   即违规——平台参数面板据此解析默认值表。
     * 首次生成轮（起点无 GAME_CONFIG）不参与：并行写入的正常时序（先引用后建键）
     * 不该被拦，结构存在性由验收校验把关。键集/引用集不可解析时跳过对应检查
     * （宁可漏报不误伤）。
     */
    fun configContractViolations(
        files: Map<String, String>,
        startFiles: Map<String, String>,
        isKeyDeletionAuthorized: (String) -> Boolean
    ): List<String> {
        val violations = mutableListOf<String>()
        val participates = startFiles.values.any { GameConfigContract.hasConfigObject(it) }
        if (participates) {
            val currentKeys = files.values.mapNotNull { GameConfigContract.extractConfigKeys(it) }
                .flatten().toSet()
            // 悬空引用：引用了不存在的键（新增引用先于补键落盘时同样命中——
            // 报错文案引导"先补 config.js 再写引用"，与导出契约的处理一致）
            val refs = GameConfigContract.collectConfigRefs(files)
            val dangling = refs.filterKeys { it !in currentKeys }
            if (dangling.isNotEmpty()) {
                violations += "参数外置契约被破坏——悬空引用：GAME_CONFIG 未定义的键被引用：" +
                    dangling.entries.sortedBy { it.key }
                        .joinToString("、") { "${it.key}（${it.value.sorted().joinToString("、")} 引用）" } +
                    "。请先在 js/config.js 补齐参数键（或修正引用处拼写），再落盘引用它的代码"
            }
            // 参数键守恒：删除/改名需点名（新增/调值不受限）
            val startKeys = startFiles.values.mapNotNull { GameConfigContract.extractConfigKeys(it) }
                .flatten().toSet()
            val unauthorized = startKeys.filterNot { it in currentKeys || isKeyDeletionAuthorized(it) }
            if (unauthorized.isNotEmpty()) {
                violations += "参数外置契约被破坏——参数键只增不减：以下键被删除或改名：" +
                    unauthorized.sorted().joinToString("、") +
                    "（玩家参数面板与参数覆盖依赖键名稳定）。新增参数直接追加键、调值不受限；" +
                    "确需删除或改名，须用户指令明确点名该键名或授权重做；改名时先改 config.js 再同步全部引用"
            }
        }
        // 值类型：字面量存在但规范化后仍非 JSON（函数/表达式值破坏面板解析）
        files.forEach { (path, content) ->
            if (GameConfigContract.configLiteralIsJsonCompatible(content) == false) {
                violations += "参数外置契约被破坏——$path 的 GAME_CONFIG 含非 JSON 值（函数或表达式）。" +
                    "参数值只能是数字/字符串/布尔/数组/对象（平台参数面板据此解析默认值表）"
            }
        }
        return violations
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
