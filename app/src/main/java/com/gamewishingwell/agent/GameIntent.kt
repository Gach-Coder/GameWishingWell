package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 意图层输出：IntentDecision JSON。
 *
 * 二分类：dev（创建/追加/改进/调整/修复——一切希望游戏发生变化的诉求，
 * read/write，可调用工具开发游戏文件）与 chat（纯文本问答/寒暄，readonly，
 * 不改动任何外部文件）。
 *
 * 修复的“快车道”不经文本分类，由会话状态推导（已锚定游戏 + 存在未消化的
 * 运行时错误签名），见 GameAgent.runUserTurn；feature/bug 之分下推给
 * 策划层与 Agent Loop——它们看得到代码与用户诉求，判得更准。
 */
@Serializable
data class IntentDecision(
    val intent: String = INTENT_DEV,
    val confidence: Double = 0.0,
    val reason: String = ""
) {
    companion object {
        const val INTENT_DEV = "dev"
        const val INTENT_CHAT = "chat"
    }

    /** 二分类语义：不是 chat 就是 dev（对旧持久化会话里的 new_feature/fix_bug 值也成立）。 */
    val isDevelop: Boolean get() = !isChat
    val isChat: Boolean get() = intent == INTENT_CHAT
}

/**
 * 意图层：区分“要让游戏变化（dev）”和“纯文本问答（chat）”这一个问题。
 * 判定只走 LLM 单次调用（[liteLlmPrompt] + [parseLiteLlmReply]）——历史版本曾用
 * 正则关键词先行短路（dev 动词表/聊天句式/否定窗口/元问题特征四组正则），但正则
 * 无法区分“关闭弹窗”是诉求还是被谈论的对象、也无法覆盖口语化表达，误判的代价
 * 是整轮 Agent Loop 白跑或开发请求被一句回复打发。正则已全部删除；
 * [fallback] 仅在 LLM 不可用/输出不可解析时兜底——默认偏 dev：dev 误判成 chat
 * 会让用户的开发请求被一句回复打发（致命），chat 误判成 dev 只多花一轮识别
 * 然后回退聊天（温和）。
 */
object IntentLayer {

    /** LLM 意图判定的兜底结果（LLM 失败/低置信/不可解析时）：按 dev 处理。 */
    fun fallback(reason: String = "意图识别不可用，按开发意图处理"): IntentDecision =
        IntentDecision(
            intent = IntentDecision.INTENT_DEV,
            confidence = 0.5,
            reason = reason
        )

    /**
     * 开发动词快速通道（仅已锚定游戏的会话使用，跳过意图二分类的 LLM 往返省 ~3s/回合）：
     * 指令以明确的开发/修改动词开头且不是疑问句 → 直接判 dev。只向 dev 方向短路——
     * 白名单不命中或疑似提问（句尾问号）一律照走 LLM 分类，误判面收敛在
     * "把一个 chat 问题判成 dev"（迭代循环一轮内即可自我纠正为纯文本答复）。
     */
    private val devVerbStartRegex = Regex(
        "^(新增|加入|添加|加上|补充|修改|更改|调整|删|移除|去掉|修复|优化|重构|实现|支持|配置|" +
            "更新|替换|完善|美化|强化|削弱|放大|缩小|固定|隐藏|显示|开启|关闭|禁用|启用|" +
            "恢复|重做|导入|加|改|修)一下?"
    )

    fun devVerbFastPath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.endsWith("？") || t.endsWith("?")) return false
        return devVerbStartRegex.containsMatchIn(t)
    }

    /** 意图层 Lite LLM 提示词：只做 dev / chat 二分类。 */
    fun liteLlmPrompt(userText: String): String = """
        你是对话意图分类器，只做二分类，不抽取任何游戏特征。
        请只输出一个 JSON 对象（不要 Markdown、不要解释）：
        {
          "intent": "dev | chat",
          "confidence": 0.0,
          "reason": "简短原因"
        }
        - dev：用户想创建游戏，或对现有游戏做任何改动（追加/改进/调整/修复异常均算，可读写游戏文件）；
        - chat：用户只是聊天、寒暄、询问平台能力，或问一个不希望改动游戏的普通问题（不改任何文件，可只读查询游戏代码后作答，纯文本回复）。
        用户消息：$userText
    """.trimIndent()

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** LLM 自报置信度低于该值时视为不可信，返回 null 让调用方回落本地默认（dev）。 */
    private const val MIN_LLM_CONFIDENCE = 0.6

    fun parseLiteLlmReply(raw: String): IntentDecision? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        val obj = try {
            lenientJson.parseToJsonElement(cleaned.substring(start)).jsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val rawIntent = (obj["intent"] as? JsonPrimitive)?.contentOrNull
        val intent = when (rawIntent?.lowercase()) {
            IntentDecision.INTENT_CHAT -> IntentDecision.INTENT_CHAT
            // 历史标签归一：旧三分类的 new_feature/fix_bug 与旧二分类的 develop
            // 系标签都表示“要动游戏”，统一落到 dev。
            IntentDecision.INTENT_DEV, "new", "newgame", "new_game", "feature", "new_feature",
            "develop", "dev", "develope", "development", "fix_bug", "fixbug", "fix", "bug", "repair" ->
                IntentDecision.INTENT_DEV
            else -> return null
        }
        val confidence = (obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.5
        if (confidence < MIN_LLM_CONFIDENCE) return null
        return IntentDecision(
            intent = intent,
            confidence = confidence.coerceIn(0.0, 1.0),
            reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
        )
    }
}
