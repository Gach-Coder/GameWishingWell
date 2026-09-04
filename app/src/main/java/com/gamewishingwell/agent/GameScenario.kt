package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 功能断言（场景回归测试）：把策划验收边界固化为可执行断言，由沙箱确定性裁决。
 *
 * 吸收 Generator-Critic 工作流的语义验证层，但把裁决权从 LLM Critic 交给确定性执行：
 * 每条断言 = 输入脚本（视口百分比点按/拖动）+ 确定性帧推进（复用探针 tick 队列，
 * 每帧 50ms 游戏时间）+ 针对 __wwDebugState() 快照的布尔表达式，全部在真实
 * Chromium 内核里求值——"LLM 提出（模型编写断言），确定门裁决（探针执行）"。
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
    /** 确定性推进 N 帧（每帧 50ms 游戏时间）。 */
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
    /** 针对 s（= __wwDebugState() 返回快照）的布尔表达式，如 s.score > 0。 */
    val expect: String
)

object GameScenarios {

    const val FILE = "scenarios.json"
    const val MAX_SCENARIOS = 6
    const val MAX_STEPS = 12
    const val MAX_FRAMES_PER_SCENARIO = 40
    const val MAX_EXPECT_LEN = 300
    const val DEFAULT_FRAMES = 24

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
                "{\"scenarios\":[{\"id\":\"...\",\"system\":\"...\",\"name\":\"...\",\"steps\":[{\"tap\":[50,80]},{\"frames\":24}],\"expect\":\"s.score > 0\"}]}；" +
                "expect 为针对 __wwDebugState() 快照 s 的布尔表达式，且不得为空或超过 $MAX_EXPECT_LEN 字符。"
        }
        val weak = list.filter { containsTautology(it.expect) }
        if (weak.isNotEmpty()) {
            return false to "功能断言包含恒真/近恒真子式（无法失败，等于没测）：" +
                weak.joinToString("；") { "「${it.name}」的 ${it.expect}" } +
                "。禁止 >=0（计数/长度天然非负）、!== undefined / !== null / typeof 判存在这类写法；" +
                "请改写为可失败的数值变化或状态迁移断言（如 s.score > 0、s.wave >= 1、s.state === 'playing'）。"
        }
        val summary = list.joinToString("；") { sc ->
            "${sc.system.ifBlank { "通用" }}/${sc.name}(${sc.steps.size}步:${sc.expect.take(60)})"
        }
        return true to "功能断言文件检查：解析成功，共 ${list.size} 条——$summary。" +
            "沙箱交付验收时会逐条确定性执行（点按/拖动→推进帧→比对快照），未通过将以 scenario-fail 回传。"
    }

    /** 恒真/近恒真子式检测：这类断言无法失败，写了等于没测（实测出现过 entities.length>=0）。 */
    private fun containsTautology(expect: String): Boolean =
        Regex(""">=\s*0\s*(?![.\d])""").containsMatchIn(expect) ||
            Regex("""!==?\s*(?:undefined|null)\b""", RegexOption.IGNORE_CASE).containsMatchIn(expect) ||
            Regex("""typeof\s+\S{1,40}?\s*!==?\s*['"]undefined['"]""").containsMatchIn(expect)

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
                    ScenarioStep(frames = step.frames.coerceIn(1, MAX_FRAMES_PER_SCENARIO))
                else -> null
            }
        }.take(MAX_STEPS)
        return GameScenario(
            id = raw.id?.trim().orEmpty().ifEmpty { label },
            system = raw.system?.trim().orEmpty(),
            name = label,
            steps = steps.ifEmpty { listOf(ScenarioStep(frames = DEFAULT_FRAMES)) },
            expect = expect
        )
    }

    @Serializable
    private data class RawScenario(
        val id: String? = null,
        val system: String? = null,
        val name: String? = null,
        val steps: List<RawStep>? = null,
        val expect: String? = null
    )

    @Serializable
    private data class RawStep(
        val tap: List<Double>? = null,
        val drag: List<Double>? = null,
        val frames: Int? = null
    )
}
