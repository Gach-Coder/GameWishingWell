package com.gamewishingwell.agent

import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 策划层输出：完整策划 Schema JSON。
 * 只做“选系统 + 覆盖参数”，不自由发明：系统来自识别层共享的 Game Schema 白名单，
 * 参数来自 template_class（画面维度 × 画面方向 × 主类型）特征矩阵；
 * 每个系统的具体实现方式由策划层 LLM 结合该游戏阐述，模板库仅作种子与回退。
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
    val acceptanceBoundary: String,
    /** 策划层 LLM 生成的玩家视角系统阐述；为空时确认门回退到 methods 解释。 */
    val playerFacing: String = ""
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

    private val coreP0Features = listOf(
        "移动端触控输入", "核心循环可玩", "得分/胜负/重开",
        "requestAnimationFrame 主循环", "全局 restart() 完整重置"
    )

    // ---------- 新流程：策划层输入/输出共享同一份 Game Schema ----------

    fun draft(schema: GameSchema, existingTitle: String? = null): DesignPlan =
        build(schema, existingTitle)

    fun finalize(
        schema: GameSchema,
        confirmedDraft: DesignPlan? = null,
        existingTitle: String? = null,
        uncheckedModules: Set<String> = emptySet()
    ): DesignPlan {
        val effectiveSchema = schemaApplyingUnchecked(schema, uncheckedModules)
        val base = confirmedDraft ?: build(effectiveSchema, existingTitle)
        val title = existingTitle ?: base.title
        if (uncheckedModules.isEmpty()) {
            return base.copy(
                title = title,
                designAssumptions = base.designAssumptions.ifEmpty { RecognitionEngine.buildAssumptions(effectiveSchema) }
            )
        }

        // 确认门取消勾选的 module：直接从已确认草案中剔除（无 LLM 调用），
        // 派生字段（主系统/模板class/P0-P2/验收/排除清单）按剩余 module 重建。
        // 区别于明确排除：未勾选 module 不进入 excluded_systems，提示词中完全不出现，
        // 生成层只是“不在本轮范围内”，不是“禁止实现”。
        val implByModule = base.implementations.associateBy { it.system }
        val remaining = effectiveSchema.gameSystems
            .mapNotNull { GameSystemCatalog.admit(it) }
            .filter { it !in uncheckedModules }
            .distinct()
        val implementations = remaining.map { module ->
            implByModule[module] ?: staticImplementation(effectiveSchema, module)
        }
        val assembled = assemblePlan(effectiveSchema, title, remaining, implementations)
        return assembled.copy(
            designAssumptions = assembled.designAssumptions.ifEmpty { RecognitionEngine.buildAssumptions(effectiveSchema) }
        )
    }

    /**
     * 把确认门取消勾选的 module 落到 Game Schema：移出本轮实现范围并记入
     * uncheckedSystems；不写入 excludedSystems（那是玩家文本明确排除的语义）。
     */
    fun schemaApplyingUnchecked(schema: GameSchema, uncheckedModules: Set<String>): GameSchema {
        if (uncheckedModules.isEmpty()) return schema
        val valid = uncheckedModules.mapNotNull { GameSystemCatalog.admit(it) }.toSet()
        if (valid.isEmpty()) return schema
        return GameSchemaValidator.validate(
            schema.copy(
                gameSystems = schema.gameSystems.filter { it !in valid },
                uncheckedSystems = (schema.uncheckedSystems + valid).distinct()
            )
        )
    }

    /**
     * 策划层核心：根据 Game Schema JSON 整理系统列表，并用 LLM 细化每个系统
     * “在这款游戏里的具体实现方式”。LLM 失败时回退到模板库/通用系统矩阵，
     * 保证确认门仍可获得可用的策划草案。
     */
    suspend fun draftWithLlm(
        schema: GameSchema,
        llm: LlmClient,
        existingTitle: String? = null,
        currentUserRequest: String? = null
    ): DesignPlan {
        val base = build(schema, existingTitle)
        if (base.gameSystems.isEmpty()) return base

        val elaborations = requestLlmDesigns(llm, schema, base.gameSystems, currentUserRequest)
        return applyLlmSystemDesigns(base, schema, elaborations)
    }

    /**
     * Game Schema 版本的系统范围继承已随旧确认门管线一并移除：新流程由
     * [RecognitionEngine.recognize] 在同一份 Game Schema 上补全，确认门重组
     * 走"重建卡片 → 确认时统一策划定稿"，不再有独立的重组策划路径。
     */

    /** 新流程核心构建：Game Schema 中已经包含最终系统集合。
     *  allowEmptySystems=true 时不做默认系统兜底（直接制作路径：系统由生成
     *  LLM 按用户需求与指令原话自行推导，硬塞"反应躲避+收集"反而框住设计）。 */
    fun build(schema: GameSchema, existingTitle: String? = null, allowEmptySystems: Boolean = false): DesignPlan {
        val excluded = schema.excludedSystems.toSet()
        val unchecked = schema.uncheckedSystems.toSet()
        val fallbackSystems = listOf("反应躲避", "收集").filterNot { it in excluded || it in unchecked }
        val selected = schema.gameSystems
            .mapNotNull { GameSystemCatalog.admit(it) }
            .filter { it !in excluded && it !in unchecked }
            .distinct()

        val merged = selected
        val effective = if (allowEmptySystems) merged else merged.ifEmpty { fallbackSystems }

        val implementations = effective.map { staticImplementation(schema, it) }
        return assemblePlan(schema, existingTitle ?: defaultTitle(schema), effective, implementations)
    }

    /** 模板库/系统矩阵给出的静态实现：LLM 策划的种子与回退。 */
    private fun staticImplementation(schema: GameSchema, system: String): SystemImplementation {
        val spec = systemMatrix[system]
        return SystemImplementation(
            system = system,
            methods = GameSystemCatalog.implementationMethods(
                system = system,
                dimension = schema.visualDimension,
                orientation = schema.screenOrientation
            ),
            // 未知系统默认 P1（类型常见标配）：识别 LLM 抽出的核心玩法不因不在
            // 注册表就被当成锦上添花的打磨项。
            layer = spec?.layer ?: 1,
            acceptanceBoundary = GameSystemCatalog.acceptanceBoundary(system)
        )
    }

    /**
     * 由“已确定的 module 列表 + 每项实现方式”组装完整策划 Schema：
     * 主系统/模板class/P0-P2/验收清单/排除清单等派生字段全部由此重建，
     * 首次策划、确认门重组、确认剔除共用同一套派生逻辑。
     */
    private fun assemblePlan(
        schema: GameSchema,
        title: String,
        systems: List<String>,
        implementations: List<SystemImplementation>
    ): DesignPlan {
        val primary = derivePrimarySystem(systems)
        val matrixClass = "${schema.visualDimension}×${schema.screenOrientation}×${primary}"

        val p0 = linkedSetOf<String>()
        val p1 = linkedSetOf<String>()
        val p2 = linkedSetOf<String>()
        p0 += coreP0Features
        implementations.forEach { impl ->
            val params = impl.methods.take(4).map { "${impl.system}：$it" }
            when (impl.layer) {
                0 -> p0 += params
                1 -> p1 += params
                else -> p2 += params
            }
        }
        p1 += "音效反馈（WebAudio）"
        p2 += "界面动效与难度曲线"

        val lines = 220 + systems.size * 60
        val scripts = 1 + if (systems.size >= 4) 1 else 0

        return DesignPlan(
            templateClass = matrixClass,
            title = title,
            visualDimension = schema.visualDimension,
            screenOrientation = schema.screenOrientation,
            primarySystem = primary,
            gameSystems = systems,
            p0Features = p0.toList(),
            p1Features = p1.toList(),
            p2Features = p2.toList(),
            acceptanceChecklist = buildAcceptance(schema, implementations),
            complexityBudget = ComplexityBudget(estimatedLines = lines, estimatedScripts = scripts),
            designAssumptions = RecognitionEngine.buildAssumptions(schema),
            implementations = implementations,
            excludedSystems = buildExcludedSystems(schema),
            excludedApproaches = buildExcludedApproaches(schema)
        )
    }

    // ---------- 策划层 LLM 细化 ----------

    @Serializable
    data class LlmSystemDesign(
        val system: String = "",
        /** 玩家视角阐述：只聊玩法和规则，不聊引擎/算法/Canvas 等技术实现。 */
        val implementation: String = "",
        /** 生成层可执行的具体实现要点。 */
        val methods: List<String> = emptyList(),
        val acceptanceBoundary: String = "",
        /** 0=P0 核心玩法，1=P1 重要特性，2=P2 打磨项；非法值回退系统矩阵。 */
        val layer: Int = -1
    )

    @Serializable
    private data class LlmPlanningReply(
        val systems: List<LlmSystemDesign> = emptyList()
    )

    private val planningJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 供单元测试使用的纯解析入口。 */
    fun parseLlmSystemDesigns(raw: String): List<LlmSystemDesign> {
        if (raw.isBlank()) return emptyList()
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val objectStart = cleaned.indexOf('{')
        val arrayStart = cleaned.indexOf('[')
        val rawDesigns = when {
            objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart) -> {
                val objectEnd = cleaned.lastIndexOf('}')
                if (objectEnd <= objectStart) return emptyList()
                runCatching {
                    planningJson.decodeFromString<LlmPlanningReply>(
                        cleaned.substring(objectStart, objectEnd + 1)
                    )
                }.getOrNull()?.systems.orEmpty()
            }
            arrayStart >= 0 -> {
                val arrayEnd = cleaned.lastIndexOf(']')
                if (arrayEnd <= arrayStart) return emptyList()
                runCatching {
                    planningJson.decodeFromString<List<LlmSystemDesign>>(
                        cleaned.substring(arrayStart, arrayEnd + 1)
                    )
                }.getOrNull().orEmpty()
            }
            else -> emptyList()
        }
        return rawDesigns
            .mapNotNull { d -> GameSystemCatalog.admit(d.system)?.let { d.copy(system = it) } }
            .distinctBy { it.system }
    }

    /**
     * 策划子 Agent LLM 调用：只为 [systems] 中列出的 module 请求策划结果。
     * LLM 异常/输出非法时返回空列表，由调用方回退到静态种子或上一版实现。
     */
    private suspend fun requestLlmDesigns(
        llm: LlmClient,
        schema: GameSchema,
        systems: List<String>,
        currentUserRequest: String?
    ): List<LlmSystemDesign> {
        if (systems.isEmpty()) return emptyList()
        val reply = try {
            collectPlanningReply(llm, schema, currentUserRequest, systems)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return reply?.let { parseLlmSystemDesigns(it) }.orEmpty()
    }

    private suspend fun collectPlanningReply(
        llm: LlmClient,
        schema: GameSchema,
        currentUserRequest: String?,
        systems: List<String>
    ): String {
        val sb = StringBuilder()
        llm.streamChat(
            listOf(
                ChatMessage("system", GamePrompt.planningSystemPrompt(systems.size < schema.gameSystems.size)),
                ChatMessage("user", GamePrompt.planningPrompt(schema, currentUserRequest, systems))
            ),
            onDelta = { sb.append(it) },
            onThinking = {},
            onDone = {}
        )
        return sb.toString()
    }

    /** 单个 module 的 LLM 策划结果覆盖到回退实现上；缺失字段保留回退值。 */
    private fun mergeLlmDesign(
        fallback: SystemImplementation?,
        design: LlmSystemDesign
    ): SystemImplementation {
        val methods = design.methods
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
            .ifEmpty { fallback?.methods ?: emptyList() }
        return SystemImplementation(
            system = design.system,
            methods = methods,
            layer = design.layer.takeIf { it in 0..2 } ?: fallback?.layer ?: 2,
            acceptanceBoundary = design.acceptanceBoundary.trim().ifBlank { fallback?.acceptanceBoundary ?: "" },
            playerFacing = design.implementation.trim()
        )
    }

    /** 把 LLM 系统阐述覆盖到静态草案上；LLM 缺失/非法系统保留静态实现。 */
    private fun applyLlmSystemDesigns(
        base: DesignPlan,
        schema: GameSchema,
        designs: List<LlmSystemDesign>
    ): DesignPlan {
        if (designs.isEmpty()) return base

        val validBySystem = designs
            .filter { it.system in base.gameSystems }
            .associateBy { it.system }

        val implementations = base.implementations.map { old ->
            val llm = validBySystem[old.system]
            if (llm == null) old else mergeLlmDesign(old, llm)
        }
        return assemblePlan(schema, base.title, base.gameSystems, implementations)
    }

    private fun derivePrimarySystem(systems: List<String>): String {
        val priority = listOf("塔防", "弹幕射击", "平台跳跃", "竞速", "音乐节奏", "解谜", "合成", "战斗", "收集", "反应躲避")
        // 空系统时的回退必须是中性标签而不是某个具体系统名：伪装成系统名会同时
        // 污染阶段条展示（"正在生成「反应躲避」"）与 design_schema 的 primary_system，
        // 把生成往用户从未选择的系统上带。
        return priority.firstOrNull { systems.contains(it) } ?: systems.firstOrNull() ?: "核心玩法"
    }

    private fun buildAcceptance(
        schema: GameSchema,
        implementations: List<SystemImplementation>
    ): List<String> {
        val result = mutableListOf(
            "基础校验 0 error（JS 语法 / HTML 配对 / no-undef / 资源与代码契约）",
            "无 eval、无动态 require、无外部资源、浏览器全局白名单内",
            "触控可用且不依赖键盘鼠标；全局 restart() 可重复调用",
            "基础校验通过即退出 Agent Loop，交由玩家实际试玩并回传运行问题"
        )
        implementations.forEach { impl ->
            impl.methods.firstOrNull()?.let { result += "已实现：${impl.system}·$it" }
            result += "业务验收：${impl.system}·${impl.acceptanceBoundary}"
        }
        result += "${schema.visualDimension} / ${schema.screenOrientation} 呈现正确"
        return result
    }

    /**
     * 策划层的排除清单：只包含玩家在文本里明确排除的系统（如“不要商店经济”）。
     * 勾选机制是纯加法：design_schema.systems 即本轮要实现的范围，范围之外的
     * 系统不做任何禁止——模型按用户原话与类型标配的完整可玩版本自行取舍；
     * 取消勾选 ≠ 禁止实现（后续文本点名该系统自动恢复）。
     */
    private fun buildExcludedSystems(schema: GameSchema): List<String> =
        schema.excludedSystems.mapNotNull { GameSystemCatalog.admit(it) }.distinct()

    /** 由已确认的画面维度/方向推导必须排除的实现方案，并追加平台硬约束。 */
    private fun buildExcludedApproaches(schema: GameSchema): List<String> {
        val result = linkedSetOf<String>()
        when (schema.visualDimension) {
            GameSchema.DIMENSION_2D -> {
                result += "2.5D 斜 45° 渲染方案"
                result += "3D 场景/模型渲染方案"
            }
            GameSchema.DIMENSION_2_5D -> {
                result += "纯平面 2D 渲染方案"
                result += "3D 场景/模型渲染方案"
            }
            GameSchema.DIMENSION_3D -> {
                result += "纯平面 2D 渲染方案"
                result += "2.5D 斜 45° 渲染方案"
            }
        }
        result += if (schema.screenOrientation == GameSchema.ORIENTATION_PORTRAIT) {
            "横板/横屏布局方案"
        } else {
            "竖版/竖屏布局方案"
        }
        // 引擎条件化：3D 用内置 three.js（+真刚体需求可选 cannon）；物理系统用内置
        // matter.js（手写碰撞是 bug 重灾区）；2D/2.5D 默认 Canvas 2D（弹幕类性能
        // 不足时可选 pixi）。条件裁决见 GameEngines.allowedFor。
        result += if (schema.visualDimension == GameSchema.DIMENSION_3D) {
            "手写 Canvas 2D 软件光栅化模拟 3D（3D 必须使用内置 three.js 引擎：在 head 声明 <meta name=\"ww-engine\" content=\"three\">，平台渲染时自动注入）"
        } else {
            "3D 渲染方案（2D/2.5D 用 Canvas 2D 自绘；弹幕类同屏数百实体性能不足时可选内置 pixi.js）"
        }
        if (schema.gameSystems.contains("物理")) {
            result += "手写物理/碰撞模拟（物理系统必须使用内置 matter.js：声明 ww-engine 由平台注入；3D 真刚体需求可选 cannon）"
        }
        result += "外部模型/贴图资产（3D 几何与纹理一律程序化生成）"
        result += "联机对战、账号系统与服务端存档"
        result += "外部图片、音频、字体、CDN 与第三方 JS 库"
        result += "eval / new Function / 动态 require / import()"
        result += "多文件工程化构建（本轮只交付单个 index.html）"
        result += "照搬未授权商业游戏的美术素材与源码"
        return result.toList()
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
            "${impl.system}=${impl.methods.joinToString("|")}（玩家说明：${impl.playerFacing.ifBlank { "略" }}；验收：${impl.acceptanceBoundary}）"
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

    private fun defaultTitle(schema: GameSchema): String =
        schema.requestedSystems.firstOrNull()?.let { "$it 小游戏" }
            ?: schema.gameSystems.firstOrNull()?.let { "$it 小游戏" }
            ?: "我的小游戏"

}
