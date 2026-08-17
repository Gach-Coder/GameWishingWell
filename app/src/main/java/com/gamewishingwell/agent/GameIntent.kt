package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 意图层输出：IntentDecision JSON（新流程）。
 *
 * 与 instruct.txt 第二章对齐：意图层只做 develop / chat 二分类：
 * - develop：用户想制作或改进游戏，后续进入识别层并可读写游戏文件；
 * - chat：纯文本聊天/询问，readonly，不写文件、也不修改 Game Schema。
 * 游戏特征抽取已分离到 GameRecognition.kt 的识别层。
 */
@Serializable
data class IntentDecision(
    val intent: String = INTENT_DEVELOP,
    val confidence: Double = 0.0,
    val reason: String = ""
) {
    companion object {
        const val INTENT_DEVELOP = "develop"
        const val INTENT_DEVELOPE = INTENT_DEVELOP
        const val INTENT_DEV = INTENT_DEVELOP
        const val INTENT_CHAT = "chat"
    }

    val isDevelop: Boolean get() = intent == INTENT_DEVELOP
    val isChat: Boolean get() = intent == INTENT_CHAT
}

/**
 * 意图层：只区分“开发游戏（develop）”和“文本聊天（chat）”。
 * 识别层（GameRecognition.kt）在 develop 分支中负责抽取/补全 Game Schema JSON。
 */
object IntentLayer {
    private val devCommandPattern = Regex(
        "(做一个?|制作|创建|生成|开发|设计|写|给我|帮我|想要?|要一个|来个|加一个?|加个|增加|添加|新增|改成?|修改|调整|优化|修复|继续|迭代|重做|换一个?|来一个?).{0,12}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|分数|音效|背景|颜色|2D|2.5D|3D|横板|横版|竖版|竖屏|横屏)"
    )
    private val chatOnlyPattern = Regex(
        "^(你好|您好|hi|hello|在吗|你是谁|你能做什么|你会什么|怎么用|使用说明|谢谢|感谢|再见|帮助|介绍|什么是|为什么|能不能教我|教我)"
    )

    /** 纯正则意图分类；低置信度时由 Lite LLM 复核。 */
    fun inferLocally(userText: String): IntentDecision {
        val text = userText.trim()
        val devCommand = devCommandPattern.containsMatchIn(text)
        val explicitDev = Regex(
            "(做|制作|创建|生成|开发|设计|写|加|改|修改|增加|添加|删除|去掉|调整|优化|修复|继续|迭代|重做|换).{0,10}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|分数|音效|背景|颜色|计分|金币|敌人|道具|技能|商店|2D|2.5D|3D|横板|横版|竖版|竖屏|横屏)"
        ).containsMatchIn(text)
        val chatOnly = chatOnlyPattern.containsMatchIn(text) ||
            (text.length <= 12 && Regex("^(什么|怎么|如何|能不能|可以|能|会|是)").containsMatchIn(text))

        return when {
            devCommand || explicitDev -> IntentDecision(
                intent = IntentDecision.INTENT_DEVELOP,
                confidence = 0.9,
                reason = "命中制作/修改游戏的开发指令"
            )
            chatOnly -> IntentDecision(
                intent = IntentDecision.INTENT_CHAT,
                confidence = 0.9,
                reason = "命中纯聊天/提问句式"
            )
            else -> IntentDecision(
                intent = IntentDecision.INTENT_DEVELOP,
                confidence = 0.55,
                reason = "未命中聊天句式，默认按开发意图处理，由识别层复核"
            )
        }
    }

    /** 意图层 Lite LLM 提示词：只输出 develop / chat 二分类。 */
    fun liteLlmPrompt(userText: String): String = """
        你是对话意图分类器，只做二分类，不抽取任何游戏特征。
        请只输出一个 JSON 对象（不要 Markdown、不要解释）：
        {
          "intent": "develop | chat",
          "confidence": 0.0,
          "reason": "简短原因"
        }
        - develop：用户想制作、创建、修改或改进一个游戏；
        - chat：用户只是聊天、询问平台能力、寒暄或问一个普通问题。
        用户消息：$userText
    """.trimIndent()

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
            Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(cleaned.substring(start)).jsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val rawIntent = (obj["intent"] as? JsonPrimitive)?.contentOrNull
        val intent = when (rawIntent?.lowercase()) {
            IntentDecision.INTENT_CHAT -> IntentDecision.INTENT_CHAT
            "dev", "develop", "develope", "development" -> IntentDecision.INTENT_DEVELOP
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
