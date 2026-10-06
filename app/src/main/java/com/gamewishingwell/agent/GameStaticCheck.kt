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
 *
 * 性能契约（2026-10 实测事故）：460KB main.js 工作区每次 editfile 写入前的三项
 * 跨文件检查串行耗时 53~104 秒（一轮 22 个写操作光本地执行就烧 30 分钟，与 LLM
 * 无关）——本对象的全部派生数据（声明名集/导出键集/成员调用点）按【内容哈希】
 * 记忆化：一次写入只改一个文件，未变文件命中缓存，改哪个文件就只重算哪个文件。
 */
object GameStaticCheck {

    data class Violation(val module: String, val member: String, val callers: List<String>)

    private val moduleStartRegex = Regex("""window\.([A-Za-z_$][\w$]*)\s*=\s*\(\s*function""")
    private val returnBlockStartRegex = Regex("""return\s*\{""")
    private val keyRegex = Regex("""([A-Za-z_$][\w$]*)\s*:""")
    private val memberCallRegex = Regex("""\b([A-Za-z_$][\w$]*)\s*\.\s*([A-Za-z_$][\w$]*)\s*\(""")
    private val declKeywordRegex = Regex("""\b(?:var|let|const)\b""")

    // ---------- 内容级记忆化（写入护栏性能关键路径） ----------
    // 同一回合每次写入重跑检查，而工作区内容按"文件版本"演化：以 64 位 FNV-1a
    // 内容哈希为键，容量满即整体清空（活跃集＝当前 5 文件 + 回合起点 5 文件，
    // 远小于上限；清空后首个写入全量重算一次， correctness 不受影响）。

    private const val CACHE_MAX_ENTRIES = 64

    private val declaredNamesCache = HashMap<Long, Set<String>>()
    private val returnKeysCache = HashMap<Long, Set<String>>()
    private val memberCallsCache = HashMap<Long, List<Pair<String, String>>>()

    private fun <T> memo(cache: HashMap<Long, T>, content: String, compute: () -> T): T {
        val key = fnv1a(content)
        synchronized(cache) { cache[key]?.let { return it } }
        val value = compute()
        synchronized(cache) {
            if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
            cache[key] = value
        }
        return value
    }

    /** 64 位 FNV-1a：单遍 O(n)、无中间分配、碰撞面可忽略（仅作缓存键）。 */
    private fun fnv1a(s: String): Long {
        var h = 0xcbf29ce484222325uL
        for (c in s) h = (h xor c.code.toULong()) * 0x100000001b3uL
        return h.toLong()
    }

    /** files：写入后的工作区全量视图（相对路径 → 内容，含 index.html）。 */
    fun moduleExportViolations(files: Map<String, String>): List<Violation> {
        // 1) 模块 → 导出表（跨全部文件：模块定义多在 js/，调用方含入口内联脚本；
        //    同名模块多文件声明取并集——导出面只增不减，方向性安全）
        val exports = HashMap<String, MutableSet<String>>()
        files.forEach { (path, content) ->
            moduleStartRegex.findAll(content).forEach { m ->
                exports.getOrPut(m.groupValues[1]) { mutableSetOf() } += returnBlockKeys(content)
            }
        }
        if (exports.isEmpty()) return emptyList()
        // 2) 调用点核对：X.member( 缺席即违规。单遍收集全部「接收者.成员(」对再按
        //    模块名过滤——与"每个模块一条正则全文件扫一遍"结果等价，扫描量从
        //    模块数×全文件降为全文件一遍（调用点对按内容缓存）。
        val missing = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        files.forEach { (path, content) ->
            memberCalls(content).forEach { (recv, member) ->
                val table = exports[recv] ?: return@forEach
                if (table.isEmpty()) return@forEach // 导出表不可解析：跳过（不误伤）
                if (member !in table) {
                    missing.getOrPut(recv) { LinkedHashMap() }
                        .getOrPut(member) { mutableListOf() }.add(path)
                }
            }
        }
        return missing.flatMap { (name, members) ->
            members.map { (member, callers) -> Violation(name, member, callers) }
        }
    }

    /** 提取文件内全部 `return { ... }` 平衡块的对象键（导出面高估方向；按内容缓存）。 */
    private fun returnBlockKeys(content: String): Set<String> =
        memo(returnKeysCache, content) {
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
            keys
        }

    /** 全部 `X.member(` 调用对（接收者, 成员），按内容缓存。 */
    private fun memberCalls(content: String): List<Pair<String, String>> =
        memo(memberCallsCache, content) {
            memberCallRegex.findAll(content).map { it.groupValues[1] to it.groupValues[2] }.toList()
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

    // ---------- 删除残留检查（顶层声明消失 → 全工作区裸引用扫描） ----------

    private val identRegex = Regex("[A-Za-z_$][\\w$]*")

    /**
     * 收集文件内全部【声明名】（跨文件全局视角，宁多勿漏——多收集只会降低误报）：
     * - var/let/const 声明列表（含逗号多声明与初始化式，逐段跳过）；
     * - function 声明名与全部形参；
     * - catch 参数、for(x of/in) 循环变量；
     * - `window.X =` 赋值名（plain script 下同为全局可引用）。
     * 形参一并计入：局部参数与消失的全局同名时会掩盖真悬空（漏报方向），但避免
     * "删了全局、某函数恰好有同名参数"被误拦（误报代价是致命拒绝一整次写入）。
     * 嵌套在初始化式里的声明（var a = function(){ var b }）一并收集——仍是
     * "多收集"的安全方向。按内容哈希缓存（见 [memo]）。
     */
    fun declaredNames(content: String): Set<String> =
        memo(declaredNamesCache, content) { computeDeclaredNames(content) }

    private fun computeDeclaredNames(content: String): Set<String> {
        val names = mutableSetOf<String>()
        val n = content.length
        // 关键字一遍定位（原实现对每个字符位置调用一次 matchAt——460KB 文件即
        // 46 万次正则调用、每次写入跑两遍快照+当前，是写护栏延迟的主热点）；
        // 声明列表解析逻辑保持原语义不变。
        for (km in declKeywordRegex.findAll(content)) {
            val after = km.range.last + 1
            var j = after
            var expectName = true
            while (j < n) {
                val c = content[j]
                if (c.isWhitespace()) { j++; continue }
                if (expectName) {
                    val dm = identRegex.matchAt(content, j) ?: break
                    names += dm.value
                    j += dm.value.length
                    expectName = false
                } else when (c) {
                    '=' -> { // 跳过初始化式：到顶层逗号/分号/换行/右花括号为止
                        var depth = 0
                        var k = j + 1
                        while (k < n) {
                            val ic = content[k]
                            if (ic in "([{") depth++
                            else if (ic in ")]}") { if (depth == 0) break; depth-- }
                            else if (depth == 0 && (ic == ',' || ic == ';' || ic == '\n')) break
                            k++
                        }
                        j = k
                    }
                    ',' -> { expectName = true; j++ }
                    else -> break
                }
            }
        }
        for (m in Regex("""\bfunction\s+([A-Za-z_$][\w$]*)?\s*\(([^)]*)\)""").findAll(content)) {
            m.groupValues[1].takeIf { it.isNotEmpty() }?.let { names += it }
            for (p in m.groupValues[2].split(',')) {
                val t = p.trim().removePrefix("...").substringBefore('=').trim()
                if (t.isNotEmpty() && identRegex.matches(t)) names += t
            }
        }
        for (m in Regex("""\bcatch\s*\(\s*([A-Za-z_$][\w$]*)""").findAll(content)) names += m.groupValues[1]
        for (m in Regex("""\bfor\s*\(\s*([A-Za-z_$][\w$]*)\s+(?:of|in)\b""").findAll(content)) names += m.groupValues[1]
        for (m in Regex("""\bwindow\.([A-Za-z_$][\w$]*)\s*=""").findAll(content)) names += m.groupValues[1]
        return names
    }

    private fun Char.isJavaScriptIdent(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

    /** 剥离注释与字符串字面量（引用扫描不得命中注释/文案里的同名文本）。 */
    private fun stripCommentsAndStrings(content: String): String {
        val sb = StringBuilder(content.length)
        var i = 0
        var inStr: Char? = null
        while (i < content.length) {
            val c = content[i]
            if (inStr != null) {
                when {
                    c == '\\' -> { sb.append("  "); i += 2; continue }
                    c == inStr -> inStr = null
                }
                sb.append(' ')
            } else when {
                c == '/' && i + 1 < content.length && content[i + 1] == '/' -> {
                    while (i < content.length && content[i] != '\n') { sb.append(' '); i++ }
                    continue
                }
                c == '/' && i + 1 < content.length && content[i + 1] == '*' -> {
                    sb.append("  "); i += 2
                    while (i < content.length && !(content[i] == '*' && i + 1 < content.length && content[i + 1] == '/')) {
                        sb.append(if (content[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < content.length) { sb.append("  "); i += 2 }
                    continue
                }
                c == '\'' || c == '"' || c == '`' -> { inStr = c; sb.append(' ') }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 删除残留检查（写入时刻，写盘前致命拦截）：
     * 本次写入使某标识符【工作区范围内】不再有任何声明、但仍有裸引用 → 确定性
     * 运行时 ReferenceError（'use strict' 下赋值同样抛）。历史事故：删除"刀盾二段
     * 盾击"系统时漏删 newGame 重置里的 bashQ=null，交付时沙箱绿灯（该行所在路径
     * 不可达），下一回合"跳过开始菜单直接开局"才引爆——用户救火 35 分钟。
     * 首次生成轮（起点快照基本为空）自然不参与；startFiles 为空时直接跳过。
     * 只拦确定性证据：引用计数排除属性访问（.x）、对象键（x:）与字符串/注释。
     */
    fun danglingReferenceViolations(
        files: Map<String, String>,
        startFiles: Map<String, String>
    ): List<String> {
        if (startFiles.isEmpty()) return emptyList()
        val startDecls = startFiles.values.flatMap { declaredNames(it) }.toSet()
        val currentDecls = files.values.flatMap { declaredNames(it) }.toSet()
        val disappeared = startDecls - currentDecls
        if (disappeared.isEmpty()) return emptyList()
        val violations = mutableListOf<String>()
        // 剥注释视图与行切分每文件只做一次（原实现按「消失标识符数×文件数」重复
        // 剥离——初始化式里的整段删除一次可带来几十个消失名，重复计算秒级起步）。
        val strippedLines = files.map { (path, content) ->
            path to stripCommentsAndStrings(content).lines()
        }
        for (name in disappeared) {
            if (name.length < 2) continue // 单字母名（i/j/k）误报面过大，交给沙箱
            val refRegex = Regex("""(?<![.\w$])${Regex.escape(name)}\b(?!\s*:)""")
            val refs = mutableListOf<String>()
            for ((path, lines) in strippedLines) {
                lines.forEachIndexed { idx, line ->
                    if (refRegex.containsMatchIn(line) && refs.size < 6) refs += "$path:${idx + 1}"
                }
            }
            if (refs.isNotEmpty()) {
                violations += "删除残留——标识符「$name」的声明已被删除，但 ${refs.joinToString("、")} 仍引用它" +
                    "（运行时 ReferenceError：'use strict' 下裸赋值同样抛错）。请补回声明，或把全部残留引用一并删除" +
                    "（删除系统类修改必须 grepfile 确认工作区无同名残留——历史上正是漏删一处重置赋值，让地雷存活到两个回合后才引爆）"
            }
        }
        return violations
    }
}
