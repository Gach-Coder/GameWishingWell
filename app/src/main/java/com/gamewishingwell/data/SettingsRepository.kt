package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.gamewishingwell.llm.ProviderPresets

data class LlmSettings(
    val providerId: String = ProviderPresets.DEFAULT.id,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    /** 模型思考能力开关：默认关闭。关闭时请求不启用模型思考输出。 */
    val thinkingEnabled: Boolean = false
)

class SettingsRepository(context: Context) {

    private val secure = SecurePrefs(context)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LlmSettings> = _settings.asStateFlow()

    /** 每个服务商独立记忆的 API Key 存储槽（api_key_deepseek / api_key_kimi …）。 */
    private fun providerKeySlot(providerId: String): String = "api_key_$providerId"

    init {
        // 一次性迁移：升级前只存过当前厂商的 api_key——补写到该厂商的记忆槽，
        // 之后切换厂商即可自动回填，不必重新输入。
        val s = _settings.value
        if (s.apiKey.isNotBlank() && rememberedApiKey(s.providerId).isBlank()) {
            secure.putString(providerKeySlot(s.providerId), s.apiKey)
        }
    }

    /** 该厂商上次保存过的 API Key（无记忆返回空串；切换厂商时用于自动回填）。 */
    fun rememberedApiKey(providerId: String): String =
        secure.getString(providerKeySlot(providerId)) ?: ""

    private fun load(): LlmSettings {
        val providerId = secure.getString("provider_id") ?: ProviderPresets.DEFAULT.id
        val preset = ProviderPresets.byId(providerId) ?: ProviderPresets.DEFAULT
        // 旧版本曾把 DeepSeek 默认模型写成已弃用的 deepseek-chat；
        // 以 instruct.txt 协议预设表为准，读到旧值时自动迁移到 deepseek-v4-flash。
        var model = secure.getString("model") ?: preset.defaultModel
        if (providerId == "deepseek" && model == "deepseek-chat") {
            model = preset.defaultModel
        }
        return LlmSettings(
            providerId = providerId,
            apiKey = secure.getString("api_key") ?: "",
            baseUrl = secure.getString("base_url") ?: preset.baseUrl,
            model = model,
            systemPrompt = secure.getString("system_prompt") ?: "",
            thinkingEnabled = secure.getString("thinking_enabled")?.toBoolean() ?: false
        )
    }

    fun save(settings: LlmSettings) {
        val normalized = settings.copy(
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            model = settings.model.trim(),
            apiKey = settings.apiKey.trim()
        )
        secure.putString("provider_id", normalized.providerId)
        secure.putString("api_key", normalized.apiKey)
        // 按厂商记忆 Key：切回该厂商时自动回填，免重复输入（Keystore 加密存储）。
        secure.putString(providerKeySlot(normalized.providerId), normalized.apiKey)
        secure.putString("base_url", normalized.baseUrl)
        secure.putString("model", normalized.model)
        secure.putString("system_prompt", normalized.systemPrompt)
        secure.putString("thinking_enabled", normalized.thinkingEnabled.toString())
        _settings.value = normalized
    }

    /**
     * 仅更新思考开关（设置页 Switch 即时生效）：不连带保存其他未提交的表单草稿
     *（API Key/Base URL/模型名等仍走"保存设置"按钮），切页/重进不再被还原。
     */
    fun updateThinking(enabled: Boolean) {
        secure.putString("thinking_enabled", enabled.toString())
        _settings.value = _settings.value.copy(thinkingEnabled = enabled)
    }

    fun isConfigured(): Boolean {
        val s = _settings.value
        return s.apiKey.isNotBlank() && s.baseUrl.isNotBlank() && s.model.isNotBlank()
    }
}
