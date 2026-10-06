package com.gamewishingwell.agent

/**
 * 工作区文件地图（确定性符号索引，零 LLM）：
 * 按当前工作区文件实时生成「符号 → 行号」目录注入回合上下文——把"定位代码在哪"
 * （某函数/某模块/某元素 id）从模型侧的多轮 readfile 切片通读压缩为开局即可见。
 * 实测反例：7118 行单文件的"删一个弹窗"回合，模型 11 轮纯读侦查（每轮一次
 * LLM 往返、约 4.5 分钟）才找到 boot 分支；而地图 + grepfile 两步内可达。
 *
 * 与跨停止缓存方案的对比（同为"侦查记忆"）：运行时从文件现算——永远新鲜、无
 * 陈旧行号误导、无需持久化字段（丢叙述、留事实的原则下，事实最好直接从事实源
 * 派生）。小工作区（无超过 [MIN_LINES_FOR_MAP] 行的文件）不注入——整读一遍更省。
 */
object WorkspaceMap {

    /** 任一 js/入口文件超过该行数才注入地图（小文件整读即可，地图是噪音）。 */
    const val MIN_LINES_FOR_MAP = 400

    /** 每文件最多列出的符号数（更多符号由 grepfile 按名定位——地图是索引不是全文）。 */
    private const val MAX_SYMBOLS_PER_FILE = 50

    private val functionRegex = Regex("""\bfunction\s+([A-Za-z_$][\w$]*)\s*\(""")
    private val windowModuleRegex = Regex("""\bwindow\.([A-Za-z_$][\w$]*)\s*=""")
    private val htmlIdRegex = Regex("""\bid\s*=\s*["']([^"']+)["']""")

    /** files：工作区清单（相对路径 → 内容）；无可绘内容（文件都小）返回 null。 */
    fun render(files: Map<String, String>): String? {
        val candidates = files.filterKeys { it.endsWith(".js") || it == GameFileWorkspaceEntryPoint.DEFAULT }
            .filterValues { it.count { c -> c == '\n' } + 1 >= MIN_LINES_FOR_MAP }
        if (candidates.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("【工作区文件地图（确定性生成的符号→行号索引；行号对应当前版本，文件被修改后以 grepfile 实查为准）】\n")
        var totalSymbols = 0
        for ((path, content) in candidates.entries.sortedBy { it.key }) {
            val lines = content.lines()
            val symbols = LinkedHashMap<String, Int>()
            for (idx in lines.indices) {
                if (symbols.size >= MAX_SYMBOLS_PER_FILE) break
                val line = lines[idx]
                if (path == GameFileWorkspaceEntryPoint.DEFAULT) {
                    htmlIdRegex.findAll(line).forEach { m ->
                        if (symbols.size < MAX_SYMBOLS_PER_FILE && m.groupValues[1] !in symbols) {
                            symbols["#" + m.groupValues[1]] = idx + 1
                        }
                    }
                } else {
                    for (m in functionRegex.findAll(line)) {
                        if (symbols.size < MAX_SYMBOLS_PER_FILE && m.groupValues[1] !in symbols) {
                            symbols[m.groupValues[1] + "()"] = idx + 1
                        }
                    }
                    windowModuleRegex.findAll(line).forEach { m ->
                        if (symbols.size < MAX_SYMBOLS_PER_FILE && ("window." + m.groupValues[1]) !in symbols) {
                            symbols["window." + m.groupValues[1]] = idx + 1
                        }
                    }
                }
            }
            if (symbols.isEmpty()) continue
            totalSymbols += symbols.size
            sb.append(path).append("（").append(lines.size).append(" 行）：")
            sb.append(symbols.entries.joinToString("、") { "${it.key}@${it.value}" })
            if (symbols.size >= MAX_SYMBOLS_PER_FILE) sb.append("（已截断，未尽符号用 grepfile 按名定位）")
            sb.append('\n')
        }
        if (totalSymbols == 0) return null
        sb.append("定位未列出的目标：grepfile 按符号名/文案搜索，再 readfile 对应行号区段——不要为定位而通读大文件。")
        return sb.toString()
    }
}
