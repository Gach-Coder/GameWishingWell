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

@Serializable
data class IntentConfirmation(
    val userRequest: String,
    val intent: IntentSchema,
    /** 人话摘要，确认门回显用。 */
    val summary: String,
    /** 由模板/策划默认值补出来的设计假设，必须逐条回显。 */
    val designAssumptions: List<String> = emptyList(),
    /** 确认门回显的每个系统一句话解释，供玩家逐项核对。 */
    val systemExplanations: List<String> = emptyList(),
    /** 玩家明确排除的系统，确认门同样要回显。 */
    val excludedSystems: List<String> = emptyList(),
    /** 策划层在确认门前产出的草案；确认通过后由决策层定稿为 DesignPlan。 */
    val draftPlan: DesignPlan? = null
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

    /**
     * 每个系统在 HTML5 Canvas 单文件游戏里的落地实现方法。
     * 确认门用它向玩家解释“游戏将会怎样实现这个系统”，策划层也用它形成实现清单。
     */
    private val implementationMethods: Map<String, List<String>> = mapOf(
        "人物实体" to listOf("Canvas 绘制 1 个玩家角色", "触控方向/点按控制移动与朝向", "角色状态与边界限制"),
        "道具" to listOf("2-3 种可拾取道具", "拾取判定与增益/反馈效果"),
        "战斗" to listOf("1 类敌人或目标", "攻击/受击判定", "生命值与胜负条件"),
        "技能" to listOf("1-2 个主动技能", "冷却计时与释放", "命中反馈"),
        "属性等级" to listOf("等级/分数成长数值", "升级后数值或能力变化"),
        "关卡场景" to listOf("3 个递进关卡或场景", "关卡切换与进度保存"),
        "AI策略" to listOf("简单状态机 AI", "可读的决策规则", "AI 行为与难度参数隔离"),
        "商店经济" to listOf("金币等货币产出", "可购买升级", "价格与余额校验"),
        "收集" to listOf("掉落物生成", "收集碰撞判定", "收集计数与完成反馈"),
        "解谜" to listOf("1 套核心谜题规则", "胜利/失败判定"),
        "物理" to listOf("简化重力/碰撞参数", "确定性运动计算"),
        "音乐节奏" to listOf("节拍/轨道生成", "命中判定", "节拍反馈与循环"),
        "塔防" to listOf("可建防御塔", "敌人按波次推进", "基地生命值与胜负判定"),
        "合成" to listOf("同元素合成规则", "合成升级与得分"),
        "放置挂机" to listOf("自动产出计时", "离线/时间收益结算"),
        "经营模拟" to listOf("资源循环", "建造/升级", "经营目标结算"),
        "竞速" to listOf("速度与操控", "跑道/路线与完成条件", "名次或计时判定"),
        "平台跳跃" to listOf("跳跃与平台碰撞", "失败重置"),
        "弹幕射击" to listOf("玩家射击", "敌方弹幕生成", "命中判定"),
        "反应躲避" to listOf("点按/滑动响应", "失误判定", "计分")
    )

    /** 业务验收边界：验收时只需确认这条玩家可感知的边界成立。 */
    private val acceptanceBoundaries: Map<String, String> = mapOf(
        "人物实体" to "角色能按触控移动、朝向正确，且不会穿出游戏区域",
        "道具" to "道具可被拾取并产生可感知的增益或反馈",
        "战斗" to "能攻击、能受击、能分出胜负",
        "技能" to "技能可主动释放、有冷却且命中后有反馈",
        "属性等级" to "成长数值变化能直接作用于游戏体验",
        "关卡场景" to "关卡可递进切换且状态正确重置",
        "AI策略" to "AI 行为符合预期且不卡死、不误判",
        "商店经济" to "货币产出-购买升级形成闭环，余额不会为负",
        "收集" to "收集物可计数，集齐后给出完成反馈",
        "解谜" to "谜题规则明确，能正确判定胜利与失败",
        "物理" to "重力/碰撞表现稳定，运动结果可复现",
        "音乐节奏" to "节拍判定及时，命中/失误反馈清晰",
        "塔防" to "能建塔、敌人按波次进攻，基地生命值归零时结束",
        "合成" to "合成规则明确，升级结果与得分正确",
        "放置挂机" to "离线/在线收益结算正确，数值可见",
        "经营模拟" to "资源循环可运转，经营目标可达成",
        "竞速" to "能操控竞速，完成条件与名次判定正确",
        "平台跳跃" to "跳跃/平台碰撞可靠，失败后能重置",
        "弹幕射击" to "射击与命中判定正确，敌弹幕有明确威胁",
        "反应躲避" to "点按/滑动响应及时，失误判定与计分正确"
    )

    /** 每个系统的一句话解释，确认门逐项回显给玩家。 */
    fun describe(system: String): String =
        descriptions[system] ?: "按该系统的基础规则实现，具体细节由策划层补全。"

    /**
     * 该系统在指定画面维度/方向下的落地实现方法；确认门与策划层共用。
     * 当用户需求命中对标游戏模板时，优先使用 [TemplateSystemCatalog] 中该模板的
     * 具体玩法实现，保证对玩家解释与最终 prompt 注入的是同一套真实功能。
     */
    fun implementationMethods(
        system: String,
        dimension: String,
        orientation: String,
        templateId: String? = null
    ): List<String> {
        val templateSpecific = TemplateSystemCatalog.resolve(templateId, system)?.methods
        val base = templateSpecific ?: implementationMethods[system] ?: return listOf("按基础规则实现")
        val result = base.toMutableList()
        if (templateSpecific == null) {
            when (dimension) {
                IntentSchema.DIMENSION_2_5D -> result += "用 Canvas 2D 斜 45° 投影表现 2.5D"
                IntentSchema.DIMENSION_3D -> result += "用 Canvas 2D 透视投影模拟 3D，不引入 WebGL/模型资产"
                else -> result += "用 2D 精灵/几何图形绘制"
            }
            result += if (orientation == IntentSchema.ORIENTATION_LANDSCAPE) {
                "横板全屏布局，左右手横向操作"
            } else {
                "竖版布局，适配手机单手操作"
            }
        }
        return result.distinct()
    }

    /** 业务验收边界：代码验收时把“能做”和“做到什么程度”分开。 */
    fun acceptanceBoundary(system: String, templateId: String? = null): String =
        TemplateSystemCatalog.resolve(templateId, system)?.acceptanceBoundary
            ?: acceptanceBoundaries[system]
            ?: "核心循环可玩，失败后可 restart() 完整重开"

    /**
     * 确认门向玩家解释时使用的玩法说明。
     * 会过滤 Canvas / DOM / WebAudio / requestAnimationFrame 等纯技术实现细节，
     * 只保留玩家可感知的游戏内容与玩法规则；对玩家只聊“玩什么、怎么玩”。
     */
    fun playerFacingMethods(
        system: String,
        dimension: String,
        orientation: String,
        templateId: String? = null,
        plannedMethods: List<String>? = null
    ): List<String> {
        val methods = plannedMethods ?: implementationMethods(system, dimension, orientation, templateId)
        val filtered = methods.filterNot { method ->
            TECHNICAL_IMPLEMENTATION_PHRASES.any { method.contains(it, ignoreCase = true) }
        }
        return filtered.ifEmpty { listOf("按${system}的基础玩法实现") }
    }

    /** 确认门逐项解释：只说明游戏玩法与业务验收边界，不讨论引擎/算法等底层技术。 */
    fun explainImplementation(
        system: String,
        dimension: String,
        orientation: String,
        templateId: String? = null,
        plannedMethods: List<String>? = null
    ): String {
        val methods = playerFacingMethods(system, dimension, orientation, templateId, plannedMethods).joinToString("、")
        return "$system：具体玩法为 $methods；验收边界：${acceptanceBoundary(system, templateId)}"
    }

    private val TECHNICAL_IMPLEMENTATION_PHRASES = listOf(
        "Canvas", "requestAnimationFrame", "WebAudio", "DOM", "HTML", "CSS",
        "touchstart", "touchmove", "touchend", "全局 restart()", "浏览器全局",
        "用 2D 精灵", "投影", "全屏布局", "竖版布局", "横板布局"
    )
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
        GameTemplateRef("gold_miner", "黄金矿工", listOf("黄金矿工", "挖矿"), listOf("物理", "道具", "商店经济"), "2D", "竖版"),
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
            "(?:不要|别要|去掉|删除|移除|取消|砍掉|去除|别加|不加|无需|不需要|不用)[^。.!！?？;；,，、\n]{0,20}",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .flatMap { GameSystemCatalog.extract(it.value) }
            .toSet()

    private fun reAddSystems(text: String): Set<String> =
        Regex(
            "(?:还是要|要保留|保留|恢复|加回|重新加|要加上|再加上|需要加上|改成要)[^。.!！?？;；,，、\n]{0,20}",
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
            intent = schema,
            summary = sb.toString(),
            designAssumptions = assumptions,
            systemExplanations = explanations,
            excludedSystems = schema.excludedSystems,
            draftPlan = draftPlan
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
