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
    val confidence: Double = 0.0
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
    val designAssumptions: List<String> = emptyList()
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
        val matched = TemplateLibrary.match(schema.referenceGame.orEmpty())
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
            confidence = schema.confidence.coerceIn(0.0, 1.0)
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

        var dimension = IntentSchema.DIMENSION_2D
        if (Regex("2\\.5\\s*d|伪3d|斜45|2.5D", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            dimension = IntentSchema.DIMENSION_2_5D
        } else if (Regex("(?<!2\\.5)3d|三维|立体", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            dimension = IntentSchema.DIMENSION_3D
        }

        val orientation = if (Regex("横板|横版|横屏|landscape", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            IntentSchema.ORIENTATION_LANDSCAPE
        } else {
            IntentSchema.ORIENTATION_PORTRAIT
        }

        val systems = GameSystemCatalog.extract(text)
        val match = TemplateLibrary.match(text)
        val confidence = (
            0.55 +
                (if (dimension != IntentSchema.DIMENSION_2D || Regex("2d|二维", RegexOption.IGNORE_CASE).containsMatchIn(text)) 0.1 else 0.0) +
                (if (orientation != IntentSchema.ORIENTATION_PORTRAIT || Regex("竖", RegexOption.IGNORE_CASE).containsMatchIn(text)) 0.1 else 0.0) +
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

    /** 生成确认门的人话摘要。 */
    fun buildConfirmation(userRequest: String, schema: IntentSchema): IntentConfirmation {
        val sb = StringBuilder()
        when (schema.intent) {
            IntentSchema.INTENT_NEW_GAME -> sb.append("我识别到你想新建一个游戏")
            IntentSchema.INTENT_MODIFY_GAME -> sb.append("我识别到你要在当前游戏上继续修改")
            IntentSchema.INTENT_CHAT -> sb.append("我识别到这是一个普通对话")
        }
        sb.append("：画面 ${schema.visualDimension}、${schema.screenOrientation}。")
        if (schema.gameSystems.isNotEmpty()) {
            sb.append("涉及系统：${schema.gameSystems.joinToString("、")}。")
        } else {
            sb.append("未明确指定系统，默认按核心玩法补全。")
        }
        if (schema.referenceGame != null) {
            sb.append("参考对标游戏：${schema.referenceGame}")
            if (schema.templateId != null) {
                sb.append("（已映射模板 ${schema.templateId}，相似度 ${((schema.templateSimilarity ?: 0.0) * 100).toInt()}%）")
            } else {
                sb.append("（模板库无对应条目，template:null）")
            }
            sb.append("。")
        }
        sb.append("确认后进入策划与代码生成。")

        val assumptions = buildAssumptions(schema)
        return IntentConfirmation(
            userRequest = userRequest,
            intent = schema,
            summary = sb.toString(),
            designAssumptions = assumptions
        )
    }

    /** 被映射/默认值补全的设计假设，逐条回显。 */
    fun buildAssumptions(schema: IntentSchema): List<String> {
        val result = mutableListOf<String>()
        if (schema.visualDimension == IntentSchema.DIMENSION_2D && schema.referenceGame == null) {
            result.add("画面维度默认假设为 2D")
        }
        if (schema.screenOrientation == IntentSchema.ORIENTATION_PORTRAIT) {
            result.add("画面方向默认假设为竖版（手机单手操作）")
        }
        val ref = schema.templateId?.let { TemplateLibrary.findById(it) }
        if (ref != null) {
            val added = ref.suggestedSystems.filterNot { schema.gameSystems.contains(it) }
            if (added.isNotEmpty()) {
                result.add("参考「${ref.title}」模板，将补全系统：${added.joinToString("、")}")
            }
        }
        if (schema.gameSystems.isEmpty() && ref == null) {
            result.add("未指定游戏系统，将按“反应躲避 + 收集”的默认轻量框架补全")
        }
        return result
    }

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
