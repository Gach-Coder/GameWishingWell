package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel(
    private val repository: GameRepository,
    private val agent: GameAgent
) : ViewModel() {

    private val _html = MutableStateFlow<String?>(null)
    val html: StateFlow<String?> = _html.asStateFlow()

    private val _jsError = MutableStateFlow<String?>(null)
    val jsError: StateFlow<String?> = _jsError.asStateFlow()

    /** 当前加载的是否为已保存游戏及其 id（null = 草稿）。 */
    private val _loadedGameId = MutableStateFlow<Long?>(null)
    val loadedGameId: StateFlow<Long?> = _loadedGameId.asStateFlow()

    fun load(source: String, gameId: Long) {
        viewModelScope.launch {
            val (html, id) = withContext(Dispatchers.IO) {
                if (source == "game" && gameId > 0) {
                    val h = repository.loadGameHtml(gameId)
                    if (h != null) repository.touchPlay(gameId)
                    h to gameId
                } else {
                    repository.loadDraftHtml() to null
                }
            }
            _html.value = html
            _loadedGameId.value = id
            _jsError.value = null
        }
    }

    fun onJsError(message: String) {
        // 同一页面反复报同一个错时保留第一条，避免覆盖层闪烁；
        // 注入的错误（含行号，[游戏错误] 前缀）优先于浏览器原生 Uncaught 提示
        val current = _jsError.value
        if (current == null) {
            _jsError.value = message
        } else if (!current.startsWith("[游戏错误]") && message.startsWith("[游戏错误]")) {
            _jsError.value = message
        }
    }

    fun dismissError() {
        _jsError.value = null
    }

    /** 让 AI 修复运行时报错：先确保 agent 会话与当前游戏一致，再发起修复。 */
    fun fixErrorAndGo() {
        val err = _jsError.value ?: return
        _jsError.value = null
        viewModelScope.launch {
            val id = _loadedGameId.value
            if (id != null) agent.loadGameSession(id)
            agent.fixWithError(err)
        }
    }

    fun saveDraft(title: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val meta = agent.saveCurrentGame(title)
            onDone(meta != null)
        }
    }
}
