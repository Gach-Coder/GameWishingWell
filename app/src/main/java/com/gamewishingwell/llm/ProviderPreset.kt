package com.gamewishingwell.llm

enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC }

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val protocol: Protocol,
    /**
     * 仅 Anthropic 协议使用（其 API 强制要求 max_tokens 字段，无法省略）：
     * 取模型输出上限使其不构成实际限制。OpenAI 兼容协议不发送 max_tokens——
     * 输出长度由网关按模型上限裁定，人为截断只会制造残缺代码与非法工具参数。
     */
    val maxTokens: Int = 8192,
    /**
     * 预设级兜底：为 true 时强制通过 thinking={"type":"disabled"} 关闭思考，
     * 即使设置页的思考开关已打开也不生效。当前所有预设均为 false，
     * 实际是否关闭思考以设置页开关（默认关闭）为准。
     */
    val disableThinking: Boolean = false,
    /**
     * 设置页模型下拉的可选清单（首位即推荐默认）：模型名称字段始终可自由编辑，
     * 手输清单外的模型名同样有效（自定义网关/新模型无需改代码）。
     * 空清单（custom）不提供下拉，纯手输。
     */
    val models: List<String> = emptyList()
)

object ProviderPresets {

    val all = listOf(
        // 协议枚举与厂商默认预设严格对齐 instruct.txt 第二章：
        // DeepSeek 默认 deepseek-v4-flash（v4-pro 可选），已弃用 deepseek-chat；关闭思考=否。
        ProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-flash",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf("deepseek-v4-flash", "deepseek-v4-pro")
        ),
        ProviderPreset(
            "kimi", "Kimi (Moonshot)", "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-k2-turbo-preview")
        ),
        ProviderPreset(
            "openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini")
        ),
        ProviderPreset(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-4-6",
            Protocol.ANTHROPIC, 64000,
            models = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5")
        ),
        // 智谱开放平台 OpenAI 兼容端点（Bearer 鉴权、/chat/completions、SSE、function calling 均兼容）；
        // 默认 glm-5.3-flash，模型名可在设置页下拉选择或直接改为其他 GLM 系列。
        ProviderPreset(
            "zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf("glm-5.3-flash", "glm-5.3", "glm-4.6", "glm-4-flash")
        ),
        // 小米 MiMo 开放平台（https://mimo.mi.com）：OpenAI 兼容端点（Bearer 鉴权、/chat/completions、SSE、function calling 均兼容）；
        // 默认 mimo-v2.5（V2 系列已于 2026-06-30 弃用，V2.5 为当前主推，可选 mimo-v2.5-pro）。
        ProviderPreset(
            "xiaomi", "小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf("mimo-v2.5", "mimo-v2.5-pro")
        ),
        // MiniMax 稀宇开放平台：OpenAI 兼容端点（Bearer 鉴权、/chat/completions、SSE、function calling 均兼容）。
        // 国内端点 api.minimaxi.com/v1（默认），国际端点 api.minimax.io/v1 可在设置页改 Base URL 切换。
        // 模型 ID 遵循平台 MiniMax-M* 命名；M 系列内置交错思考、无法关闭——
        // 若网关拒绝 thinking 字段，客户端已有"去掉该字段重试"兜底。
        ProviderPreset(
            "minimax", "MiniMax", "https://api.minimaxi.com/v1", "MiniMax-M3",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.5")
        ),
        // OpenRouter 聚合网关（https://openrouter.ai）：OpenAI 兼容端点（Bearer 鉴权、/chat/completions、
        // SSE 流式、function calling 均兼容）。下拉内置全部免费端点模型（快照同步自
        // openrouter.ai/models?variant=free，2026-09-05：已排除音频输出的 lyria 系与
        // 无工具调用的内容安全分类器，仅保留 text-out 且支持 tools 的对话模型——
        // Agent 工具模式依赖 function calling）。默认 z-ai/glm-5.2:free（256K 上下文、
        // 支持工具；免费端点有限流，429 由统一退避重试覆盖）。
        ProviderPreset(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "z-ai/glm-5.2:free",
            Protocol.OPENAI_COMPATIBLE, 16384,
            models = listOf(
                "z-ai/glm-5.2:free",
                "cohere/north-mini-code:free",
                "dots-studio/dots-3-note-preview:free",
                "google/gemma-4-26b-a4b-it:free",
                "google/gemma-4-31b-it:free",
                "inclusionai/ling-3.0-flash-fin:free",
                "inclusionai/ling-3.0-flash-sante:free",
                "liquid/lfm-2.5-2.6b:free",
                "minimax/minimax-m2.7:free",
                "minimax/minimax-m3:free",
                "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
                "nvidia/nemotron-3-super-120b-a12b:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "nvidia/nemotron-3.5-lightning:free",
                "openrouter/free",
                "poolside/laguna-s-2.1:free",
                "poolside/laguna-xs-2.1:free",
                "thinkingmachines/inkling-small:free",
                "thinkingmachines/inkling:free"
            )
        ),
        ProviderPreset("custom", "自定义", "", "", Protocol.OPENAI_COMPATIBLE, 8192)
    )

    val DEFAULT = all.first()

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
