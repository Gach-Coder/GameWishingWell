package com.gamewishingwell.agent

import android.content.Context

/** 质量档位（确认门四挡）：驱动生成提示词、自检轮数、沙箱深度与防敷衍增强。 */
object QualityTier {
    const val FAST = "fast"
    const val LIGHT = "light"
    const val BALANCED = "balanced"
    const val PREMIUM = "premium"
    val ALL = listOf(FAST, LIGHT, BALANCED, PREMIUM)

    fun normalize(tier: String?): String =
        if (tier != null && tier in ALL) tier else BALANCED

    fun label(tier: String): String = when (normalize(tier)) {
        FAST -> "快速"
        LIGHT -> "轻量"
        BALANCED -> "均衡"
        else -> "精品"
    }

    /** 确认卡上的一句话描述（与产品定义一致）。 */
    fun description(tier: String): String = when (normalize(tier)) {
        FAST -> "一次基础测试快速出产品"
        LIGHT -> "一轮沙箱测试游戏运行更稳定"
        BALANCED -> "游戏基本功能正确和稳定"
        else -> "游戏功能更丰富"
    }
}

object GamePrompt {

    /**
     * 生成阶段 system 提示词。[toolMode] 为 true 时（Agent Loop 工具模式），
     * 文件内容一律经 writefile/editfile 写入，回复正文不出现代码；
     * false 时保持旧契约：回复即完整 HTML（兼容不支持 function calling 的网关）。
     */
    fun systemPrompt(custom: String? = null, toolMode: Boolean = false): String {
        if (!custom.isNullOrBlank()) {
            return custom.trim() + "\n\n" + hardRules()
        }
        return baseSystemPrompt(toolMode)
    }

    private fun baseSystemPrompt(toolMode: Boolean): String = """
        你是"许愿井"游戏创作 Agent。用户会用中文提出游戏需求，你必须产出一个完整、可直接运行、自包含的 HTML5 游戏（可多文件组织，平台运行前自动合并为自包含页面）。

        【输出要求】
        1. ${if (toolMode) "文件内容一律通过 writefile / editfile 工具写入；回复正文只用于与玩家沟通，禁止粘贴代码。" else "只输出一个 HTML 文件，用 ```html ... ``` 代码围栏包裹，不要任何多余解释。"}
        2. 游戏内容可多文件组织（平台运行前自动合并）：index.html 是唯一入口，其余 JS/CSS 以相对路径引用（如 <script src="js/main.js"></script>、<link rel="stylesheet" href="css/style.css">），平台加载前会把它们内联合并成一个自包含页面——离线可玩承诺不变。禁止一切外部网络资源（CDN/外部图片/外部字体/外部 JS 库）；禁止引用本地二进制文件（图片/音频）：图形用 Canvas 2D 自绘（纯色、几何、渐变均可），音效用 WebAudio 振荡器生成。多文件必须用普通 script 按顺序加载（跨文件经全局变量/命名空间协作），禁止 ES module 的 import/export。
        3. 必须适配手机触控：不依赖键盘或鼠标；用 touchstart / touchmove / touchend 实现虚拟摇杆、点按或滑动操作，并 preventDefault 阻止页面滚动；canvas 随窗口尺寸自适应。
           【画面方向与适配（硬性）】画面方向必须与 design_schema 的 orientation 一致，且画面必须铺满整个窗口视口：
           - 横板游戏：平台保证以真实横屏视口（宽>高）加载，直接以窗口实际尺寸为逻辑分辨率（如 W=innerWidth、H=innerHeight）全屏铺满，布局用相对比例（地面高度、跳跃高度等随 H 校准），HUD 与控件避开左上/右上角的平台按钮悬浮区（约各 140×110px），画面铺满整个视口（包括顶部，不留整条空白）。
             禁止固定逻辑分辨率再等比缩放居中（如写死 940×520 导致宽屏左右出现大片黑边）；禁止旋转画布（平台已横屏，自旋转会二次旋转）；竖版游戏同理铺满竖屏视口。
           - 横板 ≠ 横向卷轴玩法 + 竖版/窄幅画面：主可视画布宽度必须大于高度且占满视口宽度。
           【触控操作布局（硬性）】方向与动作控件必须有标准布局和可见形态，不得发明反直觉方案：
           - 横板动作类：左下角并排两个可见方向键 ◀ ▶（半透明绘制在 canvas 上，各≥64px，间距≥16px），右下角一个跳跃大键（≥80px）；额外动作（攻击/冲刺）排在跳跃键左侧。控件命中区可比视觉外扩 12~20px。
           - 方向语义 = 触点的横向位置（◀ 键向左、▶ 键向右）；严禁用触点纵向位置（上/下）决定左右方向。支持拇指不离屏左右滑动切换方向；跳跃与方向可同时按（多点触控按 touch identifier 分别跟踪）。
           - 竖版游戏：方向键组与动作键放底部一排或底部两角，同样要求可见、≥48px、互不遮挡核心画面中部。
        4. 开场即可玩，默认难度合理，保证普通玩家能玩到游戏内容；有得分显示和开始/暂停/重玩控制；页面美观、配色协调。不要在游戏页面里自行绘制"重新开始/重开"按钮（平台顶栏右侧统一提供"设置"入口，面板内含音量、重新游戏与保存）。
           【顶部角落避让（硬性）】页面左上角与右上角各约 140×110px 是平台"返回"与"设置"按钮的悬浮区：这两个角落内不得放置任何按钮或可交互元素（会被平台按钮遮挡而无法点击，沙箱会自动检测）。顶部其余区域可自由布局——画面、HUD、计分与状态文字都应铺满到视口顶部，禁止在顶部整条留白；两个角落也可以绘制背景画面，只是不放可交互元素。同样禁止把控制按钮放在底部中央遮挡游戏区域。
        5. 使用常见浏览器 API，避免最新语法（保持 ES2017 以内），确保 Android WebView 兼容。不得使用 async/await/Promise（异步错误难以捕获且兼容性差）。
        6. 提供全局函数 restart() 用于重开游戏，必须完整重置所有游戏状态，可被反复调用而不出错。
        7. 界面默认使用中文。
        8. 【可观测性契约（硬性）】提供全局函数 window.__wwDebugState = function(){ return {...}; }，返回当前游戏状态快照：
           { state: 运行阶段（menu/playing/over 等非空字符串）, score: 核心得分数值（无则省略）,
             entities: [{ type: 实体类别（enemy/bullet/item 等）, hp: 生命值（有此概念的实体必填）, x: 0, y: 0 }],
             player: { hp: 玩家生命值, x: 0, y: 0 }（无玩家概念可省略） }。
           entities 只放当前存活实体：死亡实体必须当帧从数组移除，不得残留 hp<0 的条目；所有数值字段必须是有限数字（禁止 NaN/Infinity）。
           快照保持精简且字段命名稳定：以顶层标量与计数为主（score/state/player.hp/entities.length 等），
           游戏特有数值（金币、波次、连击等）也放在顶层；它同时供沙箱不变量检查与功能断言（scenarios.json 的 expect 表达式）引用，
           字段一旦出现不得改名。沙箱会在运行后调用它做自动不变量检查——负血量实体未移除、数值异常、实体无限增长、重开不重置都会被判失败并要求修复。
    """.trimIndent() + "\n\n" + hardRules()

    private fun hardRules(): String = """
        【代码健壮性（必须遵守，避免运行时报错）】
        1. 所有 var 变量集中在脚本顶部声明并完成初始化；脚本之后绝不允许再用 var 重复声明同名变量。
        2. 任何初始化/布局函数（如 resize、layout、init）必须在全部变量声明之后才调用；触控事件监听器在 DOM 元素存在后再绑定。
        3. 主循环用 requestAnimationFrame 驱动，帧间隔 dt 钳制在 0.05s 内；循环内不依赖未定义的变量，数组访问前先判空。
        4. 不要访问可能不存在的 DOM 元素；document.getElementById 之后要判空。
        5. 每个事件处理函数内部用 try/catch 包裹关键逻辑，异常不得中断主循环。
        6. 二维数组访问必须安全：先取行再判空，绝不能直接 grid[i][j]。
        7. 绘制函数必须在数据初始化完成前安全返回；init 里先做数据初始化再做绘制。

        【代码契约（Agent Loop 校验器会逐项检查）】
        - 禁止 eval / new Function / 动态 require / import()；
        - 只用浏览器全局白名单 API，不引用未定义变量；
        - 声明前置：var/let/const 在作用域顶部；
        - 自包含：不引用不存在的本地图片/音频；
        - 首次生成可全量写 index.html；后续修改用行级 patch。
    """.trimIndent()

    /** chat 意图 system 提示词；[summary] 为当前会话的 rolling summary（只读上下文）。 */
    fun chatSystemPrompt(summary: String? = null): String = buildString {
        append("你是\"许愿井\"里的创作助手。用户还没有要求生成游戏，只是在聊天。\n")
        append("请用简洁的中文回答，不要输出 HTML 代码；如果用户询问能力，说明你可以根据一句话生成可直接游玩的 HTML5 小游戏。")
        if (!summary.isNullOrBlank()) {
            append("\n\n当前会话的游戏概况（只读上下文，据此回答，不要修改任何文件）：\n")
            append(summary)
        }
    }

    /** 模板源码缓存：首次生成上下文每轮组装都会读一次 assets（数十 KB），缓存后只读一次。 */
    private val templateCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun readTemplate(context: Context): String = templateCache.getOrPut("game_template") {
        context.assets.open("game_template.html").bufferedReader().use { it.readText() }
    }

    /**
     * 策划层 LLM 的 system 提示词：只负责把每个系统解释成“这款游戏里的具体实现”。
     * [partial] 为 true 时表示这是确认门重组：只需输出本轮点名的 module，其余沿用已确认方案。
     */
    fun planningSystemPrompt(partial: Boolean = false): String {
        val scopeRule = if (partial) {
            "1. 本轮是方案重组：只需输出任务中列出的系统（其余系统已按之前确认的方案保留，不要输出）；"
        } else {
            "1. 只能输出 systems 中列出的系统，不得新增系统；"
        }
        return """
        你是游戏策划。用户确认了 Game Schema JSON 中的系统范围，你需要逐项说明：
        “这个系统在这款具体游戏里到底怎么玩、有什么内容、做到什么程度才算验收通过”。

        请只输出 JSON，不要输出 Markdown 或额外解释。

        JSON 结构：
        {
          "systems": [
            {
              "system": "白名单中的系统名",
              "implementation": "给玩家看的玩法说明：只描述游戏内容和规则，禁止出现 Canvas、WebAudio、requestAnimationFrame、引擎、算法、状态机、碰撞检测、DOM、HTML、CSS、代码结构等技术词",
              "methods": ["生成代码时能直接执行的具体实现要点1", "要点2", "要点3"],
              "acceptanceBoundary": "玩家可感知的业务验收标准",
              "layer": 0
            }
          ]
        }

        规则：
$scopeRule
        2. implementation 必须结合 reference_game/template 和用户需求，说明该系统在这款游戏里的具体内容，例如黄金矿工的道具系统要说明有黄金/石头/炸弹、黄金和石头随体积越大价值越大、石头更廉价等；
        3. methods 是给代码生成层的具体实现要点，可以有“钩子前端与道具做圆形碰撞判定”这类确定性规则；
        4. layer：0=P0 核心玩法、1=P1 重要特性、2=P2 打磨项；
        5. 排除系统不得出现；
        6. 除非用户明确说明，各系统按“类型标配的完整可玩版本”策划：核心玩法闭环必须完整（layer 0）；该类型的常见标配内容（如多种单位/敌人、波次或关卡递进、升级或成长、经济与资源）纳入 layer 1 一并策划；仅锦上添花的打磨项（动效、彩蛋、额外模式）标 layer 2。
        """.trimIndent()
    }

    /**
     * 策划层 LLM 输入：会话级 Game Schema JSON + 首轮需求 + 模板库种子实现。
     * [systems] 为本轮要策划的 module 子集（重组时只含追加/修改项，默认全量）。
     */
    fun planningPrompt(
        schema: GameSchema,
        currentUserRequest: String? = null,
        systems: List<String> = schema.gameSystems
    ): String {
        val seedSystems = systems.joinToString("\n") { system ->
            "  - $system：种子方法=${GameSystemCatalog.implementationMethods(
                system, schema.visualDimension, schema.screenOrientation
            ).joinToString("|")}；种子验收=${GameSystemCatalog.acceptanceBoundary(system)}"
        }
        val scopeNote = if (systems.size < schema.gameSystems.size) {
            "请只策划 <modules_to_plan> 中列出的系统（其余系统沿用已确认方案，不要输出）。"
        } else {
            "请为以下 Game Schema 中的每个系统，策划出它在这款游戏里的具体实现方式。"
        }
        return """
        $scopeNote

        <game_schema>
        schema_version:${schema.schemaVersion}
        dimension:${schema.visualDimension}
        orientation:${schema.screenOrientation}
        systems:${schema.gameSystems.joinToString(",")}
        requested_systems:${schema.requestedSystems.joinToString(",")}
        excluded_systems:${schema.excludedSystems.joinToString(",")}
        first_user_request:${schema.firstUserRequest ?: "（无）"}
        current_user_request:${currentUserRequest ?: schema.lastUserRequest ?: "（无）"}
        </game_schema>

        <modules_to_plan>
${systems.joinToString(",")}
        </modules_to_plan>

        <system_seed>
$seedSystems
        </system_seed>

        请结合用户需求，把每个系统的种子方法细化为这款游戏里的具体实现，并直接输出 JSON。
        """.trimIndent()
    }

    /** 策划 Schema 的上下文最小化注入；未勾选任何系统时退化为“裸需求”模式。 */
    fun planContext(plan: DesignPlan): String =
        if (plan.gameSystems.isEmpty()) {
            // 确认门一个系统都没勾 = 裸需求模式：不套用预设系统清单、不硬排除任何系统，
            // 需要哪些玩法系统由模型按用户指令（与对标锚定）自行判断。
            // 措辞注意：只解除"系统清单"约束，不说"不要套用任何游戏模板"——
            // 那会把指令里的对标游戏（如"我的世界"）一并否定掉，实测把模型
            // 推向脱离原作的通用玩法。
            PlanningEngine.toPrompt(plan) +
                "\n（本轮未勾选任何游戏系统：以上不含预设系统清单；" +
                "请以用户指令与对标游戏锚定为准，自行判断需要实现哪些玩法系统，" +
                "按该类型完整可玩版本落地。）"
        } else {
            PlanningEngine.toPrompt(plan)
        }

    /**
     * 知名游戏忠实度规则（生成路径，纯指令驱动）：用户指令里提到的知名游戏
     * （"制作一个我的世界游戏"这类）由生成 LLM 用自己的常识理解并忠实实现——
     * 平台不做对标匹配（对标机制已删除），但"它实际怎么玩就做什么"的纪律
     * 必须显式写进上下文：缺这段时通用质量清单的"波次/难度递进"导向会把它
     * 拉成守关小游戏（实测）。
     */
    const val KNOWN_GAME_FIDELITY_RULE =
        "【知名游戏忠实度】用户指令若提到知名游戏（如 我的世界/塞尔达/GTA/星露谷），" +
            "请先用你对该游戏的了解回想它实际怎么玩，忠实实现它可感知的核心玩法与代表元素" +
            "（它靠什么好玩就做什么），画面形态、操作方式与内容取向以该游戏的真实形态为准；" +
            "禁止替换成另一种玩法的通用小游戏（例如把沙盒建造做成守关/跑酷/三消）。"

    /**
     * 修改范围契约（仅提示词约束，无执行层检查）：修改/修复回合注入——根据用户指令
     * 精准优化改进游戏；LLM 若仍主动发挥，不做检查或撤回，由用户以停止键/撤销兜底。
     */
    const val SCOPE_FENCE_RULE =
        "【修改范围契约】根据用户指令精准优化改进游戏：只修改用户本轮点名的游戏特征，" +
            "未点名的特性（数值、规则、判定、布局、前端显示、文案、音效、命名等）应保持原样"

    /** 可玩性质量清单：与系统勾选无关的一次成稿质量底线（两种生成模式共用）。 */
    fun playabilityRequirements(): String = """
        【可玩性要求（交付底线）】
        1. 完整体验闭环：开始界面 → 游戏进行 → 胜负判定与结算 → 可重开（restart() 完整重置）。
        2. 内容丰富度：design_schema 清单内的系统全部真实实现且有可感知的内容量，不是单一元素的机械重复。
           丰富度按游戏类型判断，不以"波次/关卡"为唯一标准：动作/塔防类=多种敌人与波次或关卡递进；
           沙盒建造类=多种方块/物品与世界可自由改造（存在可验证的建造成果）；解谜类=多个谜题与机制递进；
           经营类=多条产出消耗链。用户指令提到知名游戏时，以该游戏的内容形态为准。
        3. 可见的数值反馈：分数/金币/生命等核心数值实时显示，变化有即时反馈（伴随视觉或音效反馈更佳）。
        4. 难度递进：按类型选择——对抗类=敌人/速度/生成频率随时间或关卡提升；沙盒/创造类=可用
           目标感、可建造内容的丰富度或世界复杂度随进程增长替代；前期宽松后期有可感知变化。
        5. 触控体验：操作目标足够大（≥44px）、位置顺手不遮挡视线；点按/拖动响应即时；横板动作类按标准布局（左下 ◀▶ 方向键、右下跳跃键，方向语义为触点横向位置）；左上/右上角约 140×110px 的平台按钮悬浮区内不放按钮或可交互元素（沙箱会自动检测），顶部其余区域可自由布局，画面铺满到顶部不留白。
        6. 默认参数可玩：普通玩家不调设置即可玩到核心内容并有机会获胜。
        7. 画面完整：背景、单位、UI 有基本区分度（颜色/形状），无空白区块遮挡玩法；核心 UI 避开左上/右上角的平台按钮悬浮区。
        8. 画面方向与铺满：与 design_schema 的 orientation 一致且主画布铺满视口——横板游戏主画布宽>高并占满窗口宽度，竖版游戏主画布高>宽并占满窗口高度；不得用固定逻辑分辨率等比缩放留黑边（沙箱会按对应方向视口运行并检测主画布宽高比与铺满程度，不达标回传修复）。
        9. 触控控件：符合标准布局（横板：左下 ◀▶、右下跳跃，方向=触点横向位置，可见半透明按钮≥64px；多点触控 identifier 跟踪），按下有反馈，不遮挡核心画面。
    """.trimIndent()

    /**
     * 可玩性自检轮提示词（沙箱通过后逐轮注入，全部消费完才交付）。
     * 轮数随质量档位与回合类型分档：快速/轻量无自检、均衡一轮（内容完整性＋基本功能正确）、
     * 精品两轮（追加丰富度与打磨）；修复轮（[fixTurn]）除快速档外仅一轮回归自检
     * （验证修复达成诉求且未破坏既有内容）——开销与任务大小成比例。
     * 返回 (阶段标签, 提示文本) 列表。
     */
    fun selfReviewPrompts(tier: String = QualityTier.BALANCED, fixTurn: Boolean = false): List<Pair<String, String>> {
        val t = QualityTier.normalize(tier)
        val contentReview = "校验中：可玩性自检（内容完整性）" to """
            沙箱运行已通过。请对照 design_schema 与【可玩性要求】自查基本功能正确与内容完整性：
            清单内每个系统是否真实实现且玩家可感知（而非占位、空壳或单一元素机械重复）；
            开始→进行→胜负→重开闭环是否完整；难度是否递进；核心数值反馈是否可见；
            左上/右上角平台按钮悬浮区无按钮或可交互元素、顶部无整条留白（沙箱会自动检测）。
            有缺口必须用 editfile/appendfile 补齐后再声明完成；
            若逐条确认全部达标，声明完成并在总结中按清单简述各项达标情况（每条一行）。
        """.trimIndent()
        val enrichReview = "校验中：丰富度与打磨自检" to """
            请做丰富度与打磨自查并落实改进：
            功能更丰富——对照类型标配补足内容量（更多单位/敌人种类、波次或关卡、升级或成长线）；
            数值平衡（前期宽松后期紧张，普通玩家可通关）；视觉层次（背景/单位/UI/特效区分明显）；
            音效反馈有层次（不同事件不同音色，WebAudio 振幅可取 0.15 左右）；
            文案与配色风格统一。用 editfile/appendfile 落实后再声明完成。
        """.trimIndent()
        val regressionReview = "校验中：回归自检（修复未破坏既有内容）" to """
            本次是修复/调整回合。请回归自查：
            玩家本轮诉求是否已逐条解决；
            修复没有破坏既有功能——对照 design_schema 抽查核心系统仍完整（可开局、可重开、核心数值反馈仍在），
            修复涉及的边界情形（触发条件、异常路径）已覆盖。有缺口用 editfile 修复后再声明完成。
        """.trimIndent()
        if (fixTurn) return if (t == QualityTier.FAST) emptyList() else listOf(regressionReview)
        return when (t) {
            QualityTier.FAST, QualityTier.LIGHT -> emptyList()
            QualityTier.BALANCED -> listOf(contentReview)
            else -> listOf(contentReview, enrichReview)
        }
    }

    /**
     * 功能断言契约（工具模式）：把策划验收边界固化为 scenarios.json，由沙箱确定性执行。
     * 均衡/精品档必须提供；快速/轻量档跳过。修复玩家报障时把问题固化为新断言追加
     * （回归保护：此后每次修改都重跑全部断言，防"修好新问题弄坏旧功能"）。
     */
    private fun scenarioContract(): String = """
        【功能断言 scenarios.json（均衡/精品档必须；快速/轻量档跳过）】
        首次生成时在写完 index.html 后，用 writefile 写入 scenarios.json：把 design_schema 中每个 P0（及关键 P1）系统的验收标准固化为可执行断言。
        格式（UTF-8 JSON，最多 ${GameScenarios.MAX_SCENARIOS} 条、每条最多 ${GameScenarios.MAX_STEPS} 步）：
        {"scenarios":[{"id":"score-after-kill","system":"战斗","name":"击杀敌人后得分增加","steps":[{"tap":[50,80]},{"frames":24}],"expect":"s.score > 0"}]}
        - steps 按序执行：{"tap":[x,y]}＝视口百分比处点按（如开局先点开始按钮）；{"drag":[x0,y0,x1,y1]}＝百分比拖动；{"frames":N}＝确定性推进 N 帧（每帧 50ms 游戏时间）；不写 steps 默认推进 ${GameScenarios.DEFAULT_FRAMES} 帧。
        - expect 是针对 s（即 __wwDebugState() 返回快照）的布尔表达式，只能引用快照字段：s.score>0、s.state==='playing'、s.entities.length>=1、s.player.hp>0 等。
        - 断言必须可失败：引用具体数值变化或状态迁移，禁止恒真式（如 typeof s.score!=='undefined'、s.entities.length>=0——写入时会被静态检查打回）；核心闭环至少覆盖：开局进入 playing、得分可增长、restart 后状态归零。
        - 断言涉及需要时间演进的行为（出怪、击杀、得分增长、波次推进、倒计时）时，steps 的 frames 要给足（建议 ≥36 帧，并先点掉"开始"类按钮）：预备期/生成间隔类机制在少量帧内不会发生，帧不够断言必失败。
        - 套件上限 ${GameScenarios.MAX_SCENARIOS} 条：新增回归断言导致超限时，合并同类项或替换最弱的旧断言（优先保留核心闭环与已修复缺陷的条目），保证回归覆盖不缩水。
        - 沙箱交付验收会逐条执行（点按/拖动→推进帧→比对快照）：未通过以 scenario-fail[系统/名称] 回传（附实际快照），必须修复游戏逻辑或对齐断言字段后重跑。
        - 迭代/修复轮维护此文件：玩家报障修复后把该问题固化为新断言追加；大幅重做游戏时同步重写断言。
    """.trimIndent()

    /**
     * 功能断言缺失 nudge（均衡/精品档生成轮，一次性）：交付前补写 scenarios.json。
     */
    fun scenarioNudgePrompt(): String = """
        【功能断言缺失】本档位承诺"检查基本功能正确"，交付前需通过功能断言验证，但工作区还没有 scenarios.json。
        请用 writefile 写入：为 design_schema 的每个 P0（及关键 P1）系统提供至少一条可失败的行为断言
        （针对 __wwDebugState() 快照的布尔表达式，格式见工作流中的功能断言说明），然后再次声明完成。
        核心闭环必须覆盖：开局进入 playing、得分可增长、失败可判定、restart 后状态归零。
    """.trimIndent()

    /**
     * 精品档防敷衍增强指令（Agent Loop 一次性注入）：自检期间零修改直通时，
     * 要求实质性丰富与打磨，堵住"空口完成"的敷衍交付路径。
     */
    fun premiumEnrichPrompt(): String = """
        【丰富度增强】本档位承诺"功能更丰富"，但刚才的自检没有产生任何实际修改。
        请对照【可玩性要求】与类型标配做实质性扩充：更多单位/敌人种类、波次或关卡、升级或成长线，
        以及数值平衡与视觉/音效打磨。用 editfile/appendfile 落实改进后再次声明完成；
        确实无改进空间时，在总结中逐条说明已达标的理由。
    """.trimIndent()

    /**
     * 内置引擎契约（条件引入机制）：按 design_schema 的维度/系统与质量档位组装——
     * 只"教"本轮允许的引擎（three=3D 必须、matter=物理系统必须、pixi/cannon/tween
     * 按条件可选）；其余游戏明确禁用引擎与 WebGL。声明协议统一：
     * <head> 内 <meta name="ww-engine" content="...">（可逗号分隔多个，仅此一行，
     * 不写任何 <script src>），平台在渲染层自动注入引擎源码（真机与沙箱同管道），
     * 声明白名单外或条件不符的引擎会被基础校验打回。
     */
    fun engineContract(
        visualDimension: String,
        systems: Collection<String> = emptyList(),
        tier: String = QualityTier.BALANCED
    ): String {
        val allowed = GameEngines.allowedFor(visualDimension, systems, tier)
        if (allowed.isEmpty()) {
            return """
            【引擎使用限制】本游戏不使用任何引擎：所有图形用 Canvas 2D 自绘、物理/运动用简单确定性公式手写；
            禁止声明 ww-engine（内置引擎仅特定游戏类型经平台注入使用）。
            """.trimIndent()
        }
        val hasPhysics = systems.contains("物理")
        val sections = mutableListOf<String>()
        sections += """
            【内置引擎声明协议】在 <head> 内写 <meta name="ww-engine" content="${allowed.joinToString(",")}">
            （可只声明实际用到的引擎子集，逗号分隔；不写任何 <script src>，平台渲染时自动注入源码，代码直接用全局对象）。
        """.trimIndent()
        if (GameEngines.THREE in allowed) {
            sections += """
            【3D 引擎 three.js（本游戏必须使用）】全局 THREE。
            1. 渲染器必须 new THREE.WebGLRenderer({antialias:true, preserveDrawingBuffer:true})——沙箱画面检测依赖 canvas.toDataURL 读回像素，
               缺少 preserveDrawingBuffer 会被判白屏回炉；setPixelRatio(Math.min(window.devicePixelRatio||1,2))；resize 同步 camera.aspect 与 renderer.setSize。
            2. 主循环 requestAnimationFrame（沙箱接管为确定性 tick 照常可测）；dt 钳制 0.05s。
            3. 移动端性能预算：低多边形；重复物体用合并几何或 InstancedMesh；光照 ≤1 平行光+环境光；纹理用 Canvas 程序化生成；
               场景物体数百级，保证手机 60fps。
            4. 触控（3D 标准，硬性契约）：左半屏虚拟摇杆移动（可见半透明、identifier 跟踪）、右半屏单指滑动旋转相机
               （yaw/pitch 双轴真实改变相机朝向——视角锁死=3D 游戏不可玩，固定跟随相机不算实现）、右下动作键；
               HUD 避开左上/右上角平台按钮悬浮区（约各 140×110px），其余区域可自由布局；__wwDebugState() 暴露相机朝向（如 camera:{yaw,pitch}）供断言。
            5. scenarios.json 必含一条视角旋转断言：steps 含一次右半屏 drag，expect 断言拖动后朝向值与初始不同
               （如 Math.abs(s.camera.yaw - 初始值) > 0.05）——视角不可旋转会被沙箱拦截回炉。
            严禁手写 Canvas 2D 透视投影/软件光栅化模拟 3D。
        """.trimIndent()
        }
        if (GameEngines.MATTER in allowed) {
            sections += """
            【2D 物理引擎 matter.js（本游戏含物理系统，必须使用）】全局 Matter。
            1. Engine.create() 建世界， Bodies.rectangle/circle 建刚体（静态平台/墙壁 isStatic:true），
               Composite.add(engine.world, ...) 装配；1 物理单位≈1px。
            2. 主循环每帧 Engine.update(engine, dt*1000)（dt 秒、钳制 ≤0.05）；matter 只做物理不渲染——
               渲染仍用 Canvas 2D，从 body.position/body.angle 读状态自绘。
            3. 玩家运动用 Body.setVelocity/body.force，不要直接改 position；碰撞反馈用 Events.on(engine,'collisionStart',...)；
               摆绳/吊钩用 Constraint。
            4. restart() 时 World.clear + 重建刚体；__wwDebugState() 快照从 body.position 读取 entities/player 坐标。
        """.trimIndent()
        }
        if (GameEngines.PIXI in allowed) {
            sections += """
            【2D 渲染引擎 pixi.js（可选：仅当同屏活动实体数百级、Canvas 2D 明显卡顿时才使用）】全局 PIXI。
            new PIXI.Application({view:canvas, antialias:true, backgroundAlpha:0})；精灵纹理用离屏 Canvas 程序化生成
            （PIXI.Texture.from）；大量同类精灵用 ParticleContainer；保持 rAF 主循环与 restart()/__wwDebugState 契约。
            一般 2D 游戏仍首选 Canvas 2D 自绘。
        """.trimIndent()
        }
        if (GameEngines.CANNON in allowed) {
            sections += """
            【3D 物理引擎 cannon（可选：仅真刚体需求——翻滚/堆叠/抛射时使用，与 three 配合）】全局 CANNON。
            声明 content="three,cannon"；new CANNON.World() 设 gravity；每帧 world.step(1/60, dt, 3)；
            把 body.position/quaternion 同步到 three 的 mesh。体素世界简单 AABB 碰撞直接手写更简，不必引入。
        """.trimIndent()
        }
        if (GameEngines.TWEEN in allowed) {
            sections += """
            【补间库 tween.js（精品档打磨可用）】全局 TWEEN。声明 content 加 tween；
            new TWEEN.Tween(obj).to(...).easing(...) 链式补间，主循环里 TWEEN.update(now) 驱动 UI/动效；
            游戏逻辑数值仍自管理，tween 只用于表现层。
        """.trimIndent()
        }
        if (!hasPhysics && GameEngines.MATTER !in allowed) {
            sections += """
            【物理实现边界】本游戏未含物理系统：重力/碰撞用简化确定性公式手写即可，不要声明物理引擎。
        """.trimIndent()
        }
        return sections.joinToString("\n\n")
    }

    /**
     * 质量档位 → 生成策略引导（注入工具模式与兼容模式上下文）。
     */
    fun tierPrompt(tier: String): String {
        val t = QualityTier.normalize(tier)
        return when (t) {
            QualityTier.FAST -> """
                【质量档位：快速】最快速度直出产品：一次 writefile 写出最小可玩版本即声明完成。
                本档位不做沙箱测试与自检轮——内容最简、能玩即可，速度优先。
            """.trimIndent()
            QualityTier.LIGHT -> """
                【质量档位：轻量】最简可玩完整闭环：核心玩法完整、可开局可重开。
                沙箱会验证可运行（不跑通不交付），但无自检轮——内容量以最简为准。
            """.trimIndent()
            QualityTier.BALANCED -> """
                【质量档位：均衡】完整实现 design_schema 清单内系统（p0/p1），按类型标配内容量落地；
                交付验收含功能断言（scenarios.json）：沙箱会逐系统验证行为符合验收标准，未通过会回传修复；
                通过一轮内容完整性自检后再交付；修补高效进行，避免无效往返。
            """.trimIndent()
            else -> """
                【质量档位：精品】质量优先：在清单系统之外按类型标配补充内容量
                （更多单位/敌人、波次或关卡递进、升级成长线）；P2 打磨项（视觉细节、反馈动效、
                音效层次）一并纳入；可分多段写入与多轮打磨，充分自检确认后再交付；
                交付验收含功能断言（scenarios.json）与深度重开验证。
            """.trimIndent()
        }
    }

    /** 生成代码契约与输出格式。 */
    fun codeContract(): String = """
        【本轮输出格式】
        只输出一个完整 HTML 文件，代码围栏必须是 ```html。整量输出仅限首次生成；已有文件的修改版本必须保持未要求部分不变。
        先在心里列文件计划与依赖顺序（本项目单文件：index.html，依赖顺序为 HTML 骨架 → CSS → JS），再 implement，最后自行对照验收标准逐项检查。
        design_schema.systems 是本轮要实现的系统清单（加法）：清单内的系统（含 p0 与 p1 条目）必须实现；清单外不做禁止，仅在用户指令需要时按类型标配的完整可玩版本补充。
        excluded_systems（仅含用户明确说“不要”的系统）与 excluded_approaches 是硬性排除范围，不得实现或引入。
        复杂系统（AI、商店、技能、关卡等）先在 JS 中隔离成独立函数/模块并优先自检，不得与核心循环交叉污染。
        基础校验通过后即交付玩家试玩，不要在游戏内添加自动测试代码、测试按钮或调试面板；运行错误由玩家回传后再修复。
        输出前确认：无 eval、无外部资源、restart() 存在、__wwDebugState 快照存在、触控可玩、声明前置、主画布方向与 design_schema 的 orientation 一致且铺满视口（无固定分辨率黑边）、触控控件为标准布局（横板左下◀▶右下跳跃，方向语义为横向）、左上/右上角平台按钮区无交互元素。
    """.trimIndent()

    /**
     * 工具模式工作流提示词：模型通过 readfile/writefile/editfile 亲自读写游戏文件，
     * 每次写入后宿主自动检查产品契约（资源自包含/安全禁令）；声明完成时在沙箱
     * （同内核 WebView）运行验证，运行错误文本回传驱动修复；全部通过后停止调用
     * 工具、输出给玩家的总结。
     * [firstGeneration] 为 true 表示工作区还没有 index.html（先全量写入），
     * false 表示已有文件（必须增量修改）。
     */
    fun toolWorkflowPrompt(firstGeneration: Boolean): String {
        val flow = if (firstGeneration) {
            """
        【工具工作流（首次生成）】
        1. 用 writefile 写入 index.html（入口）并按需拆分多文件：体量较大或系统较多时，
           推荐拆为 js/main.js、js/systems.js、css/style.css 等，index.html 以相对路径引用
           （<script src="js/main.js"></script> 按依赖顺序排列；平台运行前自动内联合并，游戏仍离线自包含）。
           必须完整实现 design_schema 清单内的全部系统（p0 与 p1 条目缺一不可），内容量以策划清单为准，
           不得为控制体量删减已确认的系统内容；引用的每个文件都要真实 writefile 创建（缺文件会被校验打回）；
        2. 每次写入后系统自动检查资源契约（本地文件存在性/外部资源/eval 禁令），结果附在工具结果里；有 error 必须修复后继续；
        3. 你声明完成后，系统会在沙箱（同一 WebView 内核）较长时间真实运行游戏并模拟多处触控——任何运行错误（含左上/右上角平台按钮区出现可交互元素）都会以报错文本回传，必须依据报错修复；
        4. 沙箱跑通后还有可玩性自检环节（内容完整性、体验与平台合规各一轮），全部通过后停止调用工具，直接输出给玩家的一段简短总结（不要输出代码）。
            """.trimIndent()
        } else {
            """
        【工具工作流（修改迭代）】
        1. 如上下文中没有目标文件的最新内容，先 readfile 读取；
        2. 修改优先用 editfile 精确替换（常规修改首选）或 appendfile 追加新代码段；需要改动一大段时，old_string 取该段完整原文（如整个函数块）做整段替换；彻底重构时可用 writefile 整量重写，但必须遵守【修改范围契约】（只改用户点名的特征）；改动跨文件时对每个文件分别 editfile（同轮并行发起）；
        3. 每次写入后系统自动检查资源契约，报告会附在工具结果里；有 error 必须修复；
        4. 你声明完成后，系统会在沙箱真实运行游戏验证，运行错误文本会回传，必须依据报错修复；沙箱跑通并完成可玩性自检轮后停止调用工具并输出总结。
            """.trimIndent()
        }
        return flow + "\n\n" + scenarioContract() + "\n\n【工具纪律】\n" + """
        - editfile 的 old_string 必须与文件原文逐字符一致（含缩进）；匹配失败时先 readfile 对准原文，不要凭记忆猜代码。
  - 同一处反复修复失败时，换实现思路（重构该函数 / 换数据结构），可整段替换问题模块（old_string 取该模块完整原文）。
  - 不要在回复正文里粘贴整个文件的代码——文件内容只通过工具写入。
  - 修改已有游戏时根据用户指令精准优化改进：只改与诉求直接相关的代码，数值、音效、布局、显示、命名等未点名内容一律保持原样，即使改动"更好"也不做。
  - 除非用户明确说明，新系统按“类型标配的完整可玩版本”实现：核心玩法闭环必须完整，类型常见标配内容（多种单位/波次/升级/难度递进）至少具备其二；与需求无关的锦上添花不做。
  - 互不依赖的多处修改，请在同一轮并行发起多个 editfile 调用（一次响应可包含多个 tool call），不要每轮只改一处。
  - readfile 尽量一次整读（文件通常千行以内，不要切片）；确需多个区段时也应在同一轮并行发起多个 readfile。读完定位到修改点后，尽量把互不依赖的修改在同一轮并行 editfile 落盘——每多一轮往返就多一份等待。
  - 文件被修改后，此前的 readfile 结果会被标记过期；请依据 editfile 返回的修改点上下文片段继续编辑，避免反复整读文件。
        """.trimIndent()
    }
}
