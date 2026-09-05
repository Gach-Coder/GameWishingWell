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
    val disableThinking: Boolean = false
)

object ProviderPresets {
    val all = listOf(
        // 协议枚举与厂商默认预设严格对齐 instruct.txt 第二章：
        // DeepSeek 默认 deepseek-v4-flash（deepseek-v4-pro 可选），已弃用 deepseek-chat；关闭思考=否。
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-flash", Protocol.OPENAI_COMPATIBLE, 16384, disableThinking = false),
        ProviderPreset("kimi", "Kimi (Moonshot)", "https://api.moonshot.cn/v1", "moonshot-v1-8k", Protocol.OPENAI_COMPATIBLE, 16384),
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", Protocol.OPENAI_COMPATIBLE, 16384),
        ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-4-6", Protocol.ANTHROPIC, 64000),
        // 智谱开放平台 OpenAI 兼容端点（Bearer 鉴权、/chat/completions、SSE、function calling 均兼容）；
        // 默认 glm-5.3-flash，模型名可在设置页直接改为其他 GLM 系列（如 glm-5.3 / glm-4.6 / glm-4-flash）。
        ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash", Protocol.OPENAI_COMPATIBLE, 16384),
        // 小米 MiMo 开放平台（https://mimo.mi.com）：OpenAI 兼容端点（Bearer 鉴权、/chat/completions、SSE、function calling 均兼容）；
        // 默认 mimo-v2.5（V2 系列已于 2026-06-30 弃用，V2.5 为当前主推，可在设置页改为 mimo-v2.5-pro）。
        ProviderPreset("xiaomi", "小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5", Protocol.OPENAI_COMPATIBLE, 16384),
        // OpenRouter 聚合网关（https://openrouter.ai）：OpenAI 兼容端点（Bearer 鉴权、/chat/completions、
        // SSE 流式、function calling 均兼容）。默认模型暂缺：模型 ID 形如 "厂商/模型名"
        // （如 deepseek/deepseek-chat-v3、anthropic/claude-sonnet-4），由用户在设置页自填。
        ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", Protocol.OPENAI_COMPATIBLE, 16384),
        ProviderPreset("custom", "自定义", "", "", Protocol.OPENAI_COMPATIBLE, 8192)
    )

    val DEFAULT = all.first()

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
