package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 旧版三意图 Intent Schema：仅用于兼容历史持久化数据与旧单元测试。
 * 新流程的意图层只输出 [IntentDecision]；游戏特征统一存到 [GameSchema]。
 */
@Serializable
data class IntentSchema(
    val intent: String = INTENT_NEW_GAME,
    val visualDimension: String = DIMENSION_2D,
    val screenOrientation: String = ORIENTATION_PORTRAIT,
    val gameSystems: List<String> = emptyList(),
    val referenceGame: String? = null,
    val templateId: String? = null,
    val templateSimilarity: Double? = null,
    val confidence: Double = 0.0,
    /** 玩家明确说“不要/去掉”的系统，策划层必须排除，模板补全也不得加回。 */
    val excludedSystems: List<String> = emptyList(),
    /**
     * true 表示 templateId 已经由确认门修正合并显式决定；
     * validate 不再根据 referenceGame 反向重新匹配模板，避免把玩家刚去掉的模板补全系统加回来。
     */
    val lockTemplateResolution: Boolean = false
) {
    companion object {
        const val INTENT_NEW_GAME = "new_game"
        const val INTENT_MODIFY_GAME = "modify_game"
        const val INTENT_CHAT = "chat"

        const val DIMENSION_2D = "2D"
        const val DIMENSION_2_5D = "2.5D"
        const val DIMENSION_3D = "3D"

        const val ORIENTATION_LANDSCAPE = "横板"
        const val ORIENTATION_PORTRAIT = "竖版"
    }
}

object IntentSchemaValidator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * JSON Schema + 枚举白名单校验。
     * 规则：
     * - intent 必须是 new_game / modify_game / chat，否则回退 new_game；
     * - visualDimension 必须是 2D / 2.5D / 3D，否则映射 2D；
     * - screenOrientation 必须是 横板 / 竖版，否则映射 竖版；
     * - gameSystems 逐项白名单过滤，模板未命中的系统直接丢弃；
     * - excludedSystems 逐项白名单过滤，且不得与 gameSystems 并存；
     * - templateId 不在模板库时置 null，不保留模型幻觉出来的 id。
     */
    fun validate(schema: IntentSchema): IntentSchema {
        val intent = when (schema.intent) {
            IntentSchema.INTENT_NEW_GAME,
            IntentSchema.INTENT_MODIFY_GAME,
            IntentSchema.INTENT_CHAT -> schema.intent
            else -> IntentSchema.INTENT_NEW_GAME
        }
        val dimension = if (schema.visualDimension in setOf(
                IntentSchema.DIMENSION_2D,
                IntentSchema.DIMENSION_2_5D,
                IntentSchema.DIMENSION_3D
            )
        ) schema.visualDimension else IntentSchema.DIMENSION_2D
        val orientation = if (schema.screenOrientation in setOf(
                IntentSchema.ORIENTATION_LANDSCAPE,
                IntentSchema.ORIENTATION_PORTRAIT
            )
        ) schema.screenOrientation else IntentSchema.ORIENTATION_PORTRAIT

        val excludedSystems = schema.excludedSystems
            .filter { GameSystemCatalog.isValid(it) }
            .distinct()
        val systems = schema.gameSystems
            .filter { GameSystemCatalog.isValid(it) && it !in excludedSystems }
            .distinct()
        val template = schema.templateId?.let { TemplateLibrary.findById(it) }
        val matched = if (schema.lockTemplateResolution) {
            null
        } else {
            TemplateLibrary.match(schema.referenceGame.orEmpty())
        }
        val templateId = if (template != null) template.id else matched?.first?.id
        val similarity = if (template != null) schema.templateSimilarity else matched?.second

        return IntentSchema(
            intent = intent,
            visualDimension = dimension,
            screenOrientation = orientation,
            gameSystems = systems,
            referenceGame = schema.referenceGame?.takeIf { it.isNotBlank() },
            templateId = templateId,
            templateSimilarity = similarity?.coerceIn(0.0, 1.0),
            confidence = schema.confidence.coerceIn(0.0, 1.0),
            excludedSystems = excludedSystems,
            lockTemplateResolution = schema.lockTemplateResolution
        )
    }

    /** 解析 Lite LLM 输出的 JSON（只接受 JSON object）。 */
    fun parseLiteLlmReply(raw: String): IntentSchema? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        val obj = try {
            json.parseToJsonElement(cleaned.substring(start)).jsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val intent = obj.string("intent") ?: IntentSchema.INTENT_NEW_GAME
        val systems = obj["gameSystems"]?.let { arr ->
            (arr as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } ?: emptyList()
        val reference = obj.string("referenceGame")
        val excludedSystems = obj["excludedSystems"]?.let { arr ->
            (arr as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } ?: emptyList()
        return validate(
            IntentSchema(
                intent = intent,
                visualDimension = obj.string("visualDimension") ?: IntentSchema.DIMENSION_2D,
                screenOrientation = obj.string("screenOrientation") ?: IntentSchema.ORIENTATION_PORTRAIT,
                gameSystems = systems,
                excludedSystems = excludedSystems,
                referenceGame = reference,
                templateId = obj.string("templateId"),
                templateSimilarity = obj.double("templateSimilarity"),
                confidence = obj.double("confidence") ?: 0.0
            )
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * 正则 + Lite LLM 意图抽取入口。
 * 这里只放不依赖网络的正则部分；GameAgent 会先用正则，再用 Lite LLM
 * 修正低置信度字段，最后统一走 [IntentSchemaValidator]。
 */
object IntentEngine {
    /** 新意图层便捷入口：返回 new_feature / fix_bug / chat 三分类结果。 */
    fun infer(userText: String): IntentDecision = IntentLayer.inferLocally(userText)

    private val modifyPattern = Regex(
        "修改|改一下|改成|加上|加个|增加|添加|去掉|删除|调整|优化|修复|继续|迭代|" +
            "更难点|更难|简单点|简单些|背景|颜色|音效|分数|关卡|加连击|加.*系统"
    )
    private val chatPattern = Regex("^(你好|您好|hi|hello|在吗|你是谁|你能做什么|怎么用|使用说明|谢谢)")

    fun infer(userText: String, existingHtml: String?): IntentSchema {
        val text = userText.trim()
        val hasExisting = !existingHtml.isNullOrBlank()
        val intent = when {
            chatPattern.containsMatchIn(text) && !Regex("做|游戏|生成|创建|加|改").containsMatchIn(text) -> IntentSchema.INTENT_CHAT
            hasExisting -> IntentSchema.INTENT_MODIFY_GAME
            else -> IntentSchema.INTENT_NEW_GAME
        }

        val dimension = explicitDimension(text) ?: IntentSchema.DIMENSION_2D
        val orientation = explicitOrientation(text) ?: IntentSchema.ORIENTATION_PORTRAIT
        val excludedSystems = negatedSystems(text).toList()
        val systems = GameSystemCatalog.extract(text).filterNot { it in excludedSystems }
        val match = TemplateLibrary.match(text)
        val confidence = (
            0.55 +
                (if (dimension != IntentSchema.DIMENSION_2D || explicitDimension(text) != null) 0.1 else 0.0) +
                (if (orientation != IntentSchema.ORIENTATION_PORTRAIT || explicitOrientation(text) != null) 0.1 else 0.0) +
                (systems.size * 0.05).coerceAtMost(0.2) +
                (if (match != null) 0.15 else 0.0)
            ).coerceIn(0.0, 1.0)

        return IntentSchemaValidator.validate(
            IntentSchema(
                intent = intent,
                visualDimension = dimension,
                screenOrientation = orientation,
                gameSystems = systems,
                excludedSystems = excludedSystems,
                referenceGame = match?.first?.title,
                templateId = match?.first?.id,
                templateSimilarity = match?.second,
                confidence = confidence
            )
        )
    }

    /** 确认门最终要实现的系统集合：用户明确系统 + 模板补全系统；已排除系统一律不得加回。 */
    fun plannedSystems(schema: IntentSchema): List<String> {
        val ref = schema.templateId?.let { TemplateLibrary.findById(it) }
        val excluded = schema.excludedSystems.toSet()
        val merged = (schema.gameSystems + (ref?.suggestedSystems ?: emptyList()))
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
            .distinct()
        return merged.ifEmpty { DEFAULT_SYSTEMS.filterNot { it in excluded } }
    }

    /**
     * 确认门上的用户修正合并：显式字段以玩家修正为准，未提到的字段继承已确认结果；
     * 玩家明确“不要/去掉”的系统会从模板补全与默认集合中一并移除。
     */
    fun mergeCorrection(confirmed: IntentSchema, correction: IntentSchema, correctionText: String): IntentSchema {
        val dimension = if (explicitDimension(correctionText) != null) correction.visualDimension else confirmed.visualDimension
        val orientation = if (explicitOrientation(correctionText) != null) correction.screenOrientation else confirmed.screenOrientation

        val removed = negatedSystems(correctionText) + correction.excludedSystems.toSet()
        // 玩家明确“还是要/要加/恢复”已排除系统时，允许把该系统从排除清单里拿回来。
        val reinstated = reAddSystems(correctionText)
        val excludedSystems = (confirmed.excludedSystems.toSet() + removed - reinstated).toList()
        val correctionMatch: Pair<GameTemplateRef, Double>? = TemplateLibrary.match(correctionText)
            ?: correction.templateId?.let { id ->
                TemplateLibrary.findById(id)?.let { it to (correction.templateSimilarity ?: 0.5) }
            }
        val dropsReference = negatesReference(correctionText)
        val baseline = when {
            // 明确换对标游戏：系统基线切到新模板，避免旧模板系统被带过来。
            correctionMatch != null && !dropsReference -> correctionMatch.first.suggestedSystems
            // 明确不要参考：只保留玩家已经明确过的系统，不再套模板默认值。
            dropsReference -> confirmed.gameSystems.ifEmpty { DEFAULT_SYSTEMS }
            else -> plannedSystems(confirmed)
        }
        val systems = (baseline + correction.gameSystems)
            .filterNot { removed.contains(it) }
            .filterNot { excludedSystems.contains(it) }
            .distinct()

        // 只删掉模板中的某一个系统时不再整个丢掉模板：excludedSystems 已保证
        // 被删系统不会被模板补全加回，其余系统继续使用该对标游戏的具体实现细节。
        val referenceGame: String?
        val templateId: String?
        val templateSimilarity: Double?
        when {
            dropsReference -> {
                referenceGame = null
                templateId = null
                templateSimilarity = null
            }
            correctionMatch != null -> {
                referenceGame = correctionMatch.first.title
                templateId = correctionMatch.first.id
                templateSimilarity = correctionMatch.second
            }
            else -> {
                referenceGame = confirmed.referenceGame
                templateId = confirmed.templateId
                templateSimilarity = confirmed.templateSimilarity
            }
        }

        return IntentSchemaValidator.validate(
            IntentSchema(
                intent = confirmed.intent,
                visualDimension = dimension,
                screenOrientation = orientation,
                gameSystems = systems,
                excludedSystems = excludedSystems,
                referenceGame = referenceGame,
                templateId = templateId,
                templateSimilarity = templateSimilarity,
                confidence = maxOf(confirmed.confidence, correction.confidence),
                lockTemplateResolution = true
            )
        )
    }

    /** 供策划层判断修改旧游戏时是否需要继承上一版的画面维度。 */
    fun explicitlySpecifiesDimension(text: String): Boolean = explicitDimension(text) != null

    /** 供策划层判断修改旧游戏时是否需要继承上一版的画面方向。 */
    fun explicitlySpecifiesOrientation(text: String): Boolean = explicitOrientation(text) != null

    fun explicitDimension(text: String): String? = when {
        Regex("2\\.5\\s*d|伪3d|斜45|2.5D", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.DIMENSION_2_5D
        Regex("(?<!2\\.5)3d|三维|立体", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.DIMENSION_3D
        Regex("(?<![A-Za-z0-9])2d(?![A-Za-z0-9])|二维|平面", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.DIMENSION_2D
        else -> null
    }

    fun explicitOrientation(text: String): String? = when {
        Regex("横板|横版|横屏|landscape", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.ORIENTATION_LANDSCAPE
        Regex("竖版|竖屏|portrait|竖", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.ORIENTATION_PORTRAIT
        else -> null
    }

    fun negatedSystems(text: String): Set<String> =
        Regex(
            "(?:不要|别要|去掉|删除|移除|取消|砍掉|去除|别加|不加|无需|不需要|不用)[^。.!！?？;；,，、\n]{0,20}",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .flatMap { GameSystemCatalog.extract(it.value) }
            .toSet()

    fun reAddSystems(text: String): Set<String> =
        Regex(
            "(?:还是要|要保留|保留|恢复|加回|重新加|要加上|再加上|需要加上|改成要)[^。.!！?？;；,，、\n]{0,20}",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .flatMap { GameSystemCatalog.extract(it.value) }
            .toSet()

    fun negatesReference(text: String): Boolean =
        Regex("不要参考|不参考|去掉对标|不要对标|不用参考|别参考|移除对标|取消对标|不要做成", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** 把正则结果与 Lite LLM 结果合并：合法字段优先采用 LLM，缺失字段回退正则。 */
    fun merge(regexResult: IntentSchema, liteResult: IntentSchema?): IntentSchema {
        val lite = liteResult ?: return regexResult
        return IntentSchemaValidator.validate(
            IntentSchema(
                intent = regexResult.intent,
                visualDimension = lite.visualDimension.takeUnless { it == IntentSchema.DIMENSION_2D && regexResult.visualDimension != IntentSchema.DIMENSION_2D }
                    ?: regexResult.visualDimension,
                screenOrientation = lite.screenOrientation.takeUnless {
                    it == IntentSchema.ORIENTATION_PORTRAIT && regexResult.screenOrientation != IntentSchema.ORIENTATION_PORTRAIT
                } ?: regexResult.screenOrientation,
                gameSystems = (lite.gameSystems + regexResult.gameSystems).distinct(),
                excludedSystems = (lite.excludedSystems + regexResult.excludedSystems).distinct(),
                referenceGame = lite.referenceGame ?: regexResult.referenceGame,
                templateId = lite.templateId ?: regexResult.templateId,
                templateSimilarity = lite.templateSimilarity ?: regexResult.templateSimilarity,
                confidence = maxOf(lite.confidence, regexResult.confidence)
            )
        )
    }

    /**
     * 生成确认门的人话摘要：画面维度 / 方向 / 游戏系统 / 对标游戏与默认值假设。
     *
     * 新流程先由策划层产出 [draftPlan]，确认门只回显策划草案里的玩法与验收边界；
     * 玩家确认后，决策层再根据 [schema] 定稿完整 DesignPlan。
     */
    fun buildConfirmation(userRequest: String, schema: IntentSchema, draftPlan: DesignPlan? = null): IntentConfirmation {
        val planned = draftPlan?.gameSystems?.ifEmpty { plannedSystems(schema) } ?: plannedSystems(schema)
        val ref = schema.templateId?.let { TemplateLibrary.findById(it) }
        val rawSystems = (schema.gameSystems + (ref?.suggestedSystems ?: emptyList())).distinct()

        val sb = StringBuilder()
        when (schema.intent) {
            IntentSchema.INTENT_NEW_GAME -> sb.append("我识别到你想新建一个游戏")
            IntentSchema.INTENT_MODIFY_GAME -> sb.append("我识别到你要在当前游戏上继续修改")
            IntentSchema.INTENT_CHAT -> sb.append("我识别到这是一个普通对话")
        }
        sb.append("：画面维度 ${schema.visualDimension}、画面方向 ${schema.screenOrientation}。")
        if (rawSystems.isNotEmpty()) {
            sb.append("游戏系统：${planned.joinToString("、")}。")
        } else {
            sb.append("未明确指定游戏系统，默认按核心玩法补全：${planned.joinToString("、")}。")
        }
        if (schema.referenceGame != null) {
            sb.append("参考对标游戏：${schema.referenceGame}")
            if (schema.templateId != null) {
                sb.append("（已映射模板 ${schema.templateId}，相似度 ${((schema.templateSimilarity ?: 0.0) * 100).toInt()}%）")
            } else if (schema.lockTemplateResolution) {
                sb.append("（已按你的修正改为自定义系统组合，不再套用原模板默认值）")
            } else {
                sb.append("（模板库无对应条目，template:null）")
            }
            sb.append("。")
        }
        if (schema.excludedSystems.isNotEmpty()) {
            sb.append("已明确排除系统：${schema.excludedSystems.joinToString("、")}（本轮不会实现）。")
        }
        sb.append("请核对下面的玩法解释、默认假设与排除项，确认后我会按此方案生成代码。")

        val assumptions = buildAssumptions(schema)
        val explanations = draftPlan?.let { plan ->
            plan.implementations.map { impl ->
                GameSystemCatalog.explainImplementation(
                    system = impl.system,
                    dimension = plan.visualDimension,
                    orientation = plan.screenOrientation,
                    templateId = schema.templateId,
                    plannedMethods = impl.methods
                )
            }
        } ?: planned.map {
            GameSystemCatalog.explainImplementation(
                system = it,
                dimension = schema.visualDimension,
                orientation = schema.screenOrientation,
                templateId = schema.templateId
            )
        }
        return IntentConfirmation(
            userRequest = userRequest,
            gameSchema = schema.toGameSchema(),
            intent = schema,
            summary = sb.toString(),
            designAssumptions = assumptions,
            systemExplanations = explanations,
            excludedSystems = schema.excludedSystems,
            draftPlan = draftPlan,
            isNewGame = schema.intent != IntentSchema.INTENT_MODIFY_GAME
        )
    }

    /** 被映射/默认值补全的设计假设，逐条回显。 */
    fun buildAssumptions(schema: IntentSchema): List<String> {
        val result = mutableListOf<String>()
        val ref = schema.templateId?.let { TemplateLibrary.findById(it) }
        val rawSystems = (schema.gameSystems + (ref?.suggestedSystems ?: emptyList())).distinct()
        if (ref != null) {
            if (schema.visualDimension == ref.suggestedDimension) {
                result.add("参考「${ref.title}」模板，画面维度按 ${ref.suggestedDimension} 实现")
            }
            if (schema.screenOrientation == ref.suggestedOrientation) {
                result.add("参考「${ref.title}」模板，画面方向按 ${ref.suggestedOrientation} 实现")
            }
            val added = ref.suggestedSystems.filterNot {
                schema.gameSystems.contains(it) || schema.excludedSystems.contains(it)
            }
            if (added.isNotEmpty()) {
                result.add("参考「${ref.title}」模板，将补全系统：${added.joinToString("、")}")
            }
        } else {
            if (schema.visualDimension == IntentSchema.DIMENSION_2D) {
                result.add("画面维度默认假设为 2D")
            }
            if (schema.screenOrientation == IntentSchema.ORIENTATION_PORTRAIT) {
                result.add("画面方向默认假设为竖版（手机单手操作）")
            }
        }
        if (rawSystems.isEmpty() && ref == null) {
            result.add("未指定游戏系统，将按“反应躲避 + 收集”的默认轻量框架补全")
        }
        if (schema.excludedSystems.isNotEmpty()) {
            result.add("已按你的要求排除系统：${schema.excludedSystems.joinToString("、")}，策划与代码生成均不得实现")
        }
        return result
    }

    private val DEFAULT_SYSTEMS = listOf("反应躲避", "收集")

    /** Lite LLM 抽取提示词：只输出 Intent Schema JSON。 */
    fun liteLlmPrompt(userText: String): String = """
        你是游戏需求意图抽取器。请只输出一个 JSON 对象（不要 Markdown、不要解释），字段：
        {
          "intent": "new_game | modify_game | chat",
          "visualDimension": "2D | 2.5D | 3D",
          "screenOrientation": "横板 | 竖版",
          "gameSystems": ["人物实体","道具","战斗","技能","属性等级","关卡场景","AI策略","商店经济","收集","解谜","物理","音乐节奏","塔防","合成","放置挂机","经营模拟","竞速","平台跳跃","弹幕射击","反应躲避"],
          "excludedSystems": ["玩家明确不要的系统"],
          "referenceGame": "著名游戏名或 null",
          "confidence": 0.0
        }
        用户需求：$userText
    """.trimIndent()
}


/** 旧 IntentSchema 到新会话级 GameSchema 的兼容转换。 */
fun IntentSchema.toGameSchema(): GameSchema = GameSchema(
    schemaVersion = GameSchema.SCHEMA_VERSION,
    visualDimension = visualDimension,
    screenOrientation = screenOrientation,
    gameSystems = IntentEngine.plannedSystems(this),
    requestedSystems = gameSystems,
    referenceGame = referenceGame,
    templateId = templateId,
    templateSimilarity = templateSimilarity,
    confidence = confidence,
    excludedSystems = excludedSystems,
    lockTemplateResolution = lockTemplateResolution
)
