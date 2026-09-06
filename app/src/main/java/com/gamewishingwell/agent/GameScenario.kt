package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 功能断言（场景回归测试）：把策划验收边界固化为可执行断言，由沙箱确定性裁决。
 *
 * 吸收 Generator-Critic 工作流的语义验证层，但把裁决权从 LLM Critic 交给确定性执行：
 * 每条断言 = 输入脚本（视口百分比点按/拖动）+ eventually 语义（针对 __wwDebugState()
 * 快照的布尔表达式在期限 [horizon] 内任一帧为真即通过，逐帧求值、翻真即停），
 * 全部在真实 Chromium 内核里求值——"LLM 提出（模型编写断言），确定门裁决（探针执行）"。
 *
 * 时间归执行器、内容归作者：模型只写"什么该为真"（expect）与"怎么戳游戏"（steps），
 * 不再预测"第几帧会发生什么"——断言在期限内翻真即通过，期限未到为真不是失败；
 * 失败回报自带字段轨迹与输入派发点状态，"机制未发生 / 字段错位"两类原因各自独立成信号。
 *
 * scenarios.json 由生成模型用文件工具写入并维护（它知道自己的快照字段），
 * 持久化在游戏工作区跨迭代存活：修复玩家报障时把问题固化为新断言追加，
 * 此后每次修改重跑全部断言，防"修好新问题弄坏旧功能"。
 * 仅均衡/精品档启用（快速档不测试、轻量档只保证可运行）。
 */
@Serializable
data class ScenarioStep(
    /** 视口百分比点按 [xPct, yPct]（0-100）。 */
    val tap: List<Double>? = null,
    /** 视口百分比拖动 [x0Pct, y0Pct, x1Pct, y1Pct]。 */
    val drag: List<Double>? = null,
    /** 可选：派发下一步输入前先确定性推进 N 帧（每帧 50ms 游戏时间）——只控制输入节奏，与断言判定无关。 */
    val frames: Int? = null
)

@Serializable
data class GameScenario(
    val id: String,
    /** 所属系统（module），失败时用于定位。 */
    val system: String = "",
    /** 人类可读的断言名称（回传给模型时展示）。 */
    val name: String = "",
    val steps: List<ScenarioStep> = emptyList(),
    /** 针对 s（= __wwDebugState() 返回快照）的布尔表达式，如 s.score > 0；期限内任一帧为真即通过。 */
    val expect: String,
    /** 期限（帧）：默认 [GameScenarios.HORIZON_DEFAULT_FRAMES]，上限 [GameScenarios.HORIZON_MAX_FRAMES]。 */
    val horizon: Int? = null
)

object GameScenarios {

    const val FILE = "scenarios.json"
    const val MAX_SCENARIOS = 6
    const val MAX_STEPS = 12
    /** 断言期限默认值（帧，每帧 50ms 游戏时间）＝ 15 秒游戏时间。 */
    const val HORIZON_DEFAULT_FRAMES = 300
    /** 断言期限上限（帧）＝ 30 秒游戏时间；沙箱墙钟预算的硬顶。 */
    const val HORIZON_MAX_FRAMES = 600
    /** 输入步骤之间执行器默认推进的帧数（让输入生效并被逐帧求值观察到）。 */
    const val INTER_STEP_FRAMES = 30
    const val MAX_EXPECT_LEN = 300

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 功能断言只进均衡/精品档：快速档不做测试，轻量档仅保证可运行。 */
    fun enabledForTier(tier: String): Boolean =
        tier == QualityTier.BALANCED || tier == QualityTier.PREMIUM

    /**
     * 解析模型写出的 scenarios.json。任何非法输入都降级为空列表——沙箱按
     * "无断言"运行，绝不让测试定义问题阻塞交付管线（解析问题由写入时的
     * [checkObservation] 反馈给模型修）。
     */
    fun parse(raw: String?): List<GameScenario> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        val arrayText = extractArray(cleaned) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<RawScenario>>(arrayText)
        }.getOrNull() ?: return emptyList()
        return parsed.mapNotNull { sanitize(it) }.take(MAX_SCENARIOS)
    }

    /** 清洗后的标准 JSON 数组文本（沙箱注入用；探针侧再做一层防御性解析）。 */
    fun toJson(scenarios: List<GameScenario>): String = json.encodeToString(scenarios)

    fun readFromWorkspace(workspace: GameFileWorkspace): List<GameScenario> =
        parse(workspace.read(FILE))

    /**
     * 写入 scenarios.json 后的执行器观察反馈：(ok, text)。
     * ok=false 表示内容不可用（非法 JSON / 无可执行断言 / 含恒真式），模型需重写；
     * ok=true 附断言清单摘要，让模型确认覆盖面。
     */
    fun checkObservation(raw: String): Pair<Boolean, String> {
        val list = parse(raw)
        if (list.isEmpty()) {
            return false to "scenarios.json 未解析出可执行断言。必须输出合法 JSON：" +
                "{\"scenarios\":[{\"id\":\"...\",\"system\":\"...\",\"name\":\"...\",\"steps\":[{\"tap\":[50,80]}],\"expect\":\"s.score > 0\"}]}；" +
                "expect 为针对 __wwDebugState() 快照 s 的布尔表达式（期限内任一帧为真即通过），" +
                "且不得为空或超过 $MAX_EXPECT_LEN 字符。"
        }
        val weak = list.filter { containsTautology(it.expect) }
        if (weak.isNotEmpty()) {
            return false to "功能断言包含恒真/近恒真子式（无法失败，等于没测）：" +
                weak.joinToString("；") { "「${it.name}」的 ${it.expect}" } +
                "。禁止 >=0（计数/长度天然非负）、!== undefined / !== null / typeof 判存在这类写法；" +
                "请改写为可失败的数值变化或状态迁移断言（如 s.score > 0、s.wave >= 1、s.state === 'playing'）。"
        }
        // 结构性不可满足陷阱：单调递增字段（收入/得分/击杀类）配绝对值上界——游戏进程
        // 中该字段以增长为主，"小于某绝对值"只在开局窗口可翻真，收入一旦累积超过即永不
        // 满足（实测 s.gold<200 配击杀收入曾烧 20+ 轮修复）。写入即打回，引导相对断言。
        val traps = list.mapNotNull { sc ->
            findMonotonicTrap(sc.expect)?.let { desc -> "「${sc.name}」的 ${sc.expect}（${desc}）" }
        }
        if (traps.isNotEmpty()) {
            return false to "功能断言存在结构性不可满足陷阱：" + traps.joinToString("；") +
                "。gold/score/kills/coins 等收入得分类字段在游戏进程中只增不减，配绝对值上界" +
                "（s.gold < 200）会因收入累积而永不满足。请改写为与增长方向一致的行为断言" +
                "（如建造后 s.towers 增加、击杀后 s.kills 增加），或相对断言：在 __wwDebugState" +
                " 暴露初始值/最近花费字段后比较相对变化（s.gold < s.goldInitial）。"
        }
        val summary = list.joinToString("；") { sc ->
            "${sc.system.ifBlank { "通用" }}/${sc.name}(${sc.steps.size}步:${sc.expect.take(60)})"
        }
        return true to "功能断言文件检查：解析成功，共 ${list.size} 条——$summary。" +
            "执行器按序派发输入并在期限（默认 ${HORIZON_DEFAULT_FRAMES} 帧≈15 秒游戏时间）内逐帧求值，任一帧为真即通过；" +
            "引用的快照字段必须真实存在（不存在会立即回报可用字段清单），失败回报自带字段轨迹。"
    }

    /** 恒真/近恒真子式检测：这类断言无法失败，写了等于没测（实测出现过 entities.length>=0）。 */
    private fun containsTautology(expect: String): Boolean =
        Regex(""">=\s*0\s*(?![.\d])""").containsMatchIn(expect) ||
            Regex("""!==?\s*(?:undefined|null)\b""", RegexOption.IGNORE_CASE).containsMatchIn(expect) ||
            Regex("""typeof\s+\S{1,40}?\s*!==?\s*['"]undefined['"]""").containsMatchIn(expect)

    /**
     * 单调递增字段 × 绝对值上界的结构性不可满足检测：命中返回字段说明（用于回报）。
     * 只拦"<"/"<="方向：递增字段要求小于某绝对数，收入累积后永不满足；">"方向
     * （s.score > 0）与递减字段（s.lives < 3——生命只减）都是合法可满足断言，不拦。
     */
    private fun findMonotonicTrap(expect: String): String? {
        val monotonicFields = setOf(
            "gold", "coins", "coin", "money", "cash", "score", "points",
            "kills", "kill", "killsTotal", "wave", "waveNum", "level",
            "xp", "exp", "earned", "income", "gems", "diamonds"
        )
        val re = Regex("""s\.([A-Za-z_$][\w$]*)\s*<=?\s*(\d+)""")
        re.findAll(expect).forEach { m ->
            val field = m.groupValues[1]
            if (monotonicFields.any { it.equals(field, ignoreCase = true) }) {
                return "s.$field 为收入/得分类只增字段，绝对值上界 ${m.groupValues[0]} 会因累积而不可满足"
            }
        }
        return null
    }

    /** 容错提取 JSON 数组文本：支持裸数组或 {"scenarios":[...]} 包装。 */
    private fun extractArray(text: String): String? {
        val arrStart = text.indexOf('[')
        if (arrStart < 0) return null
        val arrEnd = text.lastIndexOf(']')
        if (arrEnd <= arrStart) return null
        return text.substring(arrStart, arrEnd + 1)
    }

    private fun sanitize(raw: RawScenario): GameScenario? {
        val expect = raw.expect?.trim().orEmpty()
        if (expect.isEmpty() || expect.length > MAX_EXPECT_LEN) return null
        val label = raw.name?.trim().orEmpty().ifEmpty { raw.id?.trim().orEmpty() }
        if (label.isEmpty()) return null
        val steps = raw.steps.orEmpty().mapNotNull { step ->
            val tap = step.tap?.take(2)?.map { it.coerceIn(0.0, 100.0) }
            when {
                tap != null && tap.size == 2 -> ScenarioStep(tap = tap)
                step.drag != null && step.drag.size >= 4 ->
                    ScenarioStep(drag = step.drag.take(4).map { it.coerceIn(0.0, 100.0) })
                step.frames != null ->
                    ScenarioStep(frames = step.frames.coerceIn(1, HORIZON_MAX_FRAMES))
                else -> null
            }
        }.take(MAX_STEPS)
        return GameScenario(
            id = raw.id?.trim().orEmpty().ifEmpty { label },
            system = raw.system?.trim().orEmpty(),
            name = label,
            steps = steps,
            expect = expect,
            horizon = raw.horizon?.coerceIn(1, HORIZON_MAX_FRAMES)
        )
    }

    @Serializable
    private data class RawScenario(
        val id: String? = null,
        val system: String? = null,
        val name: String? = null,
        val steps: List<RawStep>? = null,
        val expect: String? = null,
        val horizon: Int? = null
    )

    @Serializable
    private data class RawStep(
        val tap: List<Double>? = null,
        val drag: List<Double>? = null,
        val frames: Int? = null
    )
}
