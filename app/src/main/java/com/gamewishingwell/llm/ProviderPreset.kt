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
     * 是否通过 thinking={"type":"disabled"} 关闭推理模型的思考过程。
     * 以 instruct.txt 的厂商默认预设表为准：目前所有厂商预设均为“否”，
     * 即不发送关闭思考字段，尊重服务端默认行为。
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
        ProviderPreset("custom", "自定义", "", "", Protocol.OPENAI_COMPATIBLE, 8192)
    )

    val DEFAULT = all.first()

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
