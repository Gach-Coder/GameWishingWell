package com.gamewishingwell.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamewishingwell.agent.AgentHub
import com.gamewishingwell.data.GameMeta
import com.gamewishingwell.data.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: GameRepository,
    private val hub: AgentHub
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
            // 会话实例退役（停其生成）+ 编辑区（工作区）与运行区独立：游戏删除后同步
            // 清理其编辑文件夹，避免孤儿残留（deleteWorkspace 是纯文件操作，经草稿实例执行）。
            hub.release(id)
            hub.agentFor(null).deleteWorkspace(id)
            refresh()
        }
    }

    fun renameGame(id: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameGame(id, newTitle)
            refresh()
        }
    }

    /**
     * 清空会话并等待草稿文件删除完成后回调，避免导航到创作页时
     * ChatViewModel 抢先读到旧草稿（删除与读取存在竞态）。
     */
    fun startNew(onDone: () -> Unit) {
        viewModelScope.launch {
            hub.resetDraft()
            onDone()
        }
    }
}
