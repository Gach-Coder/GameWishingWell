package com.gamewishingwell.llm

enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC }

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val protocol: Protocol,
    /** 单次回复最大 token 数。完整 HTML 游戏通常远超模型默认的 4096，必须显式调高，否则输出被截断导致反复"提取不到 HTML"。 */
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
        ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-4-6", Protocol.ANTHROPIC, 16384),
        // 智谱开放平台 OpenAI 兼容端点（Bearer 鉴权、/chat/completions、SSE、function calling 均兼容）；
        // 默认 glm-5.3-flash，模型名可在设置页直接改为其他 GLM 系列（如 glm-5.3 / glm-4.6 / glm-4-flash）。
        ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash", Protocol.OPENAI_COMPATIBLE, 16384),
        // 小米 MiMo 开放平台（https://mimo.mi.com）：OpenAI 兼容端点（Bearer 鉴权、/chat/completions、
        // SSE、function calling 均兼容）；默认 mimo-v2.5（V2 系列已于 2026-06-30 弃用，V2.5 为当前主推，
        // 可在设置页改为 mimo-v2.5-pro）。
        ProviderPreset("xiaomi", "小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5", Protocol.OPENAI_COMPATIBLE, 16384),
        ProviderPreset("custom", "自定义", "", "", Protocol.OPENAI_COMPATIBLE, 8192)
    )

    val DEFAULT = all.first()

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
