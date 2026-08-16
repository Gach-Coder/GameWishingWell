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
 * 意图层输出：Intent Schema JSON。
 *
 * 与 instruct.txt 第二章对齐：
 * - intent 只允许 new_game / modify_game / chat 三个枚举值；
 * - 画面维度 / 画面方向 / 游戏系统走枚举白名单，非法值拒绝或映射默认值；
 * - 参考对标游戏映射到内部模板库 template_id + 相似度，映射不上返回 template:null；
 * - 被模板补全出来的默认值，必须以“设计假设”逐条回显，禁止静默纠正。
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

@Serializable
data class IntentConfirmation(
    val userRequest: String,
    val intent: IntentSchema,
    /** 人话摘要，确认门回显用。 */
    val summary: String,
    /** 由模板/策划默认值补出来的设计假设，必须逐条回显。 */
    val designAssumptions: List<String> = emptyList(),
    /** 确认门回显的每个系统一句话解释，供玩家逐项核对。 */
    val systemExplanations: List<String> = emptyList()
)

/** 内部模板库条目：著名对标游戏 → template_id + 特征建议。 */
@Serializable
data class GameTemplateRef(
    val id: String,
    val title: String,
    val aliases: List<String>,
    val suggestedSystems: List<String>,
    val suggestedDimension: String = IntentSchema.DIMENSION_2D,
    val suggestedOrientation: String = IntentSchema.ORIENTATION_PORTRAIT
)

object GameSystemCatalog {
    val ALL: List<String> = listOf(
        "人物实体", "道具", "战斗", "技能", "属性等级", "关卡场景", "AI策略", "商店经济",
        "收集", "解谜", "物理", "音乐节奏", "塔防", "合成", "放置挂机", "经营模拟",
        "竞速", "平台跳跃", "弹幕射击", "反应躲避"
    )

    private val keywords: List<Pair<String, String>> = listOf(
        "人物实体" to "人物|角色|英雄|主角|玩家角色|小人",
        "道具" to "道具|物品|装备|武器|药水|奖励道具",
        "战斗" to "战斗|攻击|打斗|对战|敌人|怪物|boss",
        "技能" to "技能|大招|法术|连招|招式",
        "属性等级" to "等级|升级|经验|属性|成长|天赋",
        "关卡场景" to "关卡|场景|地图|波次|第.{0,3}关",
        "AI策略" to "AI|ai|智能|策略|寻路|电脑对手",
        "商店经济" to "商店|金币|货币|经济|购买|价格",
        "收集" to "收集|接住|捡|星星|金币",
        "解谜" to "解谜|谜题|拼图|密室|推理",
        "物理" to "物理|重力|碰撞|抛物线",
        "音乐节奏" to "音乐|节奏|节拍|音游|钢琴块|别踩白块",
        "塔防" to "塔防|防御塔|保卫萝卜",
        "合成" to "合成|合并|2048|大西瓜|消除",
        "放置挂机" to "放置|挂机|自动战斗|自动挂机",
        "经营模拟" to "经营|模拟|建造|餐厅|农场",
        "竞速" to "竞速|赛车|跑道|冲刺",
        "平台跳跃" to "平台跳跃|横板跳跃|跳台|跑酷",
        "弹幕射击" to "射击|弹幕|飞机大战|子弹",
        "反应躲避" to "反应|躲避|接水果|打地鼠|点按"
    )

    fun extract(text: String): List<String> {
        val result = LinkedHashSet<String>()
        for ((system, regex) in keywords) {
            if (Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(text)) result.add(system)
        }
        return result.toList()
    }

    fun isValid(system: String): Boolean = ALL.contains(system)

    private val descriptions: Map<String, String> = mapOf(
        "人物实体" to "玩家可操作的角色，包含移动、朝向与基础状态。",
        "道具" to "可拾取/使用的物品，提供增益、反馈或推进游戏流程。",
        "战斗" to "敌人、攻击与受击判定，以及胜负条件。",
        "技能" to "主动技能，包含冷却、释放与命中反馈。",
        "属性等级" to "等级/分数成长，升级后带来数值或能力变化。",
        "关卡场景" to "多个递进关卡或场景，以及关卡切换逻辑。",
        "AI策略" to "电脑角色的简单状态机或可读决策规则。",
        "商店经济" to "金币等货币产出，以及购买升级的消费闭环。",
        "收集" to "掉落物收集、收集计数与完成反馈。",
        "解谜" to "核心谜题规则与胜利/失败判定。",
        "物理" to "简化重力/碰撞等确定性运动参数。",
        "音乐节奏" to "节拍判定、命中反馈与节奏玩法循环。",
        "塔防" to "防御塔建造、敌人波次与基地生命值。",
        "合成" to "同元素合成规则与合成升级。",
        "放置挂机" to "自动产出与时间/离线收益。",
        "经营模拟" to "资源循环、建造/升级与经营目标。",
        "竞速" to "速度与操控、完成条件与名次判定。",
        "平台跳跃" to "跳跃与平台碰撞，以及失败重置。",
        "弹幕射击" to "玩家射击、敌方弹幕与命中判定。",
        "反应躲避" to "点按/滑动响应、失误判定与计分。",
    )

    /** 每个系统的一句话解释，确认门逐项回显给玩家。 */
    fun describe(system: String): String =
        descriptions[system] ?: "按该系统的基础规则实现，具体细节由策划层补全。"
}

object TemplateLibrary {
    val ALL: List<GameTemplateRef> = listOf(
        GameTemplateRef("whack_a_mole", "打地鼠", listOf("打地鼠", "whack"), listOf("反应躲避", "战斗"), "2D", "竖版"),
        GameTemplateRef("catch_fruit", "接水果", listOf("接水果", "水果忍者", "切水果"), listOf("收集", "反应躲避"), "2D", "竖版"),
        GameTemplateRef("snake", "贪吃蛇", listOf("贪吃蛇", "snake"), listOf("人物实体", "收集", "属性等级"), "2D", "竖版"),
        GameTemplateRef("merge_2048", "2048/合成", listOf("2048", "合成大西瓜", "合并"), listOf("合成", "商店经济"), "2D", "竖版"),
        GameTemplateRef("tetris", "俄罗斯方块", listOf("俄罗斯方块", "tetris"), listOf("反应躲避", "属性等级"), "2D", "竖版"),
        GameTemplateRef("brick_breaker", "打砖块", listOf("打砖块", "弹球", "breakout"), listOf("物理", "战斗", "关卡场景"), "2D", "竖版"),
        GameTemplateRef("plane_shooter", "飞机大战", listOf("飞机大战", "雷电", "弹幕射击"), listOf("弹幕射击", "战斗", "关卡场景"), "2D", "竖版"),
        GameTemplateRef("endless_runner", "跑酷", listOf("跑酷", "神庙逃亡", "地铁跑酷"), listOf("竞速", "平台跳跃", "收集"), "2D", "横板"),
        GameTemplateRef("tower_defense", "塔防", listOf("塔防", "保卫萝卜"), listOf("塔防", "战斗", "AI策略", "商店经济"), "2D", "竖版"),
        GameTemplateRef("match3", "消消乐", listOf("消消乐", "开心消消乐", "三消"), listOf("合成", "关卡场景"), "2D", "竖版"),
        GameTemplateRef("gold_miner", "黄金矿工", listOf("黄金矿工", "挖矿"), listOf("物理", "商店经济", "收集"), "2D", "竖版"),
        GameTemplateRef("piano_tiles", "别踩白块", listOf("别踩白块", "钢琴块"), listOf("音乐节奏", "反应躲避"), "2D", "竖版"),
        GameTemplateRef("maze", "迷宫", listOf("迷宫", "maze"), listOf("解谜", "关卡场景"), "2D", "竖版"),
        GameTemplateRef("racing", "赛车", listOf("赛车", "竞速", "卡丁车"), listOf("竞速", "AI策略"), "2D", "横板"),
        GameTemplateRef("board_game", "棋类", listOf("五子棋", "井字棋", "象棋", "围棋"), listOf("AI策略", "属性等级"), "2D", "竖版")
    )

    fun findById(id: String): GameTemplateRef? = ALL.firstOrNull { it.id == id }

    /**
     * 把用户提到的著名游戏映射到模板库。映射不上返回 templateId=null，
     * 相似度只做简单归一化（别名覆盖长度 / 用户文本长度）。
     */
    fun match(text: String): Pair<GameTemplateRef, Double>? {
        val normalized = text.lowercase()
        var best: Pair<GameTemplateRef, Double>? = null
        for (ref in ALL) {
            for (alias in ref.aliases) {
                if (normalized.contains(alias.lowercase())) {
                    val similarity = 0.75 + (alias.length.toDouble() / normalized.length.coerceAtLeast(1)).coerceAtMost(0.25)
                    if (best == null || similarity > best!!.second) best = ref to similarity
                }
            }
        }
        return best
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

        val systems = schema.gameSystems.filter { GameSystemCatalog.isValid(it) }.distinct()
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
        return validate(
            IntentSchema(
                intent = intent,
                visualDimension = obj.string("visualDimension") ?: IntentSchema.DIMENSION_2D,
                screenOrientation = obj.string("screenOrientation") ?: IntentSchema.ORIENTATION_PORTRAIT,
                gameSystems = systems,
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
    private val modifyPattern = Regex(
        "修改|改一下|改成|加上|加个|增加|添加|去掉|删除|调整|优化|修复|继续|迭代|" +
            "更难点|更难|简单点|简单些|背景|颜色|音效|分数|关卡|加连击|加.*系统"
    )
    private val chatPattern = Regex("^(你好|您好|hi|hello|在吗|你是谁|你能做什么|怎么用|使用说明|谢谢)\\b.*")

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
        val systems = GameSystemCatalog.extract(text)
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
                referenceGame = match?.first?.title,
                templateId = match?.first?.id,
                templateSimilarity = match?.second,
                confidence = confidence
            )
        )
    }

    /** 确认门最终要实现的系统集合：用户明确系统 + 模板补全系统；全空时使用默认轻量框架。 */
    fun plannedSystems(schema: IntentSchema): List<String> {
        val ref = schema.templateId?.let { TemplateLibrary.findById(it) }
        val merged = (schema.gameSystems + (ref?.suggestedSystems ?: emptyList()))
            .filter { GameSystemCatalog.isValid(it) }
            .distinct()
        return merged.ifEmpty { DEFAULT_SYSTEMS }
    }

    /**
     * 确认门上的用户修正合并：显式字段以玩家修正为准，未提到的字段继承已确认结果；
     * 玩家明确“不要/去掉”的系统会从模板补全与默认集合中一并移除。
     */
    fun mergeCorrection(confirmed: IntentSchema, correction: IntentSchema, correctionText: String): IntentSchema {
        val dimension = if (explicitDimension(correctionText) != null) correction.visualDimension else confirmed.visualDimension
        val orientation = if (explicitOrientation(correctionText) != null) correction.screenOrientation else confirmed.screenOrientation

        val removed = negatedSystems(correctionText)
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
            .distinct()

        val dropsTemplate = dropsReference ||
            (confirmed.templateId != null && removed.any { system ->
                TemplateLibrary.findById(confirmed.templateId)?.suggestedSystems?.contains(system) == true
            })
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
            dropsTemplate -> {
                referenceGame = confirmed.referenceGame
                templateId = null
                templateSimilarity = null
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
                referenceGame = referenceGame,
                templateId = templateId,
                templateSimilarity = templateSimilarity,
                confidence = maxOf(confirmed.confidence, correction.confidence),
                lockTemplateResolution = true
            )
        )
    }

    private fun explicitDimension(text: String): String? = when {
        Regex("2\\.5\\s*d|伪3d|斜45|2.5D", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.DIMENSION_2_5D
        Regex("(?<!2\\.5)3d|三维|立体", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.DIMENSION_3D
        Regex("(?<![A-Za-z0-9])2d(?![A-Za-z0-9])|二维|平面", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.DIMENSION_2D
        else -> null
    }

    private fun explicitOrientation(text: String): String? = when {
        Regex("横板|横版|横屏|landscape", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.ORIENTATION_LANDSCAPE
        Regex("竖版|竖屏|portrait|竖", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IntentSchema.ORIENTATION_PORTRAIT
        else -> null
    }

    private fun negatedSystems(text: String): Set<String> =
        Regex(
            "(?:不要|别要|去掉|删除|移除|取消|砍掉|去除|没有|别加|不加|无需|不需要|不用)[^。.!！?？;；,，、\n]{0,20}",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .flatMap { GameSystemCatalog.extract(it.value) }
            .toSet()

    private fun negatesReference(text: String): Boolean =
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
                referenceGame = lite.referenceGame ?: regexResult.referenceGame,
                templateId = lite.templateId ?: regexResult.templateId,
                templateSimilarity = lite.templateSimilarity ?: regexResult.templateSimilarity,
                confidence = maxOf(lite.confidence, regexResult.confidence)
            )
        )
    }

    /** 生成确认门的人话摘要：画面维度 / 方向 / 游戏系统 / 对标游戏与默认值假设。 */
    fun buildConfirmation(userRequest: String, schema: IntentSchema): IntentConfirmation {
        val planned = plannedSystems(schema)
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
        sb.append("请核对下面的系统解释与默认假设，确认后进入策划与代码生成。")

        val assumptions = buildAssumptions(schema)
        return IntentConfirmation(
            userRequest = userRequest,
            intent = schema,
            summary = sb.toString(),
            designAssumptions = assumptions,
            systemExplanations = planned.map { "${it}：${GameSystemCatalog.describe(it)}" }
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
            val added = ref.suggestedSystems.filterNot { schema.gameSystems.contains(it) }
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
          "referenceGame": "著名游戏名或 null",
          "confidence": 0.0
        }
        用户需求：$userText
    """.trimIndent()
}
