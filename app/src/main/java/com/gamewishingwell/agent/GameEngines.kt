package com.gamewishingwell.agent

/**
 * 内置 JS 引擎库（平台注入而非游戏引用）：
 * - 生成模型在 HTML <head> 里写声明标记 <meta name="ww-engine" content="three,matter">；
 * - 渲染前 HtmlEnhancer 检测声明，把 APK assets/engines/<file> 源码注入 <head>
 *   （真实游戏页与沙箱共用同一条注入管道）；
 * - 游戏文件保持文本自包含（不含任何 src 引用），离线可玩承诺不破，
 *   GameValidator 的外部资源禁令原样保留。
 *
 * 条件引入机制（tier 制）：每个引擎绑定触发条件（画面维度 × 游戏系统 × 质量档位）——
 * - 提示词只"教"满足条件的引擎（GamePrompt.engineContract 按条件组装范式段）；
 * - 校验层打回不满足条件的声明（GameValidator.validate(allowedEngines)），
 *   防止贪心模型给贪吃蛇之类的小游戏强上物理/渲染引擎。
 *
 * 白名单准入条件：单文件无依赖（UMD 全局）、版本锁定、真机与沙箱注入路径一致、
 * 提示词有使用范式。新增引擎 = 注册表加一行 + assets 放文件 + 范式文案 + 单测。
 */
object GameEngines {

    const val META_NAME = "ww-engine"

    const val THREE = "three"
    const val MATTER = "matter"
    const val PIXI = "pixi"
    const val CANNON = "cannon"
    const val TWEEN = "tween"

    /** 引擎注册项：全局对象名 + assets 文件名 + 触发条件描述（提示词报错文案用）。 */
    data class EngineSpec(
        val name: String,
        val globalName: String,
        val file: String,
        val condition: String
    )

    private val THREE_SPEC = EngineSpec(THREE, "THREE", "three.min.js", "3D 游戏")
    private val MATTER_SPEC = EngineSpec(MATTER, "Matter", "matter.min.js", "含物理系统的游戏")
    private val PIXI_SPEC = EngineSpec(PIXI, "PIXI", "pixi.min.js", "弹幕类同屏数百活动实体（可选）")
    private val CANNON_SPEC = EngineSpec(CANNON, "CANNON", "cannon.min.js", "3D 且需真刚体物理（可选）")
    private val TWEEN_SPEC = EngineSpec(TWEEN, "TWEEN", "tween.umd.js", "精品档打磨动效")

    /** 已打包进 APK 的全部引擎（渲染层注入与全局白名单的并集）。 */
    val BUNDLED: Map<String, EngineSpec> = listOf(THREE_SPEC, MATTER_SPEC, PIXI_SPEC, CANNON_SPEC, TWEEN_SPEC)
        .associateBy { it.name }

    /** 引擎名 → assets 内的路径。 */
    fun assetPath(engine: String): String? = BUNDLED[engine]?.let { "engines/${it.file}" }

    /**
     * 本轮生成允许使用的引擎集合（条件引入机制的裁决点）：
     * - three：3D 必须；
     * - matter：物理系统必须（手写碰撞是实测 bug 重灾区）；
     * - pixi：弹幕射击系统时可选（默认仍 Canvas 2D 自绘）；
     * - cannon：3D+物理时可选（体素 AABB 手写更简，真刚体才需要）；
     * - tween：精品档可选（打磨轮补间动效）。
     */
    fun allowedFor(
        visualDimension: String,
        systems: Collection<String>,
        tier: String = QualityTier.BALANCED
    ): Set<String> {
        val allowed = linkedSetOf<String>()
        val is3D = visualDimension == GameSchema.DIMENSION_3D
        val hasPhysics = systems.contains("物理")
        if (is3D) allowed += THREE
        if (hasPhysics) allowed += MATTER
        if (systems.contains("弹幕射击")) allowed += PIXI
        if (is3D && hasPhysics) allowed += CANNON
        if (QualityTier.normalize(tier) == QualityTier.PREMIUM) allowed += TWEEN
        return allowed
    }

    private val metaRegex = Regex(
        """<meta\b[^>]*\bname\s*=\s*["']$META_NAME["'][^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val contentRegex = Regex(
        """\bcontent\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    /** 从 HTML 中解析声明的引擎列表（content 支持逗号分隔多个，容忍属性顺序与引号风格）。 */
    fun declaredEngines(html: String): List<String> = metaRegex.findAll(html)
        .mapNotNull { contentRegex.find(it.value)?.groupValues?.get(1) }
        .flatMap { it.split(',').map(String::trim).filter(String::isNotEmpty) }
        .distinct()
        .toList()

    /** 便捷入口：按 schema（维度+系统）与档位判定允许集合。 */
    fun allowedFor(schema: GameSchema, tier: String = QualityTier.BALANCED): Set<String> =
        allowedFor(schema.visualDimension, schema.gameSystems + schema.requestedSystems, tier)
}
