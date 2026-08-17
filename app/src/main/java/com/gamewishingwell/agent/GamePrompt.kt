package com.gamewishingwell.agent

import android.content.Context

object GamePrompt {

    fun systemPrompt(custom: String? = null): String {
        if (!custom.isNullOrBlank()) {
            return custom.trim() + "\n\n" + hardRules()
        }
        return baseSystemPrompt()
    }

    private fun baseSystemPrompt(): String = """
        你是"许愿井"游戏创作 Agent。用户会用中文提出游戏需求，你必须产出一个完整、可直接运行、自包含的 HTML5 单文件游戏。

        【输出要求】
        1. 只输出一个 HTML 文件，用 ```html ... ``` 代码围栏包裹，不要任何多余解释。
        2. 禁止外部资源：不得使用 CDN、外部图片、外部字体、外部 JS 库。所有图形用 Canvas 2D 自绘（纯色、几何、渐变均可），音效使用 WebAudio 振荡器生成。全部代码内联在文件中。
        3. 必须适配手机触控：不依赖键盘或鼠标；用 touchstart / touchmove / touchend 实现虚拟摇杆、点按或滑动操作，并 preventDefault 阻止页面滚动；canvas 随窗口尺寸自适应。
        4. 开场即可玩，默认难度合理，保证普通玩家能玩到游戏内容；有得分显示和开始/暂停/重玩控制；页面美观、配色协调。不要在游戏页面里自行绘制"重新开始/重开"按钮（平台会统一在右上角提供"设置"按钮，设置面板内含重新游戏和音量控制），禁止把任何控制按钮放在底部中央遮挡游戏区域。
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

    fun chatSystemPrompt(): String = """
        你是"许愿井"里的创作助手。用户还没有要求生成游戏，只是在聊天。
        请用简洁的中文回答，不要输出 HTML 代码；如果用户询问能力，说明你可以根据一句话生成可直接游玩的 HTML5 小游戏。
    """.trimIndent()

    fun readTemplate(context: Context): String =
        context.assets.open("game_template.html").bufferedReader().use { it.readText() }

    /** 策划层 LLM 的 system 提示词：只负责把每个系统解释成“这款游戏里的具体实现”。 */
    fun planningSystemPrompt(): String = """
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
        1. 只能输出 systems 中列出的系统，不得新增系统；
        2. implementation 必须结合 reference_game/template 和用户需求，说明该系统在这款游戏里的具体内容，例如黄金矿工的道具系统要说明有黄金/石头/炸弹、黄金和石头随体积越大价值越大、石头更廉价等；
        3. methods 是给代码生成层的具体实现要点，可以有“钩子前端与道具做圆形碰撞判定”这类确定性规则；
        4. layer：0=P0 核心玩法、1=P1 重要特性、2=P2 打磨项；
        5. 排除系统不得出现。
    """.trimIndent()

    /** 策划层 LLM 输入：会话级 Game Schema JSON + 首轮需求 + 模板库种子实现。 */
    fun planningPrompt(schema: GameSchema, currentUserRequest: String? = null): String {
        val seedSystems = schema.gameSystems.joinToString("\n") { system ->
            "  - $system：种子方法=${GameSystemCatalog.implementationMethods(
                system, schema.visualDimension, schema.screenOrientation, schema.templateId
            ).joinToString("|")}；种子验收=${GameSystemCatalog.acceptanceBoundary(system, schema.templateId)}"
        }
        return """
        请为以下 Game Schema 中的每个系统，策划出它在这款游戏里的具体实现方式。

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

        <template_seed>
$seedSystems
        </template_seed>

        请结合用户需求，把每个系统的种子方法细化为这款游戏里的具体实现，并直接输出 JSON。
        """.trimIndent()
    }

    /** 策划 Schema 的上下文最小化注入。 */
    fun planContext(plan: DesignPlan): String = PlanningEngine.toPrompt(plan)

    /** 生成代码契约与输出格式。 */
    fun codeContract(): String = """
        【本轮输出格式】
        只输出一个完整 HTML 文件，代码围栏必须是 ```html。全量重写仅限首次生成；修改版本必须保持未要求部分不变。
        先在心里列文件计划与依赖顺序（本项目单文件：index.html，依赖顺序为 HTML 骨架 → CSS → JS），再 implement，最后自行对照验收标准逐项检查。
        严格按 design_schema 的 implementations 实现系统；excluded_systems 与 excluded_approaches 是硬性排除范围，不得实现或引入被排除的系统与方案。
        复杂系统（AI、商店、技能、关卡等）先在 JS 中隔离成独立函数/模块并优先自检，不得与核心循环交叉污染。
        基础校验通过后即交付玩家试玩，不要在游戏内添加自动测试代码、测试按钮或调试面板；运行错误由玩家回传后再修复。
        输出前确认：无 eval、无外部资源、restart() 存在、触控可玩、声明前置。
    """.trimIndent()
}
