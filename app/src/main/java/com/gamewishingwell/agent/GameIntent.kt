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
 * 默认偏 dev：dev 误判成 chat 会让用户的开发请求被一句回复打发（致命），
 * chat 误判成 dev 只多花一次识别层调用然后回退聊天（温和）。
 */
object IntentLayer {
    /** 强烈的“制作/新建游戏”指令：命中即 dev。 */
    private val createGamePattern = Regex(
        "(做一个?|制作|创建|生成|开发|设计|写一个?|来一个?|来个|给我做一个?|帮我做一个?|我想要|想要|要一个).{0,12}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|计分|音游|2D|2.5D|3D|横板|横版|竖版|竖屏|横屏)"
    )

    /** 追加/改进/调整类开发指令（含口语化的修复诉求——修复不再单独分类）：dev。 */
    private val featureCommandPattern = Regex(
        "(加一个?|加个|增加|添加|新增|加上|改成?|修改|调整|优化|继续|迭代|重做|换一个?|换个|升级|修复|修一下|修个|简单一点|难一点|快点|慢点).{0,12}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|分数|计分|音效|音乐|背景|颜色|金币|敌人|道具|技能|商店|连击|生命|操作|手感|节奏|速度|2D|2.5D|3D|横板|横版|竖版|竖屏|横屏)?"
    )

    /**
     * 纯聊天/平台问句：必须整句以问候或平台问法开头才判 chat。
     * 不做“短消息 + 疑问前缀”这类宽松匹配——那会把“能不能简单一点”这类
     * 开发诉求误判成 chat 且以 0.9 置信度短路掉 LLM 复核；模糊短消息
     * 一律落入默认 dev 分支交给 Lite LLM 判断。
     */
    private val chatOnlyPattern = Regex(
        "^(你好|您好|hi|hello|在吗|你是谁|你能做什么|你会什么|怎么用|使用说明|谢谢|感谢|再见|帮助|介绍|什么是|为什么|能不能教我|教我)[?？。!！\\s]*$"
    )

    /** 纯正则意图分类；低置信度时由 Lite LLM 复核。 */
    fun inferLocally(userText: String): IntentDecision {
        val text = userText.trim()
        val develop = createGamePattern.containsMatchIn(text) || featureCommandPattern.containsMatchIn(text)
        val chatOnly = chatOnlyPattern.containsMatchIn(text)

        return when {
            develop -> IntentDecision(
                intent = IntentDecision.INTENT_DEV,
                confidence = 0.9,
                reason = "命中开发指令（制作/追加/改进/调整/修复游戏）"
            )
            chatOnly -> IntentDecision(
                intent = IntentDecision.INTENT_CHAT,
                confidence = 0.9,
                reason = "命中纯聊天/平台问句"
            )
            else -> IntentDecision(
                intent = IntentDecision.INTENT_DEV,
                confidence = 0.55,
                reason = "未命中聊天句式，默认按开发意图处理，由识别层复核"
            )
        }
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
        - chat：用户只是聊天、寒暄、询问平台能力，或问一个不希望改动游戏的普通问题（不改任何文件，纯文本回复）。
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
