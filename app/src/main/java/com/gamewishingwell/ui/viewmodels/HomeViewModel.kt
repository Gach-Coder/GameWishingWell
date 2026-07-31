package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.GameAgent
import com.gamewishingwell.data.GameMeta
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: GameRepository,
    private val agent: GameAgent
) : ViewModel() {

    private val _games = MutableStateFlow<List<GameMeta>>(emptyList())
    val games: StateFlow<List<GameMeta>> = _games.asStateFlow()

    private val _hasDraft = MutableStateFlow(false)
    val hasDraft: StateFlow<Boolean> = _hasDraft.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _games.value = repository.listGames()
            _hasDraft.value = repository.hasDraft()
        }
    }

    fun deleteGame(id: Long) {
        viewModelScope.launch {
            repository.deleteGame(id)
            refresh()
        }
    }

    fun onPlayed(id: Long) {
        viewModelScope.launch { repository.touchPlay(id) }
    }

    /**
     * 清空会话并等待草稿文件删除完成后回调，避免导航到创作页时
     * ChatViewModel 抢先读到旧草稿（删除与读取存在竞态）。
     */
    fun startNew(onDone: () -> Unit) {
        viewModelScope.launch {
            agent.newSession()
            onDone()
        }
    }
}
