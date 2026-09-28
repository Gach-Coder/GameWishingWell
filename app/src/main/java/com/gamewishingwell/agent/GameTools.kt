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

    private const val PATH_DESC =
        "工作区内的相对路径：index.html（唯一入口）、js/*.js 或 css/*.css（多文件组织，" +
            "由 index.html 相对路径引用，平台运行前自动合并）或 scenarios.json（功能断言，均衡/精品档）"

    /** 切片自适应阈值：不超过该行数的文件，切片请求一律整读返回。 */
    const val WHOLE_READ_MAX_LINES = 1200

    /**
     * 未授权整体重写的行相似度阈值（Dice）：低于判重写。灾难重写的相似度≈0
     * （全新代码），常规功能修改 >0.8（绝大部分行保留），0.35 居中留足余量。
     */
    const val REWRITE_SIMILARITY_MIN = 0.35

    /** 全量重写咨询信号阈值：一个回合累计整体覆盖既有文件达到该次数即提示（非硬门）。 */
    const val FULL_REWRITE_ADVISE_AT = 3

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
            description = "写入文件（内容为完整文件）：新建 index.html、js/*.js、css/*.css、scenarios.json 等，或修改后整体落盘（JSON 等整体编辑更自然时可用）。注意：已有文件与回合起点行相似度过低的重写会被致命拒绝（未经用户授权的整体重写，多次小替换逐步达成同样被拦）——常规修改建议 editfile（更省更稳）。写入后系统自动检查产品契约/断言格式并在结果中回传。",
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
    val workspaceTouched: Boolean = mutated,
    /** 本次读/写动作的实际文件路径（读结果按路径精确过期的依据；错误返回可为 null）。 */
    val path: String? = null
)

/**
 * 工具沙箱执行器：所有路径经 [GameFileWorkspace.resolve] 校验，严格限制在游戏文件夹内。
 * 写入类工具执行后自动运行 [GameValidator]，把结构化校验结果作为观察附在返回里——
 * 校验是“观察”而非“门”：模型看到报告自行决定下一步修复方式。
 * 修改范围契约只在提示词层约束（SCOPE_FENCE_RULE），执行层不做检查或撤回。
 */
class GameToolExecutor(
    private val workspace: GameFileWorkspace,
    private val validate: (String) -> ValidationReport = { GameValidator.validate(it) },
    /**
     * 写入护栏——全部在【状态层】度量（回合起点快照 vs 写入结果），与用哪个工具、
     * 分几步达成无关（操作层的语法代理必然冤杀合法编辑又漏过多步掏空，已废弃）：
     * - [turnStartSnapshot] 回合起点的工作区文件快照（path→内容）。任何写入使该文件
     *   与起点版本的行相似度跌破 [REWRITE_SIMILARITY_MIN] 且未授权 = 未授权整体重写，
     *   致命拒绝（一次 writefile 达成或多次 editfile 逐步掏空等价）；新文件不受限；
     * - [allowFullRewrite] 仅当用户指令明确授权重做/重构时为 true，解锁整体重写；
     * - [protectedScenarioIds] 回合起点的回归断言 id 快照：非重做回合断言只增不减
     *   （id 是断言身份；实测模型在击杀断言连续失败后直接删除断言过关）。
     */
    private val allowFullRewrite: Boolean = false,
    private val turnStartSnapshot: Map<String, String> = emptyMap(),
    private val protectedScenarioIds: Set<String>? = null,
    /** 3D 游戏：scenarios 必含与 s0 相对的视角旋转断言（防"改弱断言绕过视角验证"）。 */
    private val requireViewAssertion: Boolean = false,
    /**
     * 参数外置契约维护——参数键删除/改名的授权判定（键名 → 是否放行）：用户指令
     * 点名该键或授权重做时 true。修复/迭代轮里 GAME_CONFIG 参数键只增不减
     * （悬空引用与键守恒见 [configContractViolation]），与断言 id 守恒同语义。
     */
    private val configKeyDeletionAuthorized: (String) -> Boolean = { false },
    /**
     * 修复轮建议范围（软约束）：报错签名涉及的工作区文件集合。非空时，写入范围外
     * 文件在观察里附越界提醒（不拒绝——跨文件修复合法，实测 touch 事件类报错
     * 的正确修复同时涉及 ui 与 main 两个文件；这里只把"顺带改无关系统"变成
     * 显式可见的事实，配合写入铁律与最小修改契约收敛爆炸半径）。
     */
    private val advisoryScope: Set<String> = emptySet()
) {

    /**
     * 整读去重（同回合）：path → 上次完整返回时的内容哈希。文件未变更时重复整读
     * 只回简短指针（内容在上下文里，重发一份 30KB 副本纯属膨胀——实测修改轮开局
     * 连续 5 轮整读同一文件，后续每轮都背着 5 份全文）。任何成功写入后清除对应
     * 条目：改动后重读是合法的（历史里的旧读取结果已被折叠为过期提示）。
     * 不检查、不限制行为——只是不重复发送模型上下文里已有的同一份内容。
     */
    private val fullReadMarks = mutableMapOf<String, String>()

    /** 最近一次 listfiles 的清单文本：无写入时重复请求只回指针（去重同 fullReadMarks 模式）。 */
    private var lastListing: String? = null

    /** 本回合全量覆盖既有文件的累计次数与一次性咨询标记（执行器生命周期=一个回合）。 */
    private var fullOverwriteCount = 0
    private var rewriteAdvised = false

    /**
     * 本回合已固化的断言套件强度量：id 集合 + 全套件总步骤数（覆盖面代理指标）。
     * 修改/迭代回合从起点快照播种；生成回合在首次成功写入非空套件时固化——
     * 此后套件只增不减（历史事故：沙箱合成触控点不动选人卡，模型把 5 步选人
     * 断言改写成 1 步"开局即入战"绕过路径，弱化后的回归套件为后续修复放行）。
     */
    private var committedSuiteIds: Set<String>? = null
    private var committedSuiteSteps: Int = 0

    suspend fun execute(call: ToolCallData): ToolOutcome {
        val rawArgs = call.arguments.ifBlank { "{}" }
        val args = try {
            Json.parseToJsonElement(rawArgs).jsonObject
        } catch (strictError: Exception) {
            // 宽容修复（大文件参数高频损坏，2026-09-07 真机实测）：模型/网关流式输出
            // 大 writefile 参数时，常把代码里的换行/制表符按【原始字符】塞进 JSON 字符串
            // （未转义为 \n/\t）——严格解析必失败，内容本身完好。2.5D 重型游戏（MOBA/
            // 骑士团类）单文件代码量大，参数尺寸随内容增长，中招概率陡增：每个损坏参数
            // 浪费一整轮 LLM 往返（实测 14~34KB tokens），模型反复重试直至熔断失败。
            // 修复只转义字符串字面量内的控制字符，不改结构；修不了（如流被截断）按原错误反馈。
            val rescued = escapeRawControlCharsInStrings(rawArgs)
                ?.let { fixed -> runCatching { Json.parseToJsonElement(fixed).jsonObject }.getOrNull() }
            rescued ?: return argsErrorOutcome(call, strictError)
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

    private fun argsErrorOutcome(call: ToolCallData, e: Exception): ToolOutcome {
        val truncated = e.message?.contains("EOF") == true
        return outcome(
            call, ok = false,
            observation = "参数不是合法 JSON 对象：${e.message}" +
                if (truncated) {
                    "（输出在工具参数中途被截断——单次内容过长，实测多见于免费/长输出模型）。" +
                        "请改用 editfile 做小范围/整段替换，或用 appendfile 分段追加，" +
                        "或将大文件拆成多个小文件（如 js/main.js、js/systems.js）分次 writefile，" +
                        "不要原样重发整份内容。"
                } else {
                    ""
                }
        )
    }

    private suspend fun listFiles(call: ToolCallData): ToolOutcome {
        val manifest = workspace.manifest()
        val listing = if (manifest.files.isEmpty()) {
            "工作区为空（还没有任何文件）；首次生成请按文件计划并行 writefile：" +
                "先写被引用的 js/css 等辅助文件，最后写入口 index.html（单文件最简形态可直出 index.html）"
        } else {
            manifest.files.joinToString("\n") { f ->
                "- ${f.path}：${f.bytes} 字节，sha256=${f.sha256.take(12)}"
            }
        }
        // 清单去重：无写入时重复 listfiles 只回指针（实测死循环回合里 31 个 listfiles
        // 轮全是状态探测——信息在上文，重发清单纯属上下文膨胀）；任何成功写入即失效。
        if (listing == lastListing) {
            return outcome(
                call, ok = true,
                observation = "工作区清单自上次 listfiles 后未变化（无写入发生，文件清单见上文最近一次）；" +
                    "如需文件内容请 readfile。"
            )
        }
        lastListing = listing
        return outcome(call, ok = true, observation = "工作区文件清单（入口 index.html）：\n$listing")
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
                        "确需局部核对可用 start_line/end_line 切片。",
                    path = path
                )
            }
            fullReadMarks[path] = hash
            return if (smallFileOverride) {
                outcome(
                    call, ok = true,
                    observation = "文件 $path 共 ${lines.size} 行（不大，已直接返回全文；后续修改请基于此内容，无需再切片读取）：\n$content",
                    path = path
                )
            } else {
                outcome(call, ok = true, observation = "文件 $path 内容如下（共 ${lines.size} 行）：\n$content", path = path)
            }
        }
        val slice = lines.subList(startLine - 1, endLine).joinToString("\n")
        return outcome(call, ok = true, observation = "文件 $path 第 $startLine-$endLine 行（共 ${lines.size} 行）：\n$slice", path = path)
    }

    private suspend fun writeFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path") ?: GameFileWorkspaceEntryPoint.DEFAULT
        val content = args.optString("content")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 content（完整文件内容）")
        val existing = workspace.read(path)
        if (existing != null && existing == content) {
            return outcome(call, ok = true, observation = "写入内容与当前版本完全一致，未产生变更。")
        }
        rewriteViolation(path, content)?.let { return outcome(call, ok = false, it) }
        moduleContractViolation(path, content)?.let { return outcome(call, ok = false, it) }
        configContractViolation(path, content)?.let { return outcome(call, ok = false, it) }
        if (path == GameScenarios.FILE) {
            scenarioContinuityViolation(content)?.let { return outcome(call, ok = false, it) }
        }
        val saved = if (existing == null) {
            workspace.writeInitial(path, content)
        } else {
            workspace.writeUpdated(path, content)
        } ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        var result = mutatedOutcome(call, path, content)
        // 全量重写咨询信号（非硬门，与重写护栏互补）：护栏按"单次写入 vs 回合起点"
        // 度量，逐步小改永远不会触发；跨整个回合的累计重写面在这里提示——执行器
        // 生命周期=一个回合，计数即回合累计。只提示一次，避免每轮重复噪音。
        if (existing != null) {
            fullOverwriteCount++
            if (fullOverwriteCount >= GameTools.FULL_REWRITE_ADVISE_AT && !rewriteAdvised) {
                rewriteAdvised = true
                result = result.copy(
                    observation = result.observation +
                        "\n⚠ 全量重写提示：本回合已第 $fullOverwriteCount 次整体覆盖既有文件——" +
                        "重写面越大，既有功能回归风险越高（丢导出成员/丢初始化逻辑都在这类重写里发生过）。" +
                        "如非用户要求整体重做，请改用 editfile 做局部精确修改。"
                )
            }
        }
        return result
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
        rewriteViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        if (path == GameScenarios.FILE) {
            scenarioContinuityViolation(updated)?.let { return outcome(call, ok = false, it) }
        }
        val saved = workspace.writeUpdated(path, updated)
            ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        return mutatedOutcome(call, path, updated, appended = content.lines().size)
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
        rewriteViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        moduleContractViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        configContractViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        if (path == GameScenarios.FILE) {
            scenarioContinuityViolation(updated)?.let { return outcome(call, ok = false, it) }
        }
        val saved = workspace.writeUpdated(path, updated)
            ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        return mutatedOutcome(call, path, updated, replaced = occurrences, newString = newString)
    }

    /**
     * 跨文件模块导出契约预检（写盘前执行，非 null=违规文本，调用方直接拒绝且不落盘）：
     * 以【写入后】的工作区全量视图跑 [GameStaticCheck.moduleExportViolations]——
     * `X.fn(...)` 调用存在而 fn 不在 X 模块导出表 = 重写丢导出成员，游戏一启动
     * 即抛 TypeError（历史事故：touch.js 重写丢 setUI，main.js 的 Input.setUI 崩溃）。
     * 只在写 js 或入口时判定；css/scenarios 无模块语义。
     */
    private fun moduleContractViolation(path: String, newContent: String): String? {
        if (!path.endsWith(".js") && path != GameFileWorkspaceEntryPoint.DEFAULT) return null
        return try {
            val files = HashMap<String, String>()
            var readable = true
            workspace.manifest().files.forEach { f ->
                if (f.path == path) {
                    files[f.path] = newContent
                } else {
                    val c = workspace.read(f.path)
                    if (c == null) readable = false else files[f.path] = c
                }
            }
            files[path] = newContent // manifest 尚无的新文件也纳入写入后视图
            if (!readable || files.size <= 1) return null // 单文件/空工作区无跨文件契约
            val violations = GameStaticCheck.moduleExportViolations(files)
            if (violations.isEmpty()) return null
            "致命错误：跨文件模块导出契约被破坏——" +
                violations.joinToString("；") { v ->
                    "${v.module}.${v.member}（被 ${v.callers.distinct().joinToString("、")} 调用）不在其模块导出表中"
                } +
                "。重写模块文件时必须保留既有导出成员（return {...} 对象不得丢项，" +
                "入口调用的每个 X.fn 都必须能解析）；请补回缺失成员后重写。文件未被改动。"
        } catch (_: Exception) {
            null // 预检自身异常不拦写入（宁可漏报不误伤）
        }
    }

    /**
     * 参数外置契约维护预检（写盘前执行，非 null=违规文本，调用方直接拒绝且不落盘）：
     * 回合起点已定义 GAME_CONFIG 的游戏（修复/迭代轮）执行三类检查——悬空引用
     * （引用了未定义的键）、参数键守恒（删除/改名需 [configKeyDeletionAuthorized]
     * 点名授权）、值类型纯 JSON。首次生成轮不参与（并行写入的正常时序不拦，
     * 结构存在性由验收校验把关）。与模块导出契约同触发条件（写 js/入口时）。
     */
    private fun configContractViolation(path: String, newContent: String): String? {
        if (!path.endsWith(".js") && path != GameFileWorkspaceEntryPoint.DEFAULT) return null
        return try {
            val files = HashMap<String, String>()
            var readable = true
            workspace.manifest().files.forEach { f ->
                if (f.path == path) {
                    files[f.path] = newContent
                } else {
                    val c = workspace.read(f.path)
                    if (c == null) readable = false else files[f.path] = c
                }
            }
            files[path] = newContent // manifest 尚无的新文件也纳入写入后视图
            if (!readable) return null
            val violations = GameStaticCheck.configContractViolations(
                files, turnStartSnapshot, configKeyDeletionAuthorized
            )
            if (violations.isEmpty()) null
            else "致命错误：" + violations.joinToString("；") + "。文件未被改动。"
        } catch (_: Exception) {
            null // 预检自身异常不拦写入（宁可漏报不误伤）
        }
    }

    /**
     * 未授权整体重写检测（状态层，写盘前执行，非 null=违规文本，调用方直接拒绝且不落盘）：
     * 写入结果与【回合起点】版本的行相似度跌破阈值即判重写——与写入机制无关
     * （writefile 一次达成 / 多次 editfile 逐步掏空 / appendfile 顶飞，同一把尺子），
     * 新文件不受限，用户授权重做/重构放行。极小文件（起点 <8 个非空行）不判，噪声太大。
     */
    private fun rewriteViolation(path: String, newContent: String): String? {
        if (allowFullRewrite) return null
        val origin = turnStartSnapshot[path] ?: return null
        val originLines = origin.lines().filter { it.isNotBlank() }
        if (originLines.size < 8) return null
        val sim = lineSimilarity(originLines, newContent)
        if (sim >= GameTools.REWRITE_SIMILARITY_MIN) return null
        return "致命错误：$path 相对回合起点的行相似度仅 ${(sim * 100).toInt()}" +
            "（低于 ${(GameTools.REWRITE_SIMILARITY_MIN * 100).toInt()}%），构成未授权的整体重写" +
            "（无论 writefile 一次达成还是多次 editfile 逐步替换，执行层按回合起点比对，逐步掏空同样会被拦）。" +
            "请在既有代码上做局部精确修改；用户明确要求重做/重构（或授权精简）时才会放行整体重写。文件未被改动。"
    }

    /** 非空行的 Dice 相似度（多重集交集 ×2 / 两侧行数和）：对重排稳健，实现无依赖。 */
    private fun lineSimilarity(originLines: List<String>, newContent: String): Double {
        if (originLines.isEmpty()) return 1.0
        val newLines = newContent.lines().filter { it.isNotBlank() }
        if (newLines.isEmpty()) return 0.0
        val pool = newLines.toMutableList()
        var common = 0
        for (l in originLines) {
            if (pool.remove(l)) common++
        }
        return 2.0 * common / (originLines.size + newLines.size)
    }

    /**
     * 回归套件连续性预检（写盘前执行，非 null=违规文本，调用方直接拒绝且不落盘）：
     * 断言只增不减且覆盖面守恒——id 是断言身份，修内容保 id、新增追加、增强步骤均可；
     * 删除/合并/缩短需用户授权重做。守恒在生成轮同样生效（首次非空套件写入即固化，
     * 历史缺陷：只在修改轮保护，生成轮内模型可自由弱化断言——实测把 5 步选人断言
     * 缩成 1 步"开局即入战"，沙箱再也测不到选人路径，交付后选人界面被跳过）。
     */
    private fun scenarioContinuityViolation(content: String): String? {
        if (allowFullRewrite) return null
        val parsed = GameScenarios.parse(content)
        // 解析失败或空套件：交给 checkObservation 报格式问题（"断言被删除"是误报）
        if (parsed.isEmpty()) return null
        val newIds = parsed.map { it.id }.toSet()
        val newSteps = parsed.sumOf { it.steps.size }
        // 播种：修改回合用起点快照（id 集与步骤数各自独立可用——只传 protectedScenarioIds
        // 时步骤数按 0 处理，id 守恒照常生效）；生成回合用首次写入的非空套件
        if (committedSuiteIds == null && protectedScenarioIds != null) {
            committedSuiteIds = protectedScenarioIds
            committedSuiteSteps = turnStartSnapshot[GameScenarios.FILE]
                ?.let { raw -> GameScenarios.parse(raw).sumOf { it.steps.size } } ?: 0
        }
        val committedIds = committedSuiteIds
        if (committedIds != null) {
            val lostIds = committedIds - newIds
            if (lostIds.isNotEmpty()) {
                return "致命错误：回归断言只增不减（执行层强制，生成轮内首次固化后同样生效）——" +
                    "以下已存在断言被删除：${lostIds.joinToString("、")}。" +
                    "修断言内容请保留 id 原位改写（对照失败回报的字段轨迹修正 expect/steps）；" +
                    "新增回归断言直接追加；删除或合并条目需用户明确授权（重做游戏/精简断言）。文件未被改动。"
            }
            if (newSteps < committedSuiteSteps) {
                return "致命错误：断言覆盖面守恒——套件总步骤数从 $committedSuiteSteps 缩减为 $newSteps。" +
                    "缩短步骤=削弱交互覆盖（历史事故：5 步选人断言被缩成 1 步直通战斗，选人界面从此无回归保护、" +
                    "后续修复重写触摸层时无声破坏）。允许：保持 id 增强或改写 expect、增加步骤、追加断言；" +
                    "对照失败回报调整 steps 节奏（改坐标/删冗余等待）不得使总步骤数低于已固化值；" +
                    "确需精简套件需用户明确授权。文件未被改动。"
            }
        }
        // 固化本回合见过的最强套件（id 集取并集、步骤数取最大——后续写入以此为基准只增不减）
        committedSuiteIds = (committedIds ?: emptySet()) union newIds
        if (newSteps > committedSuiteSteps) committedSuiteSteps = newSteps
        return null
    }

    /**
     * 变更类工具的成功返回：附上自动契约检查（观察）；editfile 额外附修改点上下文片段。
     * 按路径三分流：
     * - 入口 index.html：跑 HTML 契约校验（本地引用存在性/外部资源/eval 禁令等），
     *   并作为 currentHtml 发布依据（mutated=true，html 非空）；
     * - scenarios.json（功能断言）：解析校验后只作工作区触碰，不发布、不跑 HTML 校验；
     * - 其余辅助文件（js 与 css 文件等）：按文件类型做轻量校验（js 的安全禁令与
     *   module 语法、css 的 url() 外链），同样不发布不跑入口校验——把 js/css 文本当 HTML
     *   校验必然产生"缺少 __wwDebugState"之类伪错误，且会被误发布为 currentHtml；
     *   历史上正是这两点把模型逼回"全部塞进 index.html"的单文件形态。
     */
    private fun mutatedOutcome(
        call: ToolCallData,
        path: String,
        content: String,
        replaced: Int? = null,
        newString: String? = null,
        appended: Int? = null
    ): ToolOutcome {
        lastListing = null // 任何成功写入都改变清单，去重缓存即失效
        val contextBlock = if (newString != null) editContextSnippet(content, newString) else null
        // 修复轮越界提醒（软约束）：报错涉及范围外的写入附注——跨文件修复合法，
        // 但"顺带改了无关系统"必须显式可见（回归断言会重跑，改动自负其责）。
        val scopeNote = if (advisoryScope.isNotEmpty() && path != GameScenarios.FILE && path !in advisoryScope) {
            "\n（越界提醒：报错涉及文件为 ${advisoryScope.joinToString("、")}，本次写入了范围外的 $path——" +
                "确认与本次修复直接相关；无关改动请撤销，最小修改契约仍然有效。）"
        } else null
        if (path == GameScenarios.FILE) {
            val (checkOk, checkText) = GameScenarios.checkObservation(content, requireViewAssertion)
            val head = when {
                appended != null -> "已追加 $appended 行并写入 $path。"
                replaced != null -> "已替换 $replaced 处并写入 $path。"
                else -> "已写入 $path（${content.length} 字符）。"
            }
            return ToolOutcome(
                callId = call.id,
                name = call.name,
                ok = checkOk,
                mutated = false,
                html = null,
                report = null,
                observation = head + "\n" + checkText,
                workspaceTouched = true,
                path = path
            )
        }
        val head = when {
            appended != null -> "已追加 $appended 行并写入 $path（现共 ${content.lines().size} 行）。"
            replaced != null -> "已替换 $replaced 处并写入 $path（${content.length} 字符）。"
            else -> "已写入 $path（${content.length} 字符）。"
        }
        if (path != GameFileWorkspaceEntryPoint.DEFAULT) {
            val auxReport = GameValidator.validateAuxFile(path, content)
            return ToolOutcome(
                callId = call.id,
                name = call.name,
                ok = true,
                mutated = false,
                html = null,
                report = null,
                observation = head + (contextBlock ?: "") + formatValidation(auxReport) + (scopeNote ?: ""),
                workspaceTouched = true,
                path = path
            )
        }
        val report = validate(content)
        val observation = head + (contextBlock ?: "") + formatValidation(report) + (scopeNote ?: "")
        return ToolOutcome(
            callId = call.id,
            name = call.name,
            ok = true,
            mutated = true,
            html = content,
            report = report,
            observation = observation,
            path = path
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

    private fun outcome(
        call: ToolCallData,
        ok: Boolean,
        observation: String,
        path: String? = null
    ): ToolOutcome = ToolOutcome(
        callId = call.id,
        name = call.name,
        ok = ok,
        mutated = false,
        html = null,
        report = null,
        observation = observation,
        path = path
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

/**
 * 工具参数 JSON 的宽容修复（两族高频损坏，2026-09-07 真机实测）：
 * 1. 【字符串字面量内】未转义的控制字符（换行/回车/制表符及其余 < 0x20）转义为
 *    合法 JSON（\n、\r、\t、\uXXXX）——大 writefile 参数高发，内容本身完好；
 * 2. 【字符串之外】模型混入的非 JSON 空白归一为半角空格：全角空格 U+3000、
 *    不换行空格 U+00A0、BOM U+FEFF 与 \t\n\r 以外的控制字符——严格 JSON 只认
 *    半角空白，这些字符落在 token 之间会让解析器在"期待引号"处直接报错
 *    （实测签名：Expected quotation mark '"', but had ' '）。
 * 只做上述两类无损归一，不碰任何结构；没有任何可修内容时返回 null
 * （调用方按原错误反馈，如流被截断的 EOF 不可修复）。
 */
internal fun escapeRawControlCharsInStrings(src: String): String? {
    val sb = StringBuilder(src.length + 64)
    var inString = false
    var changed = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (!inString) {
            when {
                c == '"' -> { inString = true; sb.append(c) }
                // 非 JSON 空白（全角空格 U+3000、不换行空格 U+00A0、BOM U+FEFF、
                // \t\n\r 以外的控制字符）归一为半角空格：严格 JSON 只认半角空白
                c == '\u3000' || c == '\u00A0' || c == '\uFEFF' -> { sb.append(' '); changed = true }
                c < ' ' -> { sb.append(' '); changed = true }
                else -> sb.append(c)
            }
            i++
            continue
        }
        when {
            c == '\\' && i + 1 < src.length -> {
                // 合法转义序列整体保留（\"、\\、\n、\uXXXX 等）；末尾孤立反斜杠照抄
                sb.append(c).append(src[i + 1])
                i += 2
            }
            c == '"' -> {
                inString = false
                sb.append(c)
                i++
            }
            c == '\n' -> { sb.append("\\n"); changed = true; i++ }
            c == '\r' -> { sb.append("\\r"); changed = true; i++ }
            c == '\t' -> { sb.append("\\t"); changed = true; i++ }
            c < ' ' -> { sb.append("\\u%04x".format(c.code)); changed = true; i++ }
            else -> { sb.append(c); i++ }
        }
    }
    return if (changed) sb.toString() else null
}
