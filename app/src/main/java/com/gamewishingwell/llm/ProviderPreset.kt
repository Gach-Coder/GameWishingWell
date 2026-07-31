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
     * DeepSeek V4 系列默认开启思考且思考会耗尽 max_tokens 预算（实测 16384 预算被思考占满、内容为 0），
     * 生成 HTML 游戏时关闭思考可让全部输出预算用于正文。
     */
    val disableThinking: Boolean = false
)

object ProviderPresets {
    val all = listOf(
        // deepseek-v4-flash 默认开启思考且会耗尽输出预算导致内容为空，必须关闭思考、并把预算给足
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", Protocol.OPENAI_COMPATIBLE, 16384, disableThinking = true),
        ProviderPreset("kimi", "Kimi (Moonshot)", "https://api.moonshot.cn/v1", "moonshot-v1-8k", Protocol.OPENAI_COMPATIBLE, 16384),
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", Protocol.OPENAI_COMPATIBLE, 16384),
        ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-4-6", Protocol.ANTHROPIC, 16384),
        ProviderPreset("custom", "自定义", "", "", Protocol.OPENAI_COMPATIBLE, 8192)
    )

    val DEFAULT = all.first()

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
