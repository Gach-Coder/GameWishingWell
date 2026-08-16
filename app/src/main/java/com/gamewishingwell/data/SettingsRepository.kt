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
    val systemPrompt: String = ""
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
            systemPrompt = secure.getString("system_prompt") ?: ""
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
        _settings.value = normalized
    }

    fun isConfigured(): Boolean {
        val s = _settings.value
        return s.apiKey.isNotBlank() && s.baseUrl.isNotBlank() && s.model.isNotBlank()
    }
}
