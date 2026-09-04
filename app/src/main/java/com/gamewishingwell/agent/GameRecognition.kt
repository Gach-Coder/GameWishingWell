package com.gamewishingwell.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 确认门卡片中一个 module（游戏系统）的回显项：
 * 勾选框标签 = module 名，勾选/取消代表玩家是否希望实现这个游戏系统。
 */
@Serializable
data class ModuleCard(
    val module: String,
    /** 玩家视角的玩法说明：只描述游戏内容与规则，不含技术细节。 */
    val implementation: String,
    /** 业务验收边界，供玩家核对“做到什么程度才算实现”。 */
    val acceptanceBoundary: String
)

@Serializable
data class IntentConfirmation(
    val userRequest: String,
    /** 新流程确认门共享的会话级 Game Schema JSON。 */
    val gameSchema: GameSchema? = null,
    /** 旧流程遗留字段，新数据中通常为 null；保留仅为兼容历史会话。 */
    val intent: IntentSchema? = null,
    /** 人话摘要，确认门回显用。 */
    val summary: String,
    /** 由模板/策划默认值补出来的设计假设，必须逐条回显。 */
    val designAssumptions: List<String> = emptyList(),
    /** 确认门回显的每个系统一句话解释，供玩家逐项核对。 */
    val systemExplanations: List<String> = emptyList(),
    /** module_list 的结构化回显项：确认门卡片据此渲染勾选框。 */
    val modules: List<ModuleCard> = emptyList(),
    /** 玩家明确排除的系统，确认门同样要回显。 */
    val excludedSystems: List<String> = emptyList(),
    /** 策划层在确认门前产出的草案；确认通过后由决策层定稿为 DesignPlan。 */
    val draftPlan: DesignPlan? = null,
    /** 本确认门描述的是不是“首次制作”；false 表示在已锚定游戏上继续修改。 */
    val isNewGame: Boolean = true,
    /** true 表示本卡片由确认门的文本修正触发重组生成（区别于首次识别）。 */
    val revised: Boolean = false,
    /**
     * 玩家取消勾选的 module：只随卡片消息持久化用于历史卡回显，
     * 不注入任何提示词，也不进入排除清单。
     */
    val uncheckedModules: List<String> = emptyList(),
    /** 质量档位（卡片四挡 fast/light/balanced/premium，默认均衡）；确认后写入会话驱动生成策略。 */
    val qualityTier: String = "balanced"
) {
    /** 确认门实际共享的 Game Schema JSON（兼容旧版 intent 字段）。 */
    val schema: GameSchema get() = gameSchema ?: intent?.toGameSchema() ?: GameSchema()

    companion object {
        private val cardJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /**
         * 卡片消息内容：剥离 draftPlan/gameSchema/intent 等非回显字段后序列化，
         * 聊天流中的 confirm_card 消息与 pendingConfirmation 用同一编码做匹配。
         */
        fun cardContent(confirmation: IntentConfirmation): String = cardJson.encodeToString(
            confirmation.copy(intent = null, gameSchema = null, draftPlan = null)
        )

        fun fromCardContent(content: String): IntentConfirmation? = runCatching {
            cardJson.decodeFromString(IntentConfirmation.serializer(), content)
        }.getOrNull()
    }
}

/** 内部模板库条目：著名对标游戏 → template_id + 特征建议。 */
@Serializable
data class GameTemplateRef(
    val id: String,
    val title: String,
    val aliases: List<String>,
    val suggestedSystems: List<String>,
    val suggestedDimension: String = GameSchema.DIMENSION_2D,
    val suggestedOrientation: String = GameSchema.ORIENTATION_PORTRAIT
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
        "反应躲避" to "反应|躲避|接水果|打地鼠"
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
                GameSchema.DIMENSION_2_5D -> result += "用 Canvas 2D 斜 45° 投影表现 2.5D"
                GameSchema.DIMENSION_3D -> result += "用 Canvas 2D 透视投影模拟 3D，不引入 WebGL/模型资产"
                else -> result += "用 2D 精灵/几何图形绘制"
            }
            result += if (orientation == GameSchema.ORIENTATION_LANDSCAPE) {
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

/**
 * 会话级 Game Schema JSON：一个对话框只制作一个游戏。
 *
 * 识别层首次抽取时创建该 JSON，之后每轮修改都在同一份 JSON 上补全/覆盖；
 * 策划层、确认门、决策层与生成层共享这同一份 Game Schema，不再依赖意图枚举
 * 推断“新建还是修改”。
 */
@Serializable
data class GameSchema(
    val schemaVersion: Int = SCHEMA_VERSION,
    val visualDimension: String = DIMENSION_2D,
    val screenOrientation: String = ORIENTATION_PORTRAIT,
    /** 最终要实现的系统集合（已合并用户明确系统与对标模板补全系统）。 */
    val gameSystems: List<String> = emptyList(),
    /** 用户明确点名的系统集合，用于区分模板/默认值补全的设计假设。 */
    val requestedSystems: List<String> = emptyList(),
    val referenceGame: String? = null,
    val templateId: String? = null,
    val templateSimilarity: Double? = null,
    val confidence: Double = 0.0,
    val excludedSystems: List<String> = emptyList(),
    /** 确认门取消勾选的系统：本轮不实现，但不算玩家明确排除；后续文本点名会自动恢复。 */
    val uncheckedSystems: List<String> = emptyList(),
    /** true 表示模板解析已由玩家修正显式锁定，validate 不再反向重匹配。 */
    val lockTemplateResolution: Boolean = false,
    /** 锚定该游戏的第一条用户需求，修改轮继续作为上下文。 */
    val firstUserRequest: String? = null,
    /** 最近一轮用户需求，策划层 LLM 按最新要求细化系统实现。 */
    val lastUserRequest: String? = null
) {
    companion object {
        const val SCHEMA_VERSION = 1

        const val DIMENSION_2D = "2D"
        const val DIMENSION_2_5D = "2.5D"
        const val DIMENSION_3D = "3D"

        const val ORIENTATION_LANDSCAPE = "横板"
        const val ORIENTATION_PORTRAIT = "竖版"
    }

    val hasMeaningfulFeatures: Boolean
        get() = gameSystems.isNotEmpty() || requestedSystems.isNotEmpty() ||
            excludedSystems.isNotEmpty() || referenceGame != null || templateId != null
}

/**
 * 识别层从单条用户消息中抽出的“补丁”。
 * 字段为 null 表示本消息没有明确提及该维度，合并时保留会话 Game Schema 中的旧值。
 */
@Serializable
data class GameSchemaPatch(
    val visualDimension: String? = null,
    val screenOrientation: String? = null,
    val gameSystems: List<String> = emptyList(),
    val excludedSystems: List<String> = emptyList(),
    /** 玩家明确要求恢复/加回的系统。 */
    val reAddSystems: List<String> = emptyList(),
    val referenceGame: String? = null,
    val templateId: String? = null,
    val templateSimilarity: Double? = null,
    val confidence: Double = 0.0,
    /** 玩家明确表示不再参考对标游戏。 */
    val dropReference: Boolean = false,
    /** 无实体但确实是“做一个游戏”类指令时置 true，避免误判成普通 chat。 */
    val hasGameCommand: Boolean = false
) {
    val hasEntities: Boolean
        get() = visualDimension != null || screenOrientation != null ||
            gameSystems.isNotEmpty() || excludedSystems.isNotEmpty() ||
            reAddSystems.isNotEmpty() || referenceGame != null || templateId != null ||
            dropReference
}

@Serializable
data class GameRecognitionResult(
    val gameSchema: GameSchema,
    val isNewGame: Boolean,
    /** true 表示没有抽到任何游戏实体，应回退为普通文本聊天。 */
    val emptyEntities: Boolean,
    /** 本轮合并后的识别补丁：策划层据此判断哪些 module 是本轮追加/修改，需要重新策划。 */
    val appliedPatch: GameSchemaPatch? = null
) {
    /** 便捷别名：识别层与下游共享的 Game Schema JSON。 */
    val schema: GameSchema get() = gameSchema
}

object GameSchemaValidator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * JSON Schema + 枚举白名单校验。
     * 非法画面维度/方向映射默认值，非法系统丢弃；templateId 不在模板库时置 null。
     */
    fun validate(schema: GameSchema): GameSchema {
        val dimension = if (schema.visualDimension in setOf(
                GameSchema.DIMENSION_2D,
                GameSchema.DIMENSION_2_5D,
                GameSchema.DIMENSION_3D
            )
        ) schema.visualDimension else GameSchema.DIMENSION_2D

        val orientation = if (schema.screenOrientation in setOf(
                GameSchema.ORIENTATION_LANDSCAPE,
                GameSchema.ORIENTATION_PORTRAIT
            )
        ) schema.screenOrientation else GameSchema.ORIENTATION_PORTRAIT

        val requested = schema.requestedSystems
            .filter { GameSystemCatalog.isValid(it) }
            .distinct()
        val excluded = schema.excludedSystems
            .filter { GameSystemCatalog.isValid(it) }
            .distinct()
        val systems = schema.gameSystems
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
            .distinct()
        // 取消勾选的系统只做白名单过滤；已回到实现范围或被明确排除的自动移出。
        val unchecked = schema.uncheckedSystems
            .filter { GameSystemCatalog.isValid(it) && it !in systems && it !in excluded }
            .distinct()

        val template = schema.templateId?.let { TemplateLibrary.findById(it) }
        val matched = if (schema.lockTemplateResolution) {
            null
        } else {
            TemplateLibrary.match(schema.referenceGame.orEmpty())
        }
        val templateId = template?.id ?: matched?.first?.id
        val templateSimilarity = template?.let { schema.templateSimilarity } ?: matched?.second

        return schema.copy(
            visualDimension = dimension,
            screenOrientation = orientation,
            requestedSystems = requested,
            excludedSystems = excluded,
            uncheckedSystems = unchecked,
            gameSystems = systems,
            templateId = templateId,
            templateSimilarity = templateSimilarity?.coerceIn(0.0, 1.0),
            confidence = schema.confidence.coerceIn(0.0, 1.0)
        )
    }

    /** 识别层 Lite LLM JSON 解析：输出 Game Schema 补丁。 */
    fun parseLiteLlmReply(raw: String): GameSchemaPatch? {
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

        return GameSchemaPatch(
            visualDimension = obj.string("visualDimension")?.takeIf { it.isNotBlank() },
            screenOrientation = obj.string("screenOrientation")?.takeIf { it.isNotBlank() },
            gameSystems = obj.stringList("gameSystems").filter { it.isNotBlank() },
            excludedSystems = obj.stringList("excludedSystems").filter { it.isNotBlank() },
            reAddSystems = obj.stringList("reAddSystems").filter { it.isNotBlank() },
            referenceGame = obj.string("referenceGame")?.takeIf { it.isNotBlank() },
            templateId = obj.string("templateId")?.takeIf { it.isNotBlank() },
            templateSimilarity = obj.double("templateSimilarity"),
            confidence = obj.double("confidence") ?: 0.0,
            dropReference = obj.boolean("dropReference"),
            hasGameCommand = obj.boolean("hasGameCommand")
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.let { arr ->
            (arr as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } ?: emptyList()
}

/**
 * 识别层：仅在意图层分类为 new_feature 后运行（fix_bug 直通修复，不经过识别层）。
 *
 * 会话 Game Schema JSON 为空 → 首次制作；非空 → 已经锚定某个游戏，默认继续修改。
 * 正则 + Lite LLM 抽取特征后，把合法字段补全/覆盖到同一份 Game Schema 上。
 */
object RecognitionEngine {

    private val gameCommandPattern = Regex(
        "(^|[。！？!?；;\\s])(请|帮我|给我|我想要|想要|我要|要|来|做一个?|制作|创建|生成|开发|设计|写|加|增加|添加|新增|修改|改成?|调整|优化|修复|继续|迭代|重做|换).{0,14}(游戏|小游戏|玩法|关卡|系统|功能|代码|版本|难度|分数|计分|音效|背景|颜色|金币|敌人|道具|技能|商店|连击|生命|操作)"
    )

    private val DEFAULT_SYSTEMS = listOf("反应躲避", "收集")

    fun extractLocal(userText: String): GameSchemaPatch {
        val text = userText.trim()
        val dimension = IntentEngine.explicitDimension(text)
        val orientation = IntentEngine.explicitOrientation(text)
        val negated = IntentEngine.negatedSystems(text)
        val reAdd = IntentEngine.reAddSystems(text)
        val systems = GameSystemCatalog.extract(text).filterNot { it in negated }
        val match = TemplateLibrary.match(text)
        val dropReference = IntentEngine.negatesReference(text)
        val hasGameCommand = gameCommandPattern.containsMatchIn(text)

        val confidence = (
            0.55 +
                (if (dimension != null) 0.1 else 0.0) +
                (if (orientation != null) 0.1 else 0.0) +
                (systems.size * 0.05).coerceAtMost(0.2) +
                (if (negated.isNotEmpty() || reAdd.isNotEmpty()) 0.1 else 0.0) +
                (if (match != null) 0.15 else 0.0)
            ).coerceIn(0.0, 1.0)

        return GameSchemaPatch(
            visualDimension = dimension,
            screenOrientation = orientation,
            gameSystems = systems,
            excludedSystems = negated.toList(),
            reAddSystems = reAdd.toList(),
            referenceGame = match?.first?.title,
            templateId = match?.first?.id,
            templateSimilarity = match?.second,
            confidence = confidence,
            dropReference = dropReference,
            hasGameCommand = hasGameCommand
        )
    }

    /** 把正则结果与 Lite LLM 结果合并：合法字段优先采用 LLM，缺失字段回退正则。 */
    fun merge(regexResult: GameSchemaPatch, liteResult: GameSchemaPatch?): GameSchemaPatch {
        val lite = liteResult ?: return regexResult
        val dimension = when {
            lite.visualDimension != null -> lite.visualDimension
            else -> regexResult.visualDimension
        }?.let { liteValue ->
            // Lite LLM 常把缺省值填成 2D/竖版；正则已明确抽出非默认值时以正则为准。
            if (liteValue == GameSchema.DIMENSION_2D &&
                regexResult.visualDimension != null &&
                regexResult.visualDimension != GameSchema.DIMENSION_2D
            ) {
                regexResult.visualDimension
            } else {
                liteValue
            }
        }
        val orientation = when {
            lite.screenOrientation != null -> lite.screenOrientation
            else -> regexResult.screenOrientation
        }?.let { liteValue ->
            if (liteValue == GameSchema.ORIENTATION_PORTRAIT &&
                regexResult.screenOrientation != null &&
                regexResult.screenOrientation != GameSchema.ORIENTATION_PORTRAIT
            ) {
                regexResult.screenOrientation
            } else {
                liteValue
            }
        }
        return GameSchemaPatch(
            visualDimension = dimension,
            screenOrientation = orientation,
            gameSystems = (lite.gameSystems + regexResult.gameSystems).distinct(),
            excludedSystems = (lite.excludedSystems + regexResult.excludedSystems).distinct(),
            reAddSystems = (lite.reAddSystems + regexResult.reAddSystems).distinct(),
            referenceGame = lite.referenceGame ?: regexResult.referenceGame,
            templateId = lite.templateId ?: regexResult.templateId,
            templateSimilarity = lite.templateSimilarity ?: regexResult.templateSimilarity,
            confidence = maxOf(lite.confidence, regexResult.confidence),
            dropReference = lite.dropReference || regexResult.dropReference,
            hasGameCommand = lite.hasGameCommand || regexResult.hasGameCommand
        )
    }

    /**
     * 识别层入口。
     *
     * @param existingSchema 会话级 Game Schema JSON；null 表示本对话还没有锚定游戏。
     */
    fun recognize(
        userText: String,
        existingSchema: GameSchema?,
        liteResult: GameSchemaPatch? = null
    ): GameRecognitionResult {
        val patch = merge(extractLocal(userText), liteResult)
        val result = if (existingSchema == null) {
            applyToNewConversation(patch, userText)
        } else {
            applyToAnchoredGame(GameSchemaValidator.validate(existingSchema), patch, userText)
        }
        val withPatch = result.copy(appliedPatch = patch)
        return if (withPatch.emptyEntities) {
            withPatch
        } else {
            withPatch.copy(gameSchema = GameSchemaValidator.validate(withPatch.gameSchema))
        }
    }

    private fun applyToNewConversation(patch: GameSchemaPatch, userText: String): GameRecognitionResult {
        if (!patch.hasEntities && !patch.hasGameCommand) {
            return GameRecognitionResult(
                gameSchema = GameSchema(),
                isNewGame = true,
                emptyEntities = true
            )
        }

        val excluded = patch.excludedSystems
            .filter { GameSystemCatalog.isValid(it) && it !in patch.reAddSystems }
            .toSet()
        val requested = (patch.gameSystems + patch.reAddSystems)
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
            .distinct()
        val template = resolveTemplate(patch)
        val planned = plannedSystems(
            requested = requested,
            templateId = template?.id,
            excluded = excluded
        )

        return GameRecognitionResult(
            gameSchema = GameSchema(
                // 对标游戏特性做“无覆盖填补”：用户明确抽出的值优先，
                // 空位由模板建议值初始化，最后才落到平台默认值。
                visualDimension = patch.visualDimension
                    ?: template?.suggestedDimension
                    ?: GameSchema.DIMENSION_2D,
                screenOrientation = patch.screenOrientation
                    ?: template?.suggestedOrientation
                    ?: GameSchema.ORIENTATION_PORTRAIT,
                gameSystems = planned,
                requestedSystems = requested,
                referenceGame = patch.referenceGame?.takeIf { patch.dropReference.not() } ?: template?.title,
                templateId = template?.id,
                templateSimilarity = template?.let { patch.templateSimilarity ?: similarityOf(patch.referenceGame) },
                confidence = patch.confidence,
                excludedSystems = excluded.toList(),
                lockTemplateResolution = false,
                firstUserRequest = userText.trim(),
                lastUserRequest = userText.trim()
            ),
            isNewGame = true,
            emptyEntities = false
        )
    }

    private fun applyToAnchoredGame(
        current: GameSchema,
        patch: GameSchemaPatch,
        userText: String
    ): GameRecognitionResult {
        if (!patch.hasEntities && !patch.hasGameCommand) {
            return GameRecognitionResult(
                gameSchema = current,
                isNewGame = false,
                emptyEntities = true
            )
        }

        val reAdd = patch.reAddSystems.filter { GameSystemCatalog.isValid(it) }.toSet()
        val removed = patch.excludedSystems.filter { GameSystemCatalog.isValid(it) }.toSet()
        val excluded = (current.excludedSystems.toSet() + removed - reAdd)
            .filter { GameSystemCatalog.isValid(it) }
            .toSet()
        // 取消勾选不是禁令：本轮文本点名的 module 视为恢复实现，自动移出 unchecked。
        val reactivated = (patch.gameSystems + reAdd).toSet()
        val unchecked = current.uncheckedSystems
            .filter { GameSystemCatalog.isValid(it) && it !in reactivated && it !in excluded }
            .toSet()

        val template = resolveTemplate(patch)
        val dropAndNoTemplate = patch.dropReference && template == null
        val targetTemplateId = template?.id ?: if (dropAndNoTemplate) null else current.templateId

        val requested = when {
            template != null -> patch.gameSystems.filter { it !in excluded }
            dropAndNoTemplate -> current.requestedSystems + patch.gameSystems
            else -> current.requestedSystems + patch.gameSystems
        }.plus(reAdd).filter { GameSystemCatalog.isValid(it) && it !in excluded }.distinct()

        val baseline = when {
            template != null -> template.suggestedSystems
            dropAndNoTemplate -> current.requestedSystems.ifEmpty { DEFAULT_SYSTEMS }
            else -> current.gameSystems
        }

        val planned = plannedSystems(
            requested = (baseline + patch.gameSystems + reAdd).toList(),
            templateId = targetTemplateId,
            excluded = excluded,
            unchecked = unchecked
        )

        val referenceGame: String?
        val templateId: String?
        val templateSimilarity: Double?
        if (template != null) {
            referenceGame = template.title
            templateId = template.id
            templateSimilarity = patch.templateSimilarity ?: similarityOf(patch.referenceGame)
        } else if (dropAndNoTemplate) {
            referenceGame = null
            templateId = null
            templateSimilarity = null
        } else {
            referenceGame = current.referenceGame
            templateId = current.templateId
            templateSimilarity = current.templateSimilarity
        }

        return GameRecognitionResult(
            gameSchema = current.copy(
                // 修改轮换上新对标模板时，模板建议值同样只填补空位：
                // 用户本轮明确指定的值优先，其次新模板建议，最后保留原值。
                visualDimension = patch.visualDimension
                    ?: template?.suggestedDimension
                    ?: current.visualDimension,
                screenOrientation = patch.screenOrientation
                    ?: template?.suggestedOrientation
                    ?: current.screenOrientation,
                gameSystems = planned,
                requestedSystems = requested,
                referenceGame = referenceGame,
                templateId = templateId,
                templateSimilarity = templateSimilarity,
                confidence = maxOf(current.confidence, patch.confidence),
                excludedSystems = excluded.toList(),
                uncheckedSystems = unchecked.toList(),
                lockTemplateResolution = true,
                firstUserRequest = current.firstUserRequest ?: userText.trim(),
                lastUserRequest = userText.trim()
            ),
            isNewGame = false,
            emptyEntities = false
        )
    }

    private fun resolveTemplate(patch: GameSchemaPatch): GameTemplateRef? {
        patch.templateId?.let { id -> TemplateLibrary.findById(id)?.let { return it } }
        if (patch.dropReference) return null
        return patch.referenceGame?.let { reference -> TemplateLibrary.match(reference)?.first }
    }

    private fun similarityOf(referenceGame: String?): Double? =
        referenceGame?.let { TemplateLibrary.match(it)?.second }

    /** 识别层最终要实现的系统：用户点名 + 模板补全；已排除/未勾选系统不得自动加回。 */
    fun plannedSystems(schema: GameSchema): List<String> = plannedSystems(
        requested = schema.gameSystems,
        templateId = schema.templateId,
        excluded = schema.excludedSystems.toSet(),
        unchecked = schema.uncheckedSystems.toSet()
    )

    private fun plannedSystems(
        requested: List<String>,
        templateId: String?,
        excluded: Set<String>,
        unchecked: Set<String> = emptySet()
    ): List<String> {
        val templateSystems = templateId?.let { TemplateLibrary.findById(it)?.suggestedSystems } ?: emptyList()
        val merged = (requested + templateSystems)
            .filter { GameSystemCatalog.isValid(it) && it !in excluded && it !in unchecked }
            .distinct()
        return merged.ifEmpty { DEFAULT_SYSTEMS.filterNot { it in excluded || it in unchecked } }
    }

    /** 识别层 Lite LLM 提示词：只输出 Game Schema 补丁 JSON。 */
    fun liteLlmPrompt(userText: String): String = """
        你是游戏特征识别器。请只输出一个 JSON 对象（不要 Markdown、不要解释），字段：
        {
          "visualDimension": "2D | 2.5D | 3D 或 null",
          "screenOrientation": "横板 | 竖版 或 null",
          "gameSystems": ["人物实体","道具","战斗","技能","属性等级","关卡场景","AI策略","商店经济","收集","解谜","物理","音乐节奏","塔防","合成","放置挂机","经营模拟","竞速","平台跳跃","弹幕射击","反应躲避"],
          "excludedSystems": ["玩家明确不要的系统"],
          "reAddSystems": ["玩家明确要求恢复的系统"],
          "referenceGame": "著名游戏名或 null",
          "templateId": "可匹配到的模板 id 或 null",
          "templateSimilarity": 0.0,
          "confidence": 0.0,
          "dropReference": false,
          "hasGameCommand": false
        }
        用户消息：$userText
    """.trimIndent()

    /**
     * 确认门人话摘要：只回显画面、方向、系统、对标游戏与默认假设。
     * 系统实现说明优先使用策划层 LLM 产出的玩家视角阐述，其次使用通用解释；
     * module_list 以 [ModuleCard] 结构化输出，卡片据此渲染勾选框。
     */
    fun buildConfirmation(
        userRequest: String,
        schema: GameSchema,
        draftPlan: DesignPlan? = null,
        isNewGame: Boolean = true,
        revised: Boolean = false
    ): IntentConfirmation {
        val planned = draftPlan?.gameSystems?.ifEmpty { plannedSystems(schema) } ?: plannedSystems(schema)

        val sb = StringBuilder()
        sb.append(if (isNewGame) "我识别到你想新建一个游戏" else "我识别到你要在当前游戏上继续修改")
        sb.append("：画面维度 ${schema.visualDimension}、画面方向 ${schema.screenOrientation}。")
        if (planned.isNotEmpty()) {
            sb.append("游戏系统：${planned.joinToString("、")}。")
        } else {
            sb.append("未明确指定游戏系统，默认按核心玩法补全：${plannedSystems(schema).joinToString("、")}。")
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
        sb.append("请核对每个系统的勾选状态、玩法解释、默认假设与排除项，确认后我会按此方案生成代码。")

        val assumptions = buildAssumptions(schema)
        val modules = buildModuleCards(schema, draftPlan, planned)
        val explanations = modules.map {
            "${it.module}：具体玩法为 ${it.implementation}；验收边界：${it.acceptanceBoundary}"
        }

        return IntentConfirmation(
            userRequest = userRequest,
            gameSchema = schema,
            intent = null,
            summary = sb.toString(),
            designAssumptions = assumptions,
            systemExplanations = explanations,
            modules = modules,
            excludedSystems = schema.excludedSystems,
            draftPlan = draftPlan,
            isNewGame = isNewGame,
            revised = revised
        )
    }

    /** module_list 的结构化回显项：优先策划层 LLM 玩家视角阐述，回退通用玩法说明。 */
    private fun buildModuleCards(
        schema: GameSchema,
        draftPlan: DesignPlan?,
        planned: List<String>
    ): List<ModuleCard> = draftPlan?.let { plan ->
        plan.implementations.map { impl ->
            ModuleCard(
                module = impl.system,
                implementation = playerFacingExplanation(schema, plan, impl),
                acceptanceBoundary = impl.acceptanceBoundary
            )
        }
    } ?: planned.map { system ->
        ModuleCard(
            module = system,
            implementation = GameSystemCatalog.playerFacingMethods(
                system = system,
                dimension = schema.visualDimension,
                orientation = schema.screenOrientation,
                templateId = schema.templateId
            ).joinToString("、"),
            acceptanceBoundary = GameSystemCatalog.acceptanceBoundary(system, schema.templateId)
        )
    }

    private fun playerFacingExplanation(
        schema: GameSchema,
        plan: DesignPlan,
        impl: SystemImplementation
    ): String {
        val facing = impl.playerFacing.trim()
        if (facing.isNotEmpty() && !containsTechnicalPhrase(facing)) {
            return facing
        }
        return GameSystemCatalog.playerFacingMethods(
            system = impl.system,
            dimension = plan.visualDimension,
            orientation = plan.screenOrientation,
            templateId = schema.templateId,
            plannedMethods = impl.methods
        ).joinToString("、")
    }

    private fun containsTechnicalPhrase(text: String): Boolean =
        TECHNICAL_PHRASES.any { text.contains(it, ignoreCase = true) }

    private val TECHNICAL_PHRASES = listOf(
        "Canvas", "requestAnimationFrame", "WebAudio", "DOM", "HTML", "CSS",
        "touchstart", "touchmove", "touchend", "eval", "require", "import(",
        "碰撞检测", "状态机", "算法", "引擎", "WebGL", "矩形判定", "路径点"
    )

    /** 被模板/默认值补全的设计假设，逐条回显。 */
    fun buildAssumptions(schema: GameSchema): List<String> {
        val result = mutableListOf<String>()
        val ref = schema.templateId?.let { TemplateLibrary.findById(it) }
        val requested = schema.requestedSystems.toSet()
        val excluded = schema.excludedSystems.toSet()

        if (ref != null) {
            if (schema.visualDimension == ref.suggestedDimension) {
                result.add("参考「${ref.title}」模板，画面维度按 ${ref.suggestedDimension} 实现")
            }
            if (schema.screenOrientation == ref.suggestedOrientation) {
                result.add("参考「${ref.title}」模板，画面方向按 ${ref.suggestedOrientation} 实现")
            }
            val added = schema.gameSystems.filterNot { it in requested || it in excluded }
            if (added.isNotEmpty()) {
                result.add("参考「${ref.title}」模板，将补全系统：${added.joinToString("、")}")
            }
        } else {
            if (schema.visualDimension == GameSchema.DIMENSION_2D) {
                result.add("画面维度默认假设为 2D")
            }
            if (schema.screenOrientation == GameSchema.ORIENTATION_PORTRAIT) {
                result.add("画面方向默认假设为竖版（手机单手操作）")
            }
        }
        if (schema.gameSystems.isEmpty() && ref == null) {
            result.add("未指定游戏系统，将按“反应躲避 + 收集”的默认轻量框架补全")
        }
        if (schema.excludedSystems.isNotEmpty()) {
            result.add("已按你的要求排除系统：${schema.excludedSystems.joinToString("、")}，策划与代码生成均不得实现")
        }
        return result
    }
}
