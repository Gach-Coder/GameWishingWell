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
    const val GREP_FILE = "grepfile"
    const val WRITE_FILE = "writefile"
    const val EDIT_FILE = "editfile"
    const val APPEND_FILE = "appendfile"
    const val OUTLINE = "outline"
    const val REVERT_FILE = "revertfile"

    private const val PATH_DESC =
        "工作区内的相对路径：index.html（唯一入口）、js/*.js 或 css/*.css（多文件组织，" +
            "由 index.html 相对路径引用，平台运行前自动合并）或 scenarios.json（功能断言，均衡/精品档）"

    /** 切片自适应阈值：不超过该行数的文件，切片请求一律整读返回。 */
    const val WHOLE_READ_MAX_LINES = 1200

    /**
     * 超大文件整读放宽上限（按字符预算）：行数在 [WHOLE_READ_MAX_LINES] 与本值之间且
     * 总字符数不超 [WHOLE_READ_MAX_CHARS] 的文件仍整读——大文件多轮切片的往返成本
     * （每轮重发全部历史）远高于一次多带些内容；超出则保留切片并附定位提示。
     */
    const val WHOLE_READ_MAX_LINES_LARGE = 2400
    // 整读字符预算 160K→80K：整读结果常驻本回合后续全部轮次（写入才折叠），
    // 一份 160K 全文 ≈ 50K token 让后半回合每轮 prefill 显著变贵（复利膨胀）；
    // 配合 Agent 侧"陈旧整读自动折叠"双保险。
    const val WHOLE_READ_MAX_CHARS = 80_000

    /** outline 返回的符号条目上限（防把超宽文件全量符号倒进上下文）。 */
    const val OUTLINE_MAX_SYMBOLS = 400

    /** editfile old_string 失配时返回的最相近候选段数。 */
    const val EDIT_MISMATCH_CANDIDATES = 3

    /**
     * editfile 行号锚定窗口半径（行）：提供 anchor_line 时优先在锚点 ± 本值行内匹配，
     * 吸收编辑导致的行号小幅漂移并天然消歧同文多处；窗口未命中自动回退全文匹配。
     */
    const val EDIT_ANCHOR_WINDOW_LINES = 40

    /**
     * 单文件拆分建议阈值（行）：js 文件达到该行数时在 listfiles 观察里附一次性拆分
     * 建议。实测 main.js 9113 行后，定位/编辑/校验成本随行数线性上涨，工具层全部
     * 优化被单文件膨胀吃掉——拆分是唯一"越用越快"的结构性改动。
     */
    const val SPLIT_ADVISE_LINES = 4000

    /**
     * 拆分建议的字节预筛门槛：低于该字节的文件不可能达到 [SPLIT_ADVISE_LINES] 行
     * （避免每次 listfiles 都读文件数字行）。
     */
    const val SPLIT_ADVISE_MIN_BYTES = 80_000

    /** 连续 readfile 达到该次数且期间未用 grepfile/outline 时，在观察里注入定位矫正提示。 */
    const val READFILE_STREAK_NUDGE_AT = 4

    /**
     * 未授权整体重写的行相似度阈值（Dice）：低于判重写。灾难重写的相似度≈0
     * （全新代码），常规功能修改 >0.8（绝大部分行保留），0.35 居中留足余量。
     */
    const val REWRITE_SIMILARITY_MIN = 0.35

    /** 全量重写咨询信号阈值：一个回合累计整体覆盖既有文件达到该次数即提示（非硬门）。 */
    const val FULL_REWRITE_ADVISE_AT = 3

    /** grep 单次返回的匹配行上限（防超宽 pattern 把全工作区倒进上下文）。 */
    const val GREP_MAX_MATCHES = 200

    /** 读取类工具名集合：侦查门禁期从工具清单摘除并在执行层直接拒绝（见 Agent 侧门禁）。 */
    val READ_TOOL_NAMES = setOf(LIST_FILES, READ_FILE, GREP_FILE, OUTLINE)

    /** 门禁期工具集（仅写入类）：侦查超限且拒不落盘时，模型只剩"落盘或交付"两条出口。 */
    fun writeOnlySpecs(): List<ToolSpec> = specs().filter { it.name !in READ_TOOL_NAMES }

    /**
     * 大幅删除咨询阈值：单文件相对回合起点删除非空行比例达到该值即提示（非硬门）。
     * 行相似度护栏拦不住"删一半留一半"（保留行相似度不受删除影响——实测"禁止开始
     * 弹窗"回合把 index.html 削掉 48% 仍过 0.35 相似度门，布局/说明文本一并被删）。
     */
    const val MASS_DELETION_ADVISE_RATIO = 0.3

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = LIST_FILES,
            description = "列出游戏工作区的文件清单（路径、版本、大小、哈希）。用于确认当前文件状态。",
            parameters = """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSpec(
            name = READ_FILE,
            description = "读取工作区内一个文件的内容。本项目文件通常千行以内——不带行号参数直接整读即可，不要切片（切片省下的一点上下文远抵不过多花一整轮往返）。只有确需超大文件局部时才用 start_line/end_line，且需要多个区段时应在同一轮响应里并行发起多个 readfile。目标符号不知道行号时先 outline（符号地图）或 grepfile。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"start_line":{"type":"integer","description":"起始行号（从 1 计），可选"},"end_line":{"type":"integer","description":"结束行号（含），可选"}},"required":[]}"""
        ),
        ToolSpec(
            name = OUTLINE,
            description = "返回文件的符号地图：函数/类/顶层常量/config 参数键的名称与行号区间（每符号一行）。大文件（千行以上）导航首选——一次调用得到全貌地图，再 readfile 目标区段，替代多轮切片通读；与 grepfile 互补（outline 按结构定位，grepfile 按内容定位）。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"}},"required":["path"]}"""
        ),
        ToolSpec(
            name = REVERT_FILE,
            description = "把单个文件回滚到本回合开始时的版本（只影响该文件，其余文件与本回合其他修改保留）。本次修改改坏了想重来时用它，比撤销整回合或手工逆向编辑都快；文件本轮未被修改过则无操作。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"}},"required":["path"]}"""
        ),
        ToolSpec(
            name = GREP_FILE,
            description = "在工作区文件内容里搜索定位：返回「文件:行号:匹配行」（最多 ${GREP_MAX_MATCHES} 行，命中行前后文请用 readfile 取）。定位代码/文案/元素（某函数、某变量、某按钮 id、某段中文提示在哪个文件哪一行）首选本工具——一轮搜索即可替代对大文件的多轮 readfile 切片通读。pattern 按正则匹配（非法正则自动退回普通文本匹配，区分大小写）。path 可以是单个文件，也可以是目录（如 js、css/）——传目录时递归搜索其下全部文件。",
            parameters = """{"type":"object","properties":{"pattern":{"type":"string","description":"搜索内容：正则表达式或普通文本（如 btnStart、开始游戏、function\\\\s+boot）"},"path":{"type":"string","description":"$PATH_DESC，可选（缺省搜索全部文件）"}},"required":["pattern"]}"""
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
            description = "对已有文件做字符串精确替换（增量修改，保持其余内容不变）。old_string 必须与文件原文完全一致；" +
                "出现多处时优先提供更长上下文使其唯一，或传 anchor_line（预期行号）把匹配收窄到该行附近窗口。" +
                "差异仅在空行位置/行尾空白时系统会按空白归一化在唯一命中处自动应用。写入后系统自动检查产品契约/断言格式并在结果中回传。",
            parameters = """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"old_string":{"type":"string","description":"要替换的原文片段，必须逐字符匹配"},"new_string":{"type":"string","description":"替换后的内容"},"replace_all":{"type":"boolean","description":"old_string 多处出现时是否全部替换，默认 false"},"anchor_line":{"type":"integer","description":"可选：old_string 预期所在行号（来自 grepfile 命中、outline 符号区间或文件地图）。提供时优先在 anchor_line±40 行窗口内匹配——同文多处自动消歧、行号小幅漂移也能命中；窗口未命中自动回退全文匹配"}},"required":["old_string","new_string"]}"""
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
    /** 本次调用是 editfile 且失败原因为 old_string 未找到（模型对文件现状认知过期）。
     *  驱动同轮同文件后续编辑短路与跨轮失配计数（实测单回合失配最高 83 次）。 */
    val editMiss: Boolean = false,
    /** 本次读/写动作的实际文件路径（读结果按路径精确过期的依据；错误返回可为 null）。 */
    val path: String? = null,
    /** readfile 整读（返回了全文而非切片）：Agent 据此做"陈旧整读自动折叠"，
     *  防止一份 ≤整读预算 的全文在后续所有轮次常驻上下文（每轮 prefill 复利变贵）。 */
    val fullRead: Boolean = false
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
    private val advisoryScope: Set<String> = emptySet(),
    /**
     * 只读模式（chat 会话）：严格禁止一切写入类工具（writefile/editfile/appendfile/
     * revertfile）——双层硬保障的第二层（第一层是只传读类工具 spec），即使模型
     * 硬调写工具也只得到拒绝观察，工作区不可能被修改。
     */
    private val readOnly: Boolean = false
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

    /**
     * grep 去重（同回合）：已执行的 pattern+范围 键集。无写入时完全相同的重复搜索
     * 只回指针——结果就在上文，重发一份纯属上下文膨胀；实测 20261003_201230 回合
     * 同一 pattern 被重复搜索 4 次（侦查循环的燃料之一）。任何成功写入清空
     * （文件变了重搜才有新信息）。命中与未命中都记（未命中的重复同样无意义）。
     */
    private val grepMarks = HashSet<String>()

    /**
     * 陈旧整读折叠配套：解除该文件的整读去重标记——历史里的全文已被 Agent 折叠为
     * 指针后，再整读必须重新发送内容，而不是回"上文已有"的指针（此时上文已没有）。
     */
    fun releaseFullRead(path: String) {
        fullReadMarks.remove(path)
        grepMarks.clear()   // 文件已变：grep 结果随之过期，去重指针全部作废
    }

    /** 本回合全量覆盖既有文件的累计次数与一次性咨询标记（执行器生命周期=一个回合）。 */
    private var fullOverwriteCount = 0
    private var rewriteAdvised = false

    /** 已给过大幅删除提醒的文件（每文件每回合一次，防重复噪音）。 */
    private val massDeletionAdvised = mutableSetOf<String>()

    /** 已给过拆分建议的文件（每文件每回合一次）。 */
    private val splitAdvised = mutableSetOf<String>()

    /**
     * 连续 readfile 计数（行为矫正）：实测 170 个回合日志里 readfile 占工具调用 53%
     * 而 grepfile 仅 1%——定位类需求被"切片通读"完成，每片一次 LLM 往返。grepfile/
     * outline 任一使用即清零；达到 [GameTools.READFILE_STREAK_NUDGE_AT] 时在下一次
     * readfile 观察里附定位矫正提示（只提醒不拒绝）。
     */
    private var readfileStreak = 0

    /**
     * 本回合已固化的断言套件强度量：id 集合 + 全套件总步骤数（覆盖面代理指标）。
     * 修改/迭代回合从起点快照播种；生成回合在首次成功写入非空套件时固化——
     * 此后套件只增不减（历史事故：沙箱合成触控点不动选人卡，模型把 5 步选人
     * 断言改写成 1 步"开局即入战"绕过路径，弱化后的回归套件为后续修复放行）。
     */
    private var committedSuiteIds: Set<String>? = null
    private var committedSuiteSteps: Int = 0

    /** 全部产生工作区写入的工具（readOnly 模式拒绝清单）。 */
    private val WRITE_TOOL_NAMES = setOf(GameTools.WRITE_FILE, GameTools.EDIT_FILE, GameTools.APPEND_FILE, GameTools.REVERT_FILE)

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
            if (readOnly && call.name in WRITE_TOOL_NAMES) {
                return outcome(
                    call, ok = false,
                    observation = "当前是问答（chat）会话，严格只读：${call.name} 不可用。" +
                        "可用工具仅 listfiles / readfile / grepfile / outline；如需修改游戏，请把诉求表述为开发指令（如「帮我修改…」「加一个…」）。"
                )
            }
            when (call.name) {
                GameTools.LIST_FILES -> listFiles(call)
                GameTools.READ_FILE -> readFile(call, args)
                GameTools.GREP_FILE -> grepFile(call, args)
                GameTools.OUTLINE -> outlineFile(call, args)
                GameTools.REVERT_FILE -> revertFile(call, args)
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
        // 单文件膨胀拆分建议（一次性/文件）：main.js 长到数千行后，定位/编辑/校验的
        // 每项成本都随行数线性上涨。只建议"交付后单独回合拆"，防止当回合顺手大拆
        // 触发整体重写护栏；拆分由模型执行（按系统域切块＋调引用），平台运行前自动
        // 内联合并，无运行风险。
        val splitNote = manifest.files
            .filter { it.path.endsWith(".js") && it.bytes >= GameTools.SPLIT_ADVISE_MIN_BYTES && it.path !in splitAdvised }
            .mapNotNull { f ->
                val lineCount = workspace.read(f.path)?.count { it == '\n' }?.plus(1) ?: return@mapNotNull null
                if (lineCount < GameTools.SPLIT_ADVISE_LINES) return@mapNotNull null
                splitAdvised += f.path
                "⚠ ${f.path} 已 $lineCount 行（超出单文件健康阈值 ${GameTools.SPLIT_ADVISE_LINES} 行）：定位与编辑成本随行数线性上涨。" +
                    "建议本回合交付后，用【单独一个回合】按系统域把它拆分为多个 js/<系统>.js 并在 index.html 调整引用顺序" +
                    "（平台运行前自动内联合并、离线可玩不变，拆分无运行风险）。当前回合请继续手头的小修改，不要顺手大拆分。"
            }
        return outcome(
            call, ok = true,
            observation = "工作区文件清单（入口 index.html）：\n$listing" +
                if (splitNote.isEmpty()) "" else "\n" + splitNote.joinToString("\n")
        )
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
        // 大文件按字符预算放宽（往返成本高于多带内容），超出才保留切片。
        val sliceNarrowed = startLine > 1 || endLine < lines.size
        val withinLargeBudget = lines.size <= GameTools.WHOLE_READ_MAX_LINES_LARGE &&
            content.length <= GameTools.WHOLE_READ_MAX_CHARS
        val smallFileOverride = sliceNarrowed && lines.size <= GameTools.WHOLE_READ_MAX_LINES
        val largeFileOverride = sliceNarrowed && !smallFileOverride && withinLargeBudget
        if (!sliceNarrowed || smallFileOverride || largeFileOverride) {
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
            readfileStreak++
            val streakNote = if (readfileStreak >= GameTools.READFILE_STREAK_NUDGE_AT) {
                "\n（定位提醒：已连续 $readfileStreak 次 readfile。不知道目标在哪一行时，" +
                    "grepfile 按内容/outline 按符号一次即可定位，省多轮往返。）"
            } else ""
            return if (smallFileOverride) {
                outcome(
                    call, ok = true,
                    observation = "文件 $path 共 ${lines.size} 行（不大，已直接返回全文；后续修改请基于此内容，无需再切片读取）：\n$content$streakNote",
                    path = path,
                    fullRead = true
                )
            } else if (largeFileOverride) {
                outcome(
                    call, ok = true,
                    observation = "文件 $path 共 ${lines.size} 行（在整读预算内，已直接返回全文，省去多轮切片）：\n$content$streakNote",
                    path = path,
                    fullRead = true
                )
            } else {
                outcome(call, ok = true, observation = "文件 $path 内容如下（共 ${lines.size} 行）：\n$content", path = path, fullRead = true)
            }
        }
        val slice = lines.subList(startLine - 1, endLine).joinToString("\n")
        readfileStreak++
        val bigFileNote = if (lines.size > GameTools.WHOLE_READ_MAX_LINES) {
            "\n（本文件共 ${lines.size} 行超出整读预算，只能切片。定位请优先 outline（符号地图一次拿全貌）或 grepfile（按内容搜索）；" +
                "多区段需求请在同一轮并行发起多个 readfile。）"
        } else ""
        return outcome(
            call, ok = true,
            observation = "文件 $path 第 $startLine-$endLine 行（共 ${lines.size} 行）：\n$slice$bigFileNote" +
                if (readfileStreak >= GameTools.READFILE_STREAK_NUDGE_AT && bigFileNote.isEmpty()) {
                    "\n（定位提醒：已连续 $readfileStreak 次 readfile。不知道目标在哪一行时，" +
                        "grepfile 按内容/outline 按符号一次即可定位，省多轮往返。）"
                } else "",
            path = path
        )
    }

    /**
     * 工作区内容搜索（定位专用，读取类工具）：按正则（非法时退回普通文本）逐行匹配，
     * 返回「文件:行号:匹配行原文」。定位代码在哪（函数/变量/按钮 id/中文文案）从
     * "多轮 readfile 切片通读"压缩为一轮——实测 7100 行文件的"删一个弹窗"回合，
     * 11 轮纯读侦查（每轮一次 LLM 往返）才找到目标；命中行上下文由模型按行号
     * readfile 目标区段获取。上限 [GameTools.GREP_MAX_MATCHES] 行防超宽 pattern
     * 把全工作区倒进上下文。
     */
    private suspend fun grepFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val pattern = args.optString("pattern")?.takeIf { it.isNotEmpty() }
            ?: return outcome(call, ok = false, observation = "缺少必填参数 pattern（正则或普通文本）")
        val path = args.optString("path")
        // 同 pattern+同范围 去重：无写入时重复搜索只回指针（见 grepMarks 注释）
        val markKey = "${path ?: "<all>"}\u0000$pattern"
        if (markKey in grepMarks) {
            return outcome(
                call, ok = true,
                observation = "grep「$pattern」本回合已在相同范围（${path ?: "全部文件"}）搜索过，结果见上文最近一次——" +
                    "重复搜索不会产生新信息。请基于已有结果行动，或改写 pattern / 收窄 path；" +
                    "文件被修改后该指针自动失效，可重新搜索。"
            )
        }
        val files: List<Pair<String, String>> = if (path != null) {
            val direct = workspace.read(path)
            when {
                direct != null -> listOf(path to direct)
                // 目录前缀（js、css、js/ 均可）：递归 grep 该目录下全部文件——
                // 实测模型高频把目录当 path 传入（6 次失败浪费 6 个往返），
                // 与其报错引导不如直接给出结果
                else -> {
                    val norm = path.trim().replace('\\', '/').trimEnd('/')
                    val under = workspace.manifest().files.filter { it.path.startsWith("$norm/") }
                    if (under.isEmpty()) {
                        return outcome(call, ok = false, observation = "文件不存在或不可读：$path（可用 listfiles 查看清单）")
                    }
                    under.mapNotNull { f -> workspace.read(f.path)?.let { f.path to it } }
                }
            }
        } else {
            workspace.manifest().files.mapNotNull { f -> workspace.read(f.path)?.let { f.path to it } }
        }
        val regex = try { Regex(pattern) } catch (_: Exception) { null }
        val sb = StringBuilder()
        var matches = 0
        var filesWithMatch = 0
        var truncated = false
        search@ for ((fpath, content) in files) {
            var fileCount = 0
            val lines = content.lines()
            for (idx in lines.indices) {
                if (matches >= GameTools.GREP_MAX_MATCHES) { truncated = true; break@search }
                val hit = try {
                    regex?.containsMatchIn(lines[idx]) ?: lines[idx].contains(pattern)
                } catch (_: Exception) {
                    lines[idx].contains(pattern) // 病态正则兜底：按普通文本匹配
                }
                if (hit) {
                    if (fileCount == 0) { sb.append("\n【").append(fpath).append("】"); filesWithMatch++ }
                    sb.append("\n").append(idx + 1).append(": ").append(lines[idx].trim().take(200))
                    matches++; fileCount++
                }
            }
        }
        val scope = if (path == null) {
            "全部 ${files.size} 个文件"
        } else if (files.size == 1 && files[0].first == path) {
            path
        } else {
            "$path 下 ${files.size} 个文件"
        }
        readfileStreak = 0   // 定位行为矫正：用了搜索即清零连续 readfile 计数
        grepMarks += markKey
        val observation = if (matches == 0) {
            "grep「$pattern」在 $scope 中未命中。请换关键词（元素 id / 函数名 / 文案原文），" +
                "或先用 listfiles 确认文件清单，或用 outline 按符号结构定位。"
        } else {
            "grep「$pattern」命中 $matches 行（$filesWithMatch 个文件；命中行已去首尾空白，上下文请 readfile 对应行号区段）：" +
                sb +
                if (truncated) "\n……结果超过 ${GameTools.GREP_MAX_MATCHES} 行已截断：请收窄 pattern 或指定 path。" else ""
        }
        return outcome(call, ok = true, observation = observation)
    }

    /**
     * 符号地图（读取类工具）：一次调用返回文件的函数/类/顶层常量/config 参数键与
     * 行号区间。实测 7100 行 main.js 的"定位一个函数"被切片通读耗掉 11+ 轮（每轮
     * 一次 LLM 往返）；outline 把"拿到导航地图"压缩为一轮，再按区间精准 readfile。
     * 正则识别（JS：function/class/顶层 const|let|var 与 config.js 的参数键）——
     * 不做完整语法解析，允许漏标（地图缺失远好于没有地图），误标率按保守模式控制。
     */
    private suspend fun outlineFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 path")
        val content = workspace.read(path)
            ?: return outcome(call, ok = false, observation = "文件不存在或不可读：$path（可用 listfiles 查看清单）")
        readfileStreak = 0
        val lines = content.lines()
        data class Sym(val name: String, val kind: String, val line: Int)
        val syms = mutableListOf<Sym>()
        val js = path.endsWith(".js") || path.endsWith(".html")
        val json = path.endsWith(".json")
        // JS/HTML 内联脚本：函数与类声明、顶层常量、对象常量键（config.js 顶层键命中同一模式）
        val decl = if (js) Regex(
            """^\s*(?:export\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)""" +
                """|^\s*(?:export\s+)?class\s+([A-Za-z_$][\w$]*)""" +
                """|^(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)""" +
                """|^\s{2}"([A-Za-z_$][\w$.]*)"\s*:\s*[{\[]"""
        ) else null
        val jsonKey = if (json) Regex("""^\s{2}"([^"]+)"\s*:""") else null
        for (idx in lines.indices) {
            val m = decl?.find(lines[idx])
            val j = jsonKey?.find(lines[idx])
            // drop(1) 跳过 group0（整体匹配串）：name 必须取自捕获组
            val name = m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                ?: j?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            if (name != null) {
                val kind = when {
                    j != null -> "键"
                    m?.groupValues?.getOrNull(2)?.isNotEmpty() == true -> "class"
                    m?.groupValues?.getOrNull(1)?.isNotEmpty() == true -> "function"
                    else -> "const"
                }
                syms += Sym(name, kind, idx + 1)
            }
            if (syms.size >= GameTools.OUTLINE_MAX_SYMBOLS) break
        }
        if (syms.isEmpty()) {
            return outcome(
                call, ok = true,
                observation = "文件 $path（${lines.size} 行）未识别出符号（非 JS/JSON 或均为内联逻辑）——请直接 readfile 或 grepfile 定位。",
                path = path
            )
        }
        val sb = StringBuilder()
        for (i in syms.indices) {
            val end = if (i + 1 < syms.size) syms[i + 1].line - 1 else lines.size
            sb.append("\n").append(syms[i].line).append("-").append(end)
                .append(" ").append(syms[i].kind).append(" ").append(syms[i].name)
        }
        return outcome(
            call, ok = true,
            observation = "文件 $path 符号地图（共 ${lines.size} 行 / ${syms.size} 个符号；行号区间含区间内全部内容，readfile 目标区间即可）：" + sb +
                if (syms.size >= GameTools.OUTLINE_MAX_SYMBOLS) "\n……符号超过 ${GameTools.OUTLINE_MAX_SYMBOLS} 个已截断，请 grepfile 定位后半部分。" else "",
            path = path
        )
    }

    /**
     * 单文件回滚到回合起点（写入类工具）：改坏一个文件时无需手工逆向编辑，也不必
     * 撤销整回合丢掉其他文件的好修改。起点快照由调用方注入（[turnStartSnapshot]），
     * 未提供快照或该文件本轮未被修改过则为无操作。
     */
    private suspend fun revertFile(call: ToolCallData, args: JsonObject): ToolOutcome {
        val path = args.optString("path")
            ?: return outcome(call, ok = false, observation = "缺少必填参数 path")
        val baseline = turnStartSnapshot[path]
            ?: return outcome(
                call, ok = true,
                observation = "文件 $path 在本回合开始时不存在或未被修改过，无需回滚。" +
                    "（如需撤销整回合全部修改请用界面的撤销功能。）",
                path = path
            )
        val current = workspace.read(path)
        if (current == baseline) {
            return outcome(call, ok = true, observation = "文件 $path 与回合起点版本一致，未产生变更。", path = path)
        }
        val saved = workspace.writeUpdated(path, baseline)
            ?: return outcome(call, ok = false, observation = "回滚写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        grepMarks.clear()   // 文件已变：grep 结果随之过期，去重指针全部作废
        return mutatedOutcome(call, path, baseline)
            .let {
                it.copy(
                    observation = "已把 $path 回滚到本回合开始的版本（${baseline.lines().size} 行）。" +
                        "其他文件的修改不受影响；请基于回滚后的版本继续。"
                )
            }
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
        danglingRefViolation(path, content)?.let { return outcome(call, ok = false, it) }
        if (path == GameScenarios.FILE) {
            scenarioContinuityViolation(content)?.let { return outcome(call, ok = false, it) }
        }
        val saved = if (existing == null) {
            workspace.writeInitial(path, content)
        } else {
            workspace.writeUpdated(path, content)
        } ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        grepMarks.clear()   // 文件已变：grep 结果随之过期，去重指针全部作废
        var result = mutatedOutcome(call, path, content)
        result = appendAdvisories(result, path, content)
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
        danglingRefViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        if (path == GameScenarios.FILE) {
            scenarioContinuityViolation(updated)?.let { return outcome(call, ok = false, it) }
        }
        val saved = workspace.writeUpdated(path, updated)
            ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        grepMarks.clear()   // 文件已变：grep 结果随之过期，去重指针全部作废
        return appendAdvisories(mutatedOutcome(call, path, updated, appended = content.lines().size), path, updated)
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

        var appliedNote = ""
        var updated = content
        var applied = 0

        // —— 第 1 道：行号锚定窗口（anchor_line ± [GameTools.EDIT_ANCHOR_WINDOW_LINES] 行）。
        // 窗口把匹配收紧到模型可见行号附近：同文多处由窗口天然消歧，编辑导致的行号
        // 小幅漂移也被窗口吸收；窗口未命中自动回退全文匹配（锚点是提示不是硬约束）。
        // replace_all 语义是"全部替换"，锚定收窄与之矛盾——直接走全文路径。
        val anchorLine = args.optInt("anchor_line")
        if (anchorLine != null && anchorLine > 0 && args.optBool("replace_all") != true) {
            val win = anchoredWindow(content, anchorLine)
            val windowCount = if (win != null) countOccurrences(win.text, oldString) else 0
            when {
                windowCount == 1 -> {
                    val windowUpdated = win!!.text.replaceFirst(oldString, newString)
                    updated = content.substring(0, win.startOffset) + windowUpdated +
                        content.substring(win.startOffset + win.text.length)
                    applied = 1
                    appliedNote = "（已按 anchor_line=$anchorLine 的 ±${GameTools.EDIT_ANCHOR_WINDOW_LINES} 行窗口匹配应用）"
                }
                windowCount > 1 -> return outcome(
                    call, ok = false,
                    observation = "old_string 在 anchor_line=$anchorLine 附近窗口内出现 $windowCount 处，仍不唯一。" +
                        "请扩大 old_string 上下文使其唯一，或确认全部替换时传 replace_all=true。"
                )
                else -> appliedNote = "（anchor_line=$anchorLine 的窗口内未找到 old_string，已回退全文匹配）"
            }
        }

        if (applied == 0) {
            val occurrences = countOccurrences(content, oldString)
            when {
                occurrences > 1 && args.optBool("replace_all") != true -> return outcome(
                    call, ok = false,
                    observation = "old_string 出现 $occurrences 处，不唯一。请扩大上下文使其唯一，或确认全部替换时传 replace_all=true，或传 anchor_line（预期行号）收窄匹配窗口。"
                )
                occurrences > 0 -> {
                    updated = if (occurrences == 1) content.replaceFirst(oldString, newString)
                    else content.replace(oldString, newString)
                    applied = occurrences
                }
                // 全文字面零命中 → 空白归一化唯一命中自动应用：实测 100% 相似候选
                // （差异只在空行位置/行尾空白）回传原文后仍被模型连续无视 3 轮
                // （20261003_003854，白烧约 2 分钟）——提示自救不如执行层替它落盘。
                // 仅【唯一】命中才应用；归一化后多处同形（公共代码块）维持既有
                // 候选提示路径，绝不猜。
                else -> {
                    val region = findUniqueNormalizedRegion(content, oldString)
                    if (region == null) return outcome(
                        call, ok = false,
                        observation = "old_string 在 $path 中未找到（必须与文件原文逐字符一致，含缩进与空行）。" +
                            nearestCandidatesNote(content, oldString) +
                            "如上下文中的代码可能已过期，请先 readfile 获取最新内容（重试时可附 anchor_line 行号缩小范围）。",
                        path = path,
                        editMiss = true
                    )
                    updated = replaceLineRange(content, region.first, region.last, newString)
                    applied = 1
                    appliedNote = "（old_string 与原文仅有空行/行尾空白差异：已在唯一命中区间第 ${region.first}-${region.last} 行" +
                        "按空白归一化自动应用替换，替换后内容以文件为准）"
                }
            }
        }
        if (updated == content) {
            return outcome(call, ok = true, observation = "替换前后内容一致，未产生变更。")
        }
        rewriteViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        moduleContractViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        configContractViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        danglingRefViolation(path, updated)?.let { return outcome(call, ok = false, it) }
        if (path == GameScenarios.FILE) {
            scenarioContinuityViolation(updated)?.let { return outcome(call, ok = false, it) }
        }
        val saved = workspace.writeUpdated(path, updated)
            ?: return outcome(call, ok = false, observation = "写入失败：$path（沙箱路径非法或哈希校验未通过）")
        fullReadMarks.remove(path)
        grepMarks.clear()   // 文件已变：grep 结果随之过期，去重指针全部作废
        val result = appendAdvisories(
            mutatedOutcome(call, path, updated, replaced = applied, newString = newString),
            path, updated
        )
        return if (appliedNote.isEmpty()) result
        else result.copy(observation = result.observation + "\n" + appliedNote)
    }

    /**
     * 成功写入后的咨询信号附加（非硬门，不拒绝）：
     * - 大幅删除提醒（[massDeletionNote]）：行相似度护栏拦不住"删一半留一半"——
     *   保留行的相似度不受删除影响（实测"禁止开始弹窗"回合 index.html 被削 48%
     *   仍过 0.35 相似度门，玩法说明与结构一并被删）。每文件每回合只提醒一次。
     */
    private fun appendAdvisories(result: ToolOutcome, path: String, content: String): ToolOutcome {
        val deletionNote = massDeletionNote(path, content)
        return if (deletionNote == null) result
        else result.copy(observation = result.observation + deletionNote)
    }

    /** 大幅删除咨询（非 null=提醒文本；阈值 [GameTools.MASS_DELETION_ADVISE_RATIO]）。 */
    private fun massDeletionNote(path: String, newContent: String): String? {
        if (allowFullRewrite || path == GameScenarios.FILE) return null
        if (path in massDeletionAdvised) return null
        val origin = turnStartSnapshot[path] ?: return null
        val originLines = origin.lines().filter { it.isNotBlank() }
        if (originLines.size < 8) return null
        val pool = newContent.lines().filter { it.isNotBlank() }.toMutableList()
        var deleted = 0
        for (l in originLines) if (!pool.remove(l)) deleted++
        val ratio = deleted.toDouble() / originLines.size
        if (ratio < GameTools.MASS_DELETION_ADVISE_RATIO) return null
        massDeletionAdvised += path
        return "\n⚠ 大幅删除提醒：$path 相对回合起点已删除 $deleted 个非空行（约 ${(ratio * 100).toInt()}%）。" +
            "行相似度护栏拦不住\"删一半留一半\"——若用户指令并未要求删减这些内容（玩法说明/界面结构/" +
            "既有功能都可能在其中），请立即 readfile 复核并用 editfile 恢复误删段落；确属指令要求的删减则继续。"
    }

    /**
     * 本回合改动摘要（vs 回合起点快照，多重集行差分近似）：供沙箱失败反馈附带——
     * 帮模型第一时间区分"存量 bug"与"自己本轮引入的回归"（实测：删弹窗回合大改
     * index.html 后布局断言失败，模型却在 main.js 里继续找原因）。scenarios.json
     * 天然整体重写，不计入。
     */
    fun turnChangeSummary(): String = runCatching {
        if (turnStartSnapshot.isEmpty()) return ""
        val current = HashMap<String, List<String>>()
        // 只走目录树拿路径 + 内容缓存（原 manifest 为列路径把全部文件 bytes 读了
        // 一遍算哈希，随后又 read 一遍——550KB×2 的纯浪费）
        workspace.listPaths().forEach { p -> cachedWorkspaceRead(p)?.let { current[p] = it.lines() } }
        val paths = (turnStartSnapshot.keys + current.keys).filter { it != GameScenarios.FILE }.distinct().sorted()
        val parts = mutableListOf<String>()
        for (p in paths) {
            val old = turnStartSnapshot[p]?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            val new = current[p]?.filter { it.isNotBlank() } ?: emptyList()
            if (turnStartSnapshot.containsKey(p) && !current.containsKey(p)) {
                parts += "$p（已删除）"
                continue
            }
            val pool = new.toMutableList()
            var common = 0
            for (l in old) if (pool.remove(l)) common++
            val added = new.size - common
            val removed = old.size - common
            if (added == 0 && removed == 0) continue
            parts += "$p +$added/-$removed 行"
        }
        if (parts.isEmpty()) ""
        else "本轮已改动：" + parts.take(6).joinToString("；") +
            "。若失败与新改动相关（布局/界面类断言），优先检查删改最多的文件——那大概率是你本轮自己引入的。"
    }.getOrDefault("")

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
            val files = postWriteView(path, newContent) ?: return null
            if (files.size <= 1) return null // 单文件/空工作区无跨文件契约
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
     * 删除残留预检（写盘前执行，非 null=违规文本，调用方直接拒绝且不落盘）：
     * 以写入后视图跑 [GameStaticCheck.danglingReferenceViolations]——删除系统/变量时
     * 漏删一处裸引用 = 确定性运行时 ReferenceError。历史事故：删除"刀盾二段盾击"
     * 系统漏删 newGame 重置里的 bashQ=null，当轮沙箱绿灯交付（该路径不可达），
     * 两个回合后"跳过开始菜单直接开局"引爆，用户救火 35 分钟。首次生成轮
     * （起点快照为空）与 css/scenarios 不参与。
     */
    private fun danglingRefViolation(path: String, newContent: String): String? {
        if (turnStartSnapshot.isEmpty()) return null
        if (!path.endsWith(".js") && path != GameFileWorkspaceEntryPoint.DEFAULT) return null
        return try {
            val files = postWriteView(path, newContent) ?: return null
            val violations = GameStaticCheck.danglingReferenceViolations(files, turnStartSnapshot)
            if (violations.isEmpty()) null
            else "致命错误：" + violations.joinToString("；") + "。文件未被改动。"
        } catch (_: Exception) {
            null // 预检自身异常不拦写入（宁可漏报不误伤）
        }
    }

    /**
     * 写入后的工作区全量视图（path 替换为 [newContent]，manifest 尚无的新文件也纳入）；
     * 任一文件不可读返回 null（预检整体跳过，宁可漏报不误伤）。
     */
    private fun postWriteView(path: String, newContent: String): Map<String, String>? {
        return try {
            val files = HashMap<String, String>()
            var readable = true
            // 只走目录树拿路径（manifest 会读全部文件 bytes 算哈希——写入前预检
            // 不需要哈希）；未变文件走内容缓存（mtime+size 指纹），写后自动失效
            workspace.listPaths().forEach { p ->
                if (p == path) {
                    files[p] = newContent
                } else {
                    val c = cachedWorkspaceRead(p)
                    if (c == null) readable = false else files[p] = c
                }
            }
            files[path] = newContent
            if (!readable) null else files
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 工作区文件内容缓存（[postWriteView] 专用）：path → (mtime:size 指纹, 内容)。
     * 派生数据记忆化之后，每次写入重读全部文件成了残余的磁盘大头（~550KB/写）；
     * mtime+size 未变的文件直接复用，写入过的文件指纹变化自动失效。
     */
    private val fileContentCache = HashMap<String, Pair<String, String?>>()

    private fun cachedWorkspaceRead(path: String): String? {
        val file = workspace.resolve(path) ?: return workspace.read(path)
        val stat = "${file.lastModified()}:${file.length()}"
        synchronized(fileContentCache) {
            fileContentCache[path]?.let { (s, c) -> if (s == stat) return c }
        }
        val content = workspace.read(path)
        synchronized(fileContentCache) {
            if (fileContentCache.size >= 32) fileContentCache.clear()
            fileContentCache[path] = stat to content
        }
        return content
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
            val files = postWriteView(path, newContent) ?: return null
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
    /**
     * editfile 失配时的最相近候选提示：按 [lineSimilarity] 同款 Dice 口径，对与
     * old_string 等行数的滑窗逐个评分，返回最高的 [GameTools.EDIT_MISMATCH_CANDIDATES]
     * 段（行号 + 相似度 + 首两行摘录）。实测失配率 15%（244/1624，多为缩进/已被本
     * 回合早前修改/上下文过期）——有了候选，模型一轮内即可定位真实位置修正重试，
     * 不必先整轮 readfile 再重试。
     */
    private fun nearestCandidatesNote(content: String, oldString: String): String {
        val lines = content.lines()
        val needle = oldString.lines().filter { it.isNotBlank() }
        if (needle.isEmpty() || lines.isEmpty()) return ""
        val window = minOf(needle.size, 60)
        data class Hit(val line: Int, val score: Double)
        val hits = mutableListOf<Hit>()
        var best = 0.0
        for (start in lines.indices) {
            val end = minOf(start + window, lines.size)
            if (end - start < window && start > 0) break
            val cand = lines.subList(start, end).filter { it.isNotBlank() }
            if (cand.isEmpty()) continue
            // 计数多重集（原 pool.remove(l) 为 O(窗口²)，乘上全文件滑窗即秒级——
            // editfile 失配高频回合（失配风暴）的主要本地开销）
            val pool = HashMap<String, Int>(cand.size)
            cand.forEach { pool[it] = (pool[it] ?: 0) + 1 }
            var common = 0
            for (l in needle) {
                val left = pool[l] ?: 0
                if (left > 0) {
                    pool[l] = left - 1
                    common++
                }
            }
            val score = 2.0 * common / (needle.size + cand.size)
            if (score <= 0.0) continue
            hits += Hit(start + 1, score)
            if (score > best) best = score
        }
        val top = hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.line })
            .take(GameTools.EDIT_MISMATCH_CANDIDATES)
            .filter { it.score >= 0.3 }   // 低于 0.3 的窗口多半无关，报了反而误导
        if (top.isEmpty()) return ""
        // 相似度 100% 的候选＝非空行完全一致、差异只在空行位置/行尾空白——
        // 直接回传该窗口的完整文件原文，模型可原样复制为 old_string 一次修对
        //（实测 20261003_003854 回合：100% 候选的位置与两行摘录被连续无视 3 轮，
        // 模型反复重发同一个过期 old_string 白烧约 2 分钟）。
        val exact = top.firstOrNull { it.score >= 0.999 }
        if (exact != null) {
            val end = minOf(exact.line - 1 + window, lines.size)
            val block = lines.subList(exact.line - 1, end).joinToString("\n")
            return "\n最相近候选（第 ${exact.line} 行起）相似度 100%——差异只在空行/行尾空白。" +
                "以下是该窗口的文件原文，请【原样复制】为 old_string 重试（保留全部缩进与空行）：\n" +
                block + "\n"
        }
        return top.joinToString(prefix = "\n最相近的 ${top.size} 处（疑似缩进/空白差异或已被修改，行号为窗口起点）：\n", separator = "\n") { h ->
            val head = lines.drop(h.line - 1).take(2).joinToString(" ⏎ ") { it.trim().take(90) }
            "· 第 ${h.line} 行（相似度 ${(h.score * 100).toInt()}%）：$head"
        } + "\n请对照候选窗口的当前原文修正 old_string（或直接 readfile 该区段）后重试。\n"
    }

    private fun lineSimilarity(originLines: List<String>, newContent: String): Double {
        if (originLines.isEmpty()) return 1.0
        val newLines = newContent.lines().filter { it.isNotBlank() }
        if (newLines.isEmpty()) return 0.0
        // 多重集交集用计数哈希（原 MutableList.remove 为 O(n²)：7900 行文件每次
        // 写入的护栏判定即数千万次字符串比较，属于实测写入延迟的组成部分）
        val pool = HashMap<String, Int>(newLines.size)
        newLines.forEach { pool[it] = (pool[it] ?: 0) + 1 }
        var common = 0
        for (l in originLines) {
            val left = pool[l] ?: 0
            if (left > 0) {
                pool[l] = left - 1
                common++
            }
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
        path: String? = null,
        editMiss: Boolean = false,
        fullRead: Boolean = false
    ): ToolOutcome = ToolOutcome(
        callId = call.id,
        name = call.name,
        ok = ok,
        mutated = false,
        html = null,
        report = null,
        observation = observation,
        path = path,
        editMiss = editMiss,
        fullRead = fullRead
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

    /** 行号锚定窗口：anchor_line ± [GameTools.EDIT_ANCHOR_WINDOW_LINES] 行的文本及其在全文中的起始偏移。 */
    private class AnchoredWindow(val text: String, val startOffset: Int)

    private fun anchoredWindow(content: String, anchorLine: Int): AnchoredWindow? {
        val lines = content.split('\n')
        if (anchorLine < 1 || anchorLine > lines.size) return null
        val first = (anchorLine - GameTools.EDIT_ANCHOR_WINDOW_LINES).coerceAtLeast(1)
        val last = (anchorLine + GameTools.EDIT_ANCHOR_WINDOW_LINES).coerceAtMost(lines.size)
        var offset = 0
        for (i in 0 until first - 1) offset += lines[i].length + 1
        val sb = StringBuilder()
        for (i in first - 1 until last) {
            sb.append(lines[i])
            if (i < last - 1) sb.append('\n')
        }
        return AnchoredWindow(sb.toString(), offset)
    }

    /**
     * 空白归一化唯一区间定位（editfile 失配自动应用的支撑）：
     * 归一化规则 = 逐行去行尾空白（含 \r）后丢弃空行；文件区间与 old_string 的
     * 归一化非空行序列完全一致 = 差异只在空行位置/行尾空白（[nearestCandidatesNote]
     * 的 100% 相似档）。返回唯一命中的原文行号区间（1-based 含端点，端点必为非空行）；
     * 无命中、多处命中或 old_string 归一化后过短（<8 字符，唯一性不可信）返回 null，
     * 调用方回退候选提示路径。多处命中绝不自动应用——公共代码块归一化后同形是常态。
     */
    private fun findUniqueNormalizedRegion(content: String, oldString: String): IntRange? {
        val needle = oldString.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        if (needle.isEmpty() || needle.sumOf { it.length } < 8) return null
        data class NonBlank(val lineNo: Int, val norm: String)
        val nb = ArrayList<NonBlank>(256)
        content.split('\n').forEachIndexed { i, l ->
            val n = l.trimEnd()
            if (n.isNotEmpty()) nb += NonBlank(i + 1, n)
        }
        if (nb.size < needle.size) return null
        var found: IntRange? = null
        var hits = 0
        var p = 0
        while (p <= nb.size - needle.size) {
            if (nb[p].norm == needle[0]) {
                var k = 1
                var q = p + 1
                while (k < needle.size && nb[q].norm == needle[k]) { k++; q++ }
                if (k == needle.size) {
                    hits++
                    if (hits > 1) return null
                    found = nb[p].lineNo..nb[p + needle.size - 1].lineNo
                    p += needle.size
                    continue
                }
            }
            p++
        }
        return if (hits == 1) found else null
    }

    /**
     * 按行号区间替换（1-based 含端点）：把 [firstLine, lastLine] 行的原文整体替换为
     * [replacement]，区间外的字符（含换行与行尾一切内容）逐字保留。偏移计算与
     * [findUniqueNormalizedRegion] 同用 split('\n') 口径，行号语义一致。
     */
    private fun replaceLineRange(content: String, firstLine: Int, lastLine: Int, replacement: String): String {
        var offset = 0
        var start = -1
        var end = -1
        val parts = content.split('\n')
        for (i in parts.indices) {
            val lineNo = i + 1
            if (lineNo == firstLine) start = offset
            if (lineNo == lastLine) {
                end = offset + parts[i].length
                break
            }
            offset += parts[i].length + 1
        }
        if (start < 0 || end < 0) return content
        return content.substring(0, start) + replacement + content.substring(end.coerceAtMost(content.length))
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
