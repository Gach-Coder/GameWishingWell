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
