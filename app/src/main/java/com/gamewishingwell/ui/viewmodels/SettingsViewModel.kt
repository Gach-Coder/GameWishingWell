package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.data.LlmSettings
import com.gamewishingwell.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val agent: GameAgent
) : ViewModel() {

    val settings: StateFlow<LlmSettings> = repository.settings

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    fun save(s: LlmSettings) {
        repository.save(s)
    }

    /** 该厂商上次保存过的 API Key（切换厂商时自动回填，免重复输入）。 */
    fun rememberedApiKey(providerId: String): String = repository.rememberedApiKey(providerId)

    /** 思考开关即时持久化（Switch 语义：拨动即生效，无需保存按钮）。 */
    fun setThinkingEnabled(enabled: Boolean) {
        repository.updateThinking(enabled)
    }

    fun test(s: LlmSettings) {
        viewModelScope.launch {
            _testing.value = true
            _testResult.value = null
            repository.save(s)
            _testResult.value = try {
                val r = agent.testConnection().trim()
                if (r.isEmpty()) "连接成功（无返回内容）" else "连接成功：" + r.take(80)
            } catch (e: Exception) {
                "连接失败：" + (e.message?.take(160) ?: "未知错误")
            }
            _testing.value = false
        }
    }
}
