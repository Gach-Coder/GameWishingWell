package com.gamewishingwell.agent

import kotlinx.serialization.Serializable

/**
 * 策划层输出：完整策划 Schema JSON。
 * 只做“选系统 + 覆盖参数”，不自由发明：系统来自意图层白名单，
 * 参数来自 template_class（画面维度 × 画面方向 × 主类型）特征矩阵。
 * 定稿同时写明“要实现什么 / 怎么实现 / 明确不做什么”。
 */
@Serializable
data class ComplexityBudget(
    val estimatedLines: Int,
    val estimatedScripts: Int,
    val maxExternalAssets: Int = 0
)

@Serializable
data class SystemImplementation(
    val system: String,
    val methods: List<String>,
    val layer: Int,
    val acceptanceBoundary: String
)

@Serializable
data class DesignPlan(
    val schemaVersion: Int = SCHEMA_VERSION,
    val templateClass: String,
    val title: String,
    val visualDimension: String,
    val screenOrientation: String,
    val primarySystem: String,
    val gameSystems: List<String>,
    val p0Features: List<String>,
    val p1Features: List<String>,
    val p2Features: List<String>,
    val acceptanceChecklist: List<String>,
    val complexityBudget: ComplexityBudget,
    val designAssumptions: List<String> = emptyList(),
    /** 已确认系统及其落地实现方法，供生成层逐项执行。 */
    val implementations: List<SystemImplementation> = emptyList(),
    /** 已确认排除的系统（玩家明确不要的 + 未进入本轮范围的白名单系统）。 */
    val excludedSystems: List<String> = emptyList(),
    /** 已排除的技术/产品方案，避免生成层扩大实现范围。 */
    val excludedApproaches: List<String> = emptyList()
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }

    val allSystems: List<String> get() = gameSystems
}

object PlanningEngine {

    private data class SystemSpec(
        val id: String,
        val defaultParams: List<String>,
        val layer: Int
    )

    private val systemMatrix: Map<String, SystemSpec> = mapOf(
        "人物实体" to SystemSpec("人物实体", listOf("1个玩家角色", "基础移动/朝向"), 0),
        "道具" to SystemSpec("道具", listOf("2-3种可拾取道具", "道具效果反馈"), 1),
        "战斗" to SystemSpec("战斗", listOf("1类敌人", "攻击与受击判定", "胜负条件"), 0),
        "技能" to SystemSpec("技能", listOf("1-2个主动技能", "冷却与释放"), 2),
        "属性等级" to SystemSpec("属性等级", listOf("等级/分数成长", "升级后数值变化"), 1),
        "关卡场景" to SystemSpec("关卡场景", listOf("3个递进关卡", "关卡切换"), 1),
        "AI策略" to SystemSpec("AI策略", listOf("简单状态机AI", "可读的决策规则"), 2),
        "商店经济" to SystemSpec("商店经济", listOf("金币产出", "可购买升级"), 2),
        "收集" to SystemSpec("收集", listOf("掉落物收集", "收集计数反馈"), 0),
        "解谜" to SystemSpec("解谜", listOf("1套核心谜题规则", "胜利判定"), 0),
        "物理" to SystemSpec("物理", listOf("简化的重力/碰撞", "确定性运动参数"), 0),
        "音乐节奏" to SystemSpec("音乐节奏", listOf("节拍判定", "命中反馈"), 0),
        "塔防" to SystemSpec("塔防", listOf("可建防御塔", "敌人波次", "生命值"), 0),
        "合成" to SystemSpec("合成", listOf("同元素合成规则", "合成升级"), 0),
        "放置挂机" to SystemSpec("放置挂机", listOf("自动产出", "离线/时间收益"), 0),
        "经营模拟" to SystemSpec("经营模拟", listOf("资源循环", "建造/升级"), 0),
        "竞速" to SystemSpec("竞速", listOf("速度与操控", "完成条件"), 0),
        "平台跳跃" to SystemSpec("平台跳跃", listOf("跳跃与平台碰撞", "失败重置"), 0),
        "弹幕射击" to SystemSpec("弹幕射击", listOf("玩家射击", "敌弹幕", "命中判定"), 0),
        "反应躲避" to SystemSpec("反应躲避", listOf("点按/滑动响应", "失误判定", "计分"), 0)
    )

    /**
     * 策划层：确认门前先产出各系统玩法草案，供确认门逐项回显。
     * 该草案不进入代码生成；玩家确认后由 [finalize] 定稿。
     */
    fun draft(intent: IntentSchema, existingTitle: String? = null): DesignPlan = build(intent, existingTitle)

    /**
     * 决策层：根据用户最后确认的完整信息（需要实现的系统及方法、需要排除的系统和方案）
     * 输出完整策划 Schema JSON。
     */
    fun finalize(intent: IntentSchema, existingTitle: String? = null): DesignPlan = build(intent, existingTitle)

    /**
     * 修改已有游戏时，意图层可能只识别出局部修改词（如“加连击计分”）。
     * 这里继承上一版策划方案中的系统，并叠加本轮明确新增/排除的系统；
     * 玩家明确换了对标模板时不继承旧方案，避免新旧范围混在一起。
     */
    fun inheritExistingDesign(
        intent: IntentSchema,
        previous: DesignPlan?,
        userText: String? = null
    ): IntentSchema {
        if (intent.intent != IntentSchema.INTENT_MODIFY_GAME || previous == null) return intent
        if (intent.templateId != null || intent.referenceGame != null) return intent

        val excluded = intent.excludedSystems.toSet()
        val inherited = previous.gameSystems
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
        val merged = (inherited + intent.gameSystems)
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
            .distinct()

        // 局部修改时没有重新声明维度/方向，则继承上一版，避免“加点难度”把 3D 改回 2D。
        val dimension = if (userText != null && !IntentEngine.explicitlySpecifiesDimension(userText)) {
            previous.visualDimension
        } else {
            intent.visualDimension
        }
        val orientation = if (userText != null && !IntentEngine.explicitlySpecifiesOrientation(userText)) {
            previous.screenOrientation
        } else {
            intent.screenOrientation
        }

        return intent.copy(
            gameSystems = merged,
            visualDimension = dimension,
            screenOrientation = orientation
        )
    }

    /** 模板 class = 画面维度 × 画面方向 × 主类型，只允许出现特征矩阵里的系统。 */
    fun build(intent: IntentSchema, existingTitle: String? = null): DesignPlan {
        val excluded = intent.excludedSystems.toSet()
        val fallbackSystems = listOf("反应躲避", "收集").filterNot { it in excluded }
        val explicitSystems = intent.gameSystems
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
            .distinct()
            .ifEmpty { fallbackSystems }
        val templateRef = intent.templateId?.let { TemplateLibrary.findById(it) }
        val merged = (explicitSystems + (templateRef?.suggestedSystems ?: emptyList()))
            .filter { GameSystemCatalog.isValid(it) && it !in excluded }
            .distinct()
            .ifEmpty { fallbackSystems }

        val primary = derivePrimarySystem(merged, templateRef?.id)
        val matrixClass = "${intent.visualDimension}×${intent.screenOrientation}×${primary}"

        val p0 = linkedSetOf<String>()
        val p1 = linkedSetOf<String>()
        val p2 = linkedSetOf<String>()

        p0 += listOf("移动端触控输入", "核心循环可玩", "得分/胜负/重开", "requestAnimationFrame 主循环", "全局 restart() 完整重置")
        merged.forEach { system ->
            val spec = systemMatrix[system] ?: return@forEach
            val templateSpec = TemplateSystemCatalog.resolve(intent.templateId, system)
            // 有模板具体实现时，P 层特性直接使用模板玩法；没有才回退通用系统矩阵。
            val featureBasis = templateSpec?.methods ?: spec.defaultParams
            val layer = templateSpec?.layer ?: spec.layer
            val params = featureBasis.map { "${system}：$it" }
            when (layer) {
                0 -> p0 += params
                1 -> p1 += params
                else -> p2 += params
            }
        }
        p1 += "音效反馈（WebAudio）"
        p2 += "界面动效与难度曲线"

        val implementations = merged.map { system ->
            val spec = systemMatrix[system]
            SystemImplementation(
                system = system,
                methods = GameSystemCatalog.implementationMethods(
                    system = system,
                    dimension = intent.visualDimension,
                    orientation = intent.screenOrientation,
                    templateId = intent.templateId
                ),
                layer = TemplateSystemCatalog.resolve(intent.templateId, system)?.layer ?: spec?.layer ?: 2,
                acceptanceBoundary = GameSystemCatalog.acceptanceBoundary(system, intent.templateId)
            )
        }
        val acceptance = buildAcceptance(intent, merged, templateRef)
        val lines = 220 + merged.size * 60
        val scripts = 1 + if (merged.size >= 4) 1 else 0

        return DesignPlan(
            templateClass = matrixClass,
            title = existingTitle ?: defaultTitle(intent),
            visualDimension = intent.visualDimension,
            screenOrientation = intent.screenOrientation,
            primarySystem = primary,
            gameSystems = merged,
            p0Features = p0.toList(),
            p1Features = p1.toList(),
            p2Features = p2.toList(),
            acceptanceChecklist = acceptance,
            complexityBudget = ComplexityBudget(estimatedLines = lines, estimatedScripts = scripts),
            designAssumptions = IntentEngine.buildAssumptions(intent),
            implementations = implementations,
            excludedSystems = buildExcludedSystems(intent, merged),
            excludedApproaches = buildExcludedApproaches(intent, templateRef)
        )
    }

    private fun derivePrimarySystem(systems: List<String>, templateId: String?): String {
        val priority = listOf("塔防", "弹幕射击", "平台跳跃", "竞速", "音乐节奏", "解谜", "合成", "战斗", "收集", "反应躲避")
        val byTemplate = templateId?.let { id ->
            TemplateLibrary.findById(id)?.suggestedSystems?.firstOrNull { systems.contains(it) }
        }
        return byTemplate ?: priority.firstOrNull { systems.contains(it) } ?: systems.firstOrNull() ?: "反应躲避"
    }

    private fun buildAcceptance(
        intent: IntentSchema,
        systems: List<String>,
        templateRef: GameTemplateRef?
    ): List<String> {
        val result = mutableListOf(
            "基础校验 0 error（JS 语法 / HTML 配对 / no-undef / 资源与代码契约）",
            "无 eval、无动态 require、无外部资源、浏览器全局白名单内",
            "触控可用且不依赖键盘鼠标；全局 restart() 可重复调用",
            "基础校验通过即退出 Agent Loop，交由玩家实际试玩并回传运行问题"
        )
        systems.forEach { system ->
            val spec = systemMatrix[system] ?: return@forEach
            val firstFeature = TemplateSystemCatalog.resolve(intent.templateId, system)?.methods?.firstOrNull()
                ?: spec.defaultParams.firstOrNull()
            result += firstFeature?.let { "已实现：${system}·$it" } ?: "已实现：$system"
            result += "业务验收：${system}·${GameSystemCatalog.acceptanceBoundary(system, intent.templateId)}"
        }
        if (templateRef != null) result += "对标「${templateRef.title}」的核心体验成立"
        result += "${intent.visualDimension} / ${intent.screenOrientation} 呈现正确"
        return result
    }

    /**
     * 策划层的排除清单：
     * 1) 玩家明确排除的系统；
     * 2) 白名单里未进入本次范围的其他系统，生成层不得擅自添加。
     */
    private fun buildExcludedSystems(intent: IntentSchema, selected: List<String>): List<String> {
        val result = linkedSetOf<String>()
        result += intent.excludedSystems.filter { GameSystemCatalog.isValid(it) }
        result += GameSystemCatalog.ALL.filterNot { it in selected }
        return result.toList()
    }

    /** 由已确认的画面维度/方向推导必须排除的实现方案，并追加平台硬约束。 */
    private fun buildExcludedApproaches(intent: IntentSchema, templateRef: GameTemplateRef?): List<String> {
        val result = linkedSetOf<String>()
        when (intent.visualDimension) {
            IntentSchema.DIMENSION_2D -> {
                result += "2.5D 斜 45° 渲染方案"
                result += "3D 场景/模型渲染方案"
            }
            IntentSchema.DIMENSION_2_5D -> {
                result += "纯平面 2D 渲染方案"
                result += "3D 场景/模型渲染方案"
            }
            IntentSchema.DIMENSION_3D -> {
                result += "纯平面 2D 渲染方案"
                result += "2.5D 斜 45° 渲染方案"
            }
        }
        result += if (intent.screenOrientation == IntentSchema.ORIENTATION_PORTRAIT) {
            "横板/横屏布局方案"
        } else {
            "竖版/竖屏布局方案"
        }
        result += "WebGL / 原生 3D 引擎 / 外部模型资产"
        result += "联机对战、账号系统与服务端存档"
        result += "外部图片、音频、字体、CDN 与第三方 JS 库"
        result += "eval / new Function / 动态 require / import()"
        result += "多文件工程化构建（本轮只交付单个 index.html）"
        if (templateRef == null) {
            result += "照搬未授权商业游戏的美术素材与源码"
        }
        return result.toList()
    }

    /** 超预算降级：确定性裁剪 P1/P2，只保留 P0 机制。 */
    fun p0Only(plan: DesignPlan): DesignPlan {
        val p0Systems = plan.implementations
            .filter { it.layer == 0 }
            .map { it.system }
            .toSet()
            .let { layered ->
                // 兼容旧会话里没有 implementations 的 DesignPlan，回退通用系统矩阵。
                layered.ifEmpty { plan.gameSystems.filter { systemMatrix[it]?.layer == 0 }.toSet() }
            }
        return plan.copy(
            title = plan.title,
            gameSystems = plan.gameSystems.filter { it in p0Systems },
            p1Features = emptyList(),
            p2Features = emptyList(),
            implementations = plan.implementations.filter { it.system in p0Systems },
            excludedSystems = (plan.excludedSystems + plan.gameSystems.filterNot { it in p0Systems }).distinct(),
            acceptanceChecklist = plan.acceptanceChecklist.filter { item ->
                item.startsWith("基础校验") ||
                    item.startsWith("无 eval") || item.startsWith("触控可用") ||
                    (item.startsWith("业务验收：") && p0Systems.any { system ->
                        item.startsWith("业务验收：${system}·")
                    })
            } + "P0-only 降级版：只保留核心机制，资产用几何占位符"
        )
    }

    /** 策划 Schema 的紧凑提示词片段（上下文最小化）。 */
    fun toPrompt(plan: DesignPlan): String = """
        <design_schema>
        schema_version:${plan.schemaVersion}
        template_class:${plan.templateClass}
        dimension:${plan.visualDimension}
        orientation:${plan.screenOrientation}
        primary_system:${plan.primarySystem}
        systems:${plan.gameSystems.joinToString(",")}
        implementations:${plan.implementations.joinToString(";") { impl ->
            "${impl.system}=${impl.methods.joinToString("|")}（验收：${impl.acceptanceBoundary}）"
        }}
        excluded_systems:${plan.excludedSystems.joinToString(",")}
        excluded_approaches:${plan.excludedApproaches.joinToString("；")}
        p0:${plan.p0Features.joinToString(";")}
        p1:${plan.p1Features.joinToString(";")}
        p2:${plan.p2Features.joinToString(";")}
        acceptance:${plan.acceptanceChecklist.joinToString(";")}
        budget:${plan.complexityBudget.estimatedLines} lines / ${plan.complexityBudget.estimatedScripts} scripts
        </design_schema>
    """.trimIndent()

    private fun defaultTitle(intent: IntentSchema): String {
        val ref = intent.templateId?.let { TemplateLibrary.findById(it) }
        if (ref != null) return ref.title
        return intent.gameSystems.firstOrNull()?.let { "$it 小游戏" } ?: "我的小游戏"
    }
}
