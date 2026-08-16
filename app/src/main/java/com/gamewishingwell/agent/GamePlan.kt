package com.gamewishingwell.agent

import kotlinx.serialization.Serializable

/**
 * 策划层输出：完整策划 Schema JSON。
 * 只做“选系统 + 覆盖参数”，不自由发明：系统来自意图层白名单，
 * 参数来自 template_class（画面维度 × 画面方向 × 主类型）特征矩阵。
 */
@Serializable
data class ComplexityBudget(
    val estimatedLines: Int,
    val estimatedScripts: Int,
    val maxExternalAssets: Int = 0
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
    val designAssumptions: List<String> = emptyList()
) {
    companion object {
        const val SCHEMA_VERSION = 1
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

    /** 模板 class = 画面维度 × 画面方向 × 主类型，只允许出现特征矩阵里的系统。 */
    fun build(intent: IntentSchema, existingTitle: String? = null): DesignPlan {
        val systems = (intent.gameSystems.ifEmpty { listOf("反应躲避", "收集") })
            .filter { GameSystemCatalog.isValid(it) }
            .distinct()
        val templateRef = intent.templateId?.let { TemplateLibrary.findById(it) }
        val merged = (systems + (templateRef?.suggestedSystems ?: emptyList())).distinct()
        val primary = derivePrimarySystem(merged, templateRef?.id)
        val matrixClass = "${intent.visualDimension}×${intent.screenOrientation}×${primary}"

        val p0 = linkedSetOf<String>()
        val p1 = linkedSetOf<String>()
        val p2 = linkedSetOf<String>()

        p0 += listOf("移动端触控输入", "核心循环可玩", "得分/胜负/重开", "requestAnimationFrame 主循环", "全局 restart() 完整重置")
        merged.forEach { system ->
            val spec = systemMatrix[system] ?: return@forEach
            val params = spec.defaultParams.map { "${system}：$it" }
            when (spec.layer) {
                0 -> p0 += params
                1 -> p1 += params
                else -> p2 += params
            }
        }
        p1 += "音效反馈（WebAudio）"
        p2 += "界面动效与难度曲线"

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
            designAssumptions = IntentEngine.buildAssumptions(intent)
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
            "静态校验 0 error（acorn 语法 / HTML 配对 / no-undef）",
            "WebView 冒烟测试：确定性跑满 8 帧且无未捕获异常",
            "无 eval、无动态 require、无外部资源、浏览器全局白名单内",
            "触控可用且不依赖键盘鼠标；全局 restart() 可重复调用"
        )
        systems.forEach { system ->
            val spec = systemMatrix[system] ?: return@forEach
            result += spec.defaultParams.firstOrNull()?.let { "已实现：${system}·$it" } ?: "已实现：$system"
        }
        if (templateRef != null) result += "对标「${templateRef.title}」的核心体验成立"
        result += "${intent.visualDimension} / ${intent.screenOrientation} 呈现正确"
        return result
    }

    /** 超预算降级：确定性裁剪 P1/P2，只保留 P0 机制。 */
    fun p0Only(plan: DesignPlan): DesignPlan = plan.copy(
        title = plan.title,
        p1Features = emptyList(),
        p2Features = emptyList(),
        gameSystems = plan.gameSystems,
        acceptanceChecklist = plan.acceptanceChecklist.filter { item ->
            item.startsWith("静态校验") || item.startsWith("WebView 冒烟") ||
                item.startsWith("无 eval") || item.startsWith("触控可用")
        } + "P0-only 降级版：只保留核心机制，资产用几何占位符"
    )

    /** 策划 Schema 的紧凑提示词片段（上下文最小化）。 */
    fun toPrompt(plan: DesignPlan): String = """
        <design_schema>
        schema_version:${plan.schemaVersion}
        template_class:${plan.templateClass}
        dimension:${plan.visualDimension}
        orientation:${plan.screenOrientation}
        primary_system:${plan.primarySystem}
        systems:${plan.gameSystems.joinToString(",")}
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
