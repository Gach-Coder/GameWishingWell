package com.gamewishingwell.agent

import com.gamewishingwell.data.ToolCallData
import com.gamewishingwell.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Agent Loop 的最小工具集定义（JSON Schema 参数原样字符串，由协议层嵌入请求）。 */
object GameTools {
    const val LIST_FILES = "listfiles"
    const val READ_FILE = "readfile"
    const val WRITE_FILE = "writefile"
    const val EDIT_FILE = "editfile"
    const val APPEND_FILE = "appendfile"

    private const val PATH_DESC = "工作区内的相对路径：index.html（游戏本体）或 scenarios.json（功能断言，均衡/精品档）"

    /** 切片自适应阈值：不超过该行数的文件，切片请求一律整读返回。 */
    const val WHOLE_READ_MAX_LINES = 1200

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = LIST_FILES,
            description = "列出游戏工作区的文件清单（路径、版本、大小、哈希）。用于确认当前文件状态。",
            parameters = """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSpec(
            name = READ_FILE,
            description = "读取工作区内一个文件的内容。本项目文件通常千行以内——不带行号参数直接整读即可，不要切片（切片省下的一点上下文远抵不过多花一整轮往返）。只有确需超大文件局部时才用 start_line/end_line，且需要多个区段时应在同一轮响应里并行发起多个 readfile。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"start_line":{"type":"integer","description":"起始行号（从 1 计），可选"},"end_line":{"type":"integer","description":"结束行号（含），可选"}},"required":[]}"""
        ),
        ToolSpec(
            name = WRITE_FILE,
            description = "整量写入文件（内容为完整文件）：新建文件或彻底重写时使用，可写 index.html（游戏本体）或 scenarios.json（功能断言）。修改已有文件通常优先 editfile（改动最小、更省更稳），appendfile 适合追加新代码段——修改回合请遵守【修改范围契约】（只改用户点名的特征，未点名内容保持原样）。写入后系统自动检查产品契约/断言格式并在结果中回传。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"content":{"type":"string","description":"完整文件内容"}},"required":["content"]}"""
        ),
        ToolSpec(
            name = APPEND_FILE,
            description = "在文件末尾追加一段内容。适合新增代码段/模块；追加后系统自动检查产品契约并在结果中回传。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"content":{"type":"string","description":"要追加到文件末尾的内容"}},"required":["content"]}"""
        ),
        ToolSpec(
            name = EDIT_FILE,
            description = "对已有文件做字符串精确替换（增量修改，保持其余内容不变）。old_string 必须与文件原文完全一致；出现多处时需提供更长上下文使其唯一，或传 replace_all=true。写入后系统自动检查产品契约并在结果中回传。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"old_string":{"type":"string","description":"要替换的原文片段，必须逐字符匹配"},"new_string":{"type":"string","description":"替换后的内容"},"replace_all":{"type":"boolean","description":"old_string 多处出现时是否全部替换，默认 false"}},"required":["old_string","new_string"]}"""
        )
    )
}

/** 一次工具执行的完整结果：观察文本回填给模型，mutated/html 供 Agent 更新会话状态。 */
data class ToolOutcome(
    val callId: String,
    val name: String,
    /** 工具调用本身执行成功（参数合法且动作完成）。 */
    val ok: Boolean,
    /** 是否改变了入口文件（index.html）内容——发布 currentHtml 与读结果过期的依据。 */
    val mutated: Boolean,
    /** 变更后的入口文件全文；mutated=false 时为 null。 */
    val html: String?,
    /** 变更后入口内容的基础校验报告；mutated=false 时为 null。 */
    val report: ValidationReport?,
    /** 回填给模型的观察文本。 */
    val observation: String,
    /** 是否产生了任何工作区写入（含 scenarios.json 等非入口文件）：驱动空转计数。 */
    val workspaceTouched: Boolean = mutated
)

/**
 * 工具沙箱执行器：所有路径经 [GameFileWorkspace.resolve] 校验，严格限制在游戏文件夹内。
 * 写入类工具执行后自动运行 [GameValidator]，把结构化校验结果作为观察附在返回里——
 * 校验是“观察”而非“门”：模型看到报告自行决定下一步修复方式。
 * 修改范围契约只在提示词层约束（SCOPE_FENCE_RULE），执行层不做检查或撤回。
 */
class GameToolExecutor(
    private val workspace: GameFileWorkspace,
    private val validate: (String) -> ValidationReport = { GameValidator.validate(it) }
) {

    /**
     * 整读去重（同回合）：path → 上次完整返回时的内容哈希。文件未变更时重复整读
     * 只回简短指针（内容在上下文里，重发一份 30KB 副本纯属膨胀——实测修改轮开局
     * 连续 5 轮整读同一文件，后续每轮都背着 5 份全文）。任何成功写入后清除对应
     * 条目：改动后重读是合法的（历史里的旧读取结果已被折叠为过期提示）。
     * 不检查、不限制行为——只是不重复发送模型上下文里已有的同一份内容。
     */
    private val fullReadMarks = mutableMapOf<String, String>()

    suspend fun execute(call: ToolCallData): ToolOutcome {
        val args = try {
            Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            val truncated = e.message?.contains("EOF") == true
            return outcome(
                call, ok = false,
                observation = "参数不是合法 JSON 对象：${e.message}" +
                    if (truncated) {
                        "（输出在工具参数中途被截断——内容过长）。请改用 editfile 做小范围/整段替换，" +
                            "或用 appendfile 分段追加，不要原样重发整份内容。"
                    } else {
                        ""
                    }
            )
        }
        return try {
            when (call.name) {
                GameTools.LIST_FILES -> listFiles(call)
                GameTools.READ_FILE -> readFile(call, args)
                GameTools.WRITE_FILE -> writeFile(call, args)
                GameTools.APPEND_FILE -> appendFile(call, args)
                GameTools.EDIT_FILE -> editFile(call, args)
                else -> outcome(call, ok = false, observation = "未知工具：${call.name}（可用：${GameTools.specs().joinToString { it.name }}）")
            }
        } catch (e: Exception) {
            outcome(call, ok = false, observation = "工具执行异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun listFiles(call: ToolCallData): ToolOutcome {
        val manifest = workspace.manifest()
        val listing = if (manifest.files.isEmpty()) {
            "工作区为空（还没有任何文件；首次生成请用 writefile 写入 index.html）"
        } else {
            manifest.files.joinToString("\n") { f ->
                "- ${f.path}：v${f.version}，${f.bytes} 字节，sha256=${f.sha256.take(12)}"
            }
        }
        return outcome(call, ok = true, observation = "工作区文件清单（入口 ${manifest.pointer}）：\n$listing")
    }

    private suspend fun readFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path") ?: GameFileWorkspaceEntryPoint.DEFAULT
        val content = workspace.read(path)
            ?: return outcome(call, ok = false, observation = "文件不存在或不可读：$path（可用 listfiles 查看清单）")
        val lines = content.lines()
        val startLine = (args.optInt("start_line") ?: 1).coerceIn(1, lines.size)
        val endLine = (args.optInt("end_line") ?: lines.size).coerceIn(startLine, lines.size)
        // 切片自适应：小文件（千行级）的切片请求直接整读——实测修复轮 60~70% 的
        // 轮次耗在切片重读上（每片一次 LLM 往返），整读一次拿到全貌反而更省；
        // 真正的大文件（精品档长文）保留切片能力。
        val sliceNarrowed = startLine > 1 || endLine < lines.size
        val smallFileOverride = sliceNarrowed && lines.size <= GameTools.WHOLE_READ_MAX_LINES
        if (!sliceNarrowed || smallFileOverride) {
            // 整读去重：文件自本回合上次整读后未变更时只回指针——重复整发全文
            // 会让上下文堆多份 30KB 副本（后续每轮变慢变贵，模型也更容易跑偏）。
            val hash = GameFileWorkspace.sha256(content)
            if (fullReadMarks[path] == hash) {
                return outcome(
                    call, ok = true,
                    observation = "文件 $path（${lines.size} 行）自本回合上次完整读取后未发生变更，" +
                        "完整内容就在上文最近一次 readfile 结果里——请直接基于它用 editfile 修改，不要重复整读；" +
                        "确需局部核对可用 start_line/end_line 切片。"
                )
            }
            fullReadMarks[path] = hash
            return if (smallFileOverride) {
                outcome(
                    call, ok = true,
                    observation = "文件 $path 共 ${lines.size} 行（不大，已直接返回全文；后续修改请基于此内容，无需再切片读取）：\n$content"
                )
            } else {
                outcome(call, ok = true, observation = "文件 $path 内容如下（共 ${lines.size} 行）：\n$content")
            }
        }
        val slice = lines.subList(startLine - 1, endLine).joinToString("\n")
        return outcome(call, ok = true, observation = "文件 $path 第 $startLine-$endLine 行（共 ${lines.size} 行）：\n$slice")
    }

    private suspend fun writeFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path") ?: GameFileWorkspaceEntryPoint.DEFAULT
        val content = args.optString("content")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 content（完整文件内容）")
        val existing = workspace.read(path)
        if (existing != null && existing == content) {
            return outcome(call, ok = true, observation = "写入内容与当前版本完全一致，未产生变更。")
        }
        // 一般 Agent 惯例：Write 新建或整量覆盖均可（版本化写入保底可回滚），
        // 用 Write 还是 Edit 由模型按任务自行权衡，执行层不做策略门禁——
        // 修改范围契约只在提示词层约束（SCOPE_FENCE_RULE）。
        val saved = if (existing == null) {
            workspace.writeInitial(path, content)
        } else {
            workspace.writeUpdated(path, content)
        } ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        return mutatedOutcome(call, path, content, saved.version)
    }

    /** 在文件末尾追加内容。 */
    private suspend fun appendFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path") ?: GameFileWorkspaceEntryPoint.DEFAULT
        val content = args.optString("content")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 content（要追加的内容）")
        if (content.isBlank()) {
            return outcome(call, ok = false, observation = "追加内容为空。")
        }
        val existing = workspace.read(path)
            ?: return outcome(call, ok = false, observation = "文件不存在：$path。请先用 writefile 创建文件。")
        val updated = if (existing.endsWith("\n") || existing.isEmpty()) existing + content else existing + "\n" + content
        val saved = workspace.writeUpdated(path, updated)
            ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        return mutatedOutcome(call, path, updated, saved.version, appended = content.lines().size)
    }

    private suspend fun editFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path") ?: GameFileWorkspaceEntryPoint.DEFAULT
        val oldString = args.optString("old_string")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 old_string")
        val newString = args.optString("new_string")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 new_string")
        if (oldString.isEmpty()) {
            return outcome(call, ok = false, observation = "old_string 不能为空字符串。")
        }
        val content = workspace.read(path)
            ?: return outcome(call, ok = false, observation = "文件不存在：$path（首次生成请用 writefile）")
        val occurrences = countOccurrences(content, oldString)
        when {
            occurrences == 0 -> return outcome(
                call, ok = false,
                observation = "old_string 在 $path 中未找到（必须与文件原文逐字符一致，含缩进与空行）。" +
                    "如上下文中的代码可能已过期，请先 readfile 获取最新内容。"
            )
            occurrences > 1 && args.optBool("replace_all") != true -> return outcome(
                call, ok = false,
                observation = "old_string 出现 $occurrences 处，不唯一。请扩大上下文使其唯一，或确认全部替换时传 replace_all=true。"
            )
        }
        val updated = if (occurrences == 1) {
            content.replaceFirst(oldString, newString)
        } else {
            content.replace(oldString, newString)
        }
        if (updated == content) {
            return outcome(call, ok = true, observation = "替换前后内容一致，未产生变更。")
        }
        val saved = workspace.writeUpdated(path, updated)
            ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        return mutatedOutcome(call, path, updated, saved.version, replaced = occurrences, newString = newString)
    }

    private fun versionOf(path: String): Int =
        workspace.manifest().files.firstOrNull { it.path == path }?.version ?: 0

    /**
     * 变更类工具的成功返回：附上自动契约检查（观察）；editfile 额外附修改点上下文片段。
     * 按路径分流：入口文件（index.html）走 HTML 契约校验并作为 currentHtml 发布依据；
     * scenarios.json（功能断言）解析校验后只作工作区触碰，不发布、不跑 HTML 校验。
     */
    private fun mutatedOutcome(
        call: ToolCallData,
        path: String,
        content: String,
        version: Int,
        replaced: Int? = null,
        newString: String? = null,
        appended: Int? = null
    ): ToolOutcome {
        if (path == GameScenarios.FILE) {
            val (checkOk, checkText) = GameScenarios.checkObservation(content)
            val head = when {
                appended != null -> "已追加 $appended 行并写入 $path（v$version）。"
                replaced != null -> "已替换 $replaced 处并写入 $path（v$version）。"
                else -> "已写入 $path（v$version，${content.length} 字符）。"
            }
            return ToolOutcome(
                callId = call.id,
                name = call.name,
                ok = checkOk,
                mutated = false,
                html = null,
                report = null,
                observation = head + "\n" + checkText,
                workspaceTouched = true
            )
        }
        val report = validate(content)
        val head = when {
            appended != null -> "已追加 $appended 行并写入 $path（v$version，现共 ${content.lines().size} 行）。"
            replaced != null -> "已替换 $replaced 处并写入 $path（v$version，${content.length} 字符）。"
            else -> "已写入 $path（v$version，${content.length} 字符）。"
        }
        val contextBlock = if (newString != null) editContextSnippet(content, newString) else null
        val observation = head + (contextBlock ?: "") + formatValidation(report)
        return ToolOutcome(
            callId = call.id,
            name = call.name,
            ok = true,
            mutated = true,
            html = content,
            report = report,
            observation = observation
        )
    }

    /**
     * editfile 成功后返回替换点附近上下文（前后各 2 行，上限 30 行）：模型据此
     * 掌握最新文件状态、继续后续修改，无需每改一处就重读整个文件——这是轮次
     * 膨胀的主要来源之一。片段为文件原文（无行号前缀），可直接用于下一次
     * old_string。
     */
    private fun editContextSnippet(content: String, newString: String): String? {
        if (newString.isEmpty() || !content.contains(newString)) return null
        val lines = content.lines()
        val startIdx = content.indexOf(newString)
        val startLine = content.substring(0, startIdx).count { it == '\n' }                    // 0 基
        val endLine = content.substring(0, startIdx + newString.length).count { it == '\n' }   // 0 基，含
        val from = (startLine - 2).coerceAtLeast(0)
        val to = (endLine + 2).coerceAtMost(lines.size - 1)
        if (to - from + 1 > 30) return null
        val snippet = lines.subList(from, to + 1).joinToString("\n")
        return "\n修改点上下文（第 ${from + 1}-${to + 1} 行，文件原文）：\n$snippet\n"
    }

    private fun outcome(call: ToolCallData, ok: Boolean, observation: String): ToolOutcome = ToolOutcome(
        callId = call.id,
        name = call.name,
        ok = ok,
        mutated = false,
        html = null,
        report = null,
        observation = observation
    )

    private fun formatValidation(report: ValidationReport): String = buildString {
        if (report.hasErrors) {
            append("\n基础校验未通过（必须修复后再继续）：\n")
            report.errors.take(10).forEach { issue ->
                append("- [${issue.category}] ${issue.file}:${issue.line} ${issue.message}\n")
            }
            if (report.errors.size > 10) append("……共 ${report.errors.size} 个 error\n")
        } else {
            append("\n基础校验通过（0 error")
            if (report.warnings.isNotEmpty()) append("，${report.warnings.size} warning")
            append("）。")
        }
    }

    private fun countOccurrences(content: String, token: String): Int {
        var count = 0
        var index = content.indexOf(token)
        while (index >= 0) {
            count++
            index = content.indexOf(token, index + token.length)
        }
        return count
    }

    private fun JsonObject.optString(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.optBool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun JsonObject.optInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
}

/** 入口文件常量单独存放，避免执行器与工作区实现互相依赖。 */
object GameFileWorkspaceEntryPoint {
    const val DEFAULT = "index.html"
}
