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
 * 与 instruct.txt 第二章对齐，意图层三分类：
 * - new feature：首次创建游戏或后期追加功能，read/write，可调用工具开发游戏文件；
 * - fix bug：后期修复游戏异常 bug，read/write，可调用工具开发游戏文件，游戏功能特性不变；
 * - chat：纯文本聊天/询问，readonly，不改动任何外部文件，也不改变游戏特性。
 * 游戏特征抽取已分离到 GameRecognition.kt 的识别层。
 */
@Serializable
data class IntentDecision(
    val intent: String = INTENT_NEW_FEATURE,
    val confidence: Double = 0.0,
    val reason: String = ""
) {
    companion object {
        const val INTENT_NEW_FEATURE = "new_feature"
        const val INTENT_FIX_BUG = "fix_bug"
        const val INTENT_CHAT = "chat"
    }

    /** dev 意图统称：new feature 与 fix bug 都会进入开发流程并可读写游戏文件。 */
    val isDevelop: Boolean get() = isNewFeature || isFixBug
    val isNewFeature: Boolean get() = intent == INTENT_NEW_FEATURE
    val isFixBug: Boolean get() = intent == INTENT_FIX_BUG
    val isChat: Boolean get() = intent == INTENT_CHAT
}

/**
 * 意图层：区分“新增功能（new_feature）”“修复缺陷（fix_bug）”和“文本聊天（chat）”。
 * 识别层（GameRecognition.kt）在 new_feature 分支中负责抽取/补全 Game Schema JSON；
 * fix_bug 分支由 GameAgent 直接进入 Agent Loop 修复，不改变游戏功能特性。
 */
object IntentLayer {
    /** 强烈的“制作/新建游戏”指令：命中即 new_feature。 */
    private val createGamePattern = Regex(
        "(做一个?|制作|创建|生成|开发|设计|写一个?|来一个?|来个|给我做一个?|帮我做一个?|我想要|想要|要一个).{0,12}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|计分|音游|2D|2.5D|3D|横板|横版|竖版|竖屏|横屏)"
    )

    /** 追加/改进功能的开发指令（不含修复类词汇）：new_feature。 */
    private val featureCommandPattern = Regex(
        "(加一个?|加个|增加|添加|新增|加上|改成?|修改|调整|优化|继续|迭代|重做|换一个?|换个|升级).{0,12}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|分数|计分|音效|背景|颜色|金币|敌人|道具|技能|商店|连击|生命|操作|2D|2.5D|3D|横板|横版|竖版|竖屏|横屏)"
    )

    /** 独立的缺陷/异常词：出现即强烈暗示 fix_bug。 */
    private val bugSymptomPattern = Regex(
        "bug|报错|出错|运行时错误|异常|闪退|崩溃|卡死|卡住|卡顿|点不动|点不了|没反应|无反应|不响应|不起作用|不管用|失灵|黑屏|白屏|坏掉|坏了|不能玩|玩不了|进不去|没声音",
        RegexOption.IGNORE_CASE
    )

    /** 修复指令 + 缺陷对象（含“修复游戏”这类省略缺陷词的说法）：fix_bug。 */
    private val fixCommandPattern = Regex(
        "(修复|修一下|修个|修理|改正|fix).{0,12}(bug|错误|问题|异常|报错|故障|闪退|黑屏|白屏|卡|没反应|无反应|点不动|游戏)",
        RegexOption.IGNORE_CASE
    )

    private val chatOnlyPattern = Regex(
        "^(你好|您好|hi|hello|在吗|你是谁|你能做什么|你会什么|怎么用|使用说明|谢谢|感谢|再见|帮助|介绍|什么是|为什么|能不能教我|教我)"
    )

    /** 纯正则意图分类；低置信度时由 Lite LLM 复核。 */
    fun inferLocally(userText: String): IntentDecision {
        val text = userText.trim()
        val createGame = createGamePattern.containsMatchIn(text)
        val featureCommand = featureCommandPattern.containsMatchIn(text)
        val fixBug = bugSymptomPattern.containsMatchIn(text) || fixCommandPattern.containsMatchIn(text)
        val chatOnly = chatOnlyPattern.containsMatchIn(text) ||
            (text.length <= 12 && Regex("^(什么|怎么|如何|能不能|可以|能|会|是)").containsMatchIn(text))

        return when {
            // 制作新游戏 / 追加功能的指令优先于修复词：
            // “做一个修汽车的装修游戏”应走 new_feature，而不是被“修”字误判成 fix_bug。
            createGame || featureCommand -> IntentDecision(
                intent = IntentDecision.INTENT_NEW_FEATURE,
                confidence = 0.9,
                reason = "命中制作游戏或追加功能的开发指令"
            )
            fixBug -> IntentDecision(
                intent = IntentDecision.INTENT_FIX_BUG,
                confidence = 0.9,
                reason = "命中修复游戏异常/缺陷的指令"
            )
            chatOnly -> IntentDecision(
                intent = IntentDecision.INTENT_CHAT,
                confidence = 0.9,
                reason = "命中纯聊天/提问句式"
            )
            else -> IntentDecision(
                intent = IntentDecision.INTENT_NEW_FEATURE,
                confidence = 0.55,
                reason = "未命中聊天或缺陷句式，默认按开发意图处理，由识别层复核"
            )
        }
    }

    /** 意图层 Lite LLM 提示词：只输出 new_feature / fix_bug / chat 三分类。 */
    fun liteLlmPrompt(userText: String): String = """
        你是对话意图分类器，只做三分类，不抽取任何游戏特征。
        请只输出一个 JSON 对象（不要 Markdown、不要解释）：
        {
          "intent": "new_feature | fix_bug | chat",
          "confidence": 0.0,
          "reason": "简短原因"
        }
        - new_feature：用户想首次制作一个游戏，或给现有游戏追加/改进功能（可读写游戏文件）；
        - fix_bug：用户想修复现有游戏的异常、报错或缺陷，游戏功能特性保持不变（可读写游戏文件）；
        - chat：用户只是聊天、询问平台能力、寒暄或问一个普通问题（不改动任何文件，纯文本回复）。
        用户消息：$userText
    """.trimIndent()

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

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
            IntentDecision.INTENT_FIX_BUG, "fixbug", "fix", "bug", "repair" -> IntentDecision.INTENT_FIX_BUG
            // 兼容旧二分类时期 LLM 可能输出的 develop / dev 标签：开发意图统一落到 new_feature。
            IntentDecision.INTENT_NEW_FEATURE, "new", "newgame", "new_game", "feature", "develop", "dev", "develope", "development" ->
                IntentDecision.INTENT_NEW_FEATURE
            else -> return null
        }
        val confidence = (obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.5
        return IntentDecision(
            intent = intent,
            confidence = confidence.coerceIn(0.0, 1.0),
            reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
        )
    }
}
