package com.gamewishingwell.agent

import android.content.Context

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
        你是"许愿井"游戏创作 Agent。用户会用中文提出游戏需求，你必须产出一个完整、可直接运行、自包含的 HTML5 单文件游戏。

        【输出要求】
        1. ${if (toolMode) "文件内容一律通过 writefile / editfile 工具写入；回复正文只用于与玩家沟通，禁止粘贴代码。" else "只输出一个 HTML 文件，用 ```html ... ``` 代码围栏包裹，不要任何多余解释。"}
        2. 禁止外部资源：不得使用 CDN、外部图片、外部字体、外部 JS 库。所有图形用 Canvas 2D 自绘（纯色、几何、渐变均可），音效使用 WebAudio 振荡器生成。全部代码内联在文件中。
        3. 必须适配手机触控：不依赖键盘或鼠标；用 touchstart / touchmove / touchend 实现虚拟摇杆、点按或滑动操作，并 preventDefault 阻止页面滚动；canvas 随窗口尺寸自适应。
        4. 开场即可玩，默认难度合理，保证普通玩家能玩到游戏内容；有得分显示和开始/暂停/重玩控制；页面美观、配色协调。不要在游戏页面里自行绘制"重新开始/重开"按钮（平台会统一在右上角提供"设置"按钮，设置面板内含重新游戏和音量控制）。
           【顶部保留区（硬性）】页面顶部约 110px 高度为平台操作条（返回/标题/保存）保留区：禁止在该区域放置任何游戏按钮、可交互元素或关键信息——会被平台顶栏遮挡而无法点击；游戏主界面与所有按钮从安全区（约 top 120px 以下）开始布局。同样禁止把控制按钮放在底部中央遮挡游戏区域。
        5. 使用常见浏览器 API，避免最新语法（保持 ES2017 以内），确保 Android WebView 兼容。不得使用 async/await/Promise（异步错误难以捕获且兼容性差）。
        6. 提供全局函数 restart() 用于重开游戏，必须完整重置所有游戏状态，可被反复调用而不出错。
        7. 界面默认使用中文。
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

    fun readTemplate(context: Context): String =
        context.assets.open("game_template.html").bufferedReader().use { it.readText() }

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
        6. 除非用户明确说明，各系统按“可玩的最简洁版本”策划：先保证核心玩法闭环，再考虑扩展内容。
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
                system, schema.visualDimension, schema.screenOrientation, schema.templateId
            ).joinToString("|")}；种子验收=${GameSystemCatalog.acceptanceBoundary(system, schema.templateId)}"
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
        reference_game:${schema.referenceGame ?: "null"}
        template_id:${schema.templateId ?: "null"}
        excluded_systems:${schema.excludedSystems.joinToString(",")}
        first_user_request:${schema.firstUserRequest ?: "（无）"}
        current_user_request:${currentUserRequest ?: schema.lastUserRequest ?: "（无）"}
        </game_schema>

        <modules_to_plan>
${systems.joinToString(",")}
        </modules_to_plan>

        <template_seed>
$seedSystems
        </template_seed>

        请结合用户需求，把每个系统的种子方法细化为这款游戏里的具体实现，并直接输出 JSON。
        """.trimIndent()
    }

    /** 策划 Schema 的上下文最小化注入；未勾选任何系统时退化为“裸需求”模式。 */
    fun planContext(plan: DesignPlan): String =
        if (plan.gameSystems.isEmpty()) {
            // 确认门一个系统都没勾 = 裸需求模式：不套用任何预设系统清单、
            // 不硬排除任何系统，等价于把用户原话直接交给 Agent——需要哪些
            // 玩法系统由模型按需求自行判断（保持最简可玩）。
            PlanningEngine.toPrompt(plan) +
                "\n（本轮未勾选任何游戏系统：以上不含预设系统清单，不要套用任何游戏模板；" +
                "请完全以用户指令为准，自行判断需要实现哪些玩法系统，按最简可玩落地。）"
        } else {
            PlanningEngine.toPrompt(plan)
        }

    /** 可玩性质量清单：与系统勾选无关的一次成稿质量底线（两种生成模式共用）。 */
    fun playabilityRequirements(): String = """
        【可玩性要求（交付底线）】
        1. 完整体验闭环：开始界面 → 游戏进行 → 胜负判定与结算 → 可重开（restart() 完整重置）。
        2. 可见的数值反馈：分数/金币/生命等核心数值实时显示，变化有即时反馈。
        3. 难度基本递进：敌人/速度/生成频率等随时间或关卡提升，前期宽松后期紧张。
        4. 触控体验：操作目标足够大（≥44px）、位置顺手不遮挡视线；点按/拖动响应即时；所有可交互元素避开顶部约 110px 的平台保留区（返回/保存所在，会被遮挡）。
        5. 默认参数可玩：普通玩家不调设置即可玩到核心内容并有机会获胜。
        6. 画面完整：背景、单位、UI 有基本区分度（颜色/形状），无空白区块遮挡玩法；核心 UI 不进入顶部保留区。
    """.trimIndent()

    /** 生成代码契约与输出格式。 */
    fun codeContract(): String = """
        【本轮输出格式】
        只输出一个完整 HTML 文件，代码围栏必须是 ```html。整量输出仅限首次生成；已有文件的修改版本必须保持未要求部分不变。
        先在心里列文件计划与依赖顺序（本项目单文件：index.html，依赖顺序为 HTML 骨架 → CSS → JS），再 implement，最后自行对照验收标准逐项检查。
        design_schema.systems 是本轮要实现的系统清单（加法）：清单内的系统必须实现；清单外不做禁止，仅在用户指令需要时按最简可玩补充。
        excluded_systems（仅含用户明确说“不要”的系统）与 excluded_approaches 是硬性排除范围，不得实现或引入。
        复杂系统（AI、商店、技能、关卡等）先在 JS 中隔离成独立函数/模块并优先自检，不得与核心循环交叉污染。
        基础校验通过后即交付玩家试玩，不要在游戏内添加自动测试代码、测试按钮或调试面板；运行错误由玩家回传后再修复。
        输出前确认：无 eval、无外部资源、restart() 存在、触控可玩、声明前置。
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
        1. 用 writefile 一次性写入完整 index.html——包含全部 HTML+CSS+JS 的可玩成品，按“可玩的最简洁版本”控制体量；
        2. 每次写入后系统自动检查资源契约（外部资源/eval 禁令），结果附在工具结果里；有 error 必须修复后继续；
        3. 你声明完成后，系统会在沙箱（同一 WebView 内核）真实运行游戏验证——任何运行错误都会以报错文本回传，必须依据报错修复；
        4. 沙箱跑通后，停止调用工具，直接输出给玩家的一段简短总结（不要输出代码）。
            """.trimIndent()
        } else {
            """
        【工具工作流（修改迭代）】
        1. 如上下文中没有 index.html 的最新内容，先 readfile 读取；
        2. 修改方式自行选择：editfile 精确替换（常规修改首选）、appendfile 追加新代码段、writefile 整量重写（彻底重构时）；
        3. 每次写入后系统自动检查资源契约，报告会附在工具结果里；有 error 必须修复；
        4. 你声明完成后，系统会在沙箱真实运行游戏验证，运行错误文本会回传，必须依据报错修复；沙箱跑通后停止调用工具并输出总结。
            """.trimIndent()
        }
        return flow + "\n\n【工具纪律】\n" + """
        - editfile 的 old_string 必须与文件原文逐字符一致（含缩进）；匹配失败时先 readfile 对准原文，不要凭记忆猜代码。
  - 同一处反复修复失败时，换实现思路（重构该函数 / 换数据结构），必要时直接 writefile 重写该模块所在的文件区域。
  - 不要在回复正文里粘贴整个文件的代码——文件内容只通过工具写入。
  - 除非用户明确说明，新系统按“可玩的最简洁版本”实现：先核心玩法闭环，后扩展内容。
  - 互不依赖的多处修改，请在同一轮并行发起多个 editfile 调用（一次响应可包含多个 tool call），不要每轮只改一处。
  - 文件被修改后，此前的 readfile 结果会被标记过期；请依据 editfile 返回的修改点上下文片段继续编辑，避免反复整读文件。
        """.trimIndent()
    }
}
