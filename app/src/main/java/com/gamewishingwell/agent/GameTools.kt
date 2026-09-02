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

    private const val PATH_DESC = "工作区内的相对路径，本项目单文件固定为 index.html"

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = LIST_FILES,
            description = "列出游戏工作区的文件清单（路径、版本、大小、哈希）。用于确认当前文件状态。",
            parameters = """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSpec(
            name = READ_FILE,
            description = "读取工作区内一个文件的内容。修改前如上下文中没有最新内容，先调用它；大文件可用 start_line/end_line 只读需要的区段。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"start_line":{"type":"integer","description":"起始行号（从 1 计），可选"},"end_line":{"type":"integer","description":"结束行号（含），可选"}},"required":[]}"""
        ),
        ToolSpec(
            name = WRITE_FILE,
            description = "全量写入一个文件。仅限首次生成；文件已存在时必须显式传 overwrite=true（仅结构性重构时允许），常规修改必须改用 editfile。写入后系统自动运行基础校验并在结果中回传报告。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"content":{"type":"string","description":"完整文件内容"},"overwrite":{"type":"boolean","description":"文件已存在时是否允许全量替换，默认 false"}},"required":["content"]}"""
        ),
        ToolSpec(
            name = EDIT_FILE,
            description = "对已有文件做字符串精确替换（增量修改，保持其余内容不变）。old_string 必须与文件原文完全一致；出现多处时需提供更长上下文使其唯一，或传 replace_all=true。写入后系统自动运行基础校验并在结果中回传报告。",
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
    /** 是否改变了工作区文件内容。 */
    val mutated: Boolean,
    /** 变更后的入口文件全文；mutated=false 时为 null。 */
    val html: String?,
    /** 变更后内容的基础校验报告；mutated=false 时为 null。 */
    val report: ValidationReport?,
    /** 回填给模型的观察文本。 */
    val observation: String
)

/**
 * 工具沙箱执行器：所有路径经 [GameFileWorkspace.resolve] 校验，严格限制在游戏文件夹内。
 * 写入类工具执行后自动运行 [GameValidator]，把结构化校验结果作为观察附在返回里——
 * 校验是“观察”而非“门”：模型看到报告自行决定下一步修复方式。
 */
class GameToolExecutor(
    private val workspace: GameFileWorkspace,
    private val validate: (String) -> ValidationReport = { GameValidator.validate(it) }
) {

    suspend fun execute(call: ToolCallData): ToolOutcome {
        val args = try {
            Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return outcome(
                call, ok = false,
                observation = "参数不是合法 JSON 对象：${e.message}\n" +
                    "常见原因是整文件内容超出单次输出长度被截断。请改为分步写入：" +
                    "先 writefile 写入精简骨架（HTML+CSS+核心循环），再用 editfile 逐段追加模块。"
            )
        }
        return try {
            when (call.name) {
                GameTools.LIST_FILES -> listFiles(call)
                GameTools.READ_FILE -> readFile(call, args)
                GameTools.WRITE_FILE -> writeFile(call, args)
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
        return if (startLine == 1 && endLine == lines.size) {
            outcome(call, ok = true, observation = "文件 $path 内容如下（共 ${lines.size} 行）：\n$content")
        } else {
            val slice = lines.subList(startLine - 1, endLine).joinToString("\n")
            outcome(call, ok = true, observation = "文件 $path 第 $startLine-$endLine 行（共 ${lines.size} 行）：\n$slice")
        }
    }

    private suspend fun writeFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path") ?: GameFileWorkspaceEntryPoint.DEFAULT
        val content = args.optString("content")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 content（完整文件内容）")
        val overwrite = args.optBool("overwrite") ?: false
        val existing = workspace.read(path)
        if (existing != null && !overwrite) {
            return outcome(
                call, ok = false,
                observation = "文件 $path 已存在（v${versionOf(path)}）。常规修改请改用 editfile 做增量替换；" +
                    "只有结构性重构才允许传 overwrite=true 全量替换。"
            )
        }
        if (existing != null && existing == content) {
            return outcome(call, ok = true, observation = "写入内容与当前版本完全一致，未产生变更。")
        }
        val saved = if (existing == null) {
            workspace.writeInitial(path, content)
        } else {
            workspace.writeUpdated(path, content)
        } ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        return mutatedOutcome(call, path, content, saved.version)
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
        return mutatedOutcome(call, path, updated, saved.version, replaced = occurrences, newString = newString)
    }

    private fun versionOf(path: String): Int =
        workspace.manifest().files.firstOrNull { it.path == path }?.version ?: 0

    /** 变更类工具的成功返回：附上自动基础校验报告（观察）；editfile 额外附修改点上下文片段。 */
    private fun mutatedOutcome(
        call: ToolCallData,
        path: String,
        content: String,
        version: Int,
        replaced: Int? = null,
        newString: String? = null
    ): ToolOutcome {
        val report = validate(content)
        val head = if (replaced != null) {
            "已替换 $replaced 处并写入 $path（v$version，${content.length} 字符）。"
        } else {
            "已写入 $path（v$version，${content.length} 字符）。"
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
